# ADR 030 Addendum: Mechanism/Domain Mapping Audit

Date: 2026-04-23

Status: Accepted (addendum to ADR-030)

## Context

ADR-030 declared the initial mechanism alphabet illustratively and deferred the
canonical mapping to the first implementation task (fizzle-crxc). This addendum
records the results of that audit: the confirmed alphabet, two new mechanisms
required beyond the original candidates, the resolution of the 29 vs 33 type-count
discrepancy, and the locked `{:selection/type → (:mechanism :domain)}` table.

The canonical mapping is committed as pure data in
`src/main/fizzle/events/selection/mechanism_domain.cljs`, consumed by the task-2
compat adapter and the task-4 dispatch switch.

---

## Resolution: 29 vs 33 Type Count

ADR-030's text stated "29 concrete `:selection/type` values." The investigator
report cited 33. Both counts were correct at their point of reference; the
discrepancy has four sources:

1. **ADR-030 was written from a grep-based count** (`rg ':selection/type\s+:' src/main/`)
   that returns **32 unique values** in the current codebase — not 33. The grep
   misses `:shuffle-from-graveyard-to-library` because that type is only assigned
   dynamically via the zone-pick builder config (in `core.cljs`'s `zone-pick-config`
   map), never as a literal `:selection/type :shuffle-from-graveyard-to-library`
   token in any builder function.

2. **The investigator's count of 33** matches the number of `defmethod selection-type-spec`
   entries in `events/selection/spec.cljs` — the authoritative source of truth.
   The spec.cljs defmethods are the live registered types; the grep count was
   undercounting by 1 because of the dynamic-builder case above.

3. **The ADR's "29" was an earlier estimate** that predated the addition of several
   types via subsequent epics:
   - `:replacement-choice` (epic fizzle-39qd)
   - `:unless-pay` (epic fizzle-z9ep)
   - `:ability-cast-targeting` (epic fizzle-z9ep)
   - `:untap-lands` (epic fizzle-z9ep)

4. **The definitive count is 33** (all `defmethod selection-type-spec` entries).
   `minimal-valid-selections` in spec.cljs has 31 entries because `:discard` and
   `:shuffle-from-graveyard-to-library` are missing from that map (a gap noted for
   a future test-quality task, not a type-count issue).

**Summary:** 33 live types, all mapped below.

---

## :zone-pick Resolution

ADR-030's mechanism candidates included `:zone-pick`, noting it "appears in epic
candidates but also appears as a `:selection/pattern` value." This audit resolves it:

- `:zone-pick` is the **hierarchy parent keyword** in `core.cljs`'s
  `selection-hierarchy` (`derive :discard :zone-pick`, etc.). It is also the value
  set on `:selection/pattern` by the generic zone-pick builder.
- In the new mechanism/domain model, `:zone-pick` is **retired** as a user-facing
  keyword. Its role is replaced by `:pick-from-zone` as the mechanism name.
- `:selection/pattern :zone-pick` was a view-side hint that will be removed when
  `:selection/pattern` is retired (task 5, fizzle-nayb). It is **not** a new
  mechanism — it was an artifact of the old view-dispatch compensation.

---

## Mechanism Alphabet (Confirmed + Extended)

### Confirmed from ADR-030 candidates

| Mechanism | Description |
|---|---|
| `:pick-from-zone` | Select N cards/objects from a zone; move or act on them. Replaces `:zone-pick` hierarchy parent. |
| `:reorder` | Assign cards into ordered positions (top/bottom of library, piles). |
| `:accumulate` | Distribute/increment a single numeric value via stepper controls. |
| `:n-slot-targeting` | Fill N target slots from a valid-targets set (card UUIDs or player-ids). |
| `:allocate-resource` | Assign mana from a pool to typed color-keyed slots simultaneously. Distinguished from `:accumulate` because mana allocation spans multiple color slots, not a single integer. |

**Not carried forward:** `:zone-pick` — retired, subsumed by `:pick-from-zone`.

### New mechanisms (ADR-worthy additions)

