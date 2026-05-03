# SBA Mutation Points - Quick Reference Checklist

## All Callers of `resolve-one-item`

**File**: `events/resolution.cljs`
- **Line 63**: Function definition
- **Line 114**: Called from `::resolve-top` event handler
  ```clojure
  (let [result (resolve-one-item (:game/db db))]
  ```
- **Line 133**: Called from `::resolve-all` event handler
  ```clojure
  (let [result (resolve-one-item game-db)]
  ```

**File**: `events/priority_flow.cljs`
- **Line 71**: Called from `yield-resolve-stack` (internal function)
  ```clojure
  result (resolution/resolve-one-item game-db)
  ```

**Total callers**: 3 (2 event handlers, 1 internal function)

---

## All Callers of `confirm-selection-impl`

**File**: `events/selection/core.cljs`
- **Line 368**: Function definition
- **Line 447**: Called from `toggle-selection-impl` (auto-confirm path)
  ```clojure
  (confirm-selection-impl new-db)
  ```
- **Line 463**: Called from `confirm-selection-handler`
  ```clojure
  (confirm-selection-impl app-db)
  ```

**File**: `events/selection/costs.cljs`
- **Line 766**: Called from `allocate-mana-color-impl` (when allocation complete)
  ```clojure
  (core/confirm-selection-impl updated-db)
  ```

**File**: `events/resolution.cljs`
- **Line 89**: Called directly during attacker selection (bot path)
  ```clojure
  result-db (sel-core/confirm-selection-impl app-db)
  ```

**Total callers**: 4 (1 event handler, 3 internal/domain functions)

---

## All Direct SBA Invocations in Engine Layer

**File**: `engine/effects.cljs`
- **Line 207**: Inside `reduce-effects` loop - after all effects exhausted
  ```clojure
  {:db (sba/check-and-execute-sbas db)}
  ```
- **Line 211**: Inside `reduce-effects` loop - after each effect execution
  ```clojure
  (recur (sba/check-and-execute-sbas (:db result)) remaining)
  ```

**File**: `engine/mana_activation.cljs`
- **Line 115**: After mana ability effects and triggers
  ```clojure
  db-after-sbas (state-based/check-and-execute-sbas db-after-triggers)
  ```

**Total direct calls**: 3 (all in engine, called before returning to event layer)

---

## All SBA Interceptor Trigger Events

**File**: `events/interceptors/sba.cljs` (lines 14-28)

```clojure
#{:fizzle.events.priority-flow/yield
  :fizzle.events.priority-flow/yield-all
  :fizzle.events.resolution/resolve-top              ;; reduce-effects SBA + interceptor (redundant)
  :fizzle.events.casting/cast-spell                  ;; Interceptor only
  :fizzle.events.priority-flow/cast-and-yield        ;; Interceptor only
  :fizzle.events.lands/play-land                     ;; Interceptor only
  :fizzle.events.phases/advance-phase                ;; Interceptor only
  :fizzle.events.phases/start-turn                   ;; Interceptor only
  :fizzle.events.selection/confirm-selection         ;; reduce-effects SBA + interceptor (redundant)
  :fizzle.events.selection/toggle-selection          ;; reduce-effects SBA (if auto-confirm) + interceptor (redundant)
  :fizzle.events.abilities/activate-mana-ability}    ;; Direct SBA call + interceptor (redundant)
```

---

## Auto-Confirm Paths (toggle/accumulator)

### Toggle Selection Auto-Confirm
**File**: `events/selection/core.cljs:399-448`
- **Line 443-447**: Auto-confirm when `select-count=1` AND `:selection/auto-confirm?=true`
  ```clojure
  (if (and selected?
           (= select-count 1)
           (:selection/auto-confirm? selection))
    (confirm-selection-impl new-db)
    new-db)
  ```
- Event: `events/selection.cljs::toggle-selection` → dispatch to `core/toggle-selection-impl`

