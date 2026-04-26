# ADR 031: Anchor `:game/selected-card` cleanup on zone-change via post-event interceptor

Date: 2026-04-26

## Status

Accepted

## Context

`:game/selected-card` is an app-db key holding an object EID. It serves two genuinely-shared concerns: decision-gating in `::can-cast?` / `::can-play-land?` / `::can-cycle?` subscriptions (subs/game.cljs:88, 100, 109) and cross-component visual highlighting in hand and graveyard views (views/hand.cljs:29, views/graveyard.cljs:40). An empirical investigation (intuition audit, 2026-04-26) confirmed both are load-bearing — component-local atoms would lose cross-zone highlight identity, and removing the shared signal would force callers to re-implement it.

The structural problem is the cleanup lifecycle. The implicit invariant is: **the EID stored in `:game/selected-card` references an object currently in a zone where selection is meaningful (`:hand` or `:graveyard`).** That invariant is currently maintained by `dissoc :game/selected-card` scattered across 15+ sites:

- 3 dissocs in `cast-spell-handler` (events/casting.cljs:162, 168, 171)
- 2 of 3 ability handlers in events/abilities.cljs:43, 314 (the third — `::activate-granted-mana-ability` at line 401 — is the unfixed sibling, fizzle-ktba)
- 1 conditional dissoc on selection `:finalized` lifecycle (events/selection/core.cljs:456)
- 11 selection builders setting `:selection/clear-selected-card? true` (costs.cljs × 9, mana_ability.cljs:57, targeting.cljs:100)
- 1 dissoc on history navigation (history/core.cljs:76)

This event-cause-scoped maintenance produced two recurring bugs in the same week (fizzle-gr9a closed 2026-04-25, fizzle-ktba opened 2026-04-25). Each new cause of a zone change has to remember to clean up, and most do not. The triggering condition for cleanup is not "this user action ran" — it is **"the referenced object's zone changed to one where selection is no longer meaningful."** The chokepoint for that condition exists at `engine/zone_change_dispatch.cljs`, which already routes 40+ zone-change paths and broadcasts `:zone-change` events. ADR-026 (single-point trigger registration) and ADR-027 (untap chokepoint consolidation) establish the chokepoint pattern as the canonical response to "scattered maintenance of a single concern."

ADR-001 forbids re-frame in `engine/`. The cleanup must therefore live in `events/`, not `engine/`. ADR-005 places all UI state in app-db. ADR-022 establishes the precedent: cross-cutting cleanup concerns live in dedicated events-layer modules. The `bots/interceptor.cljs` pattern further establishes that re-frame post-event interceptors are an accepted mechanism for cross-cutting events-layer concerns that must observe game-db state transitions.

A secondary concern surfaced in the same audit: `cast-spell-handler` (casting.cljs:271) and `cast-and-yield-handler` (priority_flow.cljs:140, 160) accept `:game/selected-card` as an implicit fallback when no explicit `:object-id` is in the event payload. 100% of production human-click casts hit this fallback (controls.cljs:61, 68 dispatch with no args). All test sites pass explicit args. This is asymmetric with `::play-land` and `::cycle-card` in the same view, which already pass `selected` explicitly — the asymmetry is incidental, not principled, and creates a hidden coupling between user-click app-db state and event semantics.

A tertiary concern: there is no test exercising the no-arg dispatch shape `[::cast-spell]` (the production human-click path). All `cast-spell` tests dispatch with `{:object-id obj-id ...}`. After tightening the entry points (per below), the no-arg form ceases to exist and the gap closes structurally.

## Decision

We will:

1. **Anchor `:game/selected-card` cleanup at a re-frame post-event interceptor.** A new interceptor (housed in a new module, provisional name `events/ui_invariants.cljs`) inspects `:game/db` after each game-mutating event and dissocs `:game/selected-card` if the referenced object's post-event zone is not in `{:hand :graveyard}`. The interceptor is registered globally on game-mutating event handlers. The pattern mirrors `bots/interceptor.cljs`, which already runs as a post-event interceptor for cross-cutting concerns. The 15+ scattered dissocs and the entire `:selection/clear-selected-card?` flag mechanism are removed.

