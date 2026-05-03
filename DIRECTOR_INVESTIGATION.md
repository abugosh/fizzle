# Director System: Human vs Bot Path Investigation

## Executive Summary

The director system reveals a **hidden asymmetry** between human and bot paths:
- **Bot path**: Director calls pure engine functions directly (casting/cast-spell-handler, lands/play-land, mana-activation/activate-mana-ability)
- **Human path**: Event handlers dispatch re-frame events, which trigger event handlers
- **History recording**: Bot actions in the director loop **bypass history recording** entirely
- **Selection handling**: Bot actions that need selection pause the director; human actions go through the full selection system
- **Architecture question**: Is the director a game orchestrator, or is it a bot scheduler that happens to have a human auto-pass mechanism?

---

## 1. HUMAN ACTION ENTRY POINTS

All human actions go through re-frame event handlers. Each creates a `pending-entry` for history recording.

### Casting Path
- **Event**: `::cast-spell` (casting.cljs:309)
- **Handler**: `cast-spell-handler` (pure function, casting.cljs:241)
- **Flow**:
  1. Check can-cast? guard
  2. Evaluate pre-cast pipeline (costs, targeting, mana allocation) — may pause for selection
  3. If no selections needed: immediately sets `:history/pending-entry` (line 299-304)
  4. If selections needed: defers history entry to `:history/deferred-entry` (line 290-295)
- **History Recording**: Via `:history/pending-entry` set by handler (automatic, consistent)
- **Director Involvement**: NONE — cast-spell is called directly by human, doesn't interact with director

### Land Play
- **Event**: `::play-land` (lands.cljs:72)
- **Handler**: Wraps pure `play-land` function (lands.cljs:27)
- **Flow**:
  1. Guard: `rules/can-play-land?`
  2. Move to battlefield, register triggers, fire ETB effects, dispatch :land-entered event
  3. Sets `:history/pending-entry` (lands.cljs:81)
- **History Recording**: Immediate via `:history/pending-entry`
- **Director Involvement**: NONE

### Mana Activation
- **Event**: `::activate-mana-ability` (abilities.cljs:27)
- **Handler**: Calls pure `activate-mana-ability` function, sets `:history/pending-entry` (abilities.cljs:31-36)
- **History Recording**: Immediate via `:history/pending-entry`
- **Director Involvement**: NONE

### Yield/Yield-All
- **Event**: `::yield` (priority_flow.cljs:63) and `::yield-all` (priority_flow.cljs:82)
- **Handler**: Calls `director/run-to-decision` with opts (priority_flow.cljs:71, 90)
- **Flow**:
  1. Director runs game loop synchronously until human decision point
  2. If game-db changed: sets `:history/pending-entry` (priority_flow.cljs:76-78, 95-97)
  3. If no change: no entry (silent pass)
- **History Recording**: Only if director moved the game forward
- **Director Involvement**: DIRECT — entry point for human path through director

### Cast-and-Yield
- **Event**: `::cast-and-yield` (priority_flow.cljs:172)
- **Handler**: Special handling for Cast & Yield UX (priority_flow.cljs:121-160)
- **Flow**:
  1. Calls `casting/cast-spell-handler` directly
  2. If selection needed: overrides deferred-entry with `:cast-and-yield` type (line 137-144)
  3. If cast succeeded: sets deferred-entry (line 137-144)
  4. Then director resolves stack via `resolve-one-and-stop` continuation
- **History Recording**: Deferred to selection completion via deferred-entry
- **Director Involvement**: Indirect via continuation

### Summary: Human Entry Points (6 event handlers)
- `::cast-spell` — direct to engine (no director)
- `::play-land` — direct to engine (no director)
- `::activate-mana-ability` — direct to engine (no director)
- `::yield` — director entry point
- `::yield-all` — director entry point
- `::cast-and-yield` — director entry point

---

## 2. BOT ACTION ENTRY POINTS

