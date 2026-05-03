# Card Implementation Investigation: Turnabout, Frantic Search, Cloud of Faeries

**Investigation Date**: 2026-03-10
**Scope**: Card implementation patterns for advanced spell types (modal, draw+discard chaining, creature cards, ETB effects, untap mechanics)

---

## EXECUTIVE SUMMARY

The Fizzle codebase has **mature patterns for modal spells, draw+discard effects, and creatures**, but **lacks untap effects** and **creature combat is not implemented**. Here's what you can implement today and what requires additional infrastructure:

| Feature | Status | Notes |
|---------|--------|-------|
| **Modal spells** | ✅ Ready | Blue Elemental Blast pattern exists |
| **Draw+discard chains** | ✅ Ready | Careful Study/Opt/Deep Analysis patterns work |
| **Creatures** | ✅ Partial | Nimble Mongoose & Goblin Welder exist; combat not implemented |
| **ETB triggers** | ✅ Ready | Gemstone Mine pattern via `:card/etb-effects` |
| **Untap effects** | ❌ Missing | No `:effect/type :untap` or `:untap-permanents` implemented |
| **Creature combat** | ❌ Missing | Epic fizzle-u07p not started; schema exists but no rules logic |

---

## 1. CARD DEFINITION STRUCTURE

### Core Pattern: What All Cards Define

```clojure
{:card/id             :keyword-unique-id      ; e.g., :careful-study
 :card/name           "Card Name"
 :card/cmc            2                       ; Converted mana cost
 :card/mana-cost      {:blue 1 :white 2}      ; Nil keys omitted
 :card/colors         #{:blue :white}
 :card/types          #{:instant :sorcery}    ; Type keywords
 :card/subtypes       #{:elf :wizard}         ; Optional
 :card/text           "Oracle text..."        ; Display only

 ; Effects & abilities (optional based on card type)
 :card/effects        [...]                   ; Main effects (spells, lands)
 :card/abilities      [...]                   ; Activated abilities
 :card/etb-effects    [...]                   ; Enter-battlefield triggers
 :card/triggers       [...]                   ; Event-driven triggers
 :card/static-abilities [...]                 ; Passive stat/ability modifiers (NEW - see Nimble Mongoose)
 :card/modes          [...]                   ; Modal spell modes (NEW - see Blue Elemental Blast)
 :card/alternate-costs [...]                  ; Flashback, alternate costs (NEW - see Deep Analysis)

 ; Creatures only (NEW - not all creatures use this yet)
 :card/power          1
 :card/toughness      1
 :card/keywords       #{:shroud :threshold}   ; Keyword abilities
}
```

**File location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/{color}/{card_name}.cljs`

---

## 2. EXAMPLE: TURNABOUT (Modal Spell with 4 Modes)

### What Turnabout Does (Real Card)
- Mode 1: Tap up to 3 target artifact creatures
- Mode 2: Untap up to 3 target artifact creatures
- Mode 3: Tap up to 3 target creatures
- Mode 4: Untap up to 3 target creatures

### Modal Spell Pattern (from Blue Elemental Blast)

```clojure
(ns fizzle.cards.blue.blue-elemental-blast)