2. **Tighten cast event entry points.** `controls.cljs:61, 68` dispatch `[::cast-spell selected]` and `[::cast-and-yield selected]` explicitly, matching `[::play-land selected]` and `[::cycle-card selected]` in the same view. `cast-spell-handler` and `cast-and-yield-handler` drop the `(or (:object-id opts) (:game/selected-card app-db))` fallback; callers must pass `:object-id` explicitly. The single test exercising the no-arg form (`cast_and_yield_test.cljs:93`) is updated to match.

3. **Codify the storage decision.** `:game/selected-card` remains in app-db as a single source of truth shared between decision-gate subscriptions and cross-component view highlighting. Identity storage is acceptable when paired with the chokepoint cleanup mechanism above. Component-local atoms are not used because cross-zone identity is required (clicking a card in hand and switching to the graveyard tab is expected to follow the highlight if the card moved).

4. **Subsume fizzle-ktba into the chokepoint resolution.** The granted-mana sacrifice-self bug auto-clears once the interceptor lands. ktba's regression test (Rain of Filth scenario) is preserved as a verification target and closes when the interceptor satisfies it.

The selectable-zone predicate is `{:hand :graveyard}` — the union of zones from which views read `:game/selected-card`. Battlefield does not use this key (battlefield permanents are clicked for tap/activate, not for selection), and flashback/graveyard targeting uses `:selection/selected` (a separate mechanism), so this predicate fully covers current consumers.

## Consequences

**Positive:**

- One site maintains the `:game/selected-card` invariant. Adding a new zone-change cause covers cleanup automatically. The fizzle-gr9a / fizzle-ktba class of bugs is structurally eliminated.
- The `:selection/clear-selected-card?` flag pattern (11 builder sites + 1 reader) is deleted. Selection builders no longer carry app-db cleanup responsibility — they only describe the selection.
- 6+ ad-hoc dissocs in casting.cljs and abilities.cljs are removed. Handler result assembly stops braiding game-state transformation with UI-invariant maintenance.
- Cast event dispatches in controls.cljs become symmetric with play-land/cycle-card. The production human-click path uses the same shape as tests, closing the implicit testing gap.
- Decision-gate subscriptions and view highlights share a single source of truth that is reliably maintained.

**Negative:**

- One new module (`events/ui_invariants.cljs` or similar) joins events/. Small footprint.
- The interceptor runs on every game-mutating event. The predicate is cheap (single keyword lookup + zone check on one entity), but it is a new fixed cost. Same shape as the bot interceptor.
- Adding a future app-db UI invariant in this class (e.g., a hypothetical `:game/hovered-card` if it gained semantic weight) requires extending the interceptor. No second key in this class currently exists, so the seam is created on demand, not speculatively.

**Neutral:**

- ADR-023's history-navigation clear is partially superseded for `:game/selected-card`: the interceptor's reconciliation also covers the post-history-navigation game-db state. ADR-023's registration protocol is retained for non-game-state UI keys (`:history/pending-entry`, `:history/deferred-entry`); only the `:game/selected-card` and (where applicable) `:game/pending-selection` entries in `clear-stale-ui-state` are replaced.
- The interceptor approach is selected over a wrapper-around-engine-call approach (option B in fizzle-ik8x) because zone changes occur via engine-internal call chains (SBA, replacement effects, resolution disposition) where an events-layer wrapper has no visibility. The interceptor observes net pre/post `:game/db` regardless of how the change happened.
- An app-db invariant validator at the dispatch boundary (option C in fizzle-ik8x) was not selected because no second invariant in this class exists today; introducing a generalized validator would be premature. The interceptor solves the concrete problem; if more invariants in this class emerge, the interceptor can grow.