All bot actions flow through the director. The director calls engine functions directly (not via re-frame dispatch).

### Bot Entry to Director
- **Where**: `director/run-to-decision` main loop (director.cljs:290)
- **How**: Checks `bot-protocol/get-bot-archetype` (director.cljs:343)
- **If bot has priority**: `step-bot-action` (director.cljs:176)

### Bot Action: Play Land
- **Invoked by**: `step-bot-action` (director.cljs:176-214)
- **Finding land**: `find-bot-land-to-play` (director.cljs:82-87) — searches hand, validates with `rules/can-play-land?`
- **Calling engine directly**: `lands/play-land` (director.cljs:105)
- **Applying result**: `sba/check-and-execute-sbas` (director.cljs:105)
- **History Recording**: NONE — no pending-entry set, no event handler invoked
- **Key difference from human**: Human `::play-land` event handler sets pending-entry; bot calls pure function directly

### Bot Action: Cast Spell
- **Invoked by**: `step-bot-action` (director.cljs:176-214), via `bot-decide-action` (interceptor.cljs:101)
- **Mana activation**: Calls `mana-activation/activate-mana-ability` in a reduce loop (director.cljs:111-114)
- **Casting**: Calls `casting/cast-spell-handler` directly (director.cljs:115)
  - Returns `{:game/db :pending-selection?}`
  - If selection needed: returns `{:action-type :cast-spell :pending-selection selection}`
  - If cast succeeded: applies SBAs directly (director.cljs:135)
- **History Recording**: NONE — no pending-entry set
- **Key difference**: Human `::cast-spell` event handler sets pending-entry; bot calls pure function directly

### Bot Action: Pass
- **Invoked by**: `step-bot-action` when bot decides to pass (director.cljs:198-214)
- **Calling engine**: `priority/yield-priority`, `priority/both-passed?`, `priority/transfer-priority`, `priority/reset-passes`
- **History Recording**: NONE
- **Key difference**: No event handler called at all

