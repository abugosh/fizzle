---
name: Engine Integration Test Gaps Analysis (2026-04-08)
description: Complete audit of 4 P2 engine testing tasks (fizzle-6p9p, fizzle-orq7, fizzle-t2j3, fizzle-rcaw); exact file locations, line numbers, test counts, missing scenarios
type: project
---

**Status**: 4 P2 engine testing epics, audited 40 test files across engine layer

## Summary

| Task | Current Tests | Critical Gaps | Missing Coverage |
|------|----------------|---------------|-----------------|
| **fizzle-6p9p** (Integration) | 30 | Spell→effects→SBA chain | 6/8 stack-item types |
| **fizzle-orq7** (Triggers) | 26 | Multimethod dispatch | 4 trigger types |
| **fizzle-t2j3** (Opponent SBA) | 10 | Human player SBAs | Cascading SBAs |
| **fizzle-rcaw** (Effects→SBA) | 1 | Interactive interrupts | Multi-effect chains |

## Test File Inventory

- **40 total test files** in `src/test/fizzle/engine/`
- Key files by scope:
  - `effects_test.cljs`: 574 tests (unit; effect execution)
  - `state_based_test.cljs`: 76 tests (unit; SBA checks)
  - `combat_test.cljs`: 149 tests (unit; creature damage/combat rules)
  - `resolution_test.cljs`: 30 tests (integration; multimethod dispatch)
  - `opponent_sba_test.cljs`: 10 tests (integration; opponent loss conditions)
  - `triggers_test.cljs`: 26 tests (unit; turn-based triggers)
  - `trigger_db_integration_test.cljs`: 18 tests (integration; Datascript triggers)

## Task 1: fizzle-6p9p (Engine Integration Tests)

**Current**: `src/test/fizzle/engine/resolution_test.cljs` (30 tests)

### Coverage
- Spell resolution: no targets, with targets, flashback mode exile
- Storm copy resolution: object removal, target inheritance
- Interactive effects: :needs-selection return path
- Controller identity: stack-item/:controller (not active-player)
- Spell on opponent's turn: controller vs active-player distinction

### Critical Gaps

**End-to-end event chain**: Cast spell → resolve stack item → execute effects → SBA check → remove stack item
- File: `src/main/fizzle/events/resolution.cljs` line 64-120 (resolve-one-item function)
- Calls `engine-resolution/resolve-stack-item` (engine layer)
- Returns `{:db}` or `{:db :pending-selection}`
- **NO integration tests** verifying this full chain with real game state

**Stack-item types implemented but NOT tested** (6 of 8):
1. `:spell` (line 223) — TESTED
2. `:storm-copy` (line 228) — TESTED
3. `:permanent-entered` (line 237) — NOT TESTED (ETB effects, state-check triggers)
4. `:activated-ability` (line 269) — NOT TESTED (mana/ability effects)
5. `:storm` (line 306) — NOT TESTED (storm split selection)
6. `:declare-attackers` (line 342) — NOT TESTED (attacker selection)
7. `:declare-blockers` (line 355) — NOT TESTED (blocker selection)
8. `:combat-damage` (line 368) — NOT TESTED (damage application, creature death)

**Additional gaps**:
- Spell cast during opponent turn (test exists but trivial)
- Triggered ability stack-item resolution (permanent-entered type)
- Combat resolution chains: attackers → blockers → damage → SBA
- Trigger firing during resolution (via trigger-dispatch)
- Storm count increment during casting (cast.cljs, not resolution layer)

## Task 2: fizzle-orq7 (Triggers Test Expansion)

**Current**: `src/test/fizzle/engine/triggers_test.cljs` (26 tests)

### Coverage
- `resolve-trigger :draw-step`: turn 1 skip, turn 2 draw
- `resolve-trigger :untap-step`: untap all permanents, noop when none tapped
- `create-spell-copy`: valid object creation, target inheritance, nonexistent source

