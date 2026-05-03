# Fizzle Card Implementation Patterns Reference

Quick lookup guide for implementing cards similar to Turnabout, Frantic Search, and Cloud of Faeries.

---

## PATTERN 1: Modal Spells (Turnabout Template)

**When to use**: Spell with 2+ modes, each with different targeting and effects.

**Example**: Blue Elemental Blast (counter red spell OR destroy red permanent)

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/blue_elemental_blast.cljs`

**Template**:

```clojure
(ns fizzle.cards.blue.your-modal-card)

(def card
  {:card/id :your-modal-card
   :card/name "Your Modal Card"
   :card/cmc 2
   :card/mana-cost {:blue 1 :generic 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one —\n• Mode A\n• Mode B"

   :card/modes
   [{:mode/label "Label for mode 1"
     :mode/targeting [{:target/id :target-a
                       :target/type :object
                       :target/zone :battlefield
                       :target/controller :any
                       :target/criteria {:match/types #{:artifact}}
                       :target/required true}]
     :mode/effects [{:effect/type :destroy
                     :effect/target-ref :target-a}]}

    {:mode/label "Label for mode 2"
     :mode/targeting [{:target/id :target-b
                       :target/type :player
                       :target/options [:self :opponent :any-player]
                       :target/required true}]
     :mode/effects [{:effect/type :draw
                     :effect/amount 1
                     :effect/target :any-player}]}]})
```

**Key points**:
- Each mode in `:card/modes` array
- Each mode has independent `:mode/targeting` and `:mode/effects`
- `:target/id` is unique per targeting requirement (used in effects via `:effect/target-ref`)
- Targeting happens at cast time (player chooses mode, then chooses targets for that mode)
- Spell uses single `:card/effects` OR `:card/modes` (not both)

---

## PATTERN 2: Interactive Effects - Draw + Discard Sequence

**When to use**: Spell with 2+ effects where some need player interaction (draw → discard).

**Example**: Frantic Search (draw 2, then discard 2, then untap lands)

**Key card**: Opt (scry, then draw) and Careful Study (draw, then discard)

**File locations**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/opt.cljs`
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/careful_study.cljs`

**Template**:

```clojure
(ns fizzle.cards.blue.frantic-search)

(def card
  {:card/id :frantic-search
   :card/name "Frantic Search"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards. Then untap up to three lands."

   ;; Effects execute in order. When interactive effect is reached, resolution pauses.
   ;; After player confirms, remaining effects resume.
   :card/effects
   [{:effect/type :draw
     :effect/amount 2}

    {:effect/type :discard           ; Pauses here for player selection
     :effect/count 2                 ; How many
     :effect/selection :player}      ; Player chooses which

    {:effect/type :untap             ; Resumes after discard confirmed
     :effect/target :self            ; Untap your permanents
     :effect/count 3
     :effect/criteria {:match/types #{:land}}}]})
```

**Key points**:
- Effects in `:card/effects` array execute **in order**
- Interactive effect (`:discard`, `:scry`, `:tutor`, etc.) pauses resolution
- UI prompts for selection
- After confirmation, remaining effects resume
- Multiple interactive effects chain (each pauses in turn)
- `:effect/count` is used by discard/tutor effects; `:effect/amount` by draw/mill

**Interactive effect types**:
- `:draw` — interactive only if `:effect/target :any-player`
- `:discard` — needs `:effect/selection :player` + `:effect/count`
- `:scry` — automatic pause for reordering
- `:tutor` — automatic pause for library search
- `:return-from-graveyard` — automatic pause if not `:selection :random`

---

## PATTERN 3: Creatures with Static Abilities

**When to use**: Creature with passive stat modifiers or keyword abilities.

**Example**: Nimble Mongoose (1/1, grows to 3/3 with threshold)

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/nimble_mongoose.cljs`

**Template**:

```clojure
(ns fizzle.cards.green.your-creature)

(def card
  {:card/id :your-creature
   :card/name "Your Creature"
   :card/cmc 1
   :card/mana-cost {:green 1}
   :card/colors #{:green}
   :card/types #{:creature}
   :card/subtypes #{:wizard}          ; Creature types (optional)
   :card/power 2                       ; Base power (at card level!)
   :card/toughness 3                   ; Base toughness (at card level!)
   :card/keywords #{:flying :haste}    ; Keyword abilities (for display)
   :card/text "Flying, haste\nThreshold — Creature gets +1/+1"

   ;; Static abilities for conditional stat modifiers
   :card/static-abilities
   [{:static/type :pt-modifier
     :modifier/power 1
     :modifier/toughness 1
     :modifier/condition {:condition/type :threshold}
     :modifier/applies-to :self}]})
```

**Key points**:
- `:card/power` and `:card/toughness` at **card definition level** (not object level)
- `:card/subtypes` for creature type(s) (`:elf`, `:wizard`, `:goblin`, etc.)
- `:card/keywords` for display; actual mechanics in `:card/static-abilities` and triggers
- `:static/type :pt-modifier` for conditional stat changes
- `:modifier/condition` uses condition types (`:threshold`, etc.)

**Current limitations**:
- No combat system (creatures exist but don't attack/block/die)
- No creature-death SBA
- No damage tracking on creatures
- `:card/keywords` is for display only; actual mechanics elsewhere

---

## PATTERN 4: Enter-the-Battlefield (ETB) Effects

**When to use**: Permanent that does something when it enters (e.g., add counters, draw, untap).

**Example**: Gemstone Mine (enters with 3 mining counters)

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/lands/gemstone_mine.cljs`

**Template**:

```clojure
(ns fizzle.cards.lands.your-etb-land)

(def card
  {:card/id :your-etb-land
   :card/name "Your ETB Land"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "Your ETB Land enters with X counters. ..."

   ;; ETB effect: fires when permanent enters the battlefield
   :card/etb-effects
   [{:effect/type :add-counters
     :effect/counters {:charge 3}
     :effect/target :self}]})
```

**Key points**:
- `:card/etb-effects` array (separate from `:card/effects`)
- Effects execute automatically when permanent enters battlefield
- `:effect/target :self` refers to the permanent entering
- Can chain multiple ETB effects
- Currently works for all permanent types (lands, creatures, etc.)

**Creatures with ETB**:

```clojure
(def card
  {:card/id :cloud-of-faeries
   :card/name "Cloud of Faeries"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:creature}
   :card/subtypes #{:faerie}
   :card/power 0
   :card/toughness 2
   :card/keywords #{:flying}
   :card/text "Flying\nWhen Cloud of Faeries enters, untap up to two target lands."

   :card/etb-effects
   [{:effect/type :untap
     :effect/target :any-player          ; Can target any player's lands
     :effect/count 2
     :effect/criteria {:match/types #{:land}}}]})
```

---

## PATTERN 5: Activated Abilities

**When to use**: Permanent with tap or cost-based abilities (e.g., mana abilities, utility effects).

**Example**: Goblin Welder (tap: swap artifact from battlefield with artifact from graveyard)

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/red/goblin_welder.cljs`

**Simple mana ability template**:

```clojure
:card/abilities
[{:ability/type :mana              ; Type: :mana, :activated, etc.
  :ability/cost {:tap true}         ; Tap to activate
  :ability/produces {:blue 1}}]     ; Produces 1 blue mana
```

**Complex ability with targeting**:

```clojure
:card/abilities
[{:ability/type :activated
  :ability/cost {:tap true}
  :ability/description "Swap artifact from battlefield with artifact in graveyard"

  ;; Multi-target: must order targeting correctly
  :ability/targeting
  [{:target/id :welder-bf
    :target/type :object
    :target/zone :battlefield
    :target/controller :any
    :target/criteria {:match/types #{:artifact}}
    :target/required true}

   {:target/id :welder-gy
    :target/type :object
    :target/zone :graveyard
    :target/controller :any
    :target/criteria {:match/types #{:artifact}}
    :target/same-controller-as :welder-bf}]  ; Second target must match first's controller

  ;; Effects using target references
  :ability/effects
  [{:effect/type :welder-swap
    :effect/target-ref :welder-bf
    :effect/graveyard-ref :welder-gy}]}]
```

**Key points**:
- `:ability/type` — `:mana` (produces mana), `:activated` (general ability)
- `:ability/cost` — `:tap`, `:remove-counter`, `:sacrifice-self`, `:pay-life`, `:mana`, `:discard-hand`
- `:ability/targeting` — array of targeting requirements (optional)
- `:target/same-controller-as` — constrains second target to match first target's controller
- `:ability/effects` or `:ability/produces` — what the ability does

---

## PATTERN 6: Flashback & Alternate Costs

**When to use**: Spell castable from graveyard with alternate cost.

**Example**: Deep Analysis (cast from hand for 3U, or from graveyard for 1U + 3 life)

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/deep_analysis.cljs`

**Template**:

```clojure
(def card
  {:card/id :your-flashback-spell
   :card/name "Your Flashback Spell"
   :card/cmc 4
   :card/mana-cost {:colorless 3 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "... Flashback—{1}{U}, pay 3 life."

   :card/effects [{:effect/type :draw :effect/amount 2}]

   ;; Alternate costs array
   :card/alternate-costs
   [{:alternate/id :flashback
     :alternate/zone :graveyard         ; Can cast from graveyard
     :alternate/mana-cost {:colorless 1 :blue 1}
     :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
     :alternate/on-resolve :exile}]})   ; Goes to exile after casting (not graveyard)
```

**Key points**:
- `:card/alternate-costs` array (one per alternate cost)
- `:alternate/zone` — which zone it can be cast from (`:graveyard`, `:exile`, etc.)
- `:alternate/mana-cost` — mana cost for alternate
- `:alternate/additional-costs` — extra costs (`:pay-life`, `:discard-hand`, `:exile-library-top`, etc.)
- `:alternate/on-resolve` — where spell goes after resolving (`:exile` for flashback)
- Main `:card/effects` apply to both normal and alternate casts

---

## PATTERN 7: Triggered Abilities

**When to use**: Permanent with event-driven abilities (e.g., "whenever X happens").

**Example**: City of Brass (whenever this becomes tapped, deal 1 damage to you)

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/lands/city_of_brass.cljs`

**Template**:

```clojure
(def card
  {:card/id :city-of-brass
   :card/name "City of Brass"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "... Whenever City of Brass becomes tapped, it deals 1 damage to you. ..."

   ;; Triggered abilities (event-driven)
   :card/triggers
   [{:trigger/type :becomes-tapped      ; Trigger event type
     :trigger/description "Whenever this becomes tapped"}]

   ;; When trigger fires, these effects execute
   ;; NOTE: Effects must be set separately (dynamic registration)
   ;; This is a limitation of current implementation
   })
```

**Available trigger types** (from schema.cljs):
- `:becomes-tapped` — When permanent becomes tapped
- `:land-entered` — When land enters (deprecated; use ETB effects instead)

**Current limitations**:
- Triggered ability effects not stored on card (must be registered in turn_based.cljs)
- No comprehensive trigger system yet for arbitrary events
- Combat triggers not implemented (epic fizzle-u07p)

---

## EFFECT TYPE QUICK REFERENCE

### Zone Operations
| Effect | Args | Notes |
|--------|------|-------|
| `:draw` | `amount`, `target` | Interactive if target is `:any-player` |
| `:mill` | `amount`, `target` | Mill cards to graveyard |
| `:discard` | `count`, `selection` | Interactive; `:selection :player` |
| `:discard-hand` | `target` | Discard entire hand |
| `:discard-from-revealed-hand` | `target`, `criteria` | Opponent reveals, you choose |
| `:return-from-graveyard` | `target`, `count`, `selection` | Interactive if player-choice |
| `:tutor` | (varies) | Search library; interactive |
| `:scry` | `amount` | Look and reorder top cards; interactive |
| `:peek-and-select` | (varies) | Peek at cards, choose some |
| `:sacrifice` | `target` | Sacrifice permanent |
| `:destroy` | `target` | Destroy permanent |
| `:bounce` | `target` | Return to hand |
| `:exile-self` | (none) | Exile source |
| `:exile-zone` | `target`, `zone` | Exile entire zone |

### Life & Damage
| Effect | Args | Notes |
|--------|------|-------|
| `:draw` | `amount`, `target` | Draw cards; interactive if any-player |
| `:lose-life` | `amount`, `target` | Player loses life |
| `:gain-life` | `amount`, `target` | Player gains life |
| `:deal-damage` | `amount`, `target` | Damage to **player only** (not creatures) |
| `:gain-life-equal-to-cmc` | `target` | Gain life = target's CMC |

### Mana & Counters
| Effect | Args | Notes |
|--------|------|-------|
| `:add-mana` | `mana` | Add mana to pool |
| `:add-counters` | `counters`, `target` | Add counter type and amount |
| `:add-restriction` | (varies) | Restrict player actions |
| `:grant-flashback` | (varies) | Grant flashback ability |
| `:grant-delayed-draw` | (varies) | Grant draw later |
| `:grant-mana-ability` | (varies) | Grant mana ability |
| `:apply-pt-modifier` | `power`, `toughness` | Modify creature stats |

### Stack & Abilities
| Effect | Args | Notes |
|--------|------|-------|
| `:counter-spell` | `target` | Counter target spell |
| `:counter-ability` | `target` | Counter target ability |
| `:chain-bounce` | (varies) | Chain of Vapor's chain mechanic |

### MISSING (Not Yet Implemented)
| Effect | Use Case |
|--------|----------|
| `:untap` | Untap permanents (used by Turnabout, Frantic Search, Cloud of Faeries) |
| `:tap` | Tap permanents (not yet available) |

---

## TARGETING REFERENCE

### Target Type: Object

```clojure
{:target/id :target-spell
 :target/type :object
 :target/zone :battlefield            ; :battlefield, :graveyard, :stack, :hand, :exile, :library
 :target/controller :any              ; :any, :controller
 :target/criteria {:match/types #{:artifact :creature}}
 :target/required true}
```

**Criteria keys**:
- `:match/types` — Filter by type set
- `:match/not-types` — Exclude types
- `:match/colors` — Filter by color set

### Target Type: Player

```clojure
{:target/id :target-player
 :target/type :player
 :target/options [:self :opponent :any-player]}
```

### Multi-Target Constraints

```clojure
{:target/id :second-target
 :target/zone :graveyard
 :target/controller :any
 :target/same-controller-as :first-target}  ; Must match first target's controller
```

---

## CONDITION TYPES

Used in `:effect/condition` for conditional effects:

| Condition | File | Usage |
|-----------|------|-------|
| `:threshold` | conditions.cljs | 7+ cards in graveyard |
| `:no-counters` | conditions.cljs | Permanent has no counters of type |
| `:condition/type :exact-mana-value` | (Not shown) | CMC matching |

---

## REQUIRED FIELDS CHECKLIST

### All Cards
- [ ] `:card/id` — Unique keyword
- [ ] `:card/name` — Display name
- [ ] `:card/cmc` — Converted mana cost
- [ ] `:card/mana-cost` — Mana cost map (e.g., `{:blue 1}`)
- [ ] `:card/colors` — Color set
- [ ] `:card/types` — Type set (keywords like `:instant`, `:creature`, `:land`)
- [ ] `:card/text` — Display text

### Spells (Instants, Sorceries)
- [ ] `:card/effects` — OR `:card/modes` (not both)

### Creatures
- [ ] `:card/power` — Integer
- [ ] `:card/toughness` — Integer
- [ ] `:card/subtypes` — Creature types (optional)
- [ ] `:card/keywords` — Keyword abilities (for display, optional)

### Permanents with ETB
- [ ] `:card/etb-effects` — Array of effects

### Permanents with Abilities
- [ ] `:card/abilities` — Array of ability maps

### Modal Spells
- [ ] `:card/modes` — Array of mode maps (not `:card/effects`)

### Flashback/Alternate Costs
- [ ] `:card/alternate-costs` — Array of alternate cost maps

---

## TESTING PATTERN

Example test from Careful Study:

```clojure
(deftest test-careful-study-card-definition
  (testing "Card definition fields"
    (is (= :careful-study (:card/id card)))
    (is (= "Careful Study" (:card/name card)))
    (is (= 1 (:card/cmc card)))
    (is (= #{:blue} (:card/colors card)))))

(deftest careful-study-cannot-cast-without-mana-test
  (testing "Cannot cast without blue mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :careful-study :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))

(deftest test-careful-study-draws-two-cards
  (testing "Casting draws 2 cards"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 2 (count (q/get-hand db-cast :player-1)))))))
```

**Mandatory categories**:
- A: Card definition (all fields)
- B: Cast-resolve happy path
- C: Cannot-cast guards (no mana, wrong zone)
- D: Storm count (if applicable)
- E+: Domain-specific (targeting, selection, triggers, etc.)

---

## COMMON GOTCHAS

1. **Discard selection**: Use `:effect/count` (not `:effect/amount`)
2. **Draw selection**: Only interactive if `:effect/target :any-player`
3. **Creature stats**: `:card/power` and `:card/toughness` (not `:object/power`)
4. **ETB**: Spell effects are `:card/effects`; permanents use `:card/etb-effects`
5. **Modal**: Use `:card/modes` (not `:card/effects`)
6. **Modes with targeting**: Each mode has separate `:mode/targeting` array
7. **Target refs**: Use `:effect/target-ref` (resolves at resolution time)
8. **Static abilities**: PT modifiers use `:static/type :pt-modifier`
9. **Multi-target ordering**: Second target uses `:target/same-controller-as` if needed
10. **Flashback**: Effects apply to both casts; `:alternate/on-resolve` controls final zone

---

## NEXT STEPS

To implement Turnabout, Frantic Search, Cloud of Faeries:

1. **Add `:untap` effect** (30 min):
   - Edit `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs`
   - Add `defmethod execute-effect-impl :untap` that sets `:object/tapped false`
   - Add tests in `/Users/abugosh/g/fizzle/src/test/fizzle/engine/effects/zones_test.cljs`

2. **Create card files** (15 min each):
   - `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/turnabout.cljs` — 4 modes with untap effects
   - `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/frantic_search.cljs` — Draw + discard + untap
   - `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/cloud_of_faeries.cljs` — Creature with ETB untap

3. **Add to registry** (5 min):
   - Edit `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs`
   - Add `:require` for new card namespaces

4. **Add tests** (10 min each):
   - Create test files in `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/`
   - Follow mandatory test categories (A-D + E for selection)

**Total estimated time**: ~2.5 hours
