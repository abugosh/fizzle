# Selection Builder Audit — Quick Reference

**Audit Date:** 2026-04-04  
**Total Builders:** 29  
**Files Checked:** 8  
**Status:** 2 HIGH-risk issues found

---

## 🔴 FAILURES (Fix immediately)

| File | Line | Function | Type | Issue |
|------|------|----------|------|-------|
| library.cljs | 243 | `build-order-bottom-selection` | `:order-bottom` | **Missing `:selection/lifecycle`** (required by spec) |
| library.cljs | 266 | `build-order-top-selection` | `:order-top` | **Missing `:selection/lifecycle`** (required by spec) |

**Fix:** Add `:selection/lifecycle :finalized` to both builders

---

## ✅ FULLY COMPLIANT (23 builders)

### core.cljs
- `build-selection-for-effect` (default)
- `build-selection-for-effect` (zone-pick handler)

### library.cljs
- `build-tutor-selection` (`:tutor`)
- `build-pile-choice-selection` (`:pile-choice`)
- `build-scry-selection` (`:scry`)
- `build-peek-selection` (`:peek-and-select`)
- `build-peek-and-reorder-selection` (`:peek-and-reorder`)

### costs.cljs
- `build-exile-cards-selection` (`:exile-cards-cost`)
- `build-return-land-selection` (`:return-land-cost`)
- `build-discard-specific-selection` (`:discard-specific-cost`)
- `build-sacrifice-permanent-selection` (`:sacrifice-permanent-cost`)
- `build-x-mana-selection` (`:x-mana-cost`)
- `build-pay-x-life-selection` (`:pay-x-life`)
- `build-mana-allocation-selection` (`:mana-allocation`)
- Fallback mana-allocation in x-mana chain-builder

### zone_ops.cljs
- `build-chain-bounce-selection` (`:chain-bounce`)

### targeting.cljs
- `build-player-target-selection` (`:player-target`)
- `build-cast-time-target-selection` (`:cast-time-targeting`/`:ability-cast-targeting`)

### combat.cljs
- `build-attacker-selection` (`:select-attackers`)
- `build-blocker-selection` (`:assign-blockers`)

### land_types.cljs
- `build-selection-for-effect :change-land-types` (`:land-type-source`)
- `build-chain-selection :land-type-source` (`:land-type-target`)

---

## ⚠️ CONDITIONALLY COMPLIANT (4 builders)

Lifecycle is **optional per spec**, but may need explicit defaults:

| File | Line | Function | Type | Status |
|------|------|----------|------|--------|
| zone_ops.cljs | 26 | `build-hand-reveal-discard-selection` | `:hand-reveal-discard` | `:lifecycle` optional |
| zone_ops.cljs | 132 | `build-chain-bounce-target-selection` | `:chain-bounce-target` | `:lifecycle` optional |
| zone_ops.cljs | 226 | `build-unless-pay-selection` | `:unless-pay` | `:lifecycle` optional |
| untap.cljs | 18 | `build-untap-lands-selection` | `:untap-lands` | `:lifecycle` optional |

---

## 📊 Key Findings

### Lifecycle Coverage
- ✓ Always set: 25 builders
- ✗ Missing: 2 builders
- ⚠️ Optional: 4 builders (currently missing, allowed by spec)

### Player-ID Coverage
- ✓ All 29 builders have `:selection/player-id`
- ✓ No incorrect variants (`:controller-id`, etc.)

### Non-Standard Keys
- `:selection/pattern` used in 5 builders (metadata, not load-bearing)
- Safe to keep or remove

---

## How to Verify

```bash
# Run tests
cd /Users/abugosh/g/fizzle
make test -- selection

# Check lifecycle in failing builders
grep -A 20 "build-order-bottom-selection\|build-order-top-selection" \
  src/main/fizzle/events/selection/library.cljs | grep lifecycle

# List all builders with their lifecycle
grep -rn ":selection/lifecycle" src/main/fizzle/events/selection/ \
  | grep -v "spec.cljs"
```

---

## Implementation Reference

Each builder creates a map with:
- `:selection/type` — dispatch key
- `:selection/lifecycle` — `:standard` (apply effects + cleanup), `:finalized` (no effects), or `:chaining` (next selection)
- `:selection/player-id` — who controls the selection
- `:selection/selected` — player's choices
- `:selection/validation` — how many selections required (`:exact`, `:at-most`, etc.)
- `:selection/auto-confirm?` — auto-confirm when complete

See `spec.cljs` for exact required/optional keys per type.