(def card
  {:card/id :blue-elemental-blast
   :card/name "Blue Elemental Blast"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one —\n• Counter target red spell.\n• Destroy target red permanent."

   ;; Modal spell: `:card/modes` array, each with targeting + effects
   :card/modes
   [{:mode/label "Counter target red spell"
     :mode/targeting [{:target/id :target-spell
                       :target/type :object
                       :target/zone :stack
                       :target/controller :any
                       :target/criteria {:match/colors #{:red}}
                       :target/required true}]
     :mode/effects [{:effect/type :counter-spell
                     :effect/target-ref :target-spell}]}

    {:mode/label "Destroy target red permanent"
     :mode/targeting [{:target/id :target-permanent
                       :target/type :object
                       :target/zone :battlefield
                       :target/controller :any
                       :target/criteria {:match/colors #{:red}}
                       :target/required true}]
     :mode/effects [{:effect/type :destroy
                     :effect/target-ref :target-permanent}]}]})
```

**Source**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/blue_elemental_blast.cljs`

### How to Implement Turnabout

You would use this structure with 4 modes. **Challenge**: No `:effect/type :untap` exists yet. You must add it first.

**Required Implementation**:
1. Add `:untap` effect type to `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs`
2. Create `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/turnabout.cljs` with 4 modes

---

## 3. EXAMPLE: FRANTIC SEARCH (Draw + Discard Chaining)

### What Frantic Search Does
1. Draw 2 cards
2. Then discard 2 cards
3. Then untap up to 3 lands

### Pattern: Opt (Scry then Draw)

```clojure
(ns fizzle.cards.blue.opt)

(def card
  {:card/id :opt
   :card/name "Opt"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Scry 1, then draw a card."

   ;; Effects execute in order. Interactive effects pause resolution.
   :card/effects [{:effect/type :scry          ; Pauses for player selection
                   :effect/amount 1}
                  {:effect/type :draw          ; Executes after scry is confirmed
                   :effect/amount 1}]})
```

**Source**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/opt.cljs`

### How to Implement Frantic Search

```clojure
(def card
  {:card/id :frantic-search
   :card/name "Frantic Search"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards. Then untap up to three lands."

   :card/effects [{:effect/type :draw
                   :effect/amount 2}
                  {:effect/type :discard             ; Requires player selection
                   :effect/count 2                   ; How many to discard
                   :effect/selection :player}        ; Player chooses which
                  {:effect/type :untap               ; REQUIRES NEW IMPLEMENTATION
                   :effect/target :any-player        ; For the lands
                   :effect/count 3
                   :effect/criteria {:match/types #{:land}}}]})
```

**Status**: Draw+discard patterns work. Untap effect missing.

---

## 4. EXAMPLE: CLOUD OF FAERIES (Creature with ETB Effect)

### What Cloud of Faeries Does
- 0/2 creature, blue, flying
- When Cloud of Faeries enters, untap up to 2 target lands

### Existing Pattern: Nimble Mongoose (1/1 creature with threshold bonus)

```clojure
(ns fizzle.cards.green.nimble-mongoose)

(def card
  {:card/id :nimble-mongoose
   :card/name "Nimble Mongoose"
   :card/cmc 1
   :card/mana-cost {:green 1}
   :card/colors #{:green}
   :card/types #{:creature}          ; Creature type
   :card/subtypes #{:mongoose}       ; Creature type
   :card/power 1                      ; Base power (NEW - at card level!)
   :card/toughness 1                  ; Base toughness (NEW - at card level!)
   :card/keywords #{:shroud}          ; Keyword abilities
   :card/text "Shroud\nThreshold — Nimble Mongoose gets +2/+2."

   ;; Static abilities for passive effects
   :card/static-abilities [{:static/type :pt-modifier
                            :modifier/power 2
                            :modifier/toughness 2
                            :modifier/condition {:condition/type :threshold}
                            :modifier/applies-to :self}]})
```

**Source**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/nimble_mongoose.cljs`

### ETB Pattern: Gemstone Mine (Land with ETB effect)

```clojure
(ns fizzle.cards.lands.gemstone-mine)

(def card
  {:card/id :gemstone-mine
   :card/name "Gemstone Mine"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "Gemstone Mine enters the battlefield with three mining counters..."

   ;; ETB effect: fires when permanent enters the battlefield
   :card/etb-effects [{:effect/type :add-counters
                       :effect/counters {:mining 3}
                       :effect/target :self}]

   ;; Mana ability with conditional sacrifice
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true
                                    :remove-counter {:mining 1}}
                     :ability/produces {:any 1}
                     :ability/effects [{:effect/type :sacrifice
                                        :effect/target :self
                                        :effect/condition {:condition/type :no-counters
                                                           :condition/counter-type :mining}}]}]})
```

**Source**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/lands/gemstone_mine.cljs`

### How to Implement Cloud of Faeries

```clojure
(def card
  {:card/id :cloud-of-faeries
   :card/name "Cloud of Faeries"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:creature}
   :card/subtypes #{:faerie}
   :card/power 0                      ; 0/2 creature
   :card/toughness 2
   :card/keywords #{:flying}
   :card/text "Flying\nWhen Cloud of Faeries enters the battlefield, untap up to two target lands."

   ;; ETB trigger
   :card/etb-effects [{:effect/type :untap          ; REQUIRES NEW IMPLEMENTATION
                       :effect/count 2
                       :effect/target :any-player
                       :effect/criteria {:match/types #{:land}}}]})
```

**Status**: ETB pattern works. Untap effect missing.

---

## 5. EFFECT TYPES: WHAT EXISTS & WHAT'S MISSING

### Currently Implemented Effect Types (33 total)

**Zone Operations** (zones.cljs):
- `:mill` — Mill cards from library to graveyard
- `:draw` — Draw cards (interactive if `:effect/target :any-player`)
- `:discard` — Discard cards (requires `:effect/selection :player` for interactive)
- `:discard-hand` — Discard entire hand
- `:return-from-graveyard` — Return from graveyard (interactive selection)
- `:sacrifice` — Sacrifice permanent
- `:destroy` — Destroy target permanent
- `:exile-self` — Exile source object
- `:exile-zone` — Exile entire zone
- `:bounce` — Return permanent to hand
- `:welder-swap` — Goblin Welder's artifact swap

**Life & Damage** (life.cljs):
- `:lose-life` — Player loses life
- `:gain-life` — Player gains life
- `:deal-damage` — Deal damage to player (players only, not creatures)
- `:gain-life-equal-to-cmc` — Gain life = target CMC

**Mana & Resources** (simple.cljs, grants.cljs):
- `:add-mana` — Add mana to pool
- `:add-counters` — Add counters
- `:add-restriction` — Restrict player actions
- `:grant-flashback` — Grant flashback ability
- `:grant-delayed-draw` — Grant draw on next turn
- `:grant-mana-ability` — Grant mana ability
- `:peek-random-hand` — Peek at random hand card

**Interactive/Selection** (selection.cljs):
- `:tutor` — Search library
- `:scry` — Look and arrange library
- `:peek-and-select` — Peek at cards, choose some
- `:peek-and-reorder` — Peek and rearrange order
- `:discard-from-revealed-hand` — Opponent reveals, you choose discard

**Stack/Countering** (stack.cljs):
- `:counter-spell` — Counter target spell
- `:counter-ability` — Counter target ability
- `:chain-bounce` — Chain of Vapor's chain mechanic

**Power/Toughness** (pt_modifier.cljs):
- `:apply-pt-modifier` — Modify creature power/toughness

**Tokens** (tokens.cljs):
- `:create-token` — Create token creatures

### MISSING: Untap Effect

**What's missing for Turnabout, Frantic Search, Cloud of Faeries**:

```clojure
{:effect/type :untap                  ; NOT IMPLEMENTED
 :effect/target :any-player           ; Which player's permanents to untap
 :effect/count 3                       ; How many permanents
 :effect/criteria {:match/types #{:land}}}  ; Filter (optional)
```

**Implementation location**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs`

**What it must do**:
1. Take up to N permanents (filtered by criteria)
2. Set their `:object/tapped` field to `false`
3. Return updated db

---

## 6. TARGETING SYSTEM

### Cast-Time Targeting (Blue Elemental Blast pattern)

```clojure
:card/modes
[{:mode/label "Counter target red spell"
  :mode/targeting [{:target/id :target-spell          ; Unique ID within card
                    :target/type :object              ; :object or :player
                    :target/zone :stack               ; Required zone
                    :target/controller :any           ; :any or :controller
                    :target/criteria {:match/colors #{:red}}  ; Filter criteria
                    :target/required true}]           ; Must be satisfied
  :mode/effects [{:effect/type :counter-spell
                  :effect/target-ref :target-spell}]} ; Reference the target
]
```

**Criteria keys available**:
- `:match/types` — Filter by type set (e.g., `#{:artifact :creature}`)
- `:match/not-types` — Exclude types
- `:match/colors` — Filter by color set

### Multi-Target Ordering (Goblin Welder pattern)

For effects that target multiple objects with controller constraints:

```clojure
:card/abilities [{:ability/type :activated
                  :ability/targeting [{:target/id :welder-bf
                                       :target/type :object
                                       :target/zone :battlefield
                                       :target/controller :any
                                       :target/criteria {:match/types #{:artifact}}}
                                      {:target/id :welder-gy
                                       :target/type :object
                                       :target/zone :graveyard
                                       :target/controller :any
                                       :target/criteria {:match/types #{:artifact}}
                                       :target/same-controller-as :welder-bf}]  ; NEW!
                  :ability/effects [{:effect/type :welder-swap
                                     :effect/target-ref :welder-bf
                                     :effect/graveyard-ref :welder-gy}]}]
```

**Key**: `:target/same-controller-as` constrains second target to same controller as first.

---

## 7. DISCARD SELECTION PATTERN

### How Careful Study Works (Draw 2, Discard 2)

```clojure
:card/effects [{:effect/type :draw
                :effect/amount 2}
               {:effect/type :discard
                :effect/count 2                   ; How many to discard
                :effect/selection :player}]       ; Player chooses which
```

**Flow**:
1. Draw effect executes immediately
2. Discard effect pauses resolution (returns `{:db db :needs-selection effect}`)
3. UI prompts player to select N cards from hand
4. Selection confirmed → discard executed → remaining effects resume

**Selection builder**: Generic `:zone-pick` pattern (dispatch hierarchy in `events/selection/core.cljs`)

**Implementation file**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/zone_ops.cljs`

---

## 8. CREATURE INFRASTRUCTURE STATUS

### What Exists

**Database schema** (db/schema.cljs):
- `:object/power` — Base power (from card)
- `:object/toughness` — Base toughness (from card)
- `:object/damage-marked` — Damage taken this turn
- `:object/summoning-sick` — New creature immunity
- `:object/attacking` — Declared as attacker
- `:object/blocking` — Blocking assignment (ref to attacker eid)
- `:object/is-token` — Token marker

**Card definition fields** (new in existing creatures):
- `:card/power` — Integer (1/1, 0/2, etc.)
- `:card/toughness` — Integer
- `:card/subtypes` — Creature types (`:mongoose`, `:goblin`, `:artificer`, etc.)
- `:card/keywords` — Keyword abilities (`:shroud`, `:flying`, etc.)
- `:card/static-abilities` — Passive stat modifiers

**UI layers**:
- Battlefield sections group creatures separately (`subs/game.cljs`)
- Creature border styling exists (`views/card_styles.cljs`)

### What's Missing

**Combat phase logic**: No rules implementation for:
- Declare attackers event
- Declare blockers event
- Damage assignment
- Creature death SBA
- Combat trigger execution

**Bot actions**: No `:declare-attackers` action for bots

**Damage-to-creature effect**: `:deal-damage` targets only players

---

## 9. FILE LOCATIONS REFERENCE

### Card Files
- Individual cards: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/{color}/{card_name}.cljs`
- Card registry: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs`

**Color directories**: `black/`, `blue/`, `white/`, `red/`, `green/`, `multicolor/`, `lands/`, `artifacts/`

### Effect System
- Main dispatcher: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects.cljs`
- Zone effects: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs`
- Life effects: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/life.cljs`
- Interactive effects: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/selection.cljs`
- Stack effects: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/stack.cljs`
- Grants/abilities: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/grants.cljs`
- PT modifiers: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/pt_modifier.cljs`
- Tokens: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/tokens.cljs`

### Selection System
- Core mechanism: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`
- Zone operations: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/zone_ops.cljs`
- Library ops: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/library.cljs`
- Costs: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/costs.cljs`
- Storm: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/storm.cljs`

### Database
- Schema: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`
- Queries: `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs`

### Testing
- Test helpers: `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs`
- Example test: `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/careful_study_test.cljs`

---

## 10. WHAT YOU CAN IMPLEMENT TODAY

### ✅ Ready to Build
1. **Modal spells with any mix of modes** — Use Blue Elemental Blast pattern
2. **Draw+discard sequences** — Use Opt/Careful Study pattern
3. **Creature cards (static, no combat)** — Use Nimble Mongoose pattern
4. **ETB triggers** — Use Gemstone Mine pattern
5. **Card abilities with targeting** — Use Goblin Welder pattern

### ❌ Requires Implementation First
1. **Untap effects** — Must add `:untap` to effects/zones.cljs
2. **Creature combat** — Epic fizzle-u07p not started (large scope)
3. **Damage to creatures** — Must extend `:deal-damage` or create `:apply-damage-to-creature`

---

## 11. HOW TO ADD UNTAP EFFECT (Step-by-Step)

### Step 1: Add to effects/zones.cljs

```clojure
(defmethod effects/execute-effect-impl :untap
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)
        count-limit (or (:effect/count effect) 999)
        criteria (or (:effect/criteria effect) {})
        permanents (or (queries/query-zone-by-criteria
                         db target-player :battlefield criteria)
                      [])
        to-untap (take count-limit permanents)]
    (reduce (fn [db' obj]
              (let [obj-eid (:db/id obj)]
                (d/db-with db' [[:db/add obj-eid :object/tapped false]])))
            db
            to-untap)))
```

### Step 2: Use in Card Definition

```clojure
:card/effects [{:effect/type :untap
                :effect/target :self                    ; Untap your permanents
                :effect/count 3
                :effect/criteria {:match/types #{:land}}}]
```

### Step 3: Test

```clojure
(deftest untap-lands-test
  (let [db (th/create-test-db)
        [db land-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
        db-tapped (d/db-with db [[:db/add (q/get-object-eid db land-id) :object/tapped true]])
        db-untapped (effects/execute-effect db-tapped :player-1
                      {:effect/type :untap
                       :effect/target :self
                       :effect/count 1
                       :effect/criteria {:match/types #{:land}}})]
    (is (not (q/get-object db-untapped land-id :object/tapped)))))
```

---

## 12. SUMMARY: Implementation Roadmap

| Card | Status | Challenges | Notes |
|------|--------|------------|-------|
| **Turnabout** | Need untap | Add `:untap` effect | 4 modes, targets creatures/lands |
| **Frantic Search** | Need untap | Add `:untap` effect | Draw+discard works; land untap blocks it |
| **Cloud of Faeries** | Need untap | Add `:untap` effect | Creature card + ETB both work |

**Effort to unblock all three**: ~30 minutes (add untap effect + tests)

---

## APPENDIX: Key Multimethod Dispatch Points

### Effect Execution
- **File**: `engine/effects.cljs`
- **Multimethod**: `execute-effect-impl`
- **Dispatch on**: `:effect/type` keyword
- **New registration**: Add defmethod in effects/zones.cljs

### Selection Execution
- **File**: `events/selection/core.cljs`
- **Multimethod**: `execute-confirmed-selection`
- **Dispatch on**: `:selection/type` keyword

### Selection Building
- **File**: `events/selection/core.cljs`
- **Multimethod**: `build-selection-for-effect`
- **Dispatch on**: effect `:effect/type` or `:effect/selection`

### Trigger Resolution
- **File**: `engine/triggers.cljs`
- **Multimethod**: `resolve-trigger`
- **Dispatch on**: `:trigger/type` keyword
