# ADR 029: Flat disjoint ability-type taxonomy; `:match/has-ability-type` uses a single keyword

Date: 2026-04-22

## Status

Accepted

## Context

MTG's ability taxonomy (rule 113) is hierarchical: mana abilities are a specific subset of activated abilities. A card referring to "each activated ability" in the abstract would, by rule, include mana abilities; cards that mean "activated ability that isn't a mana ability" (Tsabo's Web, Stasis Orb) call it out explicitly.

Fizzle's data model flattens this hierarchy. Each ability object has a singular `:ability/type` field drawn from a closed set: `:mana`, `:activated`, `:triggered` (triggers are actually stored separately under `:card/triggers`, but the keyword is reserved). Every engine check is an exact-match comparison: `(= :mana (:ability/type ability))`. Mana abilities are **not** a subset of activated abilities in Fizzle; they are a disjoint category with distinct activation semantics (no stack).

The flattened model surfaced during the Tsabo's Web brainstorm. Tsabo's Web restricts "each land with an activated ability that isn't a mana ability" — in MTG's hierarchy this is "activated minus mana"; in Fizzle's flattened taxonomy it is simply "has `:ability/type :activated`". The mapping is clean: the exclusion in MTG oracle text is implicit in Fizzle's data shape.

The proposed `:match/has-ability-type :activated` criterion takes a single keyword. A criterion that needs to match multiple ability types (e.g., a hypothetical future card restricting "any activated ability, including mana") would not map cleanly — it would need a set-valued shape like `:match/has-ability-type #{:activated :mana}`. No such card exists in Fizzle's current or near-term scope (Premodern combo practice). Accepting the single-keyword shape is a scoped decision, not a permanent one.

## Decision

We accept Fizzle's flat disjoint ability-type taxonomy and adopt a single-keyword shape for `:match/has-ability-type`:

```clojure
{:match/has-ability-type :activated}
```

The predicate matches when the candidate object's card has at least one entry in `:card/abilities` whose `:ability/type` equals the given keyword.

When a future card requires a criterion that spans multiple ability types (e.g., `#{:activated :mana}`), the shape will be extended at that point — most straightforwardly by allowing `:match/has-ability-type` to accept either a keyword or a set, with set meaning "match if any type in the set is present." We do not pre-emptively widen the shape now.

## Consequences

- Tsabo's Web's criterion maps directly and readably: `{:match/types #{:land} :match/has-ability-type :activated}`. No set wrapper, no subtlety about what "activated" includes.
- The flattened taxonomy remains consistent with every existing engine check, which uses exact-match on `:ability/type`. No engine code treats mana as a subset of activated; adopting `:match/has-ability-type` as a single keyword preserves that discipline.
- A future card whose oracle reads "each permanent with an activated ability" (in MTG's broad sense, including mana) will not be expressible by passing `:activated` alone — it will need either a widened criterion shape or two restrictions combined. The widening is a small, scoped change; the cost of deferring it is one future card-implementation moment's worth of "extend the criterion before adding this card."
- Documentation in `docs/card-dsl.md` records this convention under `:match/*` criteria. Deferred to a doc update in a near-future card task.
- This ADR explicitly does not endorse the flat taxonomy as the "correct" model of MTG semantics — it endorses it as a pragmatic match for Fizzle's current card pool and query patterns. A future refactor toward MTG's hierarchy would supersede this ADR.
