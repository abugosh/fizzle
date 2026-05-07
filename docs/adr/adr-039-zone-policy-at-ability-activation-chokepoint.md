# ADR 039: Zone policy at ability activation chokepoint; cost types are zone-agnostic

Date: 2026-05-07

## Status

Proposed

## Context

Two of twelve cost-type implementations in `engine/costs.cljs` contain explicit `:battlefield` zone guards: `:sacrifice-self` (line 134) and `:discard-hand` (line 156). The remaining ten cost types are zone-agnostic — they locate the source object's controller via entity ID navigation without checking zone.

These zone guards are semantically correct (sacrifice is a battlefield concept in MTG, discard-hand abilities live on permanents) but redundant: `events/abilities.cljs:activate-ability` already validates zone at line 289 before any cost checking occurs. The guards re-check what the caller has already verified.

This redundancy becomes a problem when the ability pipeline is generalized for non-battlefield abilities (ADR-038). A cycling ability with `{:ability/zone :hand :ability/cost {:discard-self true :pay-life 2}}` would pass the ability-level zone check (`:hand` matches `:ability/zone`) but could confuse future maintainers who see `:battlefield` checks inside cost types and wonder whether the cost system is enforcing zone policy independently.

The design principle "separate mechanism from policy" (CLAUDE.md) applies: costs are mechanism (deduct resources from a player), zone requirements are policy (this ability only works from this zone). Mixing policy into mechanism creates the tension where neither the caller nor the callee clearly owns zone validation.

## Decision

We will establish the ability activation chokepoint (`events/abilities.cljs:activate-ability`) as the single location for zone policy enforcement. Cost-type implementations will be zone-agnostic.

Specifically:

1. Remove the `:battlefield` zone check from `:sacrifice-self` `can-pay?` in `engine/costs.cljs`.
2. Remove the `:battlefield` zone check from `:discard-hand` `can-pay?` in `engine/costs.cljs`.
3. The ability activation chokepoint reads `:ability/zone` (defaulting to `:battlefield`) and validates the source object's zone before calling `can-activate?` or `pay-all-costs`.

No new zone checks are added to cost types. Cost types trust that the caller validated zone context before invoking them.

## Consequences

- Zone policy is enforced at a single chokepoint. No scattered zone guards across cost implementations.
- Cost types become fully composable across zone contexts. A `:sacrifice-self` cost on a hand-zone ability (if such a card existed) would fail at the ability activation chokepoint, not inside the cost method.
- The 2 removed zone guards are redundant today (the ability pipeline already checks zone), so removing them changes no observable behavior for existing cards.
- Future cost types do not need to consider zone policy — they implement pure resource deduction.
- This is a prerequisite for ADR-038 (unify cycling into ability pipeline), where the activation chokepoint must validate `:ability/zone :hand` instead of hardcoded `:battlefield`.