### Summary: Bot Entry Points
- Bot only enters via director
- Director calls engine functions directly (casting/cast-spell-handler, lands/play-land, mana-activation/activate-mana-ability)
- NO re-frame event dispatch (except via bots/interceptor::bot-decide in old architecture — but that's DEAD)
- NO history recording for bot actions during director loop

---

## 3. CONVERGENCE AND DIVERGENCE POINTS

### Convergence: Engine Functions
**Both paths call the same pure engine functions:**
- `casting/cast-spell-handler` — used by both human `::cast-spell` and director bot-act
- `lands/play-land` — used by both human `::play-land` and director find-bot-land-to-play
- `mana-activation/activate-mana-ability` — used by both human `::activate-mana-ability` and director tap loop

**Both use the same rules:**
- `rules/can-cast?` guard
- `rules/can-play-land?` guard
- `rules/can-cast-mode?` validation

### Divergence 1: Entry Path
- **Human**: Re-frame event dispatcher → event handler → cast-spell-handler
- **Bot**: Director loop → step-bot-action → cast-spell-handler

### Divergence 2: History Recording
- **Human**: Event handler sets `:history/pending-entry` (automatic, consistent)
- **Bot**: Director applies result directly; NO history recording happens

**CRITICAL ASYMMETRY**: When a bot casts a spell during the director loop, the cast is **invisible to the history system**. If the player later uses undo/fork, the bot action that moved the game is not in the history log.

### Divergence 3: Selection Handling
- **Human**: Selection pauses the event handler; game state is in app-db `:game/pending-selection`
- **Bot**: Selection from bot action pauses the director; director returns `{:done {:reason :pending-selection}}`

When selection completes:
- **Human**: Selection handler (e.g., `::confirm-selection`) dispatches more events
- **Bot**: Director is invoked again, runs to next decision point

### Divergence 4: Duplicate Function
**`find-bot-land-to-play` exists in TWO places:**
- `director.cljs:82` — director's copy
- `bots/interceptor.cljs:163` — bot-decide-handler's copy (dead code? or vestigial?)

Both implementations are identical (search hand, validate with `rules/can-play-land?`).

---

## 4. SELECTION SYSTEM INTERACTION

### Human Selection Flow
```
event handler (::cast-spell)
  → cast-spell-handler
    → [may need selection]
    → set :game/pending-selection
    → set :history/deferred-entry
  → [user confirms selection]
    → ::confirm-selection event
      → selection/core execute-confirmed-selection
        → set :history/pending-entry (deferred entry activated)
```

### Bot Selection Flow
```
director/run-to-decision (step-bot-action)
  → bot-act
    → casting/cast-spell-handler
      → [may need selection]
      → return {:pending-selection sel}
  → return {:done {:reason :pending-selection}}
  → [director pauses]
  → [human user must confirm selection]
    → ::confirm-selection event
      → selection/core execute-confirmed-selection
        → [continues director via continuation]
```

**Key insight**: When a bot spell creates a selection (e.g., targeting), the **human must resolve the selection**. The bot cannot act until the human makes the choice. Selection is a human affair.

However: Bot actions are STILL not recorded in history.

---

## 5. HISTORY SYSTEM INTERACTION

### How History Records Actions
1. Event handler completes, sets `:history/pending-entry`
2. Global history interceptor (history/interceptor.cljs) processes it
3. Entry added to history log (auto-fork if at rewound position)

### Bot Actions in Director
- Director calls engine functions directly
- **No re-frame event handler is invoked** (no dispatch)
- **No pending-entry is set**
- **History interceptor never sees the action**

**Result**: Bot actions are invisible to history system. If player rewinds, then directs the game forward, bot actions in the director loop are lost.

### Example Scenario
```
1. Human casts a spell (recorded in history)
2. Director runs, bot plays a land (NOT recorded)
3. Director runs, bot casts another spell (NOT recorded)
4. Game-state: board has 2 permanents, hand has 3 cards
5. Human clicks "undo" to step back 1 action
6. History replays up to first spell cast
7. Board now has 1 permanent (the land is gone!)
8. But if you "redo" the forward direction, director runs again
   and bot replays the land cast — now board has 2 permanents again
9. BUT: History log only shows 1 action, yet board state is different
```

**This is a BUG**: History log and game state are out of sync.

---

## 6. THE DIRECTOR AS AGENT

### What is the Director?
The director is positioned as a **pure game orchestrator** that:
- Runs the game loop synchronously
- Handles both human and bot turns
- Returns when a human needs to make a decision or when selection is needed

### But the Architecture Reveals
The director is actually:
- A **bot action dispatcher** that happens to be pure and testable
- A human auto-pass mechanism tacked on top
- A **rules engine invoker** that bypasses event handlers entirely
- A **history system bypasser** for bot actions

### If You Removed All Bot Code
The human path through the director would still make sense:
- `::yield` → director runs → human decisions point
- This is a valid architecture for handling yield/yield-all

### If You Removed All Human Code
The bot path through the director would need redesign:
- You can't just call pure functions directly — you need history recording
- You'd need a different mechanism to record bot actions

### Why This Matters
The director was designed to replace the old `dispatch-later + db_effect interceptor` architecture. But it introduced a **new problem**: bot actions are now invisible to history.

The old architecture at least went through event dispatch (even if via dispatch-later), which meant the history interceptor could see bot actions.

---

## 7. SYMMETRY CHECK: STRUCTURAL ANALYSIS

### Pure Functions vs Event Handlers

The asymmetry exists at the **event handler boundary**:

```
Human path:
  re-frame event (::cast-spell)
    ↓ [EVENT HANDLER]
    ↓ cast-spell-handler (pure)
    ↓ [EVENT HANDLER SETS PENDING-ENTRY]
    ↓ game-db ← history interceptor sees it

Bot path:
  director/run-to-decision
    ↓ [PURE FUNCTION CALL]
    ↓ cast-spell-handler (pure)
    ↓ [NO EVENT HANDLER]
    ↓ game-db ← history interceptor DOESN'T see it
```

### The Fundamental Issue
The director bypasses the event handler layer entirely. Event handlers are where:
1. History recording happens
2. Validation guards are set up
3. Selection system integrates with the game loop

By calling pure functions directly, the director:
1. Skips history recording
2. Relies on internal guards in the pure functions (good)
3. Doesn't integrate properly with selection system (problematic)

### Is the Asymmetry Inherent?
**NO**. The asymmetry is architectural, not inherent to the domain:
- Both human and bot actions modify game state
- Both human and bot actions should be recorded in history
- Both human and bot actions go through the same engine rules

The director could instead:
- Dispatch bot actions as re-frame events (with bot-specific flag)
- Let event handlers set pending-entry
- Collect the effect maps and apply them inline to avoid dispatch-later complexity

OR (current architecture):
- Director calls pure functions directly ✓
- But director must also set pending-entry for bot actions ✗ (currently missing)

---

## 8. SPECIFIC OBSERVATIONS

### Duplicate Code: find-bot-land-to-play
- **director.cljs:82** — director's copy
- **bots/interceptor.cljs:163** — bot-decide-handler's copy

Both are identical. The interceptor copy is vestigial (old architecture, now dead).

### Missing: History Recording for Bot Actions
- director.cljs never sets `:history/pending-entry`
- bot-act returns `{:action-type :game-db}`
- step-bot-action applies the game-db directly to app-db (director.cljs line 182)
- NO history entry is created

### Inconsistent: Selection from Bot Actions
- When bot spell needs targeting: director pauses with `:pending-selection`
- Reason: casting/cast-spell-handler returns the selection
- Human must confirm the selection
- But the bot's cast-spell action was NOT recorded in history

### Integration Point: casting/cast-spell-handler
Both paths use the same function, but with different expectations:
- **Human path**: Expects handler to return `{:game/db :pending-selection :history/pending-entry}`
- **Bot path**: Expects handler to return `{:game/db :pending-selection}`, no history entry needed

This works accidentally because cast-spell-handler doesn't know whether it's being called from human or bot path.

---

## 9. ARCHITECTURAL IMPLICATIONS

### If Director is a Bot Scheduler
Then human auto-pass is a minor feature, and the architecture is:
- Director: main orchestrator
- Bot auto-pass: convenience for human
- Problem: history system doesn't integrate

### If Director is a Game Orchestrator
Then it should treat human and bot paths symmetrically:
- Both paths should record history
- Both paths should integrate with selection system
- Both paths should use the same event dispatch mechanism

### Current State: Hybrid
The director is trying to be both:
- A pure game orchestrator (no side effects, testable)
- A bot dispatcher (calls engine functions directly)
- A human auto-pass mechanism (checks stops, runs loop)

This hybrid approach leaves history system broken for bot actions.

---

## 10. TESTING IMPLICATIONS

### Test Coverage
- `director.cljs` has integration tests (testing-director.cljs?)
- Bot actions are tested via director
- Human path is tested via event handlers + director

### Untested Interactions
- Bot action history recording (doesn't work)
- Bot selection confirmation (works, but human must confirm)
- Undo/fork with bot actions (broken, history is out of sync)

---

## CONCLUSION

The human vs bot asymmetry is **structural, not inherent**. The director was designed to be a pure orchestrator, but:

1. **Human path**: Re-frame event → handler → pending-entry → history
2. **Bot path**: Director → pure function → no pending-entry → history skipped

**The bug**: Bot actions inside the director loop bypass history recording.

**The options**:
1. Fix bot path: Director sets pending-entry for bot actions
2. Refactor director: Use event dispatch for bot actions (dispatch-later is gone, but could use continuations)
3. Accept limitation: Document that replay/fork with bot actions is not supported
