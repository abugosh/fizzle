# Side Effects in Pure Functions: Audit Report

This report documents all instances of the anti-pattern where pure logic functions have side effects (like `rf/dispatch`) baked into them, or where validation is trapped in handler wrappers instead of being part of the pure function.

## CRITICAL ISSUES

### Issue 1: `toggle-selection-impl` calls `rf/dispatch` inside pure transformation function

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`, lines 401-453

**Function**: `toggle-selection-impl`

**Pure logic vs side effect**:
- Pure part: Toggling selection state (lines 413-444)
- Side effect: `rf/dispatch [:fizzle.events.selection/confirm-selection]` at line 451

**What's mixed**:
- This function is called via event handler `::toggle-selection` (registered at `events/selection.cljs:27-29`)
- The handler expects a pure function: `(fn [db [_ id]] (core/toggle-selection-impl db id))`
- But the impl function **dispatches a new event** when auto-confirm fires (line 451)
- This breaks the pure function contract and makes the function untestable without re-frame machinery

**Reason/Design Decision** (from code comments):
- Lines 445-447: "Auto-confirm: dispatch ::confirm-selection instead of calling inline. This ensures the confirmation goes through the event system and hits the :db effect handler chokepoint (for SBA + bot checks)."
- This is intentional: the author wanted to ensure confirmation triggers the full event pipeline for side-effect checks

**Test impact**:
- Tests calling `toggle-selection-impl` directly will trigger unexpected dispatch
- No test helpers exist for `toggle-selection-impl` (good thing, since it's not actually pure)
- Tests using the UI dispatch path work correctly because dispatch is real

**Severity**: MEDIUM
- The side effect is intentional and justified
- But the function shouldn't be labeled pure or called directly from tests
- And the `-impl` convention suggests it should be safe to call directly

---

### Issue 2: `confirm-selection-impl` splits validation into handler wrapper

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`
- Implementation: lines 370-394
- Wrapper handler: lines 487-507

**Functions**:
- `confirm-selection-impl` (pure)
- `confirm-selection-handler` (wrapper with validation)

**Pure logic vs validation**:
- `confirm-selection-impl`: Takes app-db, runs selection executor, routes by lifecycle, applies continuation
- `confirm-selection-handler`: Calls `validate-selection` BEFORE calling impl
- If validation fails, handler returns original app-db unchanged

**What's mixed**:
- Validation is in the handler, not in the impl
- Tests calling `confirm-selection-impl` directly **bypass validation** entirely
- Production code goes through `confirm-selection-handler`, which validates first

**Test helper affected** (direct bypass):
- File: `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs`, line 331
- Function: `confirm-selection` test helper
- Code:
  ```clojure
  (defn confirm-selection
    [db selection selected-items]
    (let [sel (assoc selection :selection/selected selected-items)
          app-db {:game/db db :game/pending-selection sel}
          result (sel-core/confirm-selection-impl app-db)]  ;; BYPASSES VALIDATION
      (if-let [next-sel (:game/pending-selection result)]
        {:db (:game/db result) :selection next-sel}
        {:db (:game/db result)})))
  ```
- This helper allows tests to confirm invalid selections (missing required selections, wrong counts, etc.)

**Test files affected** (calling -impl directly):
- `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs:331`
- `/Users/abugosh/g/fizzle/src/test/fizzle/cards/red/goblin_welder_test.cljs:355`
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/spell_mode_selection_test.cljs:104`
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/integration/mana_allocation_test.cljs:473`
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/lifecycle_test.cljs` (12+ calls)
- `/Users/abugosh/g/fizzle/src/test/fizzle/engine/land_type_change_test.cljs` (12+ calls)

**Severity**: CRITICAL
- Validation is explicitly separated from the impl
- Tests use the impl directly, bypassing validation
- Could hide bugs where invalid selections are accepted

---

## SECONDARY ISSUES

### Issue 3: Test helper `confirm-selection` doesn't use event handler path

**Location**: `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs`, lines 323-334

**Bypass mechanism**:
- Direct call to `sel-core/confirm-selection-impl` 
- Skips the `confirm-selection-handler` wrapper
- Skips validation (see Issue 2)
- Does NOT go through deferred entry processing (lines 503-505 in handler)

**What it bypasses**:
```clojure
;; From confirm-selection-handler (lines 498-507)
(if (validation/validate-selection selection)
  (let [result (confirm-selection-impl app-db)]
    ;; Process deferred entry only when selection chain is complete
    (if (and (:history/deferred-entry result)
             (nil? (:game/pending-selection result)))
      (process-deferred-entry result)
      result))
  app-db)
