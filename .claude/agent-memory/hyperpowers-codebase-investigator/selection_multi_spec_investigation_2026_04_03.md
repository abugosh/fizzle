---
name: Selection System Multi-Spec Investigation
description: Complete catalog of 21 selection types, builder output shapes, validation chokepoint, hierarchy structure, Phase 1 pattern reference
type: reference
---

# Selection System Multi-Spec Validation Design Reference

Completed 2026-04-03 for Phase 2 spec validation layer (parallel to effect multi-spec).

## All 21 Selection Types Catalog

**Production Types (21 total)**:
- **Zone-pick pattern (7)**: `:zone-pick` `:discard` `:graveyard-return` `:hand-reveal-discard` `:chain-bounce` `:chain-bounce-target` `:unless-pay`
- **Accumulator (3)**: `:storm-split` `:x-mana-cost` `:mana-allocation`
- **Reorder (4)**: `:scry` `:peek-and-reorder` `:order-bottom` `:order-top`
- **Library ops (3)**: `:tutor` `:pile-choice` `:peek-and-select`
- **Targeting (3)**: `:cast-time-targeting` `:ability-targeting` `:player-target`
- **Pre-cast costs (4)**: `:exile-cards-cost` `:return-land-cost` `:discard-specific-cost` `:sacrifice-permanent-cost`
- **Modal (1)**: `:spell-mode`
- **Cost special (1)**: `:pay-x-life`

## Builder Output: Base Keys (Every Selection)

```
:selection/type :selection/player-id :selection/selected :selection/spell-id
:selection/remaining-effects :selection/validation :selection/auto-confirm?
:selection/lifecycle (optional but recommended)
```

Pattern-specific keys vary (zone, cards, accumulation, targeting, etc.).

## Single Validation Chokepoint

**Location**: `events/selection/core.cljs:426` in `confirm-selection-impl`

```clojure
(if-not (validation/validate-selection selection)
  app-db
  ...)
```

This is where all selection confirmations are validated. Currently dispatches on `:selection/validation` keyword. Phase 2 multi-spec should integrate here.

## Hierarchy Structure (selection/core.cljs:31-64)

Four parent patterns:
- `:zone-pick` ← discard, graveyard-return, hand-reveal-discard, chain-bounce, etc.
- `:accumulator` ← storm-split, x-mana-cost, mana-allocation
- `:reorder` ← scry, peek-and-reorder, order-bottom, order-top
- `:builder-declared-chain` ← tutor, peek-and-select, x-mana-cost (double-registered)

Plus two cost-chain patterns:
- `:pre-cast-cost-to-targeting` ← discard-specific-cost, return-land-cost, sacrifice-permanent-cost
- `:targeting-to-mana-allocation` ← cast-time-targeting, ability-targeting

## Phase 1 Pattern (Effect Multi-Spec)

Reference: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs`

Structure: multimethod dispatcher + per-type defmethods + minimal-effects map + validation function. All defmethods required (no :default). Exact assertions in tests (not type predicates).

## Key Differences from Effects

1. **Effect-to-selection mapping** differs (`:return-from-graveyard` effect → `:graveyard-return` selection)
2. **Lifecycle is declared** by builder, not executor return
3. **Chain-builder function** stored on selection (can't validate functions)
4. **No generic inheritance** for executors—each type implements own
5. **Two parallel protocols**: validation by `:selection/validation` keyword (orthogonal to multi-spec)

## Critical Files

**Core**: selection/core.cljs, costs.cljs, library.cljs, zone_ops.cljs, targeting.cljs, storm.cljs, casting.cljs
**Reference**: engine/card_spec.cljs (Phase 1 pattern)
**Tests**: selection/hierarchy_test.cljs, core_test.cljs, lifecycle_test.cljs
**Validation chokepoint**: selection/core.cljs line 426 (confirm-selection-impl)
