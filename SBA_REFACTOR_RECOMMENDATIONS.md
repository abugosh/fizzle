# SBA Refactoring Recommendations

## Current State vs. Target

**Current**: Global interceptor fires SBA checks after trigger events
- Pros: Centralized, catches all mutations
- Cons: Implicit, redundant with inline SBA calls, hard to understand data flow

**Target**: Explicit SBA checks at mutation boundaries
- Pros: Data-driven, visible in code, eliminates redundancy
- Cons: More lines of code, must be careful not to miss points

---

## Redundancy Analysis

### REDUNDANT: Already have inline SBA calls

**1. `reduce-effects` (engine/effects.cljs:207, 211)**
```clojure
;; SBA called TWICE:
;; 1. After each effect (line 211)
;; 2. After all effects (line 207)
;; PLUS interceptor after ::resolve-top
```

**Events affected**:
- `::resolve-top`
- `::confirm-selection` (if standard path)
- `::toggle-selection` (if auto-confirm)
- Continuation `:resolve-one-and-stop`

**Impact**: These events fire SBA THREE times (after each effect + end + interceptor)

**2. `activate-mana-ability` (engine/mana_activation.cljs:115)**
```clojure
;; SBA called once:
;; Direct: state-based/check-and-execute-sbas (line 115)
;; PLUS interceptor after ::activate-mana-ability
```

**Impact**: Mana ability SBA fires twice (direct + interceptor)

---

### NOT REDUNDANT: Only have interceptor

