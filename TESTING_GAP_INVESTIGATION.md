# Testing Gap Investigation: Modal Spell Selection Pipeline

## Executive Summary

Test helpers (`cast-mode-with-target` and similar) bypass the production **selection→validation→confirmation pipeline**, which allowed a subtle bug to ship undetected in modal spell selection. The bug (commit c6e1003) was that candidates were stored as a vector but validated with `contains?` (which checks indices, not values on vectors), silently breaking validation for modal spell modes.

## The Bug (Commit c6e1003)

**File:** `src/main/fizzle/engine/validation.cljs` (line 70)

**Problem:**
```clojure
;; BEFORE (broken):
candidates-valid? (if candidates
                    (every? #(contains? candidates %) selected)
                    true)
```

When `candidates` was a vector (from `filterv` in spell-mode-selection), `contains?` checks numeric indices (0, 1, 2...), NOT value membership. So:
- `(contains? [:island :mountain] :island)` → false (`:island` is not an index)
- `(contains? [:island :mountain] 0)` → true (0 is a valid index)

**Symptom:** Modal spell dialogs became unresponsive after selecting a mode. The selection state would not validate, so clicking confirm had no effect.

**Root Cause:** The spell-mode-selection builder at `events/casting.cljs:194` stores candidates as a vector:
```clojure
:selection/candidates valid-modes  ; valid-modes is a vector from filterv
```

**Fix (line 70-73):**
```clojure
;; AFTER (fixed):
candidates-valid? (if candidates
                    (let [cand-set (set candidates)]
                      (every? #(contains? cand-set %) selected))
                    true)
```

Coerce candidates to a set before the `contains?` check, enabling value-based membership testing.

---

## Why Tests Didn't Catch It

### The Test Helper Bypass (`cast-mode-with-target`)

**Location:** `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs:281-309`

**What it does:**
1. **Validates can-cast?** (lines 289-290)
2. **Directly mutates the object** to store the chosen mode (line 296):
   ```clojure
   (d/db-with db [[:db/add obj-eid :object/chosen-mode spell-mode]])
   ```
3. **Builds targeting selection** manually (line 303-304)
4. **Confirms target** through the production targeting handler (line 307-309)

**Critical Gap:** Line 296 uses `d/db-with` to directly set `:object/chosen-mode` **without going through the production spell-mode selection→validation→confirmation pipeline**.

### Production Pipeline (What Tests Skip)

The real flow for modal spells is:

1. **Player clicks cast button** → `events/casting.cljs:cast-spell-handler` (line ~240)
2. **Modal card detected** → `get-valid-spell-modes` identifies modes with valid targets (line 169)
3. **Build spell-mode selection** → `build-spell-mode-selection` (line 184):
   ```clojure
   {:selection/type :spell-mode
    :selection/candidates valid-modes  ; VECTOR from filterv
    :selection/selected #{}
    :selection/select-count 1
    :selection/auto-confirm? true
    :selection/validation :exact}
   ```
4. **UI shows dialog** with mode buttons
5. **Player selects mode** → `::toggle-selection` event dispatches
6. **Handler calls** `toggle-selection-impl` (line 401 in selection/core.cljs):
   - Validates with `contains?` on candidates (THE BUG IS HERE)
   - If validation passes, confirms selection
7. **`::confirm-selection` event** dispatches
8. **Handler calls** `confirm-selection-impl` (line 370 in selection/core.cljs):
   - Calls `execute-confirmed-selection :spell-mode` multimethod (line 203-210 in casting.cljs)
   - Stores chosen mode on object via `d/db-with`
9. **Continuation applied** via `:cast-after-spell-mode` (line 213-224 in casting.cljs)

### Test Helper Path (Truncated)

```clojure
;; cast-mode-with-target: SKIPS steps 1-8 above
(d/db-with db [[:db/add obj-eid :object/chosen-mode spell-mode]])  ; DIRECT MUTATION
;; Jumps straight to step 9-ish (targeting selection)
```

**Result:** Tests never exercise:
- Step 5 (toggle-selection — user clicks mode button)
- Step 6 (validation via `contains?` on vector candidates)
- Step 7 (confirm-selection confirmation ceremony)

These are exactly where the bug lived.

---

## Affected Test Files

### 1. Blue Elemental Blast (`src/test/fizzle/cards/blue/blue_elemental_blast_test.cljs`)

**Uses cast-mode-with-target:**
- Line 125: `db-cast (th/cast-mode-with-target db :player-1 beb-id counter-mode bolt-id)`
- Line 147: `db-cast (th/cast-mode-with-target db :player-1 beb-id destroy-mode perm-id)`
- Line 217: `db-cast (th/cast-mode-with-target db :player-1 beb-id counter-mode bolt-id)`

**Tests:** All B (happy path), D (storm), F (targeting) tests use this helper and would NOT catch modal selection validation bugs.

