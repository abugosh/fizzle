# ADR 024: Accept Selection Core Rate-of-Change Mismatch

## Status

Accepted

## Context

selection/core.cljs changed 23 times in 6 months while stable domain modules (storm, combat, untap, land_types) changed 1-5 times each. All reside within the same `events_selection` component in the architecture model. This is a rate-of-change mismatch per Brand's shearing layers model.

The rate is driven by a specific refactoring campaign (ADRs 018-020: validation absorption, toggle purity, continuation chaining, pending-entry migration). That campaign addressed accumulated design debt and is now complete. Domain modules were unaffected by protocol changes.

## Decision

We accept the rate-of-change mismatch as incidental rather than structural. The refactoring campaign that drove core.cljs's high change rate is complete. If core.cljs stabilizes over the next 3 months, the mismatch was temporary.

If core.cljs continues changing at 3x+ the rate of domain modules after 3 months (i.e., by July 2026), revisit with a structural split between Selection Mechanism (core.cljs) and Selection Types (domain modules).

## Consequences

- No structural change to the architecture model.
- The `events_selection` component boundary remains as-is (mechanism + domain in one component).
- Monitoring: re-audit selection change rates if significant protocol work resumes.
