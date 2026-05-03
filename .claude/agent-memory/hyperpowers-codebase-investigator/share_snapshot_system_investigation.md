---
name: Share/Snapshot System Investigation
description: Full flow from share URL to restored state, including director, priority, and action restrictions
type: reference
---

# Share/Snapshot System — Complete Investigation

## Overview

Fizzle's share feature encodes the entire game state (board, hand, stack, priority, phase) as a compact binary blob, then base64url-encodes it into a URL fragment (#s=...). When players load a shared URL, the state is decoded, restored, and the game continues from that exact position. **Restored games start with empty history and players cannot undo/fork.**

---

## Data Flow: Share → Restore → Action

### 1. SHARE FLOW (snapshot/events.cljs :: share-position)

**User Action:** Click "Share" button
**Event:** `::share-position` at line 98

```
Click Share
  ↓
snapshot/events.cljs :: share-position event
  ↓ 
extractor/extract (game-db → portable map)
  ↓
encoder/encode-snapshot (portable → compact binary → base64url)
  ↓
copy-to-clipboard! (URL with #s=<encoded>)
  ↓
set-share-status :copied (clears after 2s)
```

**Key Functions:**
- `encode-for-share` (line 43): Calls extractor, encoder; returns full URL or nil if too large
- `snapshot/events.cljs` line 102: Base URL from `js/location` origin + pathname
- URL limit: 2000 chars (encoder returns nil if exceeded, triggers `:error-too-large` status)

---

### 2. RESTORE FLOW (core.cljs init, snapshot/events.cljs :: restore-from-hash)

**User Action:** Load URL with #s=... hash
**Entry Point:** `core.cljs` line 153-158

```
Page load
  ↓
core/init runs dispatch-sync [::setup/init-setup]
  ↓
Get URL hash: (.-hash js/location)
  ↓
snapshot/restore-from-hash-handler (pure function)
  ├─ Check prefix: hash must start with "#s="
  ├─ Extract encoded part: subs hash from position 4 onward
  ├─ decoder/decode-snapshot (base64url → binary → portable map)
  ├─ restorer/restore-game-state (portable → fresh Datascript db)
  └─ Return app-db map (see below)
  ↓
Clear hash: js/history.replaceState (no #s=... in URL bar after)
  ↓
Dispatch restore event: rf/dispatch-sync [::restore-from-snapshot restored-app-db]
  ↓
Merge restored state: db = merge(original-db, restored-app-db)
  ↓
mount-root (render app with restored state)
```

**Restored app-db structure** (restorer.cljs line 221-230):
```clojure
{:game/db              @conn                    ; Fully-playable Datascript db
 :active-screen        :game                    ; Skip opening-hand screen
 :game/game-over-dismissed false
 :ui/stack-collapsed   false
 :ui/gy-collapsed      false
 :ui/history-collapsed false
 :history/main         []                       ; EMPTY — no undo/fork
 :history/forks        {}
 :history/current-branch nil
 :history/position    -1}
```

**Key Functions:**
- `restore-from-hash-handler` (line 56): Pure function, returns nil for invalid hash (caller falls back to normal init)
- `decoder/decode-snapshot`: Inverse of encoder, reconstructs portable map
- `restore-game-state` (restorer.cljs line 176): Builds fresh Datascript db + app-db

---

## STATE ENCODING

**File:** `sharing/encoder.cljs`, `sharing/extractor.cljs`, `sharing/decoder.cljs`

### What Gets Encoded (extractor.cljs)

**From each player:**
- Hand: list of objects
- Graveyard, exile, library: list of objects
- Battlefield: list of permanents (tapped status, counters, grants)
- Mana pool: current mana availability
- Storm count: storm level
- Land plays left: plays remaining this turn
- Player grants: temporary abilities

**Game state:**
- Active player ID
- Priority holder ID
- Current turn number
- Current phase
- Current step (optional)
- Human player ID

**Card references are resolved to stable :card/id keywords:**
- Player refs → :player/id keyword (e.g., :player-1)
- Card refs → :card/id keyword (e.g., :dark-ritual)
- Datascript entity IDs (db/id) are stripped — objects get fresh UUIDs on restore

### Binary Format (encoder.cljs lines 6-34)

Compact MSB-first binary with bit-level packing:
- **Header** (~14 bytes): version, flags, life totals, storm counts, turn, phase, active player, priority, mana, land plays
- **Zones** (P1 then P2): variable-length card indices + object-specific bits (tapped, counters, grants)
- **Complex data** (grants): EDN blob with 16-bit length prefix

---

## RESTORER: RECONSTRUCTION ALGORITHM

**File:** `sharing/restorer.cljs`

### Object Restoration (line 114-124)

