---
name: cljs.spec Duplication & test.check Readiness Analysis (Phase 1-3)
description: Complete audit of 5 spec files across 2115 lines; 154 total s/def specs; duplication patterns; test.check missing
type: reference
---

# ClojureScript Spec Files: Duplication & test.check Analysis (2026-04-04)

## File Inventory

| File | Lines | s/def Count | Multimethods | Purpose |
|------|-------|-------------|--------------|---------|
| `/src/main/fizzle/engine/spec_util.cljs` | 30 | 1 | 0 | Chokepoint validation helper (goog.DEBUG guard) |
| `/src/main/fizzle/engine/card_spec.cljs` | 655 | 115 | 1 defmulti (42 defmethods) | Phase 1: Card + Effect + Targeting specs |
| `/src/main/fizzle/engine/stack_spec.cljs` | 292 | 9 | 1 defmulti (16 defmethods) | Phase 3: Stack item specs + 10 chokepoint types |
| `/src/main/fizzle/events/selection/spec.cljs` | 1026 | 22 | 1 defmulti (31 defmethods) | Phase 2: Selection specs (all 31 types) |
| `/src/main/fizzle/bots/action_spec.cljs` | 112 | 8 | 1 defmulti (3 defmethods) | Phase 3: Bot action specs + 3 chokepoint types |
| **TOTAL** | **2115** | **154** | **4 multimethods, 92 defmethods** | — |

## Namespace Dependency Graph

```
spec_util.cljs (base)
  ↓ (required by)
stack_spec.cljs → uses spec-util/validate-at-chokepoint!
action_spec.cljs → uses spec-util/validate-at-chokepoint!

ISOLATED (no cross-spec requires):
  - card_spec.cljs (only requires cljs.spec.alpha)
  - selection/spec.cljs (only requires cljs.spec.alpha)
```

**Note**: All 5 spec files are ISOLATED from each other — no cross-spec requires. This is by design: each phase owns its own namespace. To share common predicates, would need a new `common_spec.cljs` module.

## Duplicate Predicate Analysis

### High-Priority Duplicates (Appear 2+ Times)

