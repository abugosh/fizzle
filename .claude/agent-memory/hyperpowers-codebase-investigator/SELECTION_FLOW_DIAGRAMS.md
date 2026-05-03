# Selection System Flow Diagrams

## 1. Cast-Spell Flow (No Selection Case)

```
Player clicks card
    ↓
::cast-spell event (game.cljs)
    ↓
cast-spell-handler
    ├─ Modal spell? → show spell-mode-selection
    └─ No → initiate-cast-with-mode
            ├─ X cost? → ::x-mana-cost selection
            ├─ Targeting? → ::cast-time-targeting selection
            ├─ Generic mana? → ::mana-allocation selection
            └─ None → rules/cast-spell-mode
                        ↓
                    Spell moves to stack
                    Stack-item created with targets stored
```

## 2. Cast-Spell with Targeting

```
rules/cast-spell-mode → Spell on stack with :stack-item/targets {target-id target-value}
                              ↓
                        [Stored targets frozen at cast time]
```

## 3. Resolution and Interactive Effects

```
::resolve-one-item (game.cljs:453)
    ↓
resolve-stack-item (engine/resolution.cljs)
    ├─ Gets stack-item
    ├─ Reads :stack-item/targets (single source of truth)
    ├─ Pre-resolves all target-refs to concrete IDs
    └─ reduce-effects (engine/effects.cljs)
            ↓
        Loop: [effect & remaining]
            ↓
        execute-effect-checked
            ├─ NOT interactive? → update db, recur with remaining
            └─ Interactive?
                    ↓
                Return {:db db :needs-selection effect :remaining-effects [rest]}
                    ↓
        build-selection-from-result (game.cljs:428)
            ├─ Effect type → dispatch build-selection-for-effect
            ├─ Domain module builds selection state
            └─ Returns {:db db :pending-selection selection}
                    ↓
        UI shows selection (::toggle-selection events)
                    ↓
        Player confirms (::confirm-selection)
            ↓
        confirm-selection-impl (selection/core.cljs:129)
            ├─ Validates selection
            ├─ Calls execute-confirmed-selection multimethod
            ├─ Returns one of:
            │   ├─ {:db db :pending-selection next-sel}    → Chain to next
            │   ├─ {:db db :finalized? true}               → Done
            │   └─ {:db db}                                → Resume remaining-effects
            │
            └─ If standard return:
                    ├─ reduce via effects/execute-effect for remaining-effects
                    ├─ If another interactive found, build new selection + loop
                    ├─ cleanup-selection-source (remove spell from stack)
                    └─ Return {:db db'} (or chain to continuation)
```

## 4. Multi-Selection Chaining

```
Spell with effects: [TUTOR, DISCARD]
    ↓
resolve-one-item
    ↓
TUTOR effect → needs-selection
    ↓
build-selection → :tutor selection
    ↓
Player confirms tutor selection
    ↓
execute-confirmed-selection :tutor
    ├─ No pile-choice? → {:db db'} (standard)
    │       ↓
    │   confirm-selection-impl
    │       ├─ reduce remaining-effects: [DISCARD]
    │       ├─ DISCARD → needs-selection
    │       ├─ build-selection → :discard selection
    │       ├─ Chain: {:db db :pending-selection discard-sel}
    │       ↓
    │   NEW pending-selection in app-db
    │
    └─ Pile-choice? → {:db db :pending-selection pile-sel} (chain)
            ↓
        NEW pending-selection in app-db
```

**Key**: Remaining effects are **cloned into each selection state**. When confirm happens, they're resumed via `reduce`.

## 5. Targeting Resolution Timeline

### Cast-Time Targeting (Pre-Stack)

```
Player wants to cast Crumble (destroy target artifact)
    ↓
initiate-cast-with-mode
    ├─ Card has :card/targeting? YES
    ├─ build-cast-time-target-selection (targeting.cljs:41)
    │   ├─ Find valid targets (artifacts on battlefield)
    │   └─ Return :cast-time-targeting selection
    ├─ UI shows target choices
    └─ Player selects artifact
            ↓
        confirm-selection (:cast-time-targeting)
            ├─ Call confirm-cast-time-target (targeting.cljs:115)
            │   ├─ rules/cast-spell-mode (cast spell)
            │   ├─ Find spell's stack-item
            │   └─ d/db-with [:db/add SI :stack-item/targets {target-id artifact-id}]
            │       (Store target on stack-item)
            └─ return {:db db :finalized? true}
                    ↓
                Spell is now on stack with target stored
```

### Resolution-Time Targeting (Via :any-player)

```
Spell on stack with effect: {:effect/type :draw :effect/target :any-player}
    ↓
resolve-one-item → reduce-effects
    ↓
execute-effect-checked for DRAW effect
    ├─ effect/target is :any-player
    ├─ Handler detects → return {:db db :needs-selection effect}
    └─ reduce pauses
            ↓
        build-selection-for-effect dispatches on :player-target (special case)
            ├─ build-player-target-selection
            └─ Return :player-target selection
                    ↓
                UI shows player choices
                    ↓
                Player selects target player
                    ↓
                execute-confirmed-selection :player-target
                    ├─ Resolve :any-player → selected player
                    ├─ Execute DRAW effect with concrete target
                    ├─ reduce remaining-effects
                    └─ {:db db} or chain
```