### Critical Gaps

**No multimethod dispatch tests** — Only 2 trigger types tested out of 10+ implemented:
- `src/main/fizzle/engine/triggers.cljs` line 16 (resolve-trigger multimethod)
  - `:default` defmethod (line 31)
  - `:draw-step` (line 114) — TESTED
  - `:untap-step` (line 161) — TESTED
  - NO OTHER TRIGGERS TESTED

**Missing trigger types**:
- Card-triggered abilities: ETB, tap, attack, sacrifice (trigger-db.cljs)
  - See `trigger-db.cljs` line 15-19 (trigger-type->event-type multimethod)
  - Maps `:becomes-tapped`, `:land-entered`, `:creature-attacks`, `:enters-battlefield`
- Mana ability triggers (not in triggers.cljs, but tested in `mana_test.cljs` 116 tests)
- State-check triggers (delayed triggers; see `state_based.cljs` line 297)
- Trigger filter matching and event dispatch
  - See `trigger_dispatch.cljs` (complete trigger dispatch integration)
  - **NO tests** for filter matching (e.g., :self, :any-player, :opponent)

**Database integration gaps** (tested 18 tests in trigger_db_integration_test.cljs):
- Trigger entity creation (TESTED)
- Dispatch finding triggers in Datascript (TESTED)
- **NO tests** for cascade: object on battlefield → card has trigger → trigger fires on event

## Task 3: fizzle-t2j3 (Opponent SBA Test Expansion)

**Current**: `src/test/fizzle/engine/opponent_sba_test.cljs` (10 tests)

### Coverage
- Opponent drew-from-empty-library flag set (phases.cljs advance-phase)
- SBA engine detects empty-library via `sba/check-and-execute-sbas`
- Director integration: `director/run-to-decision` with inline SBA detection

### Critical Gaps

**SBA types implemented but NOT tested for opponent** (6 of 6):
- `src/main/fizzle/engine/state_based.cljs`:
  1. `:life-zero` (line 106/123) — NOT TESTED for opponent (human tests exist)
  2. `:empty-library` (line 130/144) — TESTED (3 tests)
  3. `:token-cleanup` (line 156/168) — NOT TESTED
  4. `:lethal-damage` (line 184/201) — NOT TESTED (zero-toughness creature death)
  5. `:zero-toughness` (line 212/227) — NOT TESTED
  6. `:state-check-trigger` (line 297/312) — NOT TESTED (delayed triggers)

**Missing scenarios**:
- Human player SBA scenarios (currently only opponent tested):
  - Player loses to empty library draw
  - Player takes lethal damage
  - Player's creature dies to lethal damage
  - Player's permanent becomes zero-toughness
- Cascading SBAs: One SBA triggers conditions for another
  - Example: creature takes damage → zero-toughness → SBA removes it → state-check-trigger fires
- Token cleanup during end step

## Task 4: fizzle-rcaw (Resolve-Effects-SBA Chain)

**Current**: `src/test/fizzle/engine/resolution_test.cljs` (1 integration test using dark-ritual)

### Coverage
- Basic spell resolution: dark-ritual casts, executes mana effect, graveyard
- Effect execution with mana pool validation

### Critical Gaps

**Effect execution → SBA loop chain is NOT tested**:
- File: `src/main/fizzle/engine/resolution.cljs` line 29-80
  - Lines 29-43: `resolve-stack-item` multimethod (entry point)
  - Lines 46-80: :default defmethod for :spell type
  - Calls `effects/reduce-effects` (effects.cljs)
  - May return `{:db :needs-selection effect :remaining-effects [...]}`
- **NO tests** for:
  - Effect execution → SBA check → effect continues
  - Interactive effect interrupts reduce-effects loop
  - Multiple effects, some interactive, some interactive
  - Cascading: SBA fires → creates delayed trigger → adds to stack

