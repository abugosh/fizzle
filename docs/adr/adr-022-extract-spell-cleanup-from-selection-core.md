# ADR 022: Extract Spell Cleanup from Selection Core

## Status

Accepted

## Context

`cleanup-selection-source` (events/selection/core.cljs:263-304) handles spell disposition after a selection resolves — determining whether to move the spell to graveyard, remove a copy, or no-op when the spell was already moved by its own effects (e.g., `:exile-self`). This is spell lifecycle logic, not selection mechanism logic.

The function is the sole reason core.cljs imports `engine/resolution` and `engine/stack`. All other engine imports in core.cljs serve selection routing (validation, effects, queries). The spell cleanup imports are architecturally anomalous — they create a coupling between the selection mechanism module and spell lifecycle concerns.

Currently the function has 4 branches (stack-item source, nil spell, on-stack spell, already-moved spell) handling 2 real paths (standard cleanup and bypass for exile-self effects). As new card effects introduce new spell dispositions (return-to-hand, shuffle-into-library, transform), each new disposition would add a branch to core.cljs for reasons unrelated to selection.

An alternative approach using the continuation protocol (ADR-020) was considered but rejected. Continuations express game flow ("after this selection, resolve the next stack item"), not bookkeeping. Adding spell cleanup to the continuation chain would mix flow-control and lifecycle concerns in the continuation protocol, and require builders to correctly compose cleanup continuations — trading one implicit contract for another.

## Decision

We will extract `cleanup-selection-source` and `remove-spell-stack-item` into a dedicated module (`events/selection/spell_cleanup.cljs`). The call site in `standard-path` remains unchanged — it calls the cleanup function at the same point in the selection lifecycle. Only the module location changes.

The engine imports (`engine/resolution/move-resolved-spell`, `engine/stack/remove-stack-item`, `engine/stack/get-stack-item-by-object-ref`) move with the function. `core.cljs` imports the new module and calls its public function.

## Consequences

**Positive:**
- core.cljs loses its anomalous engine imports — it depends only on selection-relevant engine modules (validation, effects, queries)
- Spell cleanup growth (new dispositions, new branches) is contained in the cleanup module, not the mechanism module
- The cleanup module's imports clearly declare its purpose — it depends on engine/resolution and engine/stack

**Negative:**
- One additional module in the selection directory (9 → 10 files)
- The timing and data constraints remain — cleanup still reads `:selection/spell-id` and `:selection/source-type` from the selection map, still called at the same lifecycle point

**Neutral:**
- No behavioral change — same code, same timing, same data flow
- Tests for cleanup logic can remain in core_test.cljs or move to a new test file