For each object in a zone:
1. Create fresh UUID: `:object/id (random-uuid)`
2. Resolve card reference: `:card/id` → `:object/card` (entity lookup in fresh Datascript db)
3. Resolve owner/controller: `:player/id` keyword → `:player/id` keyword (restored as-is)
4. Restore optional fields: `:object/tapped`, `:object/counters`, `:object/grants`
5. **Creatures on battlefield**: Restore `:object/power`, `:object/toughness` from card definition
   - Set `:object/summoning-sick false` (mid-game, not just-entered)
   - Set `:object/damage-marked 0`

### Player Creation (line 40-52, game-state.cljs)

Each player entity gets:
- `:player/life`, `:player/mana-pool`, `:player/storm-count`, `:player/land-plays-left`
- `:player/grants` (if any)
- `:player/max-hand-size` (default 7)
- `:player/is-opponent` (true for opponent)
- `:player/bot-archetype` (if opponent is a bot)

**Stops are restored from localStorage, not encoded:**
- Calls `storage/load-stops` to restore player's saved stops
- If opponent is a bot: calls `bot-protocol/bot-stops` to set bot's stops

### Triggers (line 130-148)

For **every permanent on battlefield**:
1. Pull card definition
2. Get `:card/triggers` from card
3. Call `trigger-db/create-triggers-for-card-tx` to recreate trigger entities
4. Transact into Datascript

This ensures triggers fire normally during play (e.g., Threshold on Nimble Mongoose).

---

## PRIORITY & ACTION RESTRICTIONS

### The Director: orchestration engine

**File:** `events/director.cljs`

The director is a **pure synchronous function** that runs the game loop until a human needs to decide something.

**Entry point:** `run-to-decision(app-db, opts) → {:app-db, :reason}`

**Options:**
- `:yield-all?` — F6 mode (auto-pass all stops). Downgraded to `:yield-through-stack?` if stack has items at entry.
- `:human-yielded?` — Human explicitly clicked Yield (auto-pass once, then stops apply normally)

**Returns reason for stopping:**
- `:await-human` — Human has a stop at current phase (can act)
- `:pending-selection` — Selection needed (targeting, mana allocation, etc.)
- `:game-over` — Game has end condition
- `:safety-limit` — Loop hit 300 steps (prevents infinite loops)

**Loop structure** (line 318-359):
1. Check if human should auto-pass at current decision point
2. If yes: pass priority, continue loop
3. If no: return `:await-human` (stop and let human decide)
4. If stack empty and both passed: advance phase
5. If stack non-empty: resolve top item

### Auto-Pass Logic: human-should-auto-pass (line 43-59)

**When player holds priority, check:**
1. If `:yield-all?` true → **always auto-pass** (F6 mode)
2. If `:yield-through-stack?` true AND stack non-empty → **auto-pass** (resolving from yield-all)
3. If stack non-empty → **STOP** (human gets priority to respond to spells/abilities)
4. If stack empty → auto-pass if current phase is NOT in player's `:player/stops` set

**Example:** If phase is :main1 and player has `:player/stops #{:draw}`, auto-pass (main1 is not in stops).

### Priority Phases (engine/priority.cljs line 11-14)

Players only receive priority in these phases:
```clojure
#{:upkeep :draw :main1 :combat :main2 :end}
```

**No priority in:** `:untap`, `:cleanup`

---

## WHY SHARED STATES CAN'T UNDO

**Key design:** Restorer creates **empty history**

```clojure
:history/main         []                ; No previous events
:history/forks        {}                ; No fork branches
:history/current-branch nil             ; No branch selected
:history/position    -1                 ; Before all events
```

The history system (`history/events.cljs`, `history/interceptor.cljs`) tracks all events dispatched. Every event gets appended. To undo, you'd pop off the history and re-reduce from 0 up to position-1. But restored states have no history, so:
- No events to pop → can't undo
- No forks to branch → can't replay from a branch
- UI buttons for undo/fork are disabled (check history-sidebar for conditional rendering)

**Architecture:** History is an immutable vector of events. Game state is derived from reducing events. Restored states start with a "full-board snapshot" instead of an event, so the history system can't unwind them.

---

## ACTION RESTRICTIONS: Rules Checks

### can-cast? (engine/rules.cljs)

**Checked by:**
- Subscription `::can-cast?` (subs/game.cljs) — determines if Cast button is enabled
- Event `::cast-spell` (events/casting.cljs line 200+) — applies pre-cast checks again

**Restrictions (in order):**
1. **Phase check:** Current phase must be in `priority/priority-phases` (:upkeep, :draw, :main1, etc.)
2. **Player restrictions:** Player must not have `:cannot-cast-spells` restriction
3. **Spell timing:** 
   - Instants: any priority phase
   - Sorceries: main phase + empty stack