**Difference**: Cast-time targets are frozen before spell enters stack. Resolution-time targets are determined as spell resolves.

## 6. Modal Spell Flow

```
Player clicks modal spell (e.g., spell with :card/modes)
    ↓
cast-spell-handler
    ├─ check get-valid-spell-modes
    │   (filter by target validity per mode)
    ├─ Multiple valid modes? → set :game/pending-spell-mode-selection
    │       ↓
    │   UI shows mode picker
    │       ↓
    │   Player selects mode
    │       ↓
    │   Mode-select event → call initiate-cast-with-mode with chosen mode
    │
    └─ Single mode → proceed to initiate-cast-with-mode
            ↓
        Check mode's cost requirements
            ├─ X cost? → x-mana-cost selection
            ├─ Targeting? → cast-time-targeting selection (per-mode targeting)
            ├─ Generic? → mana-allocation selection
            └─ None → rules/cast-spell-mode
```

**Note**: Spell-mode-selection is stored in app-db as `:game/pending-spell-mode-selection`, NOT `:game/pending-selection`. These are separate from the selection system.

## 7. Storm Split Selection (Special Case)

```
Targeted storm spell (e.g., Brain Freeze with targets) resolves
    ↓
resolve-stack-item :storm (resolution.cljs:264)
    ├─ has-targeting? YES
    └─ Return {:db db :needs-storm-split true}  [SPECIAL SIGNAL]
            ↓
        resolve-one-item (game.cljs)
            ├─ Detects :needs-storm-split
            ├─ build-storm-split-selection
            │   ├─ Copy count from effect
            │   ├─ Valid targets = opponent + self
            │   └─ Default allocation: all copies on first target
            └─ Return {:db db :pending-selection storm-split-sel}
                    ↓
                UI shows allocation steppers
                    ↓
                Player allocates copies across targets
                    ↓
                execute-confirmed-selection :storm-split
                    ├─ For each target: triggers/create-spell-copy with :player target
                    ├─ stack/remove-stack-item (consume original :storm SI)
                    └─ Return {:db db :finalized? true}
                            ↓
                        Copies now on stack, each with their target
```

**Key**: Storm-split **creates copies and consumes the original stack-item**. NOT a "remaining-effects" scenario.

## 8. Data Transformation Across Boundaries

```
Core (engine/) → Orchestration (events/)
    ↓
Effect in reduce-effects
    ├─ :effect/type :tutor
    ├─ :effect/criteria {:match/types #{:instant}}
    ├─ :effect/count 1
    └─ :effect/target :controller
            ↓
        [Convert to Selection]
            ↓
Selection state
    ├─ :selection/type :tutor
    ├─ :selection/candidates #{card-id-1 card-id-2 ...}  [Pre-computed matches]
    ├─ :selection/select-count 1
    └─ :selection/player-id player-id

    [Player toggles, confirms]
    ↓
execute-confirmed-selection :tutor
    ├─ Read :selection/selected #{chosen-card-id}
    ├─ zones/move-to-zone to :hand
    ├─ zones/shuffle-library
    └─ Return {:db db'} or chain
```

**Key transformation**: Effect's criteria become Selection's pre-computed candidates. Player picks from UI, not from criteria.

## 9. Source Cleanup Paths

```
Selection confirmed for SPELL (not ability)
    ↓
confirm-selection-impl
    ├─ execute-confirmed-selection
    ├─ reduce remaining-effects
    │
    └─ cleanup-selection-source (selection/core.cljs:100)
            ├─ :selection/source-type = :stack-item?
            │   └─ NO (it's a spell)
            │
            └─ Get :selection/spell-id
                ├─ queries/get-object to find spell
                ├─ Check :object/zone
                └─ If :stack:
                    ├─ remove-spell-stack-item (lookup by EID)
                    └─ move-resolved-spell (single source of truth)
                        ├─ Is copy? → zones/remove-object
                        └─ Not copy → zone per mode/type
                            ├─ :mode/on-resolve? → that zone
                            ├─ Permanent type? → :battlefield
                            └─ Else → :graveyard
```

**Invariant**: `resolution/move-resolved-spell` is consulted for spell destination, never hardcoded elsewhere.

## 10. Implicit Ordering Dependencies

```
Timeline:
1. Effect pre-resolution (stack-item targets frozen)
2. reduce-effects loop starts
3. execute-effect-checked (may need-selection)
4. If interactive: pause with remaining-effects vec
5. Selection confirmed
6. Resume remaining-effects via reduce
7. Cleanup (remove spell from stack)
8. (Optionally) apply-continuation

CRITICAL ORDER:
- cleanup happens AFTER remaining-effects
- grants expire AFTER cleanup discard (cleanup? flag)
- pre-resolve happens BEFORE reduce-effects
```