**`:pick-mode`** — Choose one named option from a finite list of non-card descriptors.

Justification: Three types (`:spell-mode`, `:land-type-source`, `:land-type-target`)
present the player with a button-list modal of named options. The options are mode
descriptor maps (`{:mode/id :primary …}`) or type keywords (`:forest`, `:island`,
etc.) — not card/object identifiers from a zone. Using `:pick-from-zone` would
require treating "candidate list" as a virtual zone, which misrepresents the data
and would force the zone-pick view to handle non-card items. `:pick-mode` is the
accurate mechanism: pick one from a named option list, never a card-picker UI.

**`:binary-choice`** — Choose one action keyword from a small fixed set.

Justification: Two types (`:unless-pay`, `:replacement-choice`) present the player
with a choice between action keywords — `:pay`/`:decline` or `:proceed`/`:redirect`.
The `:selection/valid-targets` field holds action keywords, not card/object IDs.
The executor interprets the chosen keyword as an intent and routes accordingly
(counter the spell, spend mana, commit the zone change, etc.). Using
`:n-slot-targeting` would conflate "picking a card target" with "picking an action
intent", making the mechanism semantically incorrect. `:binary-choice` is the
minimal extension needed.

Both additions are ADR-worthy per the ADR-030 anti-pattern: "additions beyond the
audited alphabet are ADR-worthy events." This addendum serves as that ADR-worthy
documentation.

---

## Full Mapping Table (33 types)

### :pick-from-zone (14 types)

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:discard` | `:discard` | Hand → graveyard |
| `:graveyard-return` | `:graveyard-return` | Graveyard → hand |
| `:shuffle-from-graveyard-to-library` | `:shuffle-to-library` | GY → library + shuffle; dynamic-built (no literal `:selection/type :` in builders) |
| `:hand-reveal-discard` | `:revealed-hand-discard` | Reveal opponent's hand, pick to discard |
| `:chain-bounce` | `:chain-bounce` | Chain of Vapor: pick land to sacrifice for chain copy |
| `:chain-bounce-target` | `:chain-bounce-target` | Chain of Vapor: pick object to receive bounced copy |
| `:untap-lands` | `:untap-lands` | Cloud of Faeries: pick lands to untap |
| `:discard-specific-cost` | `:discard-cost` | Discard as pre-cast cost (Mox Diamond, etc.) |
| `:return-land-cost` | `:return-land-cost` | Return land as pre-cast cost (Daze) |
| `:sacrifice-permanent-cost` | `:sacrifice-cost` | Sacrifice permanent as cost |
| `:exile-cards-cost` | `:exile-cost` | Exile cards as cost |
| `:tutor` | `:tutor` | Search library, put card to target zone |
| `:peek-and-select` | `:peek-and-select` | Reveal top N, keep some, put rest elsewhere |
| `:pile-choice` | `:pile-choice` | Divide into piles, pick one (Fact or Fiction, etc.) |

### :reorder (4 types)

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:scry` | `:scry` | Put top cards on top or bottom |
| `:peek-and-reorder` | `:peek-and-reorder` | Reveal top N, put in any order (Brainstorm) |
| `:order-bottom` | `:order-bottom` | Order cards going to bottom of library |
| `:order-top` | `:order-top` | Order cards going to top of library |

### :accumulate (3 types)

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:storm-split` | `:storm-split` | Distribute copies across players |
| `:x-mana-cost` | `:x-mana-cost` | Choose X for X-cost spells |
| `:pay-x-life` | `:pay-x-life` | Choose X life to pay |

### :allocate-resource (1 type)

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:mana-allocation` | `:mana-allocation` | Assign mana pool to generic cost slots |