**1. `::cast-spell` event**
   - Builds stack item, no effects execute
   - No inline SBA needed (stack items don't execute until resolution)

**2. `::play-land` event**
   - Puts land on battlefield, fires ETB triggers
   - SBA might be needed (e.g., land sacrifices itself)
   - **Recommendation**: Add inline SBA after land enters battlefield

**3. `::advance-phase` event**
   - Advances game phase, fires phase-triggered events
   - No explicit game state change (only phase number)
   - **Recommendation**: Keep interceptor only (no SBA needed)

**4. Combat attacker selection (bot path)**
   - Selection confirmation inside `resolve-one-item`
   - No effects execute during selection confirmation
   - Only SBA source: interceptor after `::resolve-top`

---

## Refactoring Strategy

### PHASE 1: Eliminate Redundant Interceptor Calls

**Option A: Keep inline SBAs, remove from interceptor (Low Risk)**

Remove these from `sba-trigger-events` (events/interceptors/sba.cljs:14-28):
```clojure
:fizzle.events.resolution/resolve-top              ;; ← REMOVE
:fizzle.events.selection/confirm-selection         ;; ← REMOVE
:fizzle.events.selection/toggle-selection          ;; ← REMOVE
:fizzle.events.abilities/activate-mana-ability     ;; ← REMOVE
```

Keep in interceptor:
```clojure
:fizzle.events.priority-flow/yield
:fizzle.events.priority-flow/yield-all
:fizzle.events.casting/cast-spell
:fizzle.events.priority-flow/cast-and-yield
:fizzle.events.lands/play-land
:fizzle.events.phases/advance-phase
:fizzle.events.phases/start-turn
```

**Testing**: All existing tests should pass (same number of SBA checks, just at different points in call stack)

**Risk**: LOW - only removes redundant calls

---

### PHASE 2: Add SBA Checks for Missing Coverage

**Add SBA after land enters battlefield** (`events/lands.cljs`)

Current (no SBA):
```clojure
(rf/reg-event-db
  ::play-land
  (fn [db [_ object-id player-id]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))]
      (assoc db :game/db
             (lands/put-on-battlefield game-db pid object-id)))))  ;; Returns db'
```

Modified (with SBA):
```clojure
(rf/reg-event-db
  ::play-land
  (fn [db [_ object-id player-id]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))
          game-db' (lands/put-on-battlefield game-db pid object-id)
          game-db'' (state-based/check-and-execute-sbas game-db')]  ;; ✅ ADD SBA
      (assoc db :game/db game-db''))))
```

**Files to modify**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/lands.cljs`

**Risk**: LOW - adding SBA that should fire anyway (via interceptor today)

---

**Add SBA after land ETB effects trigger** (in `events/lands.cljs` or `engine/`)

If lands have ETB effects, those are executed via triggers, which dispatch events that will fire interceptor SBA. If lands can execute effects synchronously (e.g., sacrifice effects from Gemstone Mine), need SBA after those execute.

**Status**: Check if any lands have synchronous effects in `engine/lands.cljs` or cards/lands/

---

### PHASE 3: Make SBA Explicit in Selection System

**Option B: Register `:check-sbas` continuation**

For clarity (even if redundant with reduce-effects SBA), explicitly show SBA in selection paths.

File: `events/selection/core.cljs:334-336`

Current:
```clojure
(if on-complete
  (apply-continuation on-complete updated)
  updated)
```

Could modify to always register a continuation, even if none was set:
```clojure
(let [final-continuation (or on-complete {:continuation/type :default})]
  (apply-continuation final-continuation updated))
```

But this adds no new SBA calls, just makes flow explicit.

**Not recommended** unless trying to decouple reduce-effects SBA from selection system.

---

### PHASE 4: Audit Yield-Resolve Pipeline

**Check**: Does `yield-impl` path require SBA in addition to reduce-effects?

File: `events/priority_flow.cljs:274-292`

Current flow:
```
yield-impl
  → negotiate-priority (only modifies :player/passed)
  → if all-passed + stack not empty
    → yield-resolve-stack
      → resolve-one-item
        → engine-resolution/resolve-stack-item
          → reduce-effects  [✅ SBA HERE]
```

**Verdict**: reduce-effects already calls SBA, no additional call needed.

---

## Implementation Steps

### Step 1: Confirm Redundancy (Tests First)

Write test that verifies:
1. SBA checks happen exactly once per event (not twice)
2. SBA checks happen at expected points in flow

```clojure
;; Pseudo-test
(deftest sba-called-once-per-resolve-top
  ;; Instrument check-and-execute-sbas with a counter
  ;; Dispatch ::resolve-top with spell on stack
  ;; Assert counter == 1 (not 2)
  )
```

### Step 2: Remove Redundant Interceptor Calls

File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/interceptors/sba.cljs`

Remove lines from `sba-trigger-events` set:
```clojure
:fizzle.events.resolution/resolve-top
:fizzle.events.selection/confirm-selection
:fizzle.events.selection/toggle-selection
:fizzle.events.abilities/activate-mana-ability
```

### Step 3: Add Missing SBA Call

File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/lands.cljs`

Add `state-based/check-and-execute-sbas` call after `put-on-battlefield` returns.

Require: `[fizzle.engine.state-based :as state-based]`

### Step 4: Verify Coverage

After changes, verify:
- All spell resolutions have SBA (via reduce-effects)
- All ability resolutions have SBA (via reduce-effects or direct call)
- All land plays have SBA (via new inline call or interceptor)
- All phase advances have SBA (via interceptor, which is fine for phase)

---

## Files to Modify

| File | Changes | Risk | Notes |
|------|---------|------|-------|
| `events/interceptors/sba.cljs` | Remove 4 events from trigger set | LOW | Tests should pass |
| `events/lands.cljs` | Add SBA call after land enters | LOW | Adds safety |
| (optional) `events/priority_flow.cljs` | Document SBA flow in yield-impl | NONE | Comment only |
| (optional) `events/resolution.cljs` | Document SBA in resolve-one-item | NONE | Comment only |

**No changes needed**:
- `engine/effects.cljs` ✅ Already correct
- `engine/mana_activation.cljs` ✅ Already correct
- `events/selection/core.cljs` ✅ Works via reduce-effects

---

## Validation Checklist

After refactoring:

- [ ] All tests pass
- [ ] No SBA is called more than once per event
- [ ] `::resolve-top` calls SBA once (via reduce-effects)
- [ ] `::confirm-selection` calls SBA once (via reduce-effects)
- [ ] `::activate-mana-ability` calls SBA once (direct call)
- [ ] `::play-land` calls SBA once (via new inline call)
- [ ] `::cast-spell` calls SBA once (via interceptor)
- [ ] `::advance-phase` calls SBA once (via interceptor)
- [ ] No regressions in bot behavior
- [ ] No regressions in state-based action handling

---

## Long-Term Opportunity

Once refactoring complete, consider:

1. **Rename `check-and-execute-sbas`** → `apply-state-based-actions` (more descriptive)

2. **Document SBA insertion points** in CLAUDE.md:
   ```markdown
   ## State-Based Actions

   SBA checks happen at these points:
   - Inside reduce-effects loop (after each effect)
   - Inside activate-mana-ability (after effects execute)
   - After land enters battlefield (events/lands.cljs)
   - After phase advancement (via interceptor — needed for phase-triggered events)
   ```

3. **Consider making SBA continuation explicit** in selection system if desired for clarity:
   ```clojure
   (apply-continuation {:continuation/type :check-sbas} updated)
   ```
   But this is optional and adds no new functionality.

---

## Risk Assessment

**Overall Risk**: **LOW**

- Reducing redundant calls improves code clarity
- Inline SBA calls are proven to work (they're already in code)
- New SBA call for lands is minimal change
- All changes are additions or removals of SBA calls (same operation)
- No changes to game logic

**Regression Risk**: **VERY LOW**

- Tests cover SBA behavior extensively (state-based action tests)
- Removing interceptor calls just means SBA happens earlier in call stack
- Results should be identical

**Testing Required**: **MEDIUM**

- Run full test suite (already exists)
- Add specific test to verify SBA count per event
- Manual verification of edge cases (empty library, state-based losses)
