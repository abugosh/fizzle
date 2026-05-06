# ADR 036: Structural Guard for Game Init Ordering

## Status

Accepted

## Context

Game state construction requires a specific ordering: card entities must be transacted before `create-complete-player` (which resolves card references for turn-based triggers), and player entities must exist before `build-object-tx` (which requires player EIDs as owner/controller). This 3-step dependency chain is currently enforced only by the calling sequence in `events/init.cljs` and `sharing/restorer.cljs`.

An intuition audit (2026-05-06) identified this as a temporal coupling tension. With the Scenario Builder adding a third init path (and executor subagents potentially writing it), the ordering convention could be violated silently — producing a Datascript error at runtime rather than a clear precondition failure.

## Decision

We will add precondition asserts to `build-object-tx` (engine/objects.cljs) that verify:
1. The card entity referenced by `card-eid` exists in the DB
2. The player entity referenced by `owner-eid` exists in the DB

These are cheap runtime checks (single `d/entity` lookups) that fail with clear error messages rather than opaque Datascript errors.

## Consequences

- New init paths get immediate feedback if ordering is wrong
- Existing init and restore paths are unaffected (they already satisfy the preconditions)
- Small runtime cost (~2 entity lookups per object construction) — negligible relative to the `d/transact!` that follows