### :n-slot-targeting (6 types)

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:player-target` | `:player-target` | Target a player |
| `:cast-time-targeting` | `:cast-time-targeting` | Spell cast-time target(s) |
| `:ability-cast-targeting` | `:ability-cast-targeting` | Ability activation target(s) at cast time |
| `:ability-targeting` | `:ability-targeting` | Ability activation target(s) on stack |
| `:select-attackers` | `:select-attackers` | Declare attacking creatures |
| `:assign-blockers` | `:assign-blockers` | Assign blocking creatures per attacker |

### :pick-mode (3 types) — NEW mechanism

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:spell-mode` | `:spell-mode` | Choose modal spell mode (Charm, etc.) |
| `:land-type-source` | `:land-type-source` | Choose source land type (Vision Charm) |
| `:land-type-target` | `:land-type-target` | Choose target land type (Vision Charm chain) |

### :binary-choice (2 types) — NEW mechanism

| :selection/type | :selection/domain | Notes |
|---|---|---|
| `:unless-pay` | `:unless-pay` | Pay/decline for soft counters (Mana Leak, Daze) |
| `:replacement-choice` | `:replacement-choice` | Proceed/redirect for replacement effects (Mox Diamond) |

---

## Edge Cases

### :spell-mode and modal dispatch

`:spell-mode` is built by `casting.cljs`'s `build-spell-mode-selection`, not by
any of the 13 `events/selection/` domain files. This is already noted in ADR-030's
architecture section. The `:pick-mode` mechanism correctly captures it because the
executor stores the chosen mode descriptor on the stack object (`:object/chosen-mode`),
then a continuation fires the actual cast. The "spell mode" domain policy is entirely
in the continuation (`apply-continuation :cast-after-spell-mode`), not in
`execute-confirmed-selection`. This is consistent with the mechanism/domain split.

### :binary-choice and valid-targets holding action keywords

`:unless-pay` and `:replacement-choice` both use `:selection/valid-targets` to hold
the option set — but the items are action keywords (`:pay`, `:decline`, `:proceed`,
`:redirect`), not card/object identifiers. Task 4's dispatch switch must not try to
move these "targets" to a zone. The `:binary-choice` mechanism executor will forward
the chosen action keyword to `apply-domain-policy` without any zone-move operation.

### :shuffle-from-graveyard-to-library and dynamic construction

This type is never written as a literal `:selection/type :shuffle-from-graveyard-to-library`
in any builder function. It is produced by the generic zone-pick builder in `core.cljs`
when `zone-pick-config` does not include a `:selection-type` override. The type string
falls through to `(:effect/type effect)` which is `:shuffle-from-graveyard-to-library`.
The compat adapter (task 2) must handle this type despite its absence from the
literal-grep count.

### assign-blockers and multi-step chaining

`:assign-blockers` has a custom `build-chain-selection` that creates the next
blocker assignment step (iterating over remaining attackers). The `:n-slot-targeting`
mechanism is correct — each step fills one slot (the blocker for one attacker). The
chaining behavior is lifecycle-level (`:selection/lifecycle :chaining`) and is
orthogonal to mechanism.

### :mana-allocation vs :accumulate

Both use stepper controls, but `:mana-allocation` allocates across N typed color
slots simultaneously (`:selection/allocation` is a map `{:red N :blue M …}`), while
`:accumulate` types (`:x-mana-cost`, `:pay-x-life`, `:storm-split`) maintain a
single numeric value. The view components are distinct (accumulator stepper vs mana
allocation widget). `:allocate-resource` is the correct distinct mechanism.

---

## Consequences

- The mechanism alphabet is now **7 keywords** (5 confirmed + 2 new) rather than
  the 5-6 originally anticipated. This is still "small"; future additions require
  an ADR-worthy justification.
- Task 2 (compat adapter) can set `:selection/mechanism` and `:selection/domain`
  on every pending selection at the `set-pending-selection` chokepoint using this
  map as a lookup table.
- Task 4 (dispatch switch) changes `execute-confirmed-selection` dispatch from
  `:selection/type` to `:selection/mechanism`. The two new mechanism defmethods
  (`:pick-mode`, `:binary-choice`) must be written at that point.
- The view-layer dispatch collapse (task 5, fizzle-nayb) will use `:selection/mechanism`
  as the primary dispatch key. `:pick-mode` and `:binary-choice` need view-layer
  defmethods in `views/modals.cljs` at that point.
