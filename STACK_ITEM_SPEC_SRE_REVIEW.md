# Stack-Item Spec SRE Review — Phase 3 Field Catalog & Validation Strategy

**Date**: 2026-04-03
**Scope**: Verify all 9 stack-item types, exact field lists, validation chokepoint, and goog.DEBUG pattern before spec implementation

---

## 1. CREATE-STACK-ITEM FUNCTION SIGNATURE

**File**: `src/main/fizzle/engine/stack.cljs:29-40`

```clojure
(defn create-stack-item
  "Create a stack-item entity in Datascript.

   attrs is a map with required keys: :stack-item/type, :stack-item/controller
   Optional keys: :stack-item/source, :stack-item/effects, :stack-item/targets,
                  :stack-item/description, :stack-item/is-copy, :stack-item/cast-mode,
                  :stack-item/object-ref

   Auto-assigns :stack-item/position. Caller MUST NOT set position.
   Returns updated db."
  [db attrs]
  (d/db-with db [(merge attrs {:stack-item/position (get-next-stack-order db)})]))
```

**Key findings**:
- ✓ Auto-assigns `:stack-item/position` via `get-next-stack-order` (shares counter with object positions on stack)
- ✓ **REQUIRED** keys: `:stack-item/type`, `:stack-item/controller`
- ✓ **OPTIONAL** keys listed in docstring (not exhaustive — actual usage differs)
- ✓ Pure function; returns db with merged entity

---

## 2. EXACT FIELD LISTS FOR EACH STACK-ITEM TYPE

### Type 1: `:spell` 
**Created in**: `src/main/fizzle/engine/rules.cljs:320-324` (via `create-spell-stack-item`)

```clojure
{:stack-item/type :spell
 :stack-item/controller player-id
 :stack-item/source object-id
 :stack-item/object-ref obj-eid}
```

**Minimal required**: type, controller, source, object-ref
**Resolved by**: `resolve-stack-item :spell` (line 223-225)
**Reads**: `:stack-item/controller`, `:stack-item/object-ref` (then fetches object from db)

---

### Type 2: `:storm`
**Created in**: `src/main/fizzle/engine/rules.cljs:304-310`

```clojure
{:stack-item/type :storm
 :stack-item/controller player-id
 :stack-item/source object-id
 :stack-item/effects [{:effect/type :storm-copies
                       :effect/count copy-count}]
 :stack-item/description (str "Storm - create " copy-count " copies")}
```

**Minimal required**: type, controller, source, effects
**Optional**: description
**Resolved by**: `resolve-stack-item :storm` (line 306-335)
**Reads**: `:stack-item/controller`, `:stack-item/source`, `:stack-item/effects`, `:stack-item/targets` (can be nil)

---

### Type 3: `:storm-copy`
**Created in**: `src/main/fizzle/engine/triggers.cljs:79-86`

```clojure
(cond-> {:stack-item/type :storm-copy
         :stack-item/controller controller-id
         :stack-item/source source-object-id
         :stack-item/object-ref copy-eid
         :stack-item/is-copy true}
  targets
  (assoc :stack-item/targets targets))
```

**Minimal required**: type, controller, source, object-ref, is-copy
**Optional**: targets, description
**Resolved by**: `resolve-stack-item :storm-copy` (line 228-230, uses same path as :spell)
**Reads**: `:stack-item/controller`, `:stack-item/object-ref`, `:stack-item/targets` (can be nil)

---

### Type 4: `:activated-ability`
**Created in**: `src/main/fizzle/events/abilities.cljs:131-137` (two variants)

**Variant A (with targeting)**:
```clojure
(cond-> {:stack-item/type :activated-ability
         :stack-item/controller player-id
         :stack-item/source object-id
         :stack-item/effects effects-list
         :stack-item/targets updated-chosen
         :stack-item/description (:ability/description ability)}
  pending-sacrifice-info
  (assoc :stack-item/sacrifice-info pending-sacrifice-info))
```

**Variant B (simple, no targeting)**:
```clojure
{:stack-item/type :activated-ability
 :stack-item/controller player-id
 :stack-item/source object-id
 :stack-item/effects (:ability/effects ability [])
 :stack-item/description (:ability/description ability)}
```

