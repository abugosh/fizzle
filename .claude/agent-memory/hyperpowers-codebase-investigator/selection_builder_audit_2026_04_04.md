---
name: Selection Builder Audit — Spec Compliance (2026-04-04)
description: Complete audit of 29 selection builder sites; found 2 HIGH-risk missing lifecycle keys in library.cljs
type: project
---

## Audit Complete: Selection Builder Spec Compliance

**Date:** 2026-04-04  
**Total Builders:** 29 sites across 8 files  
**Compliance Status:**
- ✓ 23 fully compliant
- ⚠ 4 conditionally compliant (lifecycle optional per spec)
- ✗ 2 non-compliant (missing required lifecycle)

## Critical Findings (Action Required)

### 🔴 HIGH Priority — Two Builders Missing Required `:selection/lifecycle`

Both in `src/main/fizzle/events/selection/library.cljs`:

1. **`build-order-bottom-selection` (line 243-250)**
   - Spec requires `:selection/lifecycle` in `:req` (spec.cljs:310-319)
   - Current: Lifecycle not set, defaults to `:standard`
   - **Fix:** Add `:selection/lifecycle :finalized` at line 249
   - **Risk:** Selection will use standard path (apply remaining-effects, cleanup source) when finalized path is intended

2. **`build-order-top-selection` (line 266-274)**
   - Spec requires `:selection/lifecycle` in `:req` (spec.cljs:324-335)
   - Current: Lifecycle not set, defaults to `:standard`
   - **Fix:** Add `:selection/lifecycle :finalized` at line 273
   - **Risk:** Selection will use standard path (apply remaining-effects, cleanup source) when finalized path is intended

## Pattern Discovery

5 builders use non-standard `:selection/pattern` key (not in spec):
- `:zone-pick` (core.cljs)
- `:accumulator` (storm.cljs, costs.cljs x3)
- `:reorder` (library.cljs x2)

**Status:** Safe (metadata only, not load-bearing). Optional to remove or formalize.

## Previously Known Bugs (Both Fixed)

From earlier audits:
1. ✓ Zone-pick builder missing `:selection/lifecycle` — **FIXED** in core.cljs line 162
2. ✓ Storm-split using `:selection/controller-id` instead of `:selection/player-id` — **FIXED** in storm.cljs line 53

## Verification Reference

All 29 builder sites logged with:
- File:line
- Selection type
- `:selection/lifecycle` status (present/missing/conditional)
- `:selection/player-id` status (present/missing/key used)
- Non-standard keys
- Spec compliance verdict

See `/Users/abugosh/g/fizzle/SELECTION_BUILDER_AUDIT.md` for complete table.

## How to Verify

```bash
# Run selection spec tests
cd /Users/abugosh/g/fizzle
make test -- selection/spec_test.cljs

# Manual grep for lifecycle presence
grep -n "build-order-bottom-selection\|build-order-top-selection" \
  src/main/fizzle/events/selection/library.cljs -A 15 | grep lifecycle
```

## When Builders Were Last Audited

- core.cljs: 2026-04-04 ✓
- storm.cljs: 2026-04-04 ✓
- library.cljs: 2026-04-04 ⚠ (2 failures found)
- costs.cljs: 2026-04-04 ✓
- zone_ops.cljs: 2026-04-04 ✓ (lifecycle optional per spec)
- targeting.cljs: 2026-04-04 ✓
- combat.cljs: 2026-04-04 ✓
- land_types.cljs: 2026-04-04 ✓
- untap.cljs: 2026-04-04 ⚠ (lifecycle optional per spec)
