# SBA Insertion Points - Investigation Summary

## Investigation Complete ✅

I've mapped every place in the Fizzle codebase where game-db is mutated and returned to the app-db layer. Below is the definitive answer to where SBA checks should be inserted.

---

## Key Findings

### 1. **SBA is Already Inline in 3 Critical Places**

**Engine Layer (2 places)**:
- `engine/effects.cljs:207, 211` - Inside `reduce-effects` loop
  - Calls SBA after EACH effect execution
  - Calls SBA after ALL effects exhausted
  - Used by: spell resolution, ability resolution, most selection confirmations

- `engine/mana_activation.cljs:115` - Direct call after mana ability effects
  - Calls SBA after mana ability executes
  - Used by: mana ability activation

**Event Layer (1 place)**:
- Global interceptor (`events/interceptors/sba.cljs`) - Fires after trigger events
  - Provides fallback coverage for events without inline SBA

### 2. **Redundancy: Some Events Call SBA Multiple Times**

| Event | Direct Call | Via reduce-effects | Interceptor | Total |
|-------|-----------|-------------------|-------------|-------|
| `::resolve-top` | ❌ | ✅ (2x) | ✅ | **3x** |
| `::confirm-selection` | ❌ | ✅ (2x) | ✅ | **3x** |
| `::activate-mana-ability` | ✅ | ❌ | ✅ | **2x** |
| `::toggle-selection` (auto) | ❌ | ✅ (2x) | ✅ | **3x** |
| `::allocate-mana-color` (auto) | ❌ | ✅ (2x) | ✅ | **3x** |
| `::play-land` | ❌ | ❌ | ✅ | **1x** |
| `::cast-spell` | ❌ | ❌ | ✅ | **1x** |
| `::advance-phase` | ❌ | ❌ | ✅ | **1x** |

### 3. **All Callers of `resolve-one-item`**

**File**: `events/resolution.cljs`
- Line 114: `::resolve-top` event handler
- Line 133: `::resolve-all` event handler

**File**: `events/priority_flow.cljs`
- Line 71: Inside `yield-resolve-stack` (called from `yield-impl`)

**Total**: 3 callers (2 event handlers, 1 internal function)

### 4. **All Callers of `confirm-selection-impl`**

**File**: `events/selection/core.cljs`
- Line 447: From `toggle-selection-impl` (when auto-confirm)
- Line 463: From `confirm-selection-handler` (event dispatcher)

**File**: `events/selection/costs.cljs`
- Line 766: From `allocate-mana-color-impl` (when allocation complete)

**File**: `events/resolution.cljs`
- Line 89: Direct call for bot attacker selection (no event)

**Total**: 4 callers (1 event handler, 3 internal/domain functions)

### 5. **Direct SBA Calls in Engine**

| Location | Call | Context |
|----------|------|---------|
| `engine/effects.cljs:207` | `sba/check-and-execute-sbas` | After all effects exhausted |
| `engine/effects.cljs:211` | `sba/check-and-execute-sbas` | After each effect (recur) |
| `engine/mana_activation.cljs:115` | `state-based/check-and-execute-sbas` | After mana ability effects |

**Total direct calls**: 3 unique locations (2 in reduce-effects, 1 in activate-mana)

### 6. **Auto-Confirm Paths**

**Toggle Selection** (`events/selection/core.cljs:443-447`):
- When `select-count=1` AND `:selection/auto-confirm?=true`
- Automatically calls `confirm-selection-impl`
- Triggers reduce-effects SBA

**Mana Allocation** (`events/selection/costs.cljs:765-766`):
- When allocation reaches zero remaining
- Automatically calls `confirm-selection-impl`
- Triggers reduce-effects SBA

### 7. **Continuation System**

**Registered continuations** (all via `apply-continuation` multimethod):

1. **`:resolve-one-and-stop`** (priority_flow.cljs:374-376)
   - Calls `yield-impl` → potentially `resolve-one-item`
   - Result: SBA via reduce-effects if stack resolved

2. **`:cast-after-spell-mode`** (casting.cljs:212-220)
   - Resumes casting pipeline
   - No SBA call

3. **`:default`** (selection/core.cljs:209-211)
   - Returns app-db unchanged

---

## Call Chain Overview

### Primary Path: Spell Resolution
```
::resolve-top (event)
  ↓
resolve-one-item (function)
  ↓
engine-resolution/resolve-stack-item (multimethod)
  ↓
effects/reduce-effects
  ├─ After each effect: sba/check-and-execute-sbas ✅
  └─ After all effects: sba/check-and-execute-sbas ✅
  ↓
Interceptor also fires SBA (REDUNDANT)
```