**Minimal required**: type, controller, source, effects
**Optional**: targets, description, sacrifice-info
**Resolved by**: `resolve-stack-item :activated-ability` (line 269-289)
**Reads**: `:stack-item/controller`, `:stack-item/source`, `:stack-item/effects`, `:stack-item/targets` (can be nil)

---

### Type 5: `:permanent-entered` (ETB Trigger)
**Created in**: `src/main/fizzle/engine/trigger_dispatch.cljs:132-139` (via event dispatch)

```clojure
(cond-> {:stack-item/type (:trigger/type st)    ;; ← triggers determine type
         :stack-item/controller (:trigger/controller st)
         :stack-item/effects (get-in st [:trigger/data :effects] [])}
  (:trigger/source st)
  (assoc :stack-item/source (:trigger/source st))
  (:trigger/description st)
  (assoc :stack-item/description (:trigger/description st)))
```

**Where trigger type = `:permanent-entered`**:
```clojure
{:stack-item/type :permanent-entered
 :stack-item/controller controller-id
 :stack-item/source object-id              ;; optional
 :stack-item/effects effects-list          ;; from trigger data
 :stack-item/description description-str}  ;; optional
```

**Minimal required**: type, controller, effects
**Optional**: source, description
**Resolved by**: `resolve-stack-item :permanent-entered` (line 237-244)
**Reads**: `:stack-item/controller`, `:stack-item/source`, `:stack-item/effects`

---

### Type 6: `:declare-attackers`
**Created in**: `src/main/fizzle/engine/combat.cljs:218-221`

```clojure
{:stack-item/type :declare-attackers
 :stack-item/controller active-player-id
 :stack-item/description "Declare Attackers"}
```

**Minimal required**: type, controller
**Optional**: description
**Resolved by**: `resolve-stack-item :declare-attackers` (line 342-352)
**Reads**: `:stack-item/controller` only (signals needs-attackers)

---

### Type 7: `:declare-blockers`
**Created in**: `src/main/fizzle/events/selection/combat.cljs:40-43`

```clojure
{:stack-item/type :declare-blockers
 :stack-item/controller controller
 :stack-item/description "Declare Blockers"}
```

**Minimal required**: type, controller
**Optional**: description
**Resolved by**: `resolve-stack-item :declare-blockers` (line 355-365)
**Reads**: `:stack-item/controller` (signals needs-blockers)

---

### Type 8: `:combat-damage`
**Created in**: `src/main/fizzle/events/selection/combat.cljs:36-39`

```clojure
{:stack-item/type :combat-damage
 :stack-item/controller controller
 :stack-item/description "Combat Damage"}
```

**Minimal required**: type, controller
**Optional**: description
**Resolved by**: `resolve-stack-item :combat-damage` (line 368-371)
**Reads**: `:stack-item/controller` only

---

### Type 9: `:delayed-trigger`
**Created in**: `src/main/fizzle/engine/turn_based.cljs:83-87`

```clojure
{:stack-item/type :delayed-trigger
 :stack-item/controller player-id
 :stack-item/effects [effect]
 :stack-item/description description}
```

**Minimal required**: type, controller, effects
**Optional**: description
**Resolved by**: Dispatches to `:default` handler (line 46-69)
**Reads**: `:stack-item/controller`, `:stack-item/source` (nil check), `:stack-item/effects`, `:stack-item/targets` (can be nil)

---

### Type 10: `:state-check-trigger`
**Created in**: `src/main/fizzle/engine/state_based.cljs:324-329`

```clojure
(cond-> {:stack-item/type :state-check-trigger
         :stack-item/controller controller-id
         :stack-item/source object-id
         :stack-item/effects effects-list}
  description
  (assoc :stack-item/description description))
```

**Minimal required**: type, controller, source, effects
**Optional**: description
**Resolved by**: Dispatches to `:default` handler (line 46-69)
**Reads**: `:stack-item/controller`, `:stack-item/source`, `:stack-item/effects`, `:stack-item/targets` (can be nil)