**Example missing chain**:
1. Cast Cabal Ritual (threshold effect)
2. Execution reduces effects: `:add-mana` → SBA checks
3. SBA detects zero-toughness creature (unrelated to ritual)
4. SBA removes creature → state-check-trigger fires
5. Trigger added to stack as `:state-check-trigger` stack-item
6. Ritual effect execution paused? Or continues?

**Multi-effect spell chain NOT tested**:
- Card with 2+ effects in :card/effects array
- First effect interactive (tutor) → pause for selection
- Second effect non-interactive → resume and execute
- NO test for this pattern (see `effects_test.cljs` 574 tests are all unit; no integration)

## Key Data: File Locations & Line Numbers

### Engine Layer Architecture

| Module | File | Key Functions | Lines | Test File |
|--------|------|---|-------|-----------|
| Resolution | `engine/resolution.cljs` | resolve-stack-item (multimethod) | 29-43 | resolution_test.cljs |
| Effects | `engine/effects.cljs` | execute-effect-impl, reduce-effects | 100-200 | effects_test.cljs |
| SBA | `engine/state_based.cljs` | check-all-sbas, execute-sba | 38-83 | state_based_test.cljs |
| Triggers | `engine/triggers.cljs` | resolve-trigger, create-spell-copy | 16-170 | triggers_test.cljs |
| Trigger DB | `engine/trigger_db.cljs` | create-trigger-tx | 1-80 | trigger_db_test.cljs |
| Trigger Dispatch | `engine/trigger_dispatch.cljs` | dispatch-event | 1-150 | trigger_dispatch_test.cljs |
| Event: Resolution | `events/resolution.cljs` | resolve-one-item | 64-120 | resolution_test.cljs |

### Test Helper Functions Available

From `src/test/fizzle/test_helpers.cljs`:
- `create-test-db` (line 31) — create game state
- `add-card-to-zone` (line 53) — add card to specific zone
- `add-opponent` (line 203) — add bot opponent
- `add-test-creature` (line 148) — add creature to battlefield
- `cast-and-resolve` (line 260) — cast spell end-to-end
- `cast-with-target` (line 288) — cast targeted spell
- `resolve-top` (line 388) — resolve top stack item
- `confirm-selection` (line 399) — confirm interactive selection

## Implementation Notes for Test Writing

**Director Integration Pattern** (used in opponent_sba_test.cljs):
```clojure
(let [app-db (merge (history/init-history) {:game/db db})
      result (director/run-to-decision app-db {:yield-all? false})]
  (:game/db (:app-db result)))
```

**Spell Resolution Pattern** (used in resolution_test.cljs):
```clojure
(let [db (th/create-test-db {:mana {:black 3}})
      [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
      db (rules/cast-spell db :player-1 obj-id)
      spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db))
      stack-item (first spell-items)
      result (engine-resolution/resolve-stack-item db stack-item)]
  (:db result))
```

**SBA Execution Pattern** (used in opponent_sba_test.cljs):
```clojure
(let [db (sba/check-and-execute-sbas db-after-advance)
      flag (:game/loss-condition (q/get-game-state db))]
  (is (= :empty-library flag)))
```

## Risk Assessment

**HIGH RISK** (untested critical paths):
1. Spell → effect execution → SBA → loop (fizzle-rcaw)
2. Combat resolution chain: attackers → blockers → damage → SBA (fizzle-6p9p)
3. ETB trigger firing during :permanent-entered stack-item resolution (fizzle-6p9p)
4. Human player SBA scenarios (fizzle-t2j3)

**MEDIUM RISK** (partially tested):
1. Cascading SBAs (state-check-trigger → subsequent SBA)
2. Interactive effects interrupting multi-effect chains
3. Trigger dispatch filter matching

**LOW RISK** (well-tested):
1. Individual effect execution (effects_test.cljs 574 tests)
2. SBA condition detection (state_based_test.cljs 76 tests)
3. Basic spell resolution (resolution_test.cljs 30 tests)