### 2. Vision Charm (`src/test/fizzle/cards/blue/vision_charm_test.cljs`)

**Uses cast-mode-with-target:**
- Line 164: `db-cast (th/cast-mode-with-target db :player-1 vc-id mode-1 :player-2)`
- Line 216: `db-cast (th/cast-mode-with-target db :player-1 vc-id mode-3 artifact-id)`

**Tests:** B (happy path), D (storm), E (phase-out), G (edge cases) — all skip validation.

**BUT**: Test category F (Mode 2 - Land Type Change) at line 253 DOES go through production pipeline:
```clojure
(deftest vision-charm-mode2-returns-needs-selection-test
  (testing "Mode 2: resolve returns :needs-selection (interactive effect)"
    (let [db (th/create-test-db) ...]
      ;; Sets chosen-mode directly (same bypass!)
      (d/db-with db [[:db/add obj-eid :object/chosen-mode mode-2]])
      ;; But then resolves through production path
      result (th/resolve-top db-on-stack)
      ;; Tests the resolution, not the selection
```

Even this test sets the mode directly with `d/db-with`, bypassing validation.

---

## The Full Production Pipeline (Detailed)

### Phase 1: Modal Selection (User Picks Mode)

**File:** `events/casting.cljs:184-200` and `selection/core.cljs:401-453`

```clojure
;; BUILD (casting.cljs:184-200)
{:selection/type :spell-mode
 :selection/candidates valid-modes          ; VECTOR from filterv line 174
 :selection/selected #{}
 :selection/select-count 1
 :selection/validation :exact
 :selection/auto-confirm? true}

;; TOGGLE (selection/core.cljs:401-453 - toggle-selection-impl)
;; User clicks a mode button, dispatches ::toggle-selection
;; new-db = assoc-in selected-ids set with mode-map
;; if selected-count == 1 and auto-confirm? true
;;   dispatch ::confirm-selection (line 451)

;; VALIDATE (engine/validation.cljs:51-91 - validate-selection)
;; Checks: candidates-valid? = every? selected in (set candidates) ✓ FIXED
;;         count-valid? = count(selected) == select-count ✓ EXACT
;;         Result: true if valid, false if not

;; CONFIRM (selection/core.cljs:370-394 - confirm-selection-impl)
;; if valid:
;;   execute-confirmed-selection :spell-mode (casting.cljs:203-210)
;;   apply-continuation :cast-after-spell-mode (casting.cljs:213-224)
;; else:
;;   app-db unchanged (selection stays pending)
```

### Phase 2: Casting Mode Selection (Builder Picks Primary Mode)

**File:** `events/casting.cljs:213-224`

```clojure
(defmethod sel-core/apply-continuation :cast-after-spell-mode
  [continuation app-db]
  (let [object-id (:continuation/object-id continuation)
        game-db (:game/db app-db)
        player-id (queries/get-human-player-id game-db)
        modes (rules/get-casting-modes game-db player-id object-id)
        chosen-mode (or (d/q '[:find ?e . :in $ ?id 
                              :where [?e :object/chosen-mode ?id]]
                          game-db (queries/get-object-eid game-db object-id))
                        (first modes))
        mode-mana-cost (:mode/mana-cost chosen-mode)
        ...]
      ;; Continue with targeting selection or mana allocation if needed
```

After spell-mode is chosen (and stored on object by executor), the continuation picks the casting mode (usually the one user selected, or primary mode if available).

### Phase 3: Targeting (If Needed)

**File:** `events/selection/targeting.cljs:43-78`

```clojure
;; Cast-time targeting selection
{:selection/type :cast-time-targeting
 :selection/valid-targets valid-targets
 :selection/selected #{}
 :selection/select-count 1
 :selection/validation :exact
 :selection/auto-confirm? true
 :selection/lifecycle :finalized or :chaining}

;; TOGGLE → VALIDATE → CONFIRM
;; Like spell-mode selection, but validates against :selection/valid-targets
```

### Phase 4: Mana Allocation (If Generic Mana)

If `:chaining` lifecycle from targeting, chains to mana allocation.

---

## Why The Bug Was Hard to Spot

1. **Subtle symptom:** Validation "silently fails" — no error thrown, selection just stays pending.
2. **Modal spells are rare:** Only BEB, REB, Hydroblast, and Vision Charm tested (4 modal cards).
3. **Test coverage skips the pipeline:** Tests use helper that directly mutates, never exercising the validation layer.
4. **The vector-vs-set semantics are easy to miss:** `contains?` on vectors is a common gotcha in Clojure.
5. **Tests still pass:** Happy path tests work because helper bypasses validation entirely.

---

## Verification: Before/After c6e1003

