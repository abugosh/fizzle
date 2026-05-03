# Game.cljs Decomposition — Concern Dependencies

Visual map of which functions call which, to verify extraction order.

---

## Casting Concern (0 dependencies on other concerns)

```
cast-spell-handler
  ├─ cast/cast-spell-handler (recursive call, line 262)
  ├─ initiate-cast-with-mode
  │   └─ evaluate-pre-cast-step (multimethod dispatch)
  │       ├─ :exile-cards-cost (lines 78-84)
  │       ├─ :return-land-cost (lines 87-93)
  │       ├─ :discard-specific-cost (lines 96-102)
  │       ├─ :sacrifice-permanent-cost (lines 105-111)
  │       ├─ :pay-x-life (lines 114-117)
  │       ├─ :x-mana-cost (lines 120-123)
  │       ├─ :targeting (lines 126-158)
  │       └─ :mana-allocation (lines 161-170)
  ├─ get-valid-spell-modes
  └─ build-spell-mode-selection

select-casting-mode-handler
  └─ initiate-cast-with-mode

cancel-mode-selection-handler
  └─ (no internal calls, just dissoc)

::cast-spell event
  └─ cast-spell-handler

::select-casting-mode event
  └─ select-casting-mode-handler

::cancel-mode-selection event
  └─ cancel-mode-selection-handler

Multimethod: execute-confirmed-selection :spell-mode
  └─ (no internal calls)

Multimethod: apply-continuation :cast-after-spell-mode
  └─ initiate-cast-with-mode
```

**External calls out:**
- `engine/rules`: can-cast?, get-casting-modes, can-cast-mode?, cast-spell-mode
- `engine/targeting`: get-targeting-requirements, find-valid-targets
- `engine/static-abilities`: get-effective-mana-cost
- `selection/costs`: has-*-cost?, build-*-selection
- `selection/targeting`: build-cast-time-target-selection, confirm-cast-time-target

**No calls to resolution or priority functions.**

---

## Resolution Concern (1 dependency: only called by priority)

```
resolve-one-item (PUBLIC ENTRY POINT)
  ├─ clear-peek-result
  │   └─ (queries/get-game-state, d/db-with)
  ├─ get-source-id
  │   └─ (d/pull, queries/get-object)
  ├─ engine-resolution/resolve-stack-item (ENGINE DISPATCH)
  │   └─ [returns {:db, :needs-selection, :needs-storm-split, :needs-attackers, :needs-blockers}]
  ├─ BRANCH: if :needs-storm-split
  │   └─ sel-storm/build-storm-split-selection
  ├─ BRANCH: if :needs-attackers
  │   └─ bot-protocol/get-bot-archetype
  │   ├─ bot-protocol/bot-choose-attackers (for bot)
  │   └─ sel-combat/build-attacker-selection
  ├─ BRANCH: if :needs-blockers
  │   └─ sel-combat/build-blocker-selection
  ├─ BRANCH: if :needs-selection
  │   └─ build-selection-from-result
  │       └─ sel-core/build-selection-for-effect
  └─ [return {:db} or {:db :pending-selection}]

::resolve-top event
  ├─ resolve-one-item
  └─ cleanup/maybe-continue-cleanup

::resolve-all event
  ├─ resolve-one-item (recursive via :fx dispatch)
  └─ cleanup/maybe-continue-cleanup
```

**External calls out:**
- `engine/resolution`: resolve-stack-item
- `engine/stack`: get-top-stack-item, remove-stack-item
- `engine/mana` (implicit via effects)
- `selection/combat`: build-attacker-selection, build-blocker-selection
- `selection/storm`: build-storm-split-selection
- `selection/core`: build-selection-for-effect
- `bots/protocol`: get-bot-archetype, bot-choose-attackers
- `cleanup`: maybe-continue-cleanup

**Called ONLY by:**
- `yield-resolve-stack` (priority concern, line 491)
- `::resolve-top` event (direct dispatch)
- `::resolve-all` event (direct dispatch)

---

## Priority Flow Concern (orchestrates casting + resolution + phases)

