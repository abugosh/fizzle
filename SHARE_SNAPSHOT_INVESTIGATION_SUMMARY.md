# Share/Snapshot System Investigation Summary

## Problem Statement

Fizzle's share feature lets players create shareable URLs of game states. When players load a shared state, they can't advance (take actions). This investigation mapped the complete flow from "share button" to "why actions are blocked."

## Key Findings

### 1. Share → Restore Flow (Data Movement)

**Share side:**
- `snapshot/events.cljs :: share-position` extracts game state via `extractor/extract`
- Binary encoder compresses state to compact format (player life, mana, phase, all zones)
- Base64url encodes to string, appends to URL: `https://example.com/#s=<encoded>`
- Copies to clipboard

**Restore side:**
- On page load, `core.cljs` checks `location.hash` for `#s=` prefix
- `snapshot/restore-from-hash-handler` decodes URL hash (pure function)
- `restorer/restore-game-state` reconstructs fresh Datascript db from portable map
- Merges restored state into app-db, clears hash, renders game screen

**Critical detail:** Restored state initializes with **empty history**:
```clojure
:history/main         []
:history/forks        {}
:history/current-branch nil
:history/position    -1
```

### 2. Director: Pure Game Loop Orchestrator

**File:** `events/director.cljs` (280 lines)

The director is a **synchronous pure function** that runs the game loop until a human player must decide something. It replaced the old dispatch-later architecture (epic fizzle-bcz9).

**Entry point:** `run-to-decision(app-db, opts) → {:app-db, :reason}`

**Stops when reason is:**
- `:await-human` — human holds priority AND doesn't have a stop at current phase
- `:pending-selection` — selection needed (targeting, mana allocation, etc.)
- `:game-over` — loss condition triggered
- `:safety-limit` — safety exit after 300 steps

**Bot actions:** Director calls `bot-act` (line 90) directly as a pure function:
1. Check phase actions (play a land?)
2. Ask bot protocol for decision (cast spell or pass?)
3. Apply decision inline (cast spell, mana payment, SBAs)
4. Return control to director loop

### 3. Why Shared States Can't Undo/Fork

**Reason:** Empty history means no events to replay from.

Fizzle's undo system works by:
- Every action is an event dispatched
- Events are appended to `history/main` vector
- Undo pops event, re-reduces from event 0 to position-1

**Shared states have no history**, so:
- No events to pop → undo disabled
- No forks to branch → fork UI hidden
- Players can't save positions within a shared game

**By design:** Shared states are read-only practice/puzzle scenarios.

### 4. Why Actions Are Restricted (Priority + Stops + Checks)

**Three layers of action restriction:**

#### Layer 1: Priority & Phase (engine/priority.cljs, director.cljs)

Only these phases grant priority: `#{:upkeep :draw :main1 :combat :main2 :end}`

No priority in: `:untap`, `:cleanup`

Director checks `human-should-auto-pass`:
- If `:yield-all?` true → auto-pass (F6 mode)
- If stack non-empty → STOP (human gets priority to respond)
- If stack empty → auto-pass if phase NOT in `:player/stops` set

#### Layer 2: Stop Sets (Stops Accessors, director.cljs line 64-77)

Each player has `:player/stops` and `:player/opponent-stops` sets.

Director uses stops to decide when to pause:
```
If phase in stops → human gets priority (director returns :await-human)
If phase not in stops → auto-pass (continue loop)
```

Stops are restored from localStorage, not encoded in snapshot.

#### Layer 3: can-cast? / can-play-land? Checks (engine/rules.cljs)

UI buttons (views/controls.cljs) disable unless subscription returns true.

**can-cast?** requires:
1. Priority phase check (director already ensures this)
2. No player restrictions (`:cannot-cast-spells`)
3. Spell timing (instant any time, sorcery = main + empty stack)
4. Card in hand
5. Can pay mana cost
6. Can pay additional costs
7. Valid targets exist (if needed)
8. Card-specific cast restrictions

**can-play-land?** requires:
1. Main phase + empty stack
2. Haven't played a land this turn (`:player/land-plays-left > 0`)
3. Land in hand

---

## File Organization

### Core Sharing System
- `src/main/fizzle/snapshot/events.cljs` — Re-frame events (share-position, restore-from-snapshot)
- `src/main/fizzle/sharing/extractor.cljs` — Game-db → portable map
- `src/main/fizzle/sharing/encoder.cljs` — Portable map → binary → base64url
- `src/main/fizzle/sharing/decoder.cljs` — base64url → binary → portable map
- `src/main/fizzle/sharing/restorer.cljs` — Portable map → fresh Datascript db
- `src/main/fizzle/sharing/{bits,card_index}.cljs` — Binary encoding helpers

### Director & Priority
- `src/main/fizzle/events/director.cljs` — Pure game loop (280 lines, detailed comments)
- `src/main/fizzle/engine/priority.cljs` — Priority state (9 pure functions, ~90 lines)
- `src/main/fizzle/events/priority_flow.cljs` — Event handlers (yield, yield-all)

