# ADR 042: Zone check in `can-activate?`; cost types remain zone-agnostic

Date: 2026-05-13

## Status

Accepted (amends ADR-039)

## Context

ADR-039 established that cost-type implementations (`can-pay?` multimethods in `engine/costs.cljs`) are zone-agnostic — zone enforcement lives at the ability activation chokepoint (`events/abilities.cljs:activate-ability`). This principle is sound: costs are mechanism (deduct resources), zone requirements are policy.

However, ADR-039's decision statement also placed `can-activate?` below the zone enforcement boundary: "the ability activation chokepoint reads `:ability/zone` and validates the source object's zone before calling `can-activate?`." This positioned `can-activate?` as a zone-agnostic function alongside cost types.

An architecture audit (2026-05-13) found that `can-activate?` is not mechanism — it is already a policy composition layer. It combines `costs/can-pay?` (mechanism) with `conditions/check-condition` (policy: threshold, etc.) to answer "can this ability be activated?" Conditions are game-state-dependent policy decisions, not resource deduction. Zone is the same category of precondition as a condition.

The audit found 5 production call sites of `can-activate?`. Two precede it with zone checks (events/abilities.cljs:348, engine/mana_activation.cljs:181). Three do not (subs/game.cljs:120, events/ui_invariants.cljs:40, events/selection/mana_ability.cljs:98). The two sites without zone checks that are consumer-facing (subscription, interceptor) are both bugs: they report cycling abilities as activatable for cards in the wrong zone (fizzle-fwdm, related to fizzle-7aig).

This is the third instance of the selected-card-persists bug class (fizzle-gr9a → fizzle-ktba → fizzle-fwdm). The cycling migration (commit d27608a) replaced `rules/can-cycle?` (which had an explicit zone check) with `can-cycle-via-ability?` (which calls `can-activate?` without zone check). The zone check was silently lost because `can-activate?`'s partial contract was not visible to the migration author.

No production caller of `can-activate?` needs zone-agnostic behavior. All 5 callers are asking "can this ability actually be activated right now?"

## Decision

We will add zone checking to `can-activate?` in `engine/abilities.cljs`. The function will read `(:ability/zone ability :battlefield)` and compare against the object's current zone. This makes `can-activate?` the complete policy predicate for ability activation legality.

ADR-039's principle is clarified, not reversed:
- **Cost types** (`can-pay?` multimethods) remain zone-agnostic. They are mechanism.
- **`can-activate?`** is policy composition. It combines zone, conditions, and cost checks into a single activation legality answer.

The existing zone checks in `events/abilities.cljs:348` and `engine/mana_activation.cljs:181` become defense-in-depth, not load-bearing. They may be retained or removed at implementer discretion.

## Consequences

- `can-activate?` answers the full question its name implies. Predictive callers (subscriptions, interceptors) get correct answers without independently adding zone checks.
- The scattered zone-check-before-call pattern (identified as a workaround cascade in the audit) is eliminated. Future callers of `can-activate?` do not need to independently add zone checks.
- ADR-031's actionability predicate in `events/ui_invariants.cljs` automatically gains zone awareness through `can-activate?`. The `can-cycle-via-ability?` helper and the `::cycling-ability-index` subscription are both fixed without code changes at those call sites.
- The `any-action-available?` composite predicate (proposed for `engine/rules.cljs`) can delegate to `can-activate?` knowing it returns a complete answer.
- ADR-039's cost-type zone-agnosticism is preserved. The amendment is scoped to `can-activate?` only.