---

## 3. COMPLETE FIELD ENUMERATION

**Used by all stack-item types** (discoverable from creation sites and resolution.cljs):

| Field | Type | Required? | Used by | Notes |
|-------|------|-----------|---------|-------|
| `:stack-item/type` | keyword | ✓ YES | All | Dispatch key in resolve-stack-item multimethod |
| `:stack-item/controller` | keyword | ✓ YES | All | Player ID (:player-1 or :player-2); never read from parameter |
| `:stack-item/position` | int | auto | All | Auto-assigned by create-stack-item; never set by caller |
| `:stack-item/source` | keyword/uuid | No | spell, storm, activated-ability, delayed-trigger, state-check-trigger | Object ID of source object |
| `:stack-item/object-ref` | int | No | spell, storm-copy | Datascript entity ID (backward compat with object/position) |
| `:stack-item/effects` | vector | No | spell, storm, activated-ability, permanent-entered, delayed-trigger, state-check-trigger | List of effect maps; default [] |
| `:stack-item/targets` | map | No | spell, storm, storm-copy, activated-ability | Cast-time targeting map {ref-id → object-id, :player → player-id} |
| `:stack-item/description` | string | No | All | Human-readable label for UI |
| `:stack-item/is-copy` | boolean | No | storm-copy | Marks spell as copy (triggers special removal logic) |
| `:stack-item/cast-mode` | map | No | Not directly used in resolution | Stored on object, not stack-item (should verify) |
| `:stack-item/chosen-x` | int | No | Not found in resolution.cljs | Checked in stack.cljs line 68 query but never read |
| `:stack-item/sacrifice-info` | ? | No | activated-ability | Used in ability targeting flow (verify exact structure) |

---

## 4. FIELD USAGE: :source VS :object-ref

**Both fields exist and serve different purposes**:

| Field | Type | Purpose | Stack-item types | Notes |
|-------|------|---------|-------------------|-------|
| `:stack-item/source` | keyword/uuid | Object ID; used by effects/triggers to identify source | spell, storm, storm-copy, activated-ability, permanent-entered, delayed-trigger, state-check-trigger | Read in resolution to pass to effects/reduce-effects |
| `:stack-item/object-ref` | int | Datascript entity ID for backward compat with object/position | spell, storm-copy | Used to look up object in db; then get object-id from it |

**Key insight**: 
- `:stack-item/source` = **Object ID** (UUID) — application-level identifier
- `:stack-item/object-ref` = **Datascript EID** (int) — database entity ID; only used for spell/storm-copy zone transitions
- These are **NOT the same thing**; `:object-ref` is a Datascript implementation detail for resolving the spell to move it off stack

**Resolution.cljs reads**:
- Line 206: `(let [obj-ref-raw (:stack-item/object-ref stack-item)]` — spell/storm-copy only
- Line 49, 240, 272: `:stack-item/source` — not type-specific; used when source exists

---

## 5. VALIDATION CHOKEPOINT (Phase 2 Pattern)

**Pattern location**: `src/main/fizzle/events/selection/spec.cljs`

**Chokepoint**: `set-pending-selection` (lines ~480-492)

```clojure
(defn set-pending-selection
  "Set pending selection on app-db. Validates during dev.
   In dev (goog.DEBUG): validates selection via spec, logs console.error on failure.
   In prod: no validation (dead-code eliminated by Closure compiler).
   Always returns updated app-db. Does NOT throw."
  [app-db selection]
  (when ^boolean goog.DEBUG
    (when-not (s/valid? ::selection selection)
      (js/console.error
        "set-pending-selection: invalid selection map:"
        (s/explain-str ::selection selection))))
  (assoc app-db :game/pending-selection selection))
```

**For Phase 3 (stack-item spec), the equivalent chokepoint would be**:
- `create-stack-item` in `src/main/fizzle/engine/stack.cljs:29`
- OR a wrapper function called before all 10 creation sites
- Validation logic: dev-only via `goog.DEBUG`, logs console.error, does NOT throw

---

## 6. GOOG.DEBUG DURING TEST EXECUTION

