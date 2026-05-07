# ADR 038: Unify cycling into the activated ability pipeline

Date: 2026-05-07

## Status

Proposed

## Context

Cycling is an activated ability in MTG rules (CR 702.29a) that functions from a player's hand. Fizzle implements cycling as a standalone mechanism in `events/cycling.cljs` with its own event handler, pure function, validation check, UI button, subscription, and keyboard shortcut — all parallel to equivalent ability-system code.

The architectural audit (2026-05-07) identified this as a mechanism bypass with a 5-member parallel implementation cascade:

1. `views/controls.cljs:82-87` — separate cycling button (mirrors ability buttons in `views/battlefield.cljs`)
2. `subs/game.cljs:107-112` — separate `::can-cycle?` subscription (mirrors ability activation validation)
3. `engine/rules.cljs:601-617` — separate `can-cycle?` function (mirrors `abilities/can-activate?`)
4. `events/cycling.cljs:12-44` — separate `cycle-card` pure function (mirrors `events/abilities:activate-ability`)
5. `views/keyboard.cljs:331-332` — separate cycling keyboard dispatch (mirrors ability dispatch)

The bypass exists because cycling was implemented before the ability system was generalized. The ability pipeline (`events/abilities.cljs:activate-ability`) hardcodes `:battlefield` as the only valid activation zone (line 289), and cycling operates from `:hand`. Rather than generalizing the pipeline at the time, cycling was built as a separate path.

The immediate driver is Street Wraith, which has "Cycling—Pay 2 life" — a life payment cost that the current cycling path cannot handle (it only calls `mana/pay-mana`). The costs subsystem in `engine/costs.cljs` already supports `:pay-life` via multimethod dispatch, but cycling doesn't route through it.

ADR-032 (Unify Mana Ability Activation Paths) established the precedent: when a parallel activation path exists, unify it into the standard pipeline rather than extending the parallel path. That decision eliminated 4 downstream workarounds for the grant-path bypass.

ADR-029 (Flat Disjoint Ability-Type Taxonomy) defines `:mana`, `:activated`, and `:triggered` as disjoint ability types. Cycling will be a fourth disjoint type: `:cycling`. This extends the taxonomy without changing the flat-dispatch discipline — all engine checks remain exact-match on `:ability/type`.

## Decision

We will unify cycling into the activated ability pipeline. Cycling abilities will be declared as `{:ability/type :cycling}` entries in `:card/abilities` with explicit costs and effects. The `:card/cycling` key will be retired.

Specifically:

1. **Ability data shape:** Cycling cards declare a cycling ability in `:card/abilities`:
   ```clojure
   {:ability/id :cycle
    :ability/type :cycling
    :ability/zone :hand
    :ability/cost {:discard-self true :pay-life 2}   ;; Street Wraith
    :ability/effects [{:effect/type :draw :effect/amount 1}]}
   ```

2. **Zone-aware activation:** `events/abilities.cljs:activate-ability` will read `:ability/zone` (defaulting to `:battlefield`) instead of hardcoding `:battlefield` at line 289.

3. **New cost type:** A `:discard-self` cost type in `engine/costs.cljs` handles the "discard this card" component of cycling costs. It moves the source object from its current zone to graveyard via `zone-change-dispatch/move-to-zone-db`.

4. **Stack-based resolution:** Cycling abilities go on the stack like other activated abilities, per MTG rules (CR 702.29a). The effect `[{:effect/type :draw :effect/amount 1}]` resolves normally.

5. **Deletions:** `events/cycling.cljs` (module), `rules/can-cycle?` (function), `subs/::can-cycle?` (subscription), and the dedicated cycling button/keyboard paths are all deleted. Cycling UI emerges from the standard ability rendering.

## Consequences

- The 5 parallel implementations are eliminated. Cycling validation, cost payment, UI rendering, and keyboard dispatch all route through the existing ability infrastructure.
- Street Wraith's life-payment cycling cost works naturally — `{:discard-self true :pay-life 2}` routes through `costs/can-pay?` and `costs/pay-cost` multimethods.
- Future hand-activated ability types (channel, forecast, bloodrush, typecycling) can use `{:ability/zone :hand}` without creating new parallel paths.
- The `:cycling` ability type extends the flat taxonomy (ADR-029). Engine checks remain exact-match on `:ability/type`.
- Cycling going on the stack is a behavior change from immediate execution. In goldfish/burn matchups this is transparent (bot auto-passes priority). For future interactive opponents, it enables responses to cycling (e.g., Stifle).
- 7 existing cycling card files need their `:card/cycling` entries migrated to `:card/abilities` entries. This is mechanical.
- The `events/abilities.cljs:activate-ability` zone check becomes data-driven (reads `:ability/zone`), which is more general but requires each ability to declare its valid zone (defaulting to `:battlefield` preserves backward compatibility).
- `engine/card_spec.cljs` needs updates: `:card/cycling` spec removed, `:ability/zone` and `:ability/type :cycling` added to ability spec.