### Secondary Path: Mana Ability
```
::activate-mana-ability (event)
  ↓
engine/activate-mana-ability (function)
  ├─ Execute ability effects
  ├─ Dispatch trigger events
  └─ state-based/check-and-execute-sbas ✅
  ↓
Interceptor also fires SBA (REDUNDANT)
```

### Tertiary Path: Selection Confirmation
```
::confirm-selection (event) or auto-confirm path
  ↓
confirm-selection-impl (function)
  ├─ execute-confirmed-selection (multimethod)
  └─ By lifecycle:
     ├─ :standard → reduce-effects ✅
     ├─ :finalized → no effects
     └─ :chaining → build-chain-selection or reduce-effects ✅
  ↓
Interceptor also fires SBA (REDUNDANT for standard path)
```

---

## Detailed File References

### Critical Files

**Engine Layer**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects.cljs:186-211` - `reduce-effects`
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/mana_activation.cljs:37-119` - `activate-mana-ability`
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/state_based.cljs:66` - `check-and-execute-sbas` (definition)

**Event Layer**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/resolution.cljs:63-108` - `resolve-one-item`
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs:368-392` - `confirm-selection-impl`
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs:399-448` - `toggle-selection-impl`
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/costs.cljs:750-767` - `allocate-mana-color-impl`
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs:359-376` - `resolve-one-and-stop`
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/interceptors/sba.cljs:14-52` - SBA interceptor

---

## Recommendations

### Short Term: Reduce Redundancy (Low Risk)

**Remove from interceptor trigger set** (`events/interceptors/sba.cljs:14-28`):
```clojure
;; These already have inline SBA calls:
- :fizzle.events.resolution/resolve-top
- :fizzle.events.selection/confirm-selection
- :fizzle.events.selection/toggle-selection
- :fizzle.events.abilities/activate-mana-ability
```

**Benefit**: Eliminates duplicate SBA checks, makes flow explicit

**Risk**: VERY LOW - same operation happens, just once instead of multiple times

### Medium Term: Add Missing Coverage (Low Risk)

**Add SBA after land enters battlefield** (`events/lands.cljs`):
```clojure
;; After put-on-battlefield call
game-db'' (state-based/check-and-execute-sbas game-db')
```

**Benefit**: Explicit coverage for land plays

**Risk**: LOW - only adds new SBA call that should fire anyway via interceptor

### Long Term: Make Flow Explicit (Medium Effort)

**Optional**: Document SBA insertion points in code with comments:
- `engine/effects.cljs` - Note SBA in reduce-effects loop
- `engine/mana_activation.cljs` - Note SBA after effects
- `events/lands.cljs` - Note SBA after land enters

**Benefit**: Clarity for future developers

**Risk**: NONE - documentation only

---

## Testing Strategy

1. **Run existing test suite** - Should pass unchanged (same SBA behavior)
2. **Add SBA count verification** - Instrument `check-and-execute-sbas` to verify called once per event
3. **Test edge cases** - Empty library, state-based losses, etc. (already covered by existing tests)
4. **Manual verification** - Play through game scenarios, verify state-based actions fire

---

## Deliverables

I've created 4 detailed investigation documents:

1. **`SBA_INVESTIGATION_SUMMARY.md`** (this file)
   - Executive summary
   - Key findings and recommendations

2. **`SBA_INSERTION_POINTS_INVESTIGATION.md`**
   - Complete investigation narrative
   - Call chains and architecture
   - Design alternatives

3. **`SBA_MUTATION_POINT_CHECKLIST.md`**
   - Quick reference table
   - File paths and line numbers
   - Redundancy matrix
   - Insertion point guide

4. **`SBA_CALL_CHAIN_TRACES.md`**
   - Detailed traces for each path
   - Line-by-line code walkthrough
   - SBA call verification

5. **`SBA_REFACTOR_RECOMMENDATIONS.md`**
   - Implementation steps
   - Risk assessment
   - Validation checklist

---

## Conclusion

**The interceptor is not the only place SBA happens.** SBA checks are already inline in `reduce-effects` and `activate-mana-ability`. The interceptor provides backup coverage for events that don't flow through those paths (e.g., land play, casting).

**Recommended approach**: Keep the inline SBA calls (they're correct), optionally remove redundant interceptor calls for cleaner data flow.

**Where to insert SBA instead of relying on interceptor**:
1. Land play (add inline SBA after land enters battlefield)
2. That's it - everything else already has inline SBA or should use interceptor

The codebase is well-designed; you just need to be explicit about which mutation points have SBA coverage.