```
yield-impl (PUBLIC ENTRY POINT)
  ├─ negotiate-priority
  │   ├─ engine/priority: yield-priority, both-passed?, reset-passes, transfer-priority
  │   ├─ bot-would-pass?
  │   │   └─ bot-protocol/get-bot-archetype
  │   │   └─ bot-protocol/bot-priority-decision
  │   ├─ player-is-bot?
  │   │   └─ (d/pull for bot-archetype field)
  │   └─ [return {:app-db, :all-passed?}]
  ├─ IF all-passed?: yield-resolve-stack
  │   ├─ resolve-one-item ◄─── CALLS RESOLUTION CONCERN
  │   ├─ IF (:needs-selection): clear auto-mode
  │   ├─ IF more on stack & auto-mode: {:continue-yield? true}
  │   └─ IF stack-empty: maybe-continue-cleanup
  │       └─ cleanup/maybe-continue-cleanup
  ├─ IF all-passed?: yield-advance-phase
  │   ├─ IF bot-turn: bot-turn-advance-one-phase
  │   │   ├─ phases/advance-phase ◄─── CALLS PHASES CONCERN
  │   │   ├─ cleanup/begin-cleanup ◄─── CALLS CLEANUP CONCERN
  │   │   └─ phases/start-turn ◄─── CALLS PHASES CONCERN
  │   ├─ IF human-turn: advance-with-stops ◄─── PUBLIC FUNCTION
  │   │   ├─ phases/next-phase ◄─── CALLS PHASES CONCERN
  │   │   ├─ phases/advance-phase ◄─── CALLS PHASES CONCERN
  │   │   ├─ cleanup/begin-cleanup ◄─── CALLS CLEANUP CONCERN
  │   │   ├─ phases/start-turn ◄─── CALLS PHASES CONCERN
  │   │   └─ engine/priority: check-stop, get-priority-holder-eid
  │   └─ [return {:app-db, :continue-yield?}]
  └─ [return {:app-db, :continue-yield?}]

negotiate-priority (PUBLIC ENTRY POINT)
  ├─ engine/priority: yield-priority, get-priority-holder-eid, transfer-priority, reset-passes
  ├─ player-is-bot?
  │   └─ (d/pull)
  ├─ bot-would-pass?
  │   └─ bot-protocol/get-bot-archetype, bot-priority-decision
  ├─ queries: get-active-player-id, get-player-eid, get-other-player-id, stack-empty?
  └─ [return {:app-db, :all-passed?}]

advance-with-stops (PUBLIC ENTRY POINT)
  ├─ queries: get-active-player-id, get-game-state, get-player-eid, stack-empty?
  ├─ phases/next-phase
  ├─ phases/advance-phase ◄─── CALLS PHASES CONCERN
  ├─ cleanup/begin-cleanup ◄─── CALLS CLEANUP CONCERN
  ├─ cleanup/maybe-continue-cleanup ◄─── CALLS CLEANUP CONCERN
  ├─ phases/start-turn ◄─── CALLS PHASES CONCERN
  └─ engine/priority: check-stop
  └─ [return {:app-db}]

::yield event (MAIN RECURSIVE CASCADE)
  ├─ IF (:game/pending-selection): skip
  ├─ yield-impl
  ├─ IF continue & auto-mode: dispatch-later ::yield (100ms)
  ├─ IF continue & no-auto-mode: dispatch ::yield (immediate)
  └─ Manages :yield/step-count for safety (max 200 steps)

::yield-all event (ENTER AUTO-MODE)
  ├─ engine/priority: set-auto-mode
  └─ dispatch ::yield

::cast-and-yield event
  ├─ cast-spell-handler ◄─── CALLS CASTING CONCERN
  ├─ IF pending-selection: set :resolve-one-and-stop continuation
  ├─ IF succeeded & stack non-empty: resolve-one-and-stop
  │   └─ engine/priority: set-auto-mode :resolving
  │   └─ yield-impl
  │   └─ engine/priority: clear-auto-mode
  └─ dispatch ::resolve-top or return

::cast-and-yield-resolve event
  └─ resolve-one-and-stop

resolve-one-and-stop (INTERNAL, used by continuation)
  ├─ engine/priority: set-auto-mode :resolving
  ├─ yield-impl
  └─ engine/priority: clear-auto-mode

Multimethod: apply-continuation :resolve-one-and-stop
  └─ resolve-one-and-stop
```

