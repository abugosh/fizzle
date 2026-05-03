---
name: Modal spell selection testing gap (c6e1003)
description: Test helper bypasses production validation pipeline; bug allowed vector candidates in validation layer to fail silently
type: feedback
---

**Rule:** Test helpers must exercise the full production pipeline, including validation layers, or they hide bugs.

**Why:** Commit c6e1003 fixed a subtle bug in modal spell selection where `contains?` was called on a vector (checking indices, not values) instead of a set. This made mode dialogs unresponsive after selecting a mode. Tests using `cast-mode-with-target` helper never caught it because the helper directly mutates `:object/chosen-mode` via `d/db-with` (line 296 in test_helpers.cljs), completely bypassing steps 5-8 of the production pipeline:
- Step 5: Toggle handler (user clicks mode)
- Step 6: **Validation via `contains?` on candidates** (BUG WAS HERE)
- Step 7: Confirm-selection handler
- Step 8: Execute :spell-mode multimethod

The bug's symptom (validation silently failing) is exactly the kind that happy-path tests miss because they already have the precondition (chosen mode set) and don't need validation to pass.

**How to apply:**
- When adding test helpers, ask: "Does this helper exercise validation layers or skip them?"
- If it skips validation (direct `d/db-with` mutations, setting app-db fields directly), either:
  - Document the bypass clearly (indicate what production code is being skipped)
  - Or refactor to use production handlers instead (preferred)
- For modal spells / complex selections, add dedicated tests that DON'T use shortcuts
- Build validation tests first (RED phase), then implementation (GREEN phase), before helpers (REFACTOR)

**Files affected:**
- test_helpers.cljs:296 — cast-mode-with-target uses direct `d/db-with` mutation
- blue_elemental_blast_test.cljs:125, 147, 217 — all modal tests use cast-mode-with-target
- vision_charm_test.cljs:164, 216 — modal tests use cast-mode-with-target
- engine/validation.cljs:70-73 — fixed bug (now coerces vector to set)
- events/casting.cljs:184-200 — where :selection/candidates is set as vector from filterv

See: TESTING_GAP_INVESTIGATION.md for full detailed report with production pipeline trace.