**Finding**: goog.DEBUG is **NOT mocked** in any test file.

```bash
$ grep -r "goog\.DEBUG" src/test --include="*.cljs"
# (no output — not found)
```

**Shadow-cljs test target** (`shadow-cljs.edn:22-26`):
```clojure
:test
{:target :node-test
 :output-to "out/test.js"
 :ns-regexp "-test$"
 :autorun true}
```

**Analysis**:
- Test target uses `:target :node-test` (not `:advanced` optimizations)
- Shadow-cljs defaults: no optimization flag = `:none` (preserves all code including goog.DEBUG blocks)
- In Node test environment, `goog.DEBUG` is **true by default**
- This means `set-pending-selection` validation **runs during tests** without explicit mocking
- Tests inherit the Phase 2 validation for free — no pattern to change

**Implication for Phase 3**:
- Same goog.DEBUG pattern applies to stack-item spec
- Validation runs automatically in tests (no setup needed)
- Release build (`:simple` optimizations in `release` config) will dead-code-eliminate the goog.DEBUG block

---

## 7. PHASE 2 TEST PATTERN (ALREADY WORKING)

**File**: `src/test/fizzle/events/selection/spec_test.cljs:12-25`

```clojure
(deftest test-exercise-generates-valid-selections
  (testing "spec can generate valid selection maps for all 31 types"
    (doseq [[sel-type minimal] sel-spec/minimal-valid-selections]
      (is (s/valid? ::sel-spec/selection minimal)
          (str "Failed for type " sel-type ": "
               (s/explain-str ::sel-spec/selection minimal))))))

(deftest test-minimal-selections-count
  (testing "exactly 31 selection types covered"
    (is (= 31 (count sel-spec/minimal-valid-selections)))))
```

**Pattern**: 
- Tests use `s/valid?` directly (not mocking goog.DEBUG)
- Tests create `minimal-valid-selections` map with one entry per type
- `s/explain-str` for debugging failure messages

**For Phase 3** (stack-item spec):
- Define `minimal-valid-stack-items` map with 10 entries (one per type)
- Test each via `s/valid? ::stack-item/item minimal`
- Test count and per-type required fields

---

## 8. CRITICAL GAPS & EDGE CASES

### 8.1 Field Presence by Type

**Spec challenge**: `:stack-item/effects` is optional (default []) but:
- :spell — may have effects? (NO — effects come from card definition at resolution time)
- :storm — MUST have effects (contains :storm-copies effect)
- :activated-ability — MUST have effects
- :permanent-entered — MUST have effects (ETB triggers have effects)
- :declare-attackers — NO effects
- :declare-blockers — NO effects
- :combat-damage — NO effects
- :delayed-trigger — MUST have effects
- :state-check-trigger — MUST have effects

**→ Spec must make `:stack-item/effects` required OR vary by type**

### 8.2 Source Optionality

**From creation sites**:
- :spell — source REQUIRED
- :storm — source REQUIRED
- :storm-copy — source REQUIRED
- :activated-ability — source REQUIRED
- :permanent-entered — source OPTIONAL (from trigger-dispatch cond→)
- :declare-attackers — NO source
- :declare-blockers — NO source
- :combat-damage — NO source
- :delayed-trigger — NO source
- :state-check-trigger — source REQUIRED

**→ Spec must make `:stack-item/source` optional but validate per-type requirement**

### 8.3 :chosen-x Field

**From stack.cljs line 68**: 
- Pulled in query: `[... :stack-item/chosen-x ...]`
- Never read by resolution.cljs
- Not documented in create-stack-item docstring

**→ Dead code? Or used elsewhere?** Verify before adding to spec.

### 8.4 :cast-mode Field

**Docstring mention**: "Optional keys: ... :stack-item/cast-mode"
**Actual usage**: Stored on object, not stack-item (see rules.cljs:336)
**Spec implication**: Should NOT be in stack-item spec? Or should it?

**→ Verify: is cast-mode ever set on stack-item, or only on object?**

### 8.5 :sacrifice-info Field

**Used in**: events/abilities.cljs:137
**Structure**: Unknown (not documented)
**Resolved by**: Not read in resolution.cljs