```

**Consequence**:
- History entries for cast-spell/activate-ability that required pre-cast selection are NOT processed
- Tests using this helper won't verify history recording works correctly

**Severity**: MEDIUM
- Only affects history/fork mechanics testing
- Most card tests don't care about history (they test rules, not replaying)

---

### Issue 4: `apply-continuation` multimethod dispatches inside what looks like pure continuation logic

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs`, lines 213-239

**Function**: `(defmethod sel-core/apply-continuation :cast-after-spell-mode ...)`

**Side effect**: `rf/dispatch [:fizzle.events.priority-flow/cast-and-yield-resolve]` at line 236

**Context**:
- `apply-continuation` is called from `confirm-selection-impl` at lines 337, 351
- Multimethod returns `app-db` (or modified version)
- BUT one branch (line 236) dispatches an event before returning

**What's mixed**:
- Most branches just return modified app-db (pure)
- One branch (non-targeted spell in cast-and-yield) dispatches then returns
- Called from within pure transformation logic (confirm-selection-impl)

**Reason** (from code comment):
- Line 233: "can't call priority-flow directly — circular dep"
- So dispatch is used to break the circular dependency

**Severity**: MEDIUM
- Dispatch only happens in specific condition (non-targeted cast-and-yield spell)
- The returned app-db is still correct
- Dispatch is side effect of the broader confirm-selection pipeline, not hidden within pure logic

---

### Issue 5: Test helpers use `d/db-with` to set state, bypassing production event handlers

**Pattern**: Directly setting game state with `d/db-with` instead of calling event handlers

**Examples**:
1. **Chosen mode for modal spells** (`vision_charm_test.cljs`, lines 261, 280, 315)
   ```clojure
   obj-eid (q/get-object-eid db vc-id)
   db (d/db-with db [[:db/add obj-eid :object/chosen-mode mode-2]])
   ```
   - Production: Mode selected through UI → toggle-selection-impl → auto-confirm via dispatch
   - Test: Directly set `:object/chosen-mode` on object
   - No validation that mode has valid targets runs

2. **Tapped state** (`goblin_welder_test.cljs`, line 195)
   ```clojure
   db-tapped (d/db-with db [[:db/add welder-eid :object/tapped true]])
   ```
   - Production: Event handlers `::tap-permanent` / `::untap-permanent` exist
   - Test: Directly sets via datascript transaction
   - Bypasses any validation or guards

3. **Zone changes** (`goblin_welder_test.cljs`, lines 290, 314)
   ```clojure
   db-petal-gone (d/db-with db-with-stack [[:db/add petal-eid :object/zone :graveyard]])
   ```
   - Production: Effects use proper zone functions
   - Test: Direct datascript mutation

**Impact**:
- Tests don't exercise the actual production event flow
- Won't catch bugs in event handlers or validation
- But these are mostly direct state setup (not core to the test), so risk is limited

**Severity**: LOW
- These are test setup/fixture operations, not testing the actual feature
- Production code paths are tested through other tests that use proper event handlers
- The pattern is acceptable for test data initialization

---

## PATTERNS THAT ARE ACCEPTABLE

### Pattern A: `-impl` function with direct event handler (no wrapper)

**Example**: `allocate-mana-color-impl` and `reset-mana-allocation-impl`

