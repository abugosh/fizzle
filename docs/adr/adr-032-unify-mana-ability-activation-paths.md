# ADR 032: Unify Mana Ability Activation Paths

Date: 2026-04-30

## Status

Accepted

## Context

Mana ability activation has two parallel paths: one for card-defined abilities (`:card/abilities`) and one for granted abilities (`:object/grants`). The card path routes through `engine/mana_activation.cljs` with `can-activate?` enforcement, optional selection for generic mana costs, and explicit history entry creation per ADR-018. The grant path uses an inline pure function in `events/abilities.cljs` that calls `pay-all-costs` and `execute-effect` directly, skipping `can-activate?`, selection, and history.

The two paths exist because grants were added after the card-ability activation pipeline was established. At the time, grants were simple (sacrifice-for-mana with no conditions or generic costs), so a direct path seemed sufficient. However, this created a mechanism bypass: grants achieve the same outcome (pay costs, produce mana) through a parallel path that skips 3 pipeline stages.

The bypass produces 4 downstream workarounds:
1. Hardcoded amber styling for grant buttons (views/battlefield.cljs:111) — compensates for grants not producing normalized button-specs
2. Separate grant extraction function (views/battlefield.cljs:67-74) — compensates for grants stored outside `:card/abilities`
3. No `:any` color expansion for grant buttons — compensates for lack of normalized button-spec producer
4. Inline domain logic in events/abilities.cljs:361-391 — compensates for grants not fitting the mana_activation pipeline

Additionally, the grant path violates ADR-018 (explicit history entry creation): grant activations are invisible to undo/fork.

The ability data shape inside `:grant/data` is already identical to card abilities — same keys (`:ability/type`, `:ability/cost`, `:ability/effects`). The divergence is purely in lookup and pipeline routing, not in data shape.

## Decision

We will unify mana ability activation into a single pipeline. An ability-ref (`{:source :card, :index N}` or `{:source :grant, :grant-id uuid}`) locates an ability regardless of source. A single resolver function returns the ability map. After resolution, the same pipeline applies: `can-activate?` → cost payment → effect execution → history entry.

One button-spec producer replaces the separate card and grant extraction functions. One event handler replaces the two separate handlers. The grant-specific pure function and event handler are deleted.

As part of this unification, `:ability/produces` is removed from all card definitions. Cards will declare `:ability/effects [{:effect/type :add-mana :effect/mana {...}}]` as the single source of truth. The button-spec producer derives display data (color, amount) from `:ability/effects`. This eliminates the dual-representation drift risk between `:ability/produces` and `:ability/effects`.

## Consequences

- Grant activations gain `can-activate?` enforcement, history recording, and selection support (for future grants with generic mana costs) by routing through the existing pipeline. No new code needed for these invariants — they come from the pipeline.
- The 4 downstream workarounds are eliminated. One button-spec producer, one styling rule, one event handler.
- The fizzle-jfj1 UX bug (sacrifice buttons losing color differentiation) is fixed as a natural consequence: the unified button-spec carries color data, and the unified styling rule applies color fill with an amber border accent for sacrifice.
- Cards that currently declare only `:ability/produces` (Plains, Lotus Petal, Crystal Vein) must be migrated to declare `:ability/effects`. Cards that declare both (Chromatic Sphere, Rain of Filth) drop `:ability/produces`. Engine sites that read `:ability/produces` are migrated to read `:ability/effects`.
- The event signature changes from `[object-id color player-id ability-index]` to `[object-id ability-ref color player-id]`. All dispatch sites in views/ and bots/ must be updated.
- `::activate-granted-mana-ability` event and the `activate-granted-mana-ability` pure function are deleted.
