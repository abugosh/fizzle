# Test Helpers Audit

**Epic:** fizzle-x40o — Restore production-path testing discipline
**Task:** fizzle-7ydh — Reviewer gap fixes
**Date:** 2026-04-27

## Purpose

This document classifies every public helper in `src/test/fizzle/test_helpers.cljs` as either:

- **(a) Verified production-path** — reaches the same spec chokepoints as the production browser path (at minimum: passes through `sel-spec/set-pending-selection` for any selection produced, and `casting/cast-spell-handler` or equivalent for cast actions)
- **(b) Bypass-helper** — manually constructs state that the production path constructs via chokepoints; needs migration or documented exception

---

## Classification

### Setup and Query Utilities (7 helpers)

These helpers initialize game state or read state for assertions. They do not model game actions and are not subject to the production-path rule.

| Helper | Classification | Rationale |
|--------|---------------|-----------|
| `create-test-db` | **(a) setup utility** | Creates db via `game-state/create-complete-player` (same as production init). No game-action bypass. |
| `add-card-to-zone` | **(a) setup utility** | Uses `objects/build-object-tx` (same chokepoint as production). Documented as test setup, not a game-action tap; bypasses zone-change-dispatch triggers intentionally for initial state placement. |
| `add-cards-to-library` | **(a) setup utility** | Same pattern as `add-card-to-zone`. Test setup only. |
| `add-cards-to-graveyard` | **(a) setup utility** | Same pattern as `add-card-to-zone`. Test setup only. |
| `add-test-creature` | **(a) setup utility** | Uses `build-object-tx`. Test setup for synthetic creatures with custom stats. |
| `add-opponent` | **(a) setup utility** | Uses `game-state/create-complete-player` (same as production init). |
| `create-game-scenario` | **(a) setup utility** | Creates full app-db with `history/init-history`. Full-game test setup. |

### Query / Assertion Utilities (3 helpers)

| Helper | Classification | Rationale |
|--------|---------------|-----------|
| `get-zone-count` | **(a) assertion utility** | Wraps `q/get-objects-in-zone`. Pure query, no action. |
| `get-object-zone` | **(a) assertion utility** | Wraps `q/get-object`. Pure query. |
| `get-hand-count` | **(a) assertion utility** | Wraps `get-zone-count`. Pure query. |

### State Mutation Utilities (1 helper)

| Helper | Classification | Rationale |
|--------|---------------|-----------|
| `tap-permanent` | **(a) setup utility** | Uses `d/db-with` directly to set tapped state for test setup. Docstring explicitly documents this is NOT a game-action tap — it does not dispatch `:permanent-tapped`. For game-action taps, production paths must be used. The helper's explicit contract makes this an acceptable test setup tool. |

### Production-Path Cast/Resolve Helpers (7 helpers)

| Helper | Classification | Rationale |
|--------|---------------|-----------|
| `cast-and-resolve` | **(a) with caveat — see fizzle-2qcs** | Calls `rules/cast-spell` (engine layer), not `casting/cast-spell-handler` (events layer). For non-interactive spells both paths converge at `rules/cast-spell-mode`. Helper guards against interactive spells via pre-cast pipeline check and `pending-selection` assert. Classified as (a) for the guarded subset it handles, but does not test the events layer. See bd issue fizzle-2qcs for migration to use `cast-spell-handler`. |
| `cast-with-target` | **(a) verified production-path** | Calls `casting/cast-spell-handler` with full pre-cast pipeline. Validated that `set-pending-selection` spec chokepoint is reached for any selection produced. |
| `cast-with-mode` | **(a) verified production-path** | Calls `casting/cast-spell-handler`. Returns `{:db :selection}` where selection was produced by `set-pending-selection`. Added in fizzle-dc1u as production-path alternative to deleted `cast-kicked` bypass. |
| `cast-and-yield` | **(a) verified production-path** | Calls `casting/cast-spell-handler` + `resolution/resolve-one-item`. Returns post-resolve selection produced by `set-pending-selection`. Added in fizzle-y63h. |
| `cast-mode-with-target` | **(b) bypass-helper — see fizzle-x6ew** | Manually calls `casting/build-spell-mode-selection` to construct the mode-pick selection, then assocs it directly to `app-db` WITHOUT going through `sel-spec/set-pending-selection`. The spec chokepoint that validates `:selection/mechanism` is never called. A missing `:selection/mechanism` on the mode-pick selection would NOT be caught by this helper. |
| `resolve-top` | **(a) verified production-path** | Calls `resolution/resolve-one-item` (same as production). Returns `{:db}` or `{:db :selection}`. |
| `confirm-selection` | **(a) verified production-path** | Calls `sel-core/confirm-selection-impl` (same as production). Includes `validation/validate-selection` guard before confirm. |

---

## Bypass Helpers Summary

| Helper | BD Issue | Migration Path |
|--------|----------|---------------|
| `cast-mode-with-target` | **fizzle-x6ew** | Replace with `cast-with-mode` + `confirm-selection` (mode step) + `confirm-selection` (targeting step) |
| `cast-and-resolve` (partial) | **fizzle-2qcs** (P3) | Replace `rules/cast-spell` call with `casting/cast-spell-handler` + extract game-db |