**→ Verify: what does sacrifice-info contain? When used? How validated?**

---

## 9. MINIMAL-VALID-STACK-ITEMS TEMPLATE

For Phase 3 spec test, define this map:

```clojure
(def minimal-valid-stack-items
  {:spell
   {:stack-item/type :spell
    :stack-item/controller :player-1
    :stack-item/source :card-uuid-1
    :stack-item/object-ref 123}

   :storm
   {:stack-item/type :storm
    :stack-item/controller :player-1
    :stack-item/source :card-uuid-1
    :stack-item/effects [{:effect/type :storm-copies :effect/count 2}]}

   :storm-copy
   {:stack-item/type :storm-copy
    :stack-item/controller :player-1
    :stack-item/source :card-uuid-1
    :stack-item/object-ref 124
    :stack-item/is-copy true}

   :activated-ability
   {:stack-item/type :activated-ability
    :stack-item/controller :player-1
    :stack-item/source :ability-source-uuid
    :stack-item/effects [{:effect/type :add-mana :effect/mana {:red 1}}]}

   :permanent-entered
   {:stack-item/type :permanent-entered
    :stack-item/controller :player-1
    :stack-item/effects [{:effect/type :draw :effect/target :self}]}

   :declare-attackers
   {:stack-item/type :declare-attackers
    :stack-item/controller :player-1}

   :declare-blockers
   {:stack-item/type :declare-blockers
    :stack-item/controller :player-1}

   :combat-damage
   {:stack-item/type :combat-damage
    :stack-item/controller :player-1}

   :delayed-trigger
   {:stack-item/type :delayed-trigger
    :stack-item/controller :player-1
    :stack-item/effects [{:effect/type :untap-all}]}

   :state-check-trigger
   {:stack-item/type :state-check-trigger
    :stack-item/controller :player-1
    :stack-item/source :permanent-uuid
    :stack-item/effects [{:effect/type :destroy :effect/target :self}]}})
```

---

## 10. DEPLOYMENT CHECKLIST

- [ ] **Verify edge cases** (sections 8.1-8.5) — confirm with code author
- [ ] **Create stack_spec.cljs** with 10 defmethods (one per type)
- [ ] **Define minimal-valid-stack-items** map
- [ ] **Create stack_spec_test.cljs** with:
  - Test all 10 types pass `s/valid?`
  - Test count = 10
  - Test per-type required field enforcement (sample 3-4 types)
  - Test unknown type fails
- [ ] **Add validation to create-stack-item** via goog.DEBUG pattern:
  ```clojure
  (when ^boolean goog.DEBUG
    (when-not (s/valid? ::stack-item/item attrs)
      (js/console.error "create-stack-item: invalid stack-item map:" ...)))
  ```
- [ ] **Run `make test`** — validate all existing tests still pass
- [ ] **Run `make validate`** — check linting
- [ ] **Verify release build** — ensure goog.DEBUG block dead-code-eliminated

---

## SUMMARY FOR TASK IMPLEMENTATION

**9 confirmed stack-item types** (10 if delayed-trigger + state-check-trigger are separate):
1. `:spell` — requires controller, source, object-ref
2. `:storm` — requires controller, source, effects
3. `:storm-copy` — requires controller, source, object-ref, is-copy
4. `:activated-ability` — requires controller, source, effects
5. `:permanent-entered` — requires controller, effects (source optional)
6. `:declare-attackers` — requires controller only
7. `:declare-blockers` — requires controller only
8. `:combat-damage` — requires controller only
9. `:delayed-trigger` — requires controller, effects
10. `:state-check-trigger` — requires controller, source, effects

**Validation chokepoint**: `create-stack-item` function (line 29 of stack.cljs)

**Testing pattern**: Inherit Phase 2 pattern (goog.DEBUG runs by default in tests; minimal-valid-items map; 10 defmethods)

**Open questions** (verify before implementation):
- What does `:stack-item/sacrifice-info` contain?
- Is `:stack-item/cast-mode` ever set on stack-item, or only on object?
- Is `:stack-item/chosen-x` dead code or used elsewhere?