### Mana Allocation Auto-Confirm
**File**: `events/selection/costs.cljs:750-767`
- **Line 765-766**: Auto-confirm when mana allocation reaches zero remaining
  ```clojure
  (if (zero? new-remaining)
    (core/confirm-selection-impl updated-db)
    updated-db)
  ```
- Event: `events/selection/costs.cljs::allocate-mana-color` → dispatch to `allocate-mana-color-impl`

---

## Continuation System

### Apply Continuation Multimethod
**File**: `events/selection/core.cljs:194-211`
- **Line 194**: Multimethod definition
- **Line 209**: Default case (returns app-db unchanged)

### Registered Continuations

1. **`:resolve-one-and-stop`**
   - **File**: `events/priority_flow.cljs:374-376`
   - Calls `resolve-one-and-stop` (line 359-369)
   - Flows through `yield-impl` → potentially `resolve-one-item`

2. **`:cast-after-spell-mode`**
   - **File**: `events/casting.cljs:212-220`
   - Calls `initiate-cast-with-mode` to resume casting pipeline

---

## SBA Check Redundancy Matrix

| Event | Direct SBA? | Interceptor? | Notes |
|-------|-----------|-------------|-------|
| `::resolve-top` | ✅ reduce-effects | ✅ Yes | **REDUNDANT** - SBA fires twice |
| `::confirm-selection` | ✅ reduce-effects | ✅ Yes | **REDUNDANT** if standard path |
| `::toggle-selection` | ✅ reduce-effects (if auto) | ✅ Yes | **REDUNDANT** if auto-confirm |
| `::activate-mana-ability` | ✅ Direct | ✅ Yes | **REDUNDANT** |
| `::cast-spell` | ❌ No | ✅ Yes | Interceptor only (correct) |
| `::play-land` | ❌ No | ✅ Yes | Interceptor only (correct) |
| `::advance-phase` | ❌ No | ✅ Yes | Interceptor only (correct) |
| `::start-turn` | ❌ No | ✅ Yes | Interceptor only (correct) |
| `::yield` | ✅ reduce-effects (maybe) | ✅ Yes | Maybe redundant |
| `::yield-all` | ✅ reduce-effects (maybe) | ✅ Yes | Maybe redundant |

---

## Where to Insert SBA Without Interceptor

**Option 1: Register continuation on selection paths**
- File: `events/selection/core.cljs:standard-path` (line 308-336)
- After `reduce-effects` completes (SBA already inline there)
- **Cost**: Minimal - already calling SBA

**Option 2: Wrap phase advancement**
- File: `events/phases.cljs` (phase advancement functions)
- Call SBA after each phase advancement
- **Cost**: Moderate - phase advance is called from multiple places

**Option 3: Wrap casting**
- File: `events/casting.cljs` (cast-spell-handler)
- Call SBA after stack item creation
- **Cost**: Low - single call point per cast

**Option 4: Wrap land play**
- File: `events/lands.cljs` (play-land handler)
- Call SBA after land enters battlefield
- **Cost**: Low - single call point

---

## Key Files to Review for SBA Insertion

1. **`engine/effects.cljs`** (lines 186-211)
   - Where reduce-effects already calls SBA inline
   - No changes needed

2. **`engine/mana_activation.cljs`** (line 115)
   - Where mana ability already calls SBA inline
   - No changes needed

3. **`events/selection/core.cljs`** (lines 308-336, 368-392)
   - Where standard-path could register continuation (redundant with reduce-effects SBA)
   - Could add explicit `:check-sbas` continuation for clarity

4. **`events/phases.cljs`**
   - Where SBA is NOT called directly
   - Would need SBA call added after phase advancement

5. **`events/casting.cljs`**
   - Where SBA is NOT called directly
   - Would need SBA call added after stack item creation (before returning from handler)

6. **`events/lands.cljs`**
   - Where SBA is NOT called directly
   - Would need SBA call added after land enters battlefield

7. **`events/interceptors/sba.cljs`**
   - Global interceptor (lines 14-28, 38-52)
   - To be removed/simplified after other SBA calls added
