# ADR 041: Add :sequence-pick as the 8th selection mechanism

Date: 2026-05-10

## Status

Proposed

## Context

Storm-targeted spells (e.g., object-targeting storm effects that allow choosing which copies resolve against which targets) require the player to specify an ordered sequence of selections, where duplicates may occur and the order matters. For example, a storm spell with a modal targeting effect might create 3 copies, and the player must decide the target of each copy in sequence (copy 1 → target A, copy 2 → target A, copy 3 → target B).

The existing selection mechanisms handle related but distinct input patterns:

- `:pick-from-zone` — select an unordered set of N objects from a zone (no duplicates, order irrelevant)
- `:n-slot-targeting` — fill N distinct target slots from a valid-targets set (order by slot, no duplicates within slots)
- `:accumulate` — distribute a numeric count (e.g., storm split allocating copies to players)

None of these mechanisms adequately capture "select an ordered sequence where position matters and duplicates are allowed." The closest analogs—`:reorder` (manages existing cards) and `:accumulate` (distributes numeric allocations)—require structural adaptation unsuitable for the common case.

ADR-030 established the bounded mechanism alphabet as ADR-worthy: adding mechanisms is an explicit architectural decision, not a cheap addition like domains. The current alphabet contains 7 mechanisms. Adding an 8th is architecturally significant and must be recorded.

## Decision

We will add `:sequence-pick` as the 8th selection mechanism. The mechanism represents "collect an ordered sequence of selections, allowing duplicates and requiring order preservation."

**Mechanism shape:**

```clojure
{:selection/mechanism :sequence-pick
 :selection/domain :storm-object-sequence  ;; first domain using this mechanism
 :selection/lifecycle :standard
 :selection/player-id player-id
 :selection/selected [selection-1 selection-2 ...]  ;; ordered vector of choices
 :selection/sequence-items [{:item-id 1 :prompt "Copy 1 target:"} ...]  ;; UI prompts
 :selection/valid-choices [choice-a choice-b ...]  ;; what can be chosen for each item
 ...}
```

**Mechanism alphabet expands from 7 to 8:**

1. `:pick-from-zone` — select N objects from a zone (unordered set)
2. `:reorder` — sort/assign cards into ordered positions
3. `:accumulate` — distribute/increment numeric values
4. `:allocate-resource` — assign mana to cost slots
5. `:n-slot-targeting` — fill N distinct target slots
6. `:pick-mode` — choose one named option
7. `:binary-choice` — choose one action keyword
8. `:sequence-pick` — collect ordered sequence of selections

**Implementation sites:**

1. `events/selection/spec.cljs:31-33` — add `:sequence-pick` to `:selection/mechanism` spec set
2. `events/selection/spec.cljs:100+` — add `(defmethod selection-type-spec :sequence-pick ...)`
3. `events/selection/core.cljs:213+` — add `(defmethod execute-confirmed-selection :sequence-pick ...)`
4. `views/modals.cljs:60+` — add `(defmethod render-selection :sequence-pick ...)`
5. `events/selection/spec.cljs:322+` — add minimal-valid entry for `:sequence-pick`

The first domain using this mechanism will be `:storm-object-sequence`, handling ordered target selection for storm spells with multiple copies and per-copy targeting.

## Consequences

**Positive:**

- Storm-targeted spells have a dedicated, well-named mechanism that clearly expresses "ordered sequence with duplicates allowed" instead of adapting an existing mechanism awkwardly.
- The mechanism alphabet remains small and bounded (8 mechanisms), preserving ADR-030's design intent of a comprehensible, enumerable set of input patterns.
- Future ordered-sequence-of-selections use cases (nested modals, step-by-step crafting, etc.) naturally use this mechanism without inventing new ones.

**Negative:**

- Mechanism alphabet expansion requires coordination across 4 files and 5 registration sites (spec set, spec multimethod, core defmethod, view defmethod, minimal-valid entry).
- Core.cljs's `defmulti` dispatch table and views/modals.cljs's domain dispatch `case` both grow by one arm. Small growth, but growth nonetheless.
- If future analysis shows the 8th mechanism is rarely used or overlaps significantly with existing ones, the architectural cost may prove unjustified. The decision to add this mechanism commits to maintaining a separate code path for ordered-sequence input.

**Neutral:**

- The `:selection/domain` (`:storm-object-sequence`) is separate from the mechanism. Domains may be added or removed freely without affecting the mechanism's architectural standing. If storm-targeting becomes obsolete, the domain is abandoned, but the mechanism remains available for other use cases (e.g., a "reorder with duplicates" interaction).
- `:selection/lifecycle` for `:sequence-pick` is `:standard` (default), same as `:pick-from-zone`. Non-standard lifecycles (`:finalized`, `:chaining`) are possible in future domains, but the mechanism itself is agnostic.

## Related ADRs

- **ADR-030** — Established the mechanism/domain split and bounded mechanism alphabet. This ADR expands the alphabet from 7 to 8, a rare event under ADR-030's governance model.
- **ADR-033** — Unified selection rendering dispatch. `:sequence-pick` rendering follows the same pattern (mechanism defmethod, domain case inside).

## Decision Log

- Initial investigation (2026-05-10): Selection mechanism hierarchy audit identified `:sequence-pick` as a distinct input pattern not cleanly subsumed by existing mechanisms.
- Context: Object-targeting storm effects need to prompt the player for an ordered sequence of targets (one per copy).
- Architecture: Following ADR-030, a new mechanism requires explicit decision, not ad-hoc addition.