**External calls out:**
- `engine/priority`: yield-priority, both-passed?, reset-passes, transfer-priority, get-priority-holder-eid, get-auto-mode, set-auto-mode, clear-auto-mode, check-stop
- `bots/protocol`: get-bot-archetype, bot-priority-decision, bot-choose-attackers
- `phases`: next-phase, advance-phase, start-turn
- `cleanup`: begin-cleanup, maybe-continue-cleanup
- `selection/core`: apply-continuation
- `re-frame.core`: dispatch, dispatch-later

**Calls TO other concerns:**
- ✅ `casting/cast-spell-handler` (line 792 in ::cast-and-yield)
- ✅ `resolution/resolve-one-item` (line 491 in yield-resolve-stack)
- ✅ `phases/advance-phase`, `phases/next-phase`, `phases/start-turn`
- ✅ `cleanup/begin-cleanup`, `cleanup/maybe-continue-cleanup`

---

## Current game.cljs Call Graph (Pre-extraction)

```
game.cljs top-level:
├─ resolve-one-item (line 352)
│   └─ [used by ::resolve-top (line 403), ::resolve-all (line 422)]
├─ negotiate-priority (line 638)
│   └─ [used by yield-impl (line 705)]
├─ yield-impl (line 694)
│   ├─ negotiate-priority
│   ├─ yield-resolve-stack (calls resolve-one-item at line 491)
│   └─ yield-advance-phase (calls advance-with-stops at line 607)
├─ advance-with-stops (line 435)
│   └─ [used by yield-advance-phase (line 607)]
├─ cast-spell-handler (line 256)
│   └─ [used by ::cast-spell (line 301) and ::cast-and-yield (line 792)]
└─ [other utility functions]
```

**Key insight:** Priority flow functions (`yield-impl`, `negotiate-priority`, `advance-with-stops`) form a coherent cluster that calls down to casting + resolution + phases. Casting and resolution have no upward calls.

---

## Extraction Order Verification

### Casting → (Extract first: zero dependencies)
✅ No calls to resolution, priority, or other game.cljs functions

### Resolution → (Extract second: only called by priority)
✅ No calls to casting or priority
✅ Called by `yield-resolve-stack` (priority concern, line 491)

### Priority Flow → (Extract third: calls both casting and resolution)
✅ Calls `cast-spell-handler` (casting)
✅ Calls `resolve-one-item` (resolution)
✅ Calls phases/cleanup (already extracted)

### game.cljs → (Becomes pure facade)
✅ Re-exports from all three modules
✅ Retains only `cycle-card` utility

**Extraction order is correct:** Casting → Resolution → Priority Flow

---

## Test Coverage Points

**After Phase 1 (casting extracted):**
- Verify casting behavior unchanged
- Verify event dispatch `[:fizzle.events.casting/::cast-spell opts]` works
- Verify re-export `fizzle.events.game/cast-spell-handler` works

**After Phase 2 (resolution extracted):**
- Verify resolution behavior unchanged
- Verify event dispatch `[:fizzle.events.resolution/::resolve-top]` works
- Verify re-export `fizzle.events.game/resolve-one-item` works
- Verify resolution still works when called from priority (indirectly via ::yield)

**After Phase 3 (priority_flow extracted):**
- Verify priority behavior unchanged
- Verify event dispatch `[:fizzle.events.priority-flow/::yield]` works
- Verify auto-mode cascading still works
- Verify re-exports `fizzle.events.game/{negotiate-priority,advance-with-stops,yield-impl}` work
- Verify full game flow: cast → yield → resolve → advance phases

**All phases:**
- `make test` (all 2124 tests pass)
- `make lint` (no new warnings)
- `make fmt-check` (no formatting issues)