**Before c6e1003:**
```clojure
;; validation.cljs:70 (broken)
candidates-valid? (if candidates
                    (every? #(contains? candidates %) selected)
                    true)

;; Example:
;; candidates = [:island :mountain] (vector)
;; selected = #{:island}
;; (contains? [:island :mountain] :island) = false ❌
;; Result: validation fails, mode dialog unresponsive
```

**After c6e1003:**
```clojure
;; validation.cljs:70-73 (fixed)
candidates-valid? (if candidates
                    (let [cand-set (set candidates)]
                      (every? #(contains? cand-set %) selected))
                    true)

;; Example:
;; candidates = [:island :mountain] (vector) → #{:island :mountain} (set)
;; selected = #{:island}
;; (contains? #{:island :mountain} :island) = true ✓
;; Result: validation passes, mode dialog responsive
```

---

## Recommendations

### 1. Add Production-Path Modal Selection Test

Create a test that exercises the full spell-mode selection pipeline WITHOUT the helper:

```clojure
(deftest beb-mode-selection-through-production-pipeline
  (testing "Modal spell selection validates modes through production pipeline"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          
          ;; Dispatch ::cast-spell through re-frame (production path)
          ;; This should build spell-mode selection (NOT use helper)
          ;; Then dispatch ::toggle-selection with counter-mode
          ;; Then dispatch ::confirm-selection
          ;; And verify chosen-mode is set without manual mutation
          ]))
```

### 2. Fix cast-mode-with-target Helper

Either:
- **Option A (Safer):** Remove cast-mode-with-target; require tests to use production pipeline
- **Option B (Backwards Compatible):** Make cast-mode-with-target exercise the full pipeline:
  ```clojure
  (defn cast-mode-with-target [db player-id obj-id spell-mode target-id]
    ;; Build spell-mode selection
    ;; Dispatch ::toggle-selection
    ;; Validate with production validate-selection
    ;; Dispatch ::confirm-selection
    ;; Build targeting selection
    ;; Dispatch ::toggle-selection with target
    ;; Validate and confirm target
    ;; Return final db
    )
  ```

### 3. Audit Similar Helpers

Check all helpers in `test_helpers.cljs` for production-pipeline bypasses:
- `cast-and-resolve` — looks safe (uses `rules/cast-spell` → `resolution/resolve-one-item`)
- `cast-with-target` — looks safe (uses `sel-targeting/cast-spell-with-targeting` → production targeting flow)
- `resolve-top` — safe (delegates to `resolution/resolve-one-item`)
- `confirm-selection` — safe (delegates to `sel-core/confirm-selection-impl`)

### 4. Add Validation-Layer Tests

Create tests specifically for `validate-selection` function:

```clojure
(deftest validate-selection-with-vector-candidates
  (testing "validate-selection coerces vector candidates to set"
    (is (true? (validate-selection
                  {:selection/candidates [:island :mountain]  ; VECTOR
                   :selection/selected #{:island}
                   :selection/validation :exact
                   :selection/select-count 1})))))

(deftest validate-selection-with-set-candidates
  (testing "validate-selection works with set candidates"
    (is (true? (validate-selection
                  {:selection/candidates #{:island :mountain}
                   :selection/selected #{:island}
                   :selection/validation :exact
                   :selection/select-count 1})))))

(deftest validate-selection-rejects-invalid-candidates
  (testing "validate-selection rejects selected items not in candidates"
    (is (false? (validate-selection
                   {:selection/candidates [:island :mountain]
                    :selection/selected #{:swamp}  ; NOT in candidates
                    :selection/validation :exact
                    :selection/select-count 1})))))
```

---

## Files Involved

### Production Code
- **Bug Location:** `/Users/abugosh/g/fizzle/src/main/fizzle/engine/validation.cljs:70-73`
- **Selection Builder:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs:184-200`
- **Selection Executor:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs:203-210`
- **Selection Confirmation:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs:370-394`
- **Toggle Handler:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs:401-453`

### Test Helpers (With Bypass)
- **cast-mode-with-target:** `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs:281-309` (line 296 direct mutation)

### Test Files (Affected)
- **Blue Elemental Blast:** `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/blue_elemental_blast_test.cljs:125, 147, 217`
- **Vision Charm:** `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/vision_charm_test.cljs:164, 216`

---

## Historical Context

This is one instance of a broader pattern: **production-path helpers that work fine for happy paths but miss validation-layer bugs**. The fix (c6e1003) is 100% correct, but tests should have caught it first via TDD. The gap exists because:

1. Modal spells are a newer feature (late implementation)
2. Modal selection is a complex pipeline (7-step flow)
3. Test helper made happy paths too easy (success without validation)
4. No dedicated validation-layer test suite

Future implementation should:
- Write validation tests FIRST (RED phase)
- Then implement validation logic (GREEN phase)
- Then implement UI/helpers (REFACTOR phase)
- Not skip validation layers in test helpers
