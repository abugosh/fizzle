# ADR 030: Decomplect `:selection/type` into `:selection/mechanism` and `:selection/domain`

Date: 2026-04-23

## Status

Accepted

## Context

The selection system's `:selection/type` keyword currently braids two orthogonal concerns: the **mechanism** of gathering input (pick-N-from-a-pool, reorder, accumulate a number, collect N named targets) and the **domain** that policy attaches to after confirmation (return-from-graveyard, sacrifice-a-permanent, shuffle-into-library, cast-after-mode-choice). Of 29 concrete `:selection/type` values in use, ~10 name a domain effect rather than a mechanism; every such keyword owns its own `defmethod core/execute-confirmed-selection` even when the underlying mechanism is already handled by a peer.

The duplication is visible in `events/selection/zone_ops.cljs` and `events/selection/costs.cljs`. Three keywords in `zone_ops.cljs` — `:discard`, `:hand-reveal-discard`, `:shuffle-from-graveyard-to-library` — each implement `(reduce #(zone-change-dispatch/move-to-zone-db %1 %2 <zone>) selected)` with a trailing domain-specific finalize. Three in `costs.cljs` — `:return-land-cost`, `:sacrifice-permanent-cost`, `:exile-cards-cost` — each pick K items from a zone and move them to another zone, then run a finalize that casts the pending spell or updates a resource pool. The mechanism in every case is "pick K from zone A, move to zone B, then run a post-move policy step"; the keyword space makes it look like N different mechanisms.

View-layer dispatch has already discovered a partial split. `views/modals.cljs`'s `modal-dispatch-key` reads `:selection/pattern` first and falls back to `:selection/type`; 16 of 29 types set a pattern so that view code (reorder, accumulator) is reused across keywords that name different domains. The pattern field is optional and inconsistent, but its presence is evidence the codebase has been drifting toward a mechanism/domain split without naming it.

ADR-024 accepted the rate-of-change mismatch between `events/selection/core.cljs` (34 commits / 6 months) and stable domain modules (1–5 commits / 6 months) as incidental, tied to the refactoring campaign of ADRs 018–020, with a stated revisit trigger: *"If core.cljs continues changing at 3x+ the rate of domain modules after 3 months (i.e., by July 2026), revisit with a structural split between Selection Mechanism (core.cljs) and Selection Types (domain modules)."* The mechanism/view audit motivating this ADR is that revisit, arriving in April 2026 because Rushing River's kicker DSL extension surfaced a new complected type (`:multi-target`) before the July 2026 review window.

The driver card is Rushing River (Premodern blue instant), whose kicker mode requires collecting two cast-time targets in a single modal. Without the mechanism/domain split, Rushing River adds either a new complected type (`:multi-target`, 11th such keyword in the braid) or a per-card view override (`:selection/pattern :multi-slot-picker` reusing `:cast-time-targeting` mechanism, which half-decomplects on the view side only). Either route ships the card; neither resolves the braid.

## Decision

We will split `:selection/type` into two fields on the selection spec:

- **`:selection/mechanism`** — a required, bounded keyword drawn from a small alphabet describing *how* input is gathered. The initial alphabet is `:pick-from-zone`, `:reorder`, `:accumulate`, `:n-slot-targeting`, `:allocate-resource`, `:zone-pick` (for legacy patterns already using this name). Additions to the alphabet are ADR-worthy events.

- **`:selection/domain`** — a free-form keyword tag identifying the domain policy that applies after confirmation. Examples: `:graveyard-return`, `:chain-bounce`, `:return-land-cost`, `:sacrifice-permanent-cost`. Domains are cheap to add; each names a policy, not a mechanism.

`core/execute-confirmed-selection` will dispatch on `:selection/mechanism`. Each mechanism defmethod runs the generic input-gathering finalization (iterate, move, return result) and delegates domain-specific post-processing to a second multimethod `apply-domain-policy` that dispatches on `:selection/domain`. Domain policies are thin: zone routing, history entry shape, continuation chaining. Mechanism defmethods are shared across all domains that use that mechanism.

`views/modals.cljs` dispatches on `:selection/mechanism` (falling back to existing `:selection/pattern` during migration, removed after). The ad-hoc `modal-dispatch-key` cond collapses to direct dispatch. Targeting views that currently special-case `:target/type :player` / `:any` become mechanism-level branches or dedicated mechanisms (`:player-target`, `:any-target`) — to be decided during implementation.

Migration is incremental. All 29 existing `:selection/type` values keep working during the transition via a compatibility adapter that sets `:selection/mechanism` and `:selection/domain` from the legacy keyword. Each domain file is migrated to the new shape one at a time, with card tests verifying no behavioral change. `:selection/type` is retired after the last domain migrates and the adapter is removed.

This refactor is a predecessor to the Rushing River kicker DSL extension. Rushing River's multi-target cast-time targeting becomes `{:selection/mechanism :n-slot-targeting, :selection/domain :cast-time-targeting, ...}` — no new mechanism defmethod, no new domain file.

## Consequences

**Positive:**

- Adding a new card with an existing mechanism does not require a new mechanism defmethod — only a new domain registration, if any. Peer-level duplication (the `reduce + move-to-zone-db` triplet in zone_ops.cljs) collapses into one generic implementation.
- The view-layer `modal-dispatch-key` cond collapses because `:selection/mechanism` is a required field; `:selection/pattern` as an optional hint goes away.
- ADR-023's intent (single selection pipeline) aligns better with a mechanism/domain split than with the current monolithic type key. Finishing `:game/pending-mode-selection` retirement (Tension 3) becomes a natural follow-up because both pieces of the unified pipeline will use the new shape.
- `events/selection/core.cljs`'s rate-of-change is expected to fall after this migration completes, validating ADR-024's incidental framing — the churn moves from mechanism-level protocol changes to domain-level additions, which are cheap.

**Negative:**

- One-time migration cost across 13 `events/selection/*.cljs` files, `events/selection/spec.cljs`, every card that references a complected type through its effect shape, and `views/modals.cljs`. Every card test that hand-builds a selection needs inspection.
- Adds a second dispatch layer: mechanism first, domain second. Trace depth increases by one hop when debugging. Mitigated by keeping the mechanism alphabet small (≤8) and domain names free-form but descriptive.
- The refactor is a predecessor to Rushing River. Shipping Rushing River requires completing this migration — or agreeing to ship Rushing River as an `:selection/pattern :multi-slot-picker` hack and retrofit it later.

**Neutral:**

- `:selection/pattern` disappears as an independent concept. View reuse happens via mechanism keyword, not a parallel hint field. Legacy patterns (`:reorder`, `:accumulator`, `:zone-pick`) become mechanism keywords.
- The `:selection/lifecycle` field (`:standard` / `:finalized` / `:chaining`) is orthogonal to this split and remains unchanged. Lifecycle is a third axis (*when* does control return to the caller), distinct from mechanism (*how* input is gathered) and domain (*what* policy applies).
- Supersedes the portion of ADR-024 that anticipated a mechanism/domain split. ADR-024's rate-of-change observation stays; its conditional revisit clause is now superseded by this ADR's decision.
