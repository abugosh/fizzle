# ADR 033: Unified Selection Rendering Dispatch

## Status

Accepted

## Context

The selection system renders player choices through two parallel paths: modal overlays (`views/modals.cljs:render-selection-modal`) and inline controls (`:allocate-resource` in `mana_pool.cljs`, `:unless-pay` in `mana_pool.cljs`). The modal multimethod dispatches on `:selection/mechanism` (ADR-030); inline mechanisms are handled by returning `nil` from their modal defmethod and rendering in a separate component.

Epic fizzle-qnk6 introduces `:binary-choice` as a third inline mechanism and plans to consolidate `unless-pay-view` from `mana_pool.cljs`. The original design placed a second multimethod (`render-inline-selection`) in `controls.cljs`, creating two dispatch tables on the same key (`:selection/mechanism`) with an implicit partition: each mechanism is either inline or modal, but the partition is not declared anywhere — it is encoded as `nil` returns from one table and non-nil returns from the other.

This implicit dual-table pattern violates the simplicity principle: a developer adding a new mechanism must understand the partition rule and register in both tables. With 3 downstream epics (accumulate, pick-mode, keyboard shortcuts) each adding inline mechanisms, the partition's coordination cost grows.

## Decision

We will replace the dual-table pattern with a single unified multimethod `render-selection` that dispatches on `:selection/mechanism` and returns a tagged result:

- `[:inline [component-hiccup]]` — rendered in the controls area (replaces priority buttons)
- `[:modal [component-hiccup]]` — rendered as a modal overlay
- `nil` — no selection UI (mechanism handled elsewhere, e.g., `:allocate-resource` in mana pool)

All 7 existing `render-selection-modal` defmethods migrate to `render-selection`, returning `[:modal ...]` instead of bare components. The `:binary-choice` defmethod returns `[:inline ...]`. The layout code in `core.cljs` calls the multimethod once, destructures the tag, and routes the component to the appropriate render location.

`modals.cljs:render-selection-modal` is retired after migration. The new multimethod lives in `views/modals.cljs` (or a new `views/selection_router.cljs` — implementation detail).

## Consequences

**Positive:**

- One dispatch table for all mechanism rendering. Adding a new mechanism requires one defmethod in one place.
- The render-mode (inline vs modal) is explicit in the return value, not implicit across two tables.
- Downstream epics (accumulate, pick-mode) register by adding a defmethod with the appropriate tag — no coordination with a second dispatch table.

**Negative:**

- Migration cost: 7 existing defmethods change from returning `[component]` to `[:modal [component]]`.
- `core.cljs` layout logic becomes slightly more complex: it must destructure the tagged result and route to either the controls area or the modal overlay.
- Return type is a tagged tuple rather than a bare component — callers must destructure.

**Neutral:**

- `:allocate-resource` continues returning `nil` from the unified dispatch (rendered inline by `mana_pool.cljs`). Migrating mana-allocation inline rendering to the unified dispatch is a future option, not a requirement of this ADR.
- The mechanism alphabet (≤8 per ADR-030) bounds the total number of defmethods in the unified table.