```clojure
(defn allocate-mana-color-impl [app-db color] ...)

(rf/reg-event-db
  ::allocate-mana-color
  (fn [db [_ color]]
    (allocate-mana-color-impl db color)))  ;; Direct call, no wrapper

(rf/reg-event-db
  ::reset-mana-allocation
  (fn [db _]
    (reset-mana-allocation-impl db)))
```

**Why it's OK**:
- No validation or side effects trapped in wrapper
- `-impl` function is pure (takes db, returns db)
- Event handler is just dispatch → impl (transparent pass-through)
- Tests can call `-impl` directly without losing any logic

**Affected functions**:
- `allocate-mana-color-impl` (costs.cljs:741)
- `reset-mana-allocation-impl` (costs.cljs:770)
- `adjust-storm-split-impl` (storm.cljs:96)

---

### Pattern B: Direct effect calls in tests via pure functions

**Example**: `tap-permanent`, `untap-permanent`

```clojure
(defn tap-permanent [db object-id]
  (let [obj-eid (queries/get-object-eid db object-id)]
    (d/db-with db [[:db/add obj-eid :object/tapped true]])))

(rf/reg-event-db ::tap-permanent
  (fn [db [_ object-id]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (tap-permanent game-db object-id)))))
```

**Why it's OK**:
- Pure functions exist and are documented
- Tests call the pure functions directly for setup
- Event handlers exist and are separate
- No hidden side effects

---

## SUMMARY TABLE

| Issue | File | Function | Type | Severity | Root Cause |
|-------|------|----------|------|----------|------------|
| 1 | events/selection/core.cljs:451 | `toggle-selection-impl` | Dispatch in impl | MEDIUM | Intentional design to trigger SBA/bot checks |
| 2 | events/selection/core.cljs:370 | `confirm-selection-impl` | Validation in wrapper | CRITICAL | Separation of impl from validation |
| 3 | test_helpers.cljs:331 | `confirm-selection` | Bypasses validation | MEDIUM | Calls impl directly instead of handler |
| 4 | events/casting.cljs:236 | `apply-continuation` | Dispatch in continuation | MEDIUM | Circular dependency workaround |
| 5 | Various test files | `d/db-with` for state | Direct mutation | LOW | Test fixture pattern (acceptable) |

---

## RECOMMENDATIONS

### Immediate Actions

1. **Split `toggle-selection-impl` into two functions**:
   ```clojure
   (defn toggle-selection-impl-pure [app-db id] 
     ;; Pure logic only, no dispatch)
   
   (defn toggle-selection-impl [app-db id]
     ;; Wrapper that calls pure version and may dispatch
   ```
   OR: Rename current impl to make dispatch expectation clear

2. **Move validation into `confirm-selection-impl` OR rename handler**:
   - Either: Call validation in impl before processing
   - Or: Rename impl to `confirm-selection-execute` and handler to `confirm-selection-impl`

3. **Update test helper `confirm-selection` to use handler path**:
   ```clojure
   (defn confirm-selection [app-db selection selected-items]
     (let [sel (assoc selection :selection/selected selected-items)
           updated {:game/db (:game/db app-db) :game/pending-selection sel}]
       (sel-core/confirm-selection-handler updated)))
   ```

4. **Document the circular dependency in `apply-continuation`**:
   - Add ADR or code comment explaining why dispatch is necessary
   - Consider refactoring to eliminate the circular dep in a future sprint

---

## Test Coverage Notes

**Tests that directly call `-impl` functions** (bypassing validation):
- `events/selection/lifecycle_test.cljs` — 12+ direct calls to `confirm-selection-impl`
- `events/selection/costs_allocation_test.cljs` — 7 direct calls to allocation impls
- `events/selection/storm_split_test.cljs` — 4 direct calls to `adjust-storm-split-impl`
- `engine/land_type_change_test.cljs` — 12+ calls to `confirm-selection-impl`
- `cards/red/goblin_welder_test.cljs` — 1 call
- `events/integration/mana_allocation_test.cljs` — 1 call
- `events/spell_mode_selection_test.cljs` — 1 call

Most of these are intentional (testing the impl layer directly), but you should verify they're not accidentally testing invalid states.