4. **Zone:** Card must be in hand (or playable zone)
5. **Mana cost:** Can player pay the spell's mana cost?
6. **Additional costs:** Card-specific costs (e.g., sacrifice, discard)
7. **Targeting:** Required targets must exist and be valid
8. **Casting restrictions:** Card-specific restrictions (e.g., "only during your turn")

### can-play-land? (engine/rules.cljs)

**Restrictions:**
1. Phase must allow land play (main phase, currently: main1 or main2)
2. Player hasn't played a land this turn: `(:player/land-plays-left player-eid) > 0`
3. Land is in hand
4. No active selection/spell on stack (implied by priority phase check)

### UI Filtering (views/controls.cljs)

Controls view subscribes to `::can-cast?` and `::can-play-land?`.
- If both false: **Cast and Play buttons are disabled** (grayed out, not clickable)
- Yield buttons always enabled (hand priority at any time)

---

## SHARED STATE + DIRECTOR INTERACTION

When a shared state is loaded:

1. **restorer/restore-game-state** creates fresh db with:
   - All zones populated from snapshot
   - Priority holder set to `:game/priority` from snapshot
   - Active player set to `:game/active-player` from snapshot
   - Current phase/turn from snapshot

2. **core/init** merges restored state into app-db

3. **Game loop does NOT auto-run yet.** Director is called via event handlers:
   - `::yield` (line 63-78): Player clicks Yield → runs director to next decision point
   - `::yield-all` (line 81-97): Player clicks Yield All (F6) → runs director with `:yield-all? true`
   - `::cast-spell` (events/casting.cljs): Player clicks Cast → casts spell, director runs in background if needed

4. **Director auto-runs bots:** If bot holds priority, director calls `bot-act` (line 90), which:
   - Checks phase actions (play land, phase-based abilities)
   - Asks bot protocol for decision (cast or pass)
   - Applies decision inline (pure functions, no dispatch-later)
   - Returns control to director loop

5. **Human player restrictions apply:** If human holds priority:
   - `human-should-auto-pass` checks stops + yield flags
   - If false: director returns `:await-human`, director loop stops
   - UI is rendered with restricted action buttons (can-cast?, can-play-land? subs)
   - Player clicks buttons or Yield, dispatcher re-enters

---

## RECENT CHANGES (from memory)

### Epic fizzle-bcz9: Continuation Chaining (commit fec57e4)

**Changes to director.cljs:**
- Director is now pure and synchronous (no dispatch-later)
- Bot actions applied inline via pure `bot-act` function (line 90)
- SBAs run after each engine step (line 105, 162, 164, etc.)
- All yield/epoch/step-count state removed (line 307-308)

### Bot Interceptor Updates (bots/interceptor.cljs)

**Old model (db_effect.cljs chokepoint):** Every db mutation triggers bot-decide via re-frame dispatch.
**New model (director.cljs):** Director calls `bot-act` directly, which calls `bot-decide-action` to get one action, then applies it.

**Remaining in interceptor:**
- `bot-decide-handler` (line 177): Still exists for fallback/testing, but director prefers inline `bot-act`
- `build-bot-dispatches` (line 140): Unused in director path (director applies changes via pure functions)
- Action-pending guard (line 192): Still protects against stale bot-decide from db_effect during compound actions

---

## KEY FILES

### Core Sharing
- `/Users/abugosh/g/fizzle/src/main/fizzle/snapshot/events.cljs` — Re-frame events for share/restore
- `/Users/abugosh/g/fizzle/src/main/fizzle/sharing/extractor.cljs` — Portable map extraction
- `/Users/abugosh/g/fizzle/src/main/fizzle/sharing/encoder.cljs` — Binary encoding + base64url
- `/Users/abugosh/g/fizzle/src/main/fizzle/sharing/decoder.cljs` — Binary decoding
- `/Users/abugosh/g/fizzle/src/main/fizzle/sharing/restorer.cljs` — Fresh db reconstruction

### Director + Priority
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs` — Pure game loop orchestrator
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/priority.cljs` — Priority state management
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs` — Event handlers (yield, yield-all)

### Bots
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/interceptor.cljs` — Legacy bot decision logic (fallback)
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/protocol.cljs` — Bot archetype protocol
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/definitions.cljs` — Specific bot types

### UI
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/controls.cljs` — Cast/Play/Yield buttons (checks can-cast?, can-play-land?)
- `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs` — Subscriptions for action availability

### Init
- `/Users/abugosh/g/fizzle/src/main/fizzle/core.cljs` — App init, URL hash restoration (line 153-158)