---

## Sandbox Revert Verification

This section documents the sandbox revert verification performed on branch `sandbox/x40o-revert-verify` to verify the epic success criterion: "Both fizzle-8mfo and fizzle-q3g4 have regression tests that would have failed at PR time under the new flag."

### fizzle-8mfo: Mode label nil bug (rules.cljs get-primary-mode)

**Fix:** Added `:mode/label "Cast"` to `get-primary-mode` return map in `rules.cljs:33`.

**Regression test:** `get-primary-mode-includes-default-label` in `rules_test.cljs` (line 1501).

**Revert method:** Removed `:mode/label "Cast"` from `get-primary-mode` in `rules.cljs`, left test assertions at the original `(some? ...)` / `(string? ...)` form (before fizzle-7ydh strengthening).

**Result: RED as expected.** `make test` reported 2 FAIL at lines 1505-1506 of `rules_test.cljs`:
```
FAIL in (get-primary-mode-includes-default-label)
FAIL in (get-primary-mode-includes-default-label)
```
Both `(is (some? (:mode/label mode)))` and `(is (string? (:mode/label mode)))` failed because `:mode/label` was nil (absent) with the fix reverted.

**Verdict:** Regression test correctly catches fizzle-8mfo. The strengthened assertion `(= "Cast" (:mode/label mode))` (added in fizzle-7ydh) provides additional specificity.

---

### fizzle-q3g4: seq/vector type preservation bug (costs.cljs build-chain-selection)

**Fix:** Replaced `(or (seq mode-targeting) card-targeting)` with explicit `if` guard in `costs.cljs:622-627` to prevent `(seq vec)` from producing a list.

**Original regression test:** `sacrifice-cost-chain-preserves-vector-type-for-target-requirements` in `targeting_test.cljs`. The test calls `sel-core/build-chain-selection` directly with a synthesized `sac-selection` map, then asserts `(is (vector? (:selection/target-requirements built)))`.

**Round-1 revert (gap identified in first reviewer pass):** The original test did NOT go red on revert. `make test` reported 0 failures. The direct `build-chain-selection` call bypasses `set-pending-selection`, and in that path the `(seq mode-targeting)` result appeared to pass `vector?`. Filed as **fizzle-w6yi** for investigation.

**Round-2 fix (fizzle-7ydh round 2):** Added `(is (vector? (:selection/target-requirements selection)))` assertion to `rushing-river-kicked-chain-trace-test` in `rushing_river_test.cljs` (step 3: after sacrifice confirm → cast-time-targeting selection). This test goes through the full production path: `cast-with-mode` → `confirm-selection` → `confirm-selection-impl` → `set-pending-selection` spec chokepoint.

**Round-2 revert verification (branch `sandbox/x40o-q3g4-revert-verify`):** Reverted `costs.cljs` to `(or (seq mode-targeting) card-targeting)`. Result: **RED as expected.**

- `rushing-river-kicked-chain-trace-test` threw: `Error: set-pending-selection: invalid data: ({...} {:target/id :slot-b, ...}) - failed: vector? in: [:selection/target-requirements]`
- `sacrifice-cost-chain-preserves-vector-type-for-target-requirements` also FAILED: `(not (vector? ({...} {...})))` (the original test now goes red too, consistent with fizzle-w6yi fix)
- 1 failure, 5 errors reported

**Verdict:** fizzle-q3g4 regression coverage is now verified. The chain-trace assertion in `rushing-river-kicked-chain-trace-test` goes through `set-pending-selection` and fails immediately when the `if`-guard is reverted. The `*throw-on-spec-failure*` enforcement also surfaces the error in 4 additional Rushing River kicked tests as ERRORs.

---

## BD Issues Filed

| Issue | Priority | Description |
|-------|----------|-------------|
| **fizzle-x6ew** | P2 | Migrate `cast-mode-with-target` helper to production path via `cast-spell-handler` |
| **fizzle-w6yi** | P2 | Investigate q3g4 regression test: `vector?` assertion not catching seq-vs-vector bug on revert |
| **fizzle-2qcs** | P3 | Strengthen `cast-and-resolve` to use `cast-spell-handler` (events layer) instead of `rules/cast-spell` (engine layer) |

---

## Recommendations

1. **Migrate `cast-mode-with-target` (fizzle-x6ew):** This is the highest-priority bypass. The Vision Charm tests currently use it; migrate them to `cast-with-mode` + two `confirm-selection` calls, then delete or reimplement the helper.

2. **Investigate and fix q3g4 regression test (fizzle-w6yi):** The `sacrifice-cost-chain-preserves-vector-type-for-target-requirements` test does not provide the regression protection it was designed for. Replace it or augment it with a production-path chain-trace test.

3. **Long-term: migrate `cast-and-resolve` (fizzle-2qcs):** Lower priority since the guarded subset it handles is safe, but full production-path equivalence requires using `cast-spell-handler`.
