# ADR 020: Composable Continuation Chaining in Selection Pipeline

## Status

Accepted

## Context

The selection system uses an `apply-continuation` multimethod to run follow-up logic after a selection completes. Continuations are attached to selections as data (`:selection/on-complete`), letting modules declare "after this selection, do X" without requiring each other — the multimethod in `selection/core.cljs` acts as the indirection point.

Two continuations exist today: `:cast-after-spell-mode` (casting.cljs) and `:resolve-one-and-stop` (priority_flow.cljs). The current signature is `(continuation, app-db) -> app-db` — a continuation returns a transformed app-db and nothing else.

This produces three problems:

1. **Circular dependency escape hatch.** `casting.cljs` sits at the bottom of the dependency graph — both `director.cljs` and `priority_flow.cljs` require it. The `:cast-after-spell-mode` continuation needs to trigger `resolve-one-and-stop` (defined in `priority_flow.cljs`) for the non-targeted cast-and-yield path, but casting cannot require priority_flow. The workaround is `rf/dispatch` at casting.cljs:236, which fires resolution asynchronously — splitting what should be a single synchronous pipeline across two event handler invocations. The targeted path avoids this by attaching `:resolve-one-and-stop` as a continuation on the selection, but the non-targeted path has no selection to attach it to.

2. **Split ownership of deferred entry processing.** `process-deferred-entry` is called in two places: inside the `:resolve-one-and-stop` continuation (priority_flow.cljs:119) and as a fallback in `confirm-selection-impl` (core.cljs:429). Which path runs depends on control flow, not data. This makes the ordering between continuation execution and history entry creation implicit.

3. **Continuation logic that wants to compose but can't.** The `:cast-after-spell-mode` continuation has three branches: attach `:resolve-one-and-stop` to a new selection (targeted cast-and-yield), fire `rf/dispatch` to trigger resolution (non-targeted cast-and-yield), or return unchanged (normal cast). This is a continuation that wants to chain to another continuation but has no mechanism to express that.

ADR-019 identified the `rf/dispatch` escape hatch as a tracked concern (fizzle-81fi). This decision resolves it.

## Decision

We will change `apply-continuation` to return `{:app-db app-db :then continuation-or-nil}` instead of plain `app-db`. The confirm-selection-impl loop drains the continuation chain — after executing a selection and applying its continuation, if `:then` is non-nil, it applies the next continuation, repeating until the chain is exhausted. `process-deferred-entry` runs once as the terminal step after the chain completes.

The `:cast-after-spell-mode` continuation will return `{:app-db result :then {:continuation/type :resolve-one-and-stop}}` for the non-targeted cast-and-yield path, replacing the `rf/dispatch` escape hatch with a pure data declaration. The targeted path continues to work as before — the continuation is attached to the selection and fires after targeting completes.

The `:resolve-one-and-stop` continuation will stop calling `process-deferred-entry` directly. That responsibility moves to the chain-draining loop in confirm-selection-impl, which calls it exactly once after all continuations have run.

## Consequences

**Positive:**
- The circular dependency between casting.cljs and priority_flow.cljs is resolved without restructuring the require graph. Continuations compose via data, not function calls.
- `process-deferred-entry` has a single call site — the end of the continuation chain in confirm-selection-impl. No more split ownership.
- The cast-and-yield flow becomes a declared pipeline (cast, then resolve, then process history) rather than conditional branching that mixes immediate execution with deferred setup.
- No `rf/dispatch` escape hatches remain in the events layer. All flow is synchronous and pure.

**Negative:**
- `apply-continuation` callers must return `{:app-db ...}` instead of plain app-db. Both existing defmethods and the default method need updating.
- The concept of composable continuations could invite overuse. If continuations start chaining three or four deep, the flow becomes harder to trace than explicit branching. This should be guarded in review.

**Neutral:**
- The continuation chain is bounded by the number of registered continuation types (currently 2). Deep chains would require new defmethods, making overuse visible.
- The `:finalized` selection lifecycle continues to work as before — finalized selections with continuations fire the continuation immediately, which may now chain.