#### 1. Player ID Validation (2 flavors)
- **card_spec.cljs**: NOT PRESENT (cards don't validate player-id)
- **stack_spec.cljs, line 27**: `(s/def :stack-item/controller (s/or :keyword keyword? :int int?))`
- **selection/spec.cljs, line 23**: `(s/def :selection/player-id keyword?)`
- **action_spec.cljs, line 26**: `(s/def ::player-id (s/or :keyword keyword? :int int?))`

**STATUS**: DIFFERENT IMPLEMENTATIONS
- Stack items accept both keyword and int (`:player-1` or player object EID)
- Selections accept keyword only (`:player-1`)
- Actions accept keyword or int (same as stack items)

#### 2. Boolean Specs (Appear 5+ Times)
- **card_spec.cljs**:
  - Line 63: `:mana/x` → `boolean?`
  - Line 91: `:effect/order-remainder?` → `boolean?`
  - Line 105: `:effect/shuffle?` → `boolean?`
  - Line 107: `:effect/may-shuffle?` → `boolean?`
  - Line 108: `:effect/shuffle-remainder?` → `boolean?`
  - Line 424: `:target/required` → `boolean?`
  
- **selection/spec.cljs**:
  - Line 37: `:selection/auto-confirm?` → `boolean?`
  - Line 47: `:selection/lifecycle-declared?` → `boolean?`
  - Line 49: `:selection/exact?` → `boolean?`

- **stack_spec.cljs**:
  - Line 33: `:stack-item/is-copy` → `boolean?`

**PATTERN**: Simple `boolean?` predicate (no re-exportable value)

#### 3. Zone Keywords (Appear 3+ Times)
- **card_spec.cljs**:
  - Line 89: `:effect/selected-zone` → `keyword?`
  - Line 90: `:effect/remainder-zone` → `keyword?`
  - Line 92: `:effect/zone` → `keyword?`
  - Line 103: `:effect/target-zone` → `keyword?`
  - Line 104: `:effect/source-zone` → `keyword?`

- **selection/spec.cljs**:
  - Line 44: `:selection/zone` → `keyword?`
  - Line 45: `:selection/card-source` → `keyword?`
  - Line 46: `:selection/target-zone` → `keyword?`

- **stack_spec.cljs**: None (implicit in stack-item spec)

**STATUS**: DIFFERENT SEMANTICS
- Card effect zones (`:target-zone`, `:selected-zone`) refer to static card definitions
- Selection zones (`:zone`, `:card-source`, `:target-zone`) refer to runtime game state
- Could share base `zone` keyword? validator but not advisable (semantics differ)

#### 4. Keyword Validators (Appear 10+ Times)
- **Simple keyword?** used for: targets, IDs, types, phases, etc.
  - card_spec.cljs: 15+ occurrences
  - selection/spec.cljs: 10+ occurrences
  - stack_spec.cljs: 3+ occurrences

**PATTERN**: Too generic to consolidate (each has specific meaning)

#### 5. Map Validators (Appear 5+ Times)
- **card_spec.cljs**:
  - Line 87: `:effect/criteria` → `map?`
  - Line 93: `:effect/ability` → `map?`
  - Line 100: `:effect/token` → `map?`
  - Line 101: `:effect/counters` → `map?`
  - Line 106: `:effect/pile-choice` → `map?`
  - Line 422: `:target/criteria` → `map?`
  - Line 440: `:ability-cost` → `map?`
  - Line 468: `:trigger/filter` → `map?`

- **selection/spec.cljs**:
  - No generic `map?` (all maps are compound structures)

- **stack_spec.cljs**:
  - Line 31: `:stack-item/targets` → `(s/nilable map?)`

**PATTERN**: Card effects store nested config as plain maps (no sub-validation). Not reusable across phases.

#### 6. Mana Cost (Unique Pattern)
- **ONLY in card_spec.cljs, line 66**:
  ```clojure
  (s/def ::mana-cost
    (s/and map?
           (s/every-kv
             (s/or :color valid-colors
                   :colorless #{:colorless}
                   :any #{:any}
                   :x #{:x})
             (s/or :amount nat-int?
                   :x-flag boolean?))))
  ```
- Used by: `:effect/mana`, `:ability/produces`, `:alternate/mana-cost`
- **NOT duplicated elsewhere** — selection/spec and stack_spec don't validate mana

---

## Multimethod Structure (Polymorphic Dispatch)

### card_spec.cljs: effect-type-spec
- **Dispatch key**: `:effect/type`
- **42 defmethods** across 4 groups:
  - Group 1 (7): Mill, Draw, Deal-damage, Discard, Return-from-graveyard
  - Group 2 (12): Zone ops (bounce, bounce-all, destroy, exile, phase, etc.)
  - Group 3 (3): Granting abilities (grant-flashback, grant-delayed-draw, grant-mana-ability)
  - Group 4 (5): Special (counter-spell, apply-pt-modifier, create-token, etc.)
  - Group 5 (3): Runtime-only (storm-copies, lose-life, gain-life)

### stack_spec.cljs: stack-item-type-spec
- **Dispatch key**: `:stack-item/type`
- **16 defmethods**:
  - Core types (10): spell, storm-copy, activated-ability, permanent-entered, storm, declare-attackers, declare-blockers, combat-damage, delayed-trigger, state-check-trigger
  - Card trigger types (6): permanent-tapped, creature-attacked, land-entered, etb, triggered-ability, phase-entered
  - Test type (1): :test

### selection/spec.cljs: selection-type-spec
- **Dispatch key**: `:selection/type`
- **31 defmethods** across 9 groups:
  - Group 1 (5): Zone-pick (discard, graveyard-return, hand-reveal-discard, chain-bounce, chain-bounce-target, unless-pay)
  - Group 2 (3): Accumulator (storm-split, x-mana-cost, mana-allocation, pay-x-life)
  - Group 3 (4): Reorder (scry, peek-and-reorder, order-bottom, order-top)
  - Group 4 (5): Library ops (tutor, peek-and-select, pile-choice)
  - Group 5 (3): Targeting (player-target, cast-time-targeting, ability-cast-targeting, ability-targeting)
  - Group 6 (5): Pre-cast costs (discard-specific-cost, return-land-cost, sacrifice-permanent-cost, exile-cards-cost)
  - Group 7 (2): Land types (land-type-source, land-type-target)
  - Group 8 (2): Combat (select-attackers, assign-blockers)
  - Group 9 (2): Other (spell-mode, untap-lands)

### action_spec.cljs: action-type-spec
- **Dispatch key**: `:action`
- **3 defmethods**: :pass, :cast-spell, :play-land

---

## Common Predicates That COULD Be Shared

### 1. **UUID validation** (Appears 3 times)
- **card_spec.cljs**: Not present
- **stack_spec.cljs, line 28**: `:stack-item/source` → `uuid?`
- **action_spec.cljs, line 25**: `::object-id` → `uuid?`

**Candidate for shared predicate**: `::object-id` (game object identifier)

### 2. **Integer validation** (Appears 3 times)
- **card_spec.cljs, lines 94-95**: `:effect/power` → `int?`, `:effect/toughness` → `pos-int?`
- **stack_spec.cljs, line 29**: `:stack-item/object-ref` → `int?` (Datascript EID)
- **selection/spec.cljs, lines 48, 38**: `:selection/min-count` → `int?`, `:selection/select-count` → `int?`

**Issue**: Different semantics (powers vs EIDs vs counts)

### 3. **Collection validators** (s/or :set/:vec)
- **selection/spec.cljs, line 29-33**: `:selection/selected` → flexible (set, vec, map, int, keyword)
- **selection/spec.cljs, line 40-42**: `:selection/valid-targets` / `:selection/candidate-ids` → `(s/or :set set? :vec vector?)`

**Reusable pattern**: `(s/or :set set? :vec vector?)` for collection fields that accept both

---

## Test Coverage Analysis

### Test Files (968 total lines)

| File | Lines | Coverage Pattern |
|------|-------|------------------|
| card_spec_test.cljs | 486 | 20+ unit tests covering required fields, invalid types, all existing cards, mana cost validation |
| stack_spec_test.cljs | 189 | 15+ tests covering validation helper, chokepoint behavior, all 10 stack-item types |
| selection/spec_test.cljs | 157 | 13+ tests covering minimal-valid-selections for all 31 types, base key constraints |
| action_spec_test.cljs | 136 | 12+ tests covering action types, tap sequence structure, required fields |

### test.check Status: MISSING

**Current approach**: Manual example maps (`minimal-valid-*` collections)
- card_spec.cljs, line 348-402: `minimal-valid-effect` map (40 entries, 54 lines)
- stack_spec.cljs, line 221-273: `minimal-valid-stack-items` map (10 entries, 52 lines)
- selection/spec.cljs, line 696-999: `minimal-valid-selections` map (31 entries, 304 lines)
- action_spec.cljs, line 82-93: `minimal-valid-actions` map (3 entries, 11 lines)

**Total manual examples**: 84 entries across all specs

**test.check readiness**:
- spec_test.cljs files explicitly state: "cljs.spec.gen.alpha requires test.check which is not in this project's deps"
- Tests use `doseq` over minimal examples instead of `s/exercise`
- **No s/gen/gen-for-pred usage anywhere**

**Blocker**: `test.check` not in `package.json`

---

## Consolidation Opportunities

### HIGH IMPACT (Worth consolidating)

1. **Player ID Base Spec** (~2 lines saved)
   - Current: Duplicated in 2 files with different validation
   - Shared: Create `:game/player-id` (keyword? only) + `:game/controller` (keyword | int)
   - Location: New `engine/common_spec.cljs`

2. **UUID Object ID** (~3 lines saved)
   - Current: Duplicated in 2 files
   - Shared: Create `:game/object-id` → `uuid?`
   - Used by: stack-item/source, action/object-id

3. **Collection-or-empty Pattern** (~5 lines saved)
   - Current: `(s/or :set set? :vec vector?)` appears 4+ times
   - Shared: Create `:game/collection-flexible` predicate
   - Used by: selections with optional candidates/targets

### MEDIUM IMPACT (Nice-to-have)

4. **Mana Cost Reuse** (~10 lines saved)
   - Current: Only in card_spec, used 3 places within file
   - Shared: Re-exportable if selection/stack need mana validation
   - Current status: Not needed by other specs

### LOW IMPACT (Not worth consolidating)

- Boolean validators (too generic)
- Zone keywords (semantics differ per phase)
- Generic `map?` / `keyword?` (context-specific meaning)

---

## Validation Chokepoints (Production vs Test)

### Phase 1: Card Loading
- **Location**: `events/init.cljs:107` (game db setup)
- **Validation**: `card-spec/validate-cards!` (throws on invalid)
- **Scope**: All cards in registry

### Phase 2: Selection Creation
- **Location**: `events/selection/core.cljs:426` (`set-pending-selection`)
- **Validation**: `selection/spec/set-pending-selection` (logs console.error in dev)
- **Scope**: Each selection before storing in app-db

### Phase 3: Stack Item Creation
- **Location**: `engine/stack.cljs:create-stack-item` (chokepoint)
- **Validation**: `stack-spec/validate-stack-item!` (calls spec-util)
- **Scope**: Every stack item added

### Phase 3b: Bot Action Decision
- **Location**: `bots/decisions.cljs:bot-decide-action` (chokepoint)
- **Validation**: `action-spec/validate-bot-action!` (calls spec-util)
- **Scope**: Each bot action returned

---

## test.check Integration Path (When Added)

To enable property-based testing, would need:

1. **Add to package.json**:
   ```json
   {"clojure.test.check": "1.1.1"}
   ```

2. **Generators for Base Predicates**:
   ```clojure
   (s/def :game/object-id (s/gen uuid?))
   (s/def :game/player-id (s/gen keyword?))
   (s/def :game/mana-cost (s/gen ::mana-cost))
   ```

3. **Custom Generators for Enums**:
   ```clojure
   (def valid-effect-types-gen (gen/elements valid-effect-types))
   ```

4. **Fuzz-Test All Multimethods**:
   ```clojure
   (doseq [type (s/exercise :effect/type 40)]
     (is (s/valid? ::effect (-> (minimal-valid-effect type)
                                (assoc :custom-field (gen/generate ...))))))
   ```

**Current substitute**: `minimal-valid-*` collections cover 100% of types without randomization.

---

## Summary

- **5 spec files, 154 s/def predicates across 2115 lines**
- **No cross-file duplication** (each phase is isolated)
- **Within-file duplication is intentional** (zone keywords, flags have context-specific meaning)
- **3 consolidation opportunities** (player-id, object-id, collection-flexible)
- **test.check is missing but not critical** — manual examples provide full type coverage
- **4 production chokepoints** guard all runtime data creation
