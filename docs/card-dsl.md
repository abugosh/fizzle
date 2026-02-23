# Fizzle Card DSL Reference

> **Last updated:** 2026-02-01
>
> **Maintainer note:** Update this document when adding new effect, ability, cost, trigger, or condition types.

Cards in Fizzle are pure EDN data. The engine interprets this data to execute game actions. This document is the comprehensive reference for all card definition patterns.

**Comprehensive Rules Reference:** [Magic: The Gathering Comprehensive Rules](https://media.wizards.com/2026/downloads/MagicCompRules%2020260116.txt)

---

## Table of Contents

- [Base Card Attributes](#base-card-attributes)
- [Effect Types](#effect-types)
- [Ability Types](#ability-types)
- [Cost Types](#cost-types)
- [Trigger Types](#trigger-types)
- [Condition Types](#condition-types)
- [Mana Cost Conversion](#mana-cost-conversion)
- [Common Gotchas](#common-gotchas)

---

## Base Card Attributes

Every card definition is a map with these attributes:

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `:card/id` | keyword | yes | Unique identifier for the card | `:dark-ritual` |
| `:card/name` | string | yes | Display name | `"Dark Ritual"` |
| `:card/cmc` | integer | yes | Converted mana cost | `1` |
| `:card/mana-cost` | map | yes | Mana cost breakdown (empty `{}` for lands) | `{:black 1}` |
| `:card/colors` | set | yes | Color identity (empty `#{}` for colorless) | `#{:black}` |
| `:card/types` | set | yes | Card types | `#{:instant}` |
| `:card/subtypes` | set | no | Card subtypes | `#{:island :swamp}` |
| `:card/supertypes` | set | no | Supertypes like Basic, Legendary | `#{:basic}` |
| `:card/text` | string | yes | Oracle text for display | `"Add {B}{B}{B}."` |
| `:card/keywords` | set | no | Keyword abilities | `#{:storm}` |
| `:card/effects` | vector | no | Effects when spell resolves | See [Effect Types](#effect-types) |
| `:card/conditional-effects` | map | no | Conditional effect overrides | `{:threshold [...]}` |
| `:card/etb-effects` | vector | no | Effects when permanent enters battlefield | See [Effect Types](#effect-types) |
| `:card/abilities` | vector | no | Activated abilities on permanents | See [Ability Types](#ability-types) |
| `:card/triggers` | vector | no | Triggered abilities | See [Trigger Types](#trigger-types) |

### Example: Dark Ritual (Instant)

```clojure
{:card/id :dark-ritual
 :card/name "Dark Ritual"
 :card/cmc 1
 :card/mana-cost {:black 1}
 :card/colors #{:black}
 :card/types #{:instant}
 :card/text "Add {B}{B}{B}."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}}]}
```

### Example: Island (Basic Land)

```clojure
{:card/id :island
 :card/name "Island"
 :card/cmc 0
 :card/mana-cost {}
 :card/colors #{}
 :card/types #{:land}
 :card/subtypes #{:island}
 :card/supertypes #{:basic}
 :card/text "{T}: Add {U}."
 :card/abilities [{:ability/type :mana
                   :ability/cost {:tap true}
                   :ability/produces {:blue 1}}]}
```

---

## Effect Types

Effects are executed when spells resolve or abilities trigger. Each effect has an `:effect/type` that determines how it's processed.

### :add-mana

Add mana to a player's mana pool.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:add-mana` |
| `:effect/mana` | map | yes | Mana to add `{:black 3}` or `{:any 1}` |

**Example:** Dark Ritual
```clojure
{:effect/type :add-mana
 :effect/mana {:black 3}}
```

**Used by:** Dark Ritual, Cabal Ritual, Lion's Eye Diamond, City of Brass

---

### :mill

Move cards from library to graveyard.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:mill` |
| `:effect/amount` | integer | yes | Number of cards to mill |
| `:effect/target` | keyword/UUID | no | `:opponent` or player-id (defaults to caster) |

**Example:** Brain Freeze
```clojure
{:effect/type :mill
 :effect/amount 3
 :effect/target :opponent}
```

**Example:** Mental Note (self-mill)
```clojure
{:effect/type :mill
 :effect/amount 2}
```

**Used by:** Brain Freeze, Mental Note

---

### :draw

Draw cards from library to hand.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:draw` |
| `:effect/amount` | integer | yes | Number of cards to draw |
| `:effect/target` | keyword/UUID | no | Target player (defaults to caster) |

**Example:** Mental Note
```clojure
{:effect/type :draw
 :effect/amount 1}
```

**Example:** Careful Study
```clojure
{:effect/type :draw
 :effect/amount 2}
```

**Used by:** Mental Note, Careful Study

**Note:** Drawing from an empty library sets `:game/loss-condition` to `:empty-library`.

---

### :discard

Discard cards from hand to graveyard.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:discard` |
| `:effect/count` | integer | yes | Number of cards to discard |
| `:effect/selection` | keyword | yes | `:player` (player chooses) or `:random` |

**Example:** Careful Study
```clojure
{:effect/type :discard
 :effect/count 2
 :effect/selection :player}
```

**Used by:** Careful Study

**Note:** When `:selection` is `:player`, the effect returns db unchanged and the UI handles selection via re-frame events.

---

### :tutor

Search library for a card matching criteria.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:tutor` |
| `:effect/criteria` | map | yes | Card attributes to match |
| `:effect/target-zone` | keyword | yes | Zone to put found card (`:hand` or `:battlefield`) |
| `:effect/shuffle?` | boolean | no | Shuffle library after (default `true`) |

**Example:** Merchant Scroll (to hand)
```clojure
{:effect/type :tutor
 :effect/criteria {:card/types #{:instant}
                   :card/colors #{:blue}}
 :effect/target-zone :hand
 :effect/shuffle? true}
```

**Example:** Polluted Delta (to battlefield)
```clojure
{:effect/type :tutor
 :effect/criteria {:card/subtypes #{:island :swamp}}
 :effect/target-zone :battlefield
 :effect/shuffle? true}
```

**Used by:** Merchant Scroll, Polluted Delta

**Note:** Tutors always require player selection (fail-to-find option is mandatory per MTG rules for hidden information).

---

### :sacrifice

Move a permanent to graveyard.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:sacrifice` |
| `:effect/target` | keyword/UUID | yes | `:self` or object-id |
| `:effect/condition` | map | no | Condition that must be met (see [Conditions](#condition-types)) |

**Example:** City of Traitors trigger
```clojure
{:effect/type :sacrifice
 :effect/target :self}
```

**Example:** Gemstone Mine (conditional)
```clojure
{:effect/type :sacrifice
 :effect/target :self
 :effect/condition {:condition/type :no-counters
                    :condition/counter-type :mining}}
```

**Used by:** City of Traitors, Gemstone Mine

**Note:** `:self` must be resolved to the actual object-id by the caller before effect execution.

---

### :bounce

Return target permanent to its owner's hand.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:bounce` |
| `:effect/target` | keyword/UUID | yes | Object-id (pre-resolved by caller) |

**Example:** Chain of Vapor
```clojure
{:effect/type :bounce
 :effect/target-ref :target-permanent}
```

**Used by:** Chain of Vapor

**Note:** Target is pre-resolved via `:effect/target-ref` and cast-time targeting. Returns the permanent to the owner's hand zone.

---

### :add-counters

Add counters to a permanent.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:add-counters` |
| `:effect/target` | keyword/UUID | yes | `:self` or object-id |
| `:effect/counters` | map | yes | Counter types and amounts `{:mining 3}` |

**Example:** Gemstone Mine ETB
```clojure
{:effect/type :add-counters
 :effect/counters {:mining 3}
 :effect/target :self}
```

**Used by:** Gemstone Mine

---

### :deal-damage

Deal damage to a player.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:deal-damage` |
| `:effect/amount` | integer | yes | Damage amount |
| `:effect/target` | keyword/UUID | yes | `:controller` or player-id |
| `:effect/source` | UUID | no | Object dealing damage (for future prevention effects) |

**Example:** City of Brass trigger
```clojure
{:effect/type :deal-damage
 :effect/amount 1
 :effect/target :controller}
```

**Used by:** City of Brass

**Note:** Currently behaves identically to `:lose-life`. Kept separate for future damage prevention implementation.

---

### :lose-life

Reduce a player's life total.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:lose-life` |
| `:effect/amount` | integer | yes | Life to lose |
| `:effect/target` | keyword/UUID | no | Target player (defaults to caster) |

**Example:**
```clojure
{:effect/type :lose-life
 :effect/amount 2
 :effect/target :opponent}
```

**Note:** Life loss cannot be prevented (unlike damage). Life can go negative.

---

### :gain-life

Increase a player's life total.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:effect/type` | keyword | yes | `:gain-life` |
| `:effect/amount` | integer | yes | Life to gain |
| `:effect/target` | keyword/UUID | no | Target player (defaults to caster) |

**Example:**
```clojure
{:effect/type :gain-life
 :effect/amount 3}
```

**No current usage** in Iggy Pop deck.

---

## Ability Types

Abilities are actions players can take with permanents on the battlefield.

### Mana Abilities (`:mana`)

Mana abilities **do not use the stack** and resolve immediately. They cannot be responded to.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:ability/type` | keyword | yes | `:mana` |
| `:ability/cost` | map | yes | Costs to activate (see [Cost Types](#cost-types)) |
| `:ability/produces` | map | conditional | Mana produced `{:black 1}` or `{:any 1}` |
| `:ability/effects` | vector | conditional | Additional effects to execute |

**Use `:ability/produces`** when the ability simply adds mana. Use `:ability/effects` for complex mana abilities that have additional effects.

**Example:** Basic land (simple mana production)
```clojure
{:ability/type :mana
 :ability/cost {:tap true}
 :ability/produces {:blue 1}}
```

**Example:** Lion's Eye Diamond (complex mana ability with effects)
```clojure
{:ability/type :mana
 :ability/cost {:tap true
                :sacrifice-self true
                :discard-hand true}
 :ability/effects [{:effect/type :add-mana
                    :effect/mana {:any 3}}]}
```

**Example:** Gemstone Mine (mana ability with conditional sacrifice)
```clojure
{:ability/type :mana
 :ability/cost {:tap true
                :remove-counter {:mining 1}}
 :ability/produces {:any 1}
 :ability/effects [{:effect/type :sacrifice
                    :effect/target :self
                    :effect/condition {:condition/type :no-counters
                                       :condition/counter-type :mining}}]}
```

**Used by:** Island, Swamp, Lotus Petal, Lion's Eye Diamond, Gemstone Mine, City of Brass, City of Traitors

---

### Activated Abilities (`:activated`)

Activated abilities **use the stack** and can be responded to.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:ability/type` | keyword | yes | `:activated` |
| `:ability/cost` | map | yes | Costs to activate (see [Cost Types](#cost-types)) |
| `:ability/description` | string | yes | UI display text |
| `:ability/effects` | vector | yes | Effects when ability resolves |

**Example:** Polluted Delta
```clojure
{:ability/type :activated
 :ability/cost {:tap true
                :sacrifice-self true
                :pay-life 1}
 :ability/description "Search for Island or Swamp"
 :ability/effects [{:effect/type :tutor
                    :effect/criteria {:card/subtypes #{:island :swamp}}
                    :effect/target-zone :battlefield
                    :effect/shuffle? true}]}
```

**Used by:** Polluted Delta

---

## Cost Types

Costs are paid when activating abilities. Multiple costs can be combined in a single `:ability/cost` map.

### :tap

Tap the permanent.

```clojure
{:tap true}
```

**Requirement:** Permanent must be untapped.

---

### :sacrifice-self

Sacrifice this permanent (move to graveyard).

```clojure
{:sacrifice-self true}
```

**Requirement:** Permanent must be on the battlefield.

---

### :remove-counter

Remove counters from this permanent.

```clojure
{:remove-counter {:mining 1}}
```

**Requirement:** Permanent must have sufficient counters of the specified type.

---

### :discard-hand

Discard your entire hand.

```clojure
{:discard-hand true}
```

**Requirement:** Permanent must be on the battlefield (can discard empty hand).

---

### :pay-life

Pay life.

```clojure
{:pay-life 1}
```

**Requirement:** Controller must have life >= required amount. Can pay even if it would reduce to 0.

---

### Combined Costs Example

Polluted Delta (tap + sacrifice + pay life):
```clojure
{:ability/cost {:tap true
                :sacrifice-self true
                :pay-life 1}}
```

Lion's Eye Diamond (tap + sacrifice + discard):
```clojure
{:ability/cost {:tap true
                :sacrifice-self true
                :discard-hand true}}
```

---

## Trigger Types

Triggers are abilities that happen automatically when certain game events occur. They go on the stack and can be responded to.

### Card-Level Triggers (`:card/triggers`)

Defined on cards and fire based on game events.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:trigger/type` | keyword | yes | Event type that triggers this |
| `:trigger/description` | string | yes | UI display text |
| `:trigger/effects` | vector | yes | Effects when trigger resolves |
| `:trigger/filter` | map | no | Filter conditions |

#### :becomes-tapped (resolves as :permanent-tapped)

Fires when this permanent becomes tapped.

**Example:** City of Brass
```clojure
{:trigger/type :becomes-tapped
 :trigger/description "deals 1 damage to you"
 :trigger/effects [{:effect/type :deal-damage
                    :effect/amount 1
                    :effect/target :controller}]}
```

---

#### :land-entered

Fires when a land enters the battlefield under your control.

| Filter Field | Type | Description |
|--------------|------|-------------|
| `:exclude-self` | boolean | Don't trigger when this permanent enters |

**Example:** City of Traitors
```clojure
{:trigger/type :land-entered
 :trigger/description "sacrifice City of Traitors"
 :trigger/filter {:exclude-self true}
 :trigger/effects [{:effect/type :sacrifice
                    :effect/target :self}]}
```

---

### Engine Trigger Types

These are internal trigger types created by the engine. You typically don't define these on cards.

| Trigger Type | Description |
|--------------|-------------|
| `:permanent-tapped` | Resolved form of :becomes-tapped |
| `:activated-ability` | Activated ability on the stack |
| `:storm` | Storm copies trigger |
| `:draw-step` | Turn-based draw step action |
| `:untap-step` | Turn-based untap step action |

---

## Condition Types

Conditions gate effect execution. If the condition is not met, the effect is skipped.

### :no-counters

Check if target has no counters of the specified type.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:condition/type` | keyword | yes | `:no-counters` |
| `:condition/counter-type` | keyword | yes | Counter type to check |

**Example:** Gemstone Mine sacrifice condition
```clojure
{:effect/condition {:condition/type :no-counters
                    :condition/counter-type :mining}}
```

---

## Mana Cost Conversion

Converting Scryfall mana symbols to Fizzle format:

| Scryfall | Fizzle | Description |
|----------|--------|-------------|
| `{W}` | `{:white 1}` | White mana |
| `{U}` | `{:blue 1}` | Blue mana |
| `{B}` | `{:black 1}` | Black mana |
| `{R}` | `{:red 1}` | Red mana |
| `{G}` | `{:green 1}` | Green mana |
| `{C}` | `{:colorless 1}` | Colorless mana (Eldrazi, etc.) |
| `{1}`, `{2}`, etc. | `{:colorless N}` | Generic mana in mana costs |
| `{X}` | Not yet implemented | Variable costs |

**Example:** `{1}{U}` becomes `{:colorless 1 :blue 1}`

**Example:** `{B}{B}{B}` becomes `{:black 3}`

### Mana Production

For mana abilities that produce "one mana of any color":

```clojure
{:ability/produces {:any 1}}
```

---

## Common Gotchas

1. **Mana cost is a map, not a string**
   - Correct: `{:colorless 1 :blue 1}`
   - Wrong: `"{1}{U}"` or `"1U"`

2. **Colors and types are sets**
   - Correct: `#{:black}` for colors, `#{:instant}` for types
   - Wrong: `:black` or `[:instant]`

3. **CMC is an integer, not calculated**
   - You must manually compute and provide `:card/cmc`
   - `{:colorless 1 :blue 1}` has CMC 2

4. **Empty collections for lands**
   - Lands have `{:card/mana-cost {}, :card/colors #{}}`

5. **`:self` targets must be resolved**
   - The engine doesn't resolve `:self` automatically
   - Caller must replace `:self` with actual object-id before effect execution

6. **Mana abilities vs activated abilities**
   - Mana abilities (`:mana`) resolve immediately, no stack
   - Activated abilities (`:activated`) use the stack, can be responded to

7. **`:ability/produces` vs `:ability/effects` for mana**
   - Use `:produces` for simple "add mana" abilities
   - Use `:effects` when the mana ability has additional effects (sacrifice, etc.)

8. **Tutor effects require selection**
   - Never auto-select for tutors
   - Player must always have fail-to-find option

9. **Conditional effects use `:card/conditional-effects`**
   - Keyed by condition type (e.g., `:threshold`)
   - Overrides `:card/effects` when condition met

10. **Counter types are keywords**
    - `:mining`, not `"mining"` or `mining`

11. **Trigger filters are maps**
    - `{:exclude-self true}`, not just `true`

12. **Effect targets: keyword vs UUID**
    - `:opponent`, `:controller`, `:self` are keywords (resolved by engine)
    - Specific objects use UUID