### UI & Subscriptions
- `src/main/fizzle/views/controls.cljs` — Cast/Play/Yield buttons
- `src/main/fizzle/subs/game.cljs` — `::can-cast?`, `::can-play-land?` subscriptions
- `src/main/fizzle/engine/rules.cljs` — Rule checks (can-cast?, can-play-land?, etc.)

### App Init
- `src/main/fizzle/core.cljs` — App init, hash restoration (line 153-158)
- `src/main/fizzle/events/setup.cljs` — Setup screen initialization

### Bot Integration
- `src/main/fizzle/bots/interceptor.cljs` — Fallback bot logic (still exists, not used by director)
- `src/main/fizzle/bots/protocol.cljs` — Bot archetype protocol
- `src/main/fizzle/events/director.cljs` line 90-135 — Director calls bot-act directly

---

## Detailed Flow: Load Shared State → Take Action

```
1. User loads URL with #s=<encoded>
   └─ Page load → core/init runs

2. core/init (line 144-159):
   a) Dispatch setup/init-setup (normal initialization)
   b) Get hash from location.hash
   c) Call restore-from-hash-handler (pure function)
   d) If restored: clear hash, dispatch restore-from-snapshot event
   e) Mount app

3. restore-from-snapshot event (snapshot/events.cljs line 133):
   Merge restored app-db into current db
   └─ :game/db → fresh Datascript db with all zones populated
   └─ :active-screen → :game (skip opening-hand)
   └─ :history/main → [] (empty)

4. App renders with game screen

5. User clicks "Cast" button:
   a) Subscription ::can-cast? evaluates
      - rules/can-cast? checks priority phase, restrictions, mana, targets, etc.
      - If false: button disabled (grayed out)
   b) If true: dispatch ::cast-spell event
   c) Event handler: casting/cast-spell-handler
      - Evaluates pre-cast pipeline (costs, targeting, mana allocation)
      - If selection needed: set :game/pending-selection, show dialog
      - Else: cast spell directly
   d) Director runs (embedded in event handlers via priority-flow events)
      - If bot holds priority after cast: director calls bot-act repeatedly
      - If human should auto-pass: director advances phases automatically
      - Else: return :await-human, director pauses

6. User clicks "Yield":
   a) Dispatch ::yield event
   b) Event: priority-flow-events/yield
      - Call director/run-to-decision with :human-yielded? true
      - Director runs until human needs to act again
      - Return results to app-db

7. Undo button:
   - Disabled (no history to undo from)
   - history-sidebar checks :history/main length
   - If empty: undo button not rendered

8. Fork button:
   - Disabled (no history to fork from)
   - history-sidebar checks :history/forks
   - If empty: fork button not rendered
```

---

## Recent Architecture Changes

### Epic fizzle-bcz9: Continuation Chaining (commit fec57e4)

**Before:** Dispatch-later chains, separate yield-handler and db_effect orchestrators

**After:**
- Single `director/run-to-decision` pure function
- No dispatch-later (all game logic runs synchronously)
- Bot acts are applied inline via `bot-act` (pure functions)
- SBAs run after each engine step
- Removed state: `:yield/epoch`, `:yield/step-count`, `:bot/action-pending?`, `:bot/action-count`

**Impact on shared states:**
- Director is pure and testable
- Game loop is deterministic (no race conditions from async dispatch)
- Bot behavior is predictable in shared scenarios

### Bot Interceptor Changes

**File:** `bots/interceptor.cljs`

Old model: Every db mutation triggered bot-decide via re-frame dispatch (db_effect.cljs chokepoint)

New model: Director calls `bot-act` directly (line 90-135 of director.cljs), which:
1. Checks phase actions
2. Calls `bot-decide-action` to get one action
3. Applies action via pure functions
4. Returns to director loop

**Remaining:** Fallback `bot-decide-handler` (for tests/edge cases), but director prefers inline `bot-act`.

---

## Testing Strategy

### For Share/Restore
- `src/test/fizzle/snapshot/events_test.cljs` — Tests restore-from-hash-handler, encode-for-share
- Tests verify: decode produces valid db, invalid hashes return nil, history is empty

### For Director
- `src/test/fizzle/events/director_test.cljs` — Tests run-to-decision, human-should-auto-pass, bot-act
- Tests verify: director stops at correct reasons, bot acts are applied, SBAs run

### For Rules Checks
- Individual card tests verify can-cast? is checked
- Integration tests verify UI buttons respond to can-cast? changes

---

## Conclusion

The share system is architecturally sound:

1. **Encoding is compact:** Binary format with bit-level packing handles large boards
2. **Restorer is deterministic:** Fresh db from snapshot, triggers recreated from card definitions
3. **Director is pure and testable:** No dispatch-later, all logic synchronous and reducible
4. **Action restrictions are layered:** Priority → stops → rule checks, each layer independent

**Why shared states can't advance:**
- Not a bug — by design. Shared states are practice/puzzle scenarios.
- History is empty, so undo/fork disabled.
- If you want to edit a shared state, the player must re-setup the game manually.

**Why actions are blocked in some cases:**
- Priority system (no priority in untap/cleanup)
- Stops (player chooses when to pause at certain phases)
- Rule checks (can't cast without mana, valid targets, correct timing)
- All three are working as designed.

