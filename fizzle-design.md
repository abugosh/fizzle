# Fizzle

**Fizzle, fork, try again.**

A ClojureScript-based Magic: The Gathering combo deck practice tool with fork/replay capabilities, simplified opponent AI, and tactics training.

**Version:** 0.1.0-draft  
**Last Updated:** January 2026  
**Target Format:** Premodern (starting with Iggy Pop storm)

---

```
    ╔═╗╦╔═╗╔═╗╦  ╔═╗
    ╠╣ ║╔═╝╔═╝║  ║╣ 
    ╚  ╩╚═╝╚═╝╩═╝╚═╝
    
    fizzle, fork, try again.
```

---

## Table of Contents

1. [Project Goals](#1-project-goals)
2. [User Stories](#2-user-stories)
3. [Technical Architecture](#3-technical-architecture)
4. [Data Models](#4-data-models)
5. [Game Engine](#5-game-engine)
6. [Card Effect System](#6-card-effect-system)
7. [Bot System](#7-bot-system)
8. [Fork/Replay System](#8-forkreplay-system)
9. [UI Components](#9-ui-components)
10. [Future: Persistence & Sharing](#10-future-persistence--sharing)
11. [Implementation Phases](#11-implementation-phases)
12. [Starter Deck: Iggy Pop](#12-starter-deck-iggy-pop)
13. [Testing Strategy](#13-testing-strategy)

---

## 1. Project Goals

### Primary Goals

1. **Efficient combo practice** — Get more reps in less time than Cockatrice/MTGO
2. **Post-sideboard hand sculpting** — Start games with specific cards in hand (Flores technique)
3. **Fork and replay** — Branch decision trees after fizzling, try different lines
4. **Tactics training** — Save positions as puzzles, drill specific scenarios
5. **Simplified opponents** — Practice against archetypal disruption patterns

### Non-Goals (for v1)

- Full MTG rules engine (we simplify aggressively)
- Multiplayer or networked play
- Complete card database (only implement cards we need)
- Mobile-first design (desktop-first, responsive later)

### Design Philosophy

**Simplify ruthlessly.** We're not building MTGO. We're building a practice tool for combo players who already know the rules. If a rules interaction is rare or irrelevant to storm, we skip it.

**Data over code.** Cards are data. Effects are data. Game state is data. The engine interprets data; it doesn't encode card-specific logic.

**Immutability enables features.** Fork/replay is trivial when state is immutable. This is why we chose ClojureScript.

---

## 2. User Stories

### Core Practice Flow

```
As a storm player, I want to:
- Load my decklist and start goldfishing
- Mulligan with London mulligan rules
- Play through my combo turn with accurate mana/storm tracking
- See my graveyard (threshold matters for Cabal Ritual)
- Track floating mana across priority passes
```

### Hand Sculpting (Flores Technique)

```
As a player preparing for a tournament, I want to:
- Specify that my opening hand contains [LED, Cabal Ritual, Ill-Gotten Gains]
- Specify that 2 Orim's Chant are shuffled into my deck (post-board)
- Practice the specific scenarios I expect to face
```

### Fork/Replay

```
As a player who just fizzled, I want to:
- Rewind to the decision point where I chose to cast Intuition
- Try a different pile configuration
- Compare the outcomes of different lines
- Save interesting decision points for later review
```

### Tactics Training

```
As a player drilling specific scenarios, I want to:
- Save the current game state as a "puzzle"
- Name and tag puzzles (e.g., "IGG line vs 2 cards in hand")
- Load puzzles and practice from that position
- Track my success rate on specific puzzles
```

### Simplified Opponents

```
As a player practicing against disruption, I want to:
- Face a "burn" opponent (20 lands, 40 Lightning Bolts)
- Face a "control" opponent (counterspells on a clock)
- Face a "discard" opponent (Duress effects)
- Either manually trigger opponent actions or use simple heuristics
```

---

## 3. Technical Architecture

### Stack

| Layer | Technology | Rationale |
|-------|------------|-----------|
| Build | shadow-cljs | Fast builds, excellent JS interop, hot reload |
| UI Framework | Reagent | React wrapper, hiccup syntax, simple |
| State Management | re-frame | Event sourcing built-in, time-travel debugging |
| Local Database | Datascript | In-memory Datalog, perfect for game state queries |
| Styling | Tailwind CSS | Utility-first, fast iteration |
| PWA | Workbox | Service worker tooling for offline support |

### Why This Stack?

**re-frame's event model is our action log.** Every game action dispatches an event. State is a pure reduction over events. This gives us fork/replay almost for free.

**Datascript enables declarative queries.** "What cards in hand can I cast?" is a query, not imperative code. As game state grows complex, queries stay simple.

**ClojureScript's immutability means fork is free.** Keeping a reference to an old state doesn't copy anything. Structural sharing handles it.

### Project Structure

```
fizzle/
├── deps.edn                    ; Clojure deps
├── shadow-cljs.edn             ; Build config
├── package.json                ; JS deps (tailwind, etc.)
├── resources/
│   └── public/
│       ├── index.html
│       ├── css/
│       └── js/                 ; Compiled output
├── src/
│   └── fizzle/
│       ├── core.cljs           ; Entry point, app initialization
│       ├── config.cljs         ; App configuration
│       │
│       ├── db/
│       │   ├── schema.cljs     ; Datascript schema
│       │   ├── init.cljs       ; Initial app-db state
│       │   └── queries.cljs    ; Common Datalog queries
│       │
│       ├── events/
│       │   ├── core.cljs       ; re-frame event registration
│       │   ├── game.cljs       ; Game action events
│       │   ├── ui.cljs         ; UI state events
│       │   └── tactics.cljs    ; Puzzle/save events
│       │
│       ├── subs/
│       │   ├── core.cljs       ; Subscription registration
│       │   ├── game.cljs       ; Game state subscriptions
│       │   └── derived.cljs    ; Computed values (castable cards, etc.)
│       │
│       ├── engine/
│       │   ├── rules.cljs      ; Core game rules
│       │   ├── mana.cljs       ; Mana pool logic
│       │   ├── stack.cljs      ; Spell stack (simplified)
│       │   ├── effects.cljs    ; Effect interpreter
│       │   ├── keywords.cljs   ; Keyword abilities (storm, threshold)
│       │   └── zones.cljs      ; Zone transitions
│       │
│       ├── cards/
│       │   ├── core.cljs       ; Card definition helpers
│       │   ├── iggy_pop.cljs   ; Iggy Pop card definitions
│       │   └── registry.cljs   ; Card lookup by name
│       │
│       ├── bots/
│       │   ├── protocol.cljs   ; Bot interface
│       │   ├── goldfish.cljs   ; Non-interactive (clock only)
│       │   ├── burn.cljs       ; Bolts + clock
│       │   ├── control.cljs    ; Counterspells
│       │   └── discard.cljs    ; Duress effects
│       │
│       ├── history/
│       │   ├── core.cljs       ; Action history management
│       │   ├── fork.cljs       ; Branching logic
│       │   └── replay.cljs     ; Replay/rewind
│       │
│       ├── tactics/
│       │   ├── puzzles.cljs    ; Puzzle data structures
│       │   ├── storage.cljs    ; localStorage (for now)
│       │   └── export.cljs     ; EDN export/import
│       │
│       └── views/
│           ├── app.cljs        ; Root component
│           ├── game.cljs       ; Main game view
│           ├── hand.cljs       ; Hand display
│           ├── battlefield.cljs; Permanents
│           ├── graveyard.cljs  ; Graveyard viewer
│           ├── stack.cljs      ; Spell stack display
│           ├── mana.cljs       ; Mana pool display
│           ├── history.cljs    ; Action log + fork UI
│           ├── opponent.cljs   ; Opponent state/actions
│           ├── setup.cljs      ; Game setup, hand sculpting
│           └── tactics.cljs    ; Puzzle browser
└── test/
    └── fizzle/
        ├── engine_test.cljs
        ├── cards_test.cljs
        └── history_test.cljs
```

---

## 4. Data Models

### Datascript Schema

```clojure
(def schema
  {;; === Cards (definitions, immutable) ===
   :card/id           {:db/unique :db.unique/identity}
   :card/name         {:db/index true}
   :card/cmc          {}
   :card/colors       {:db/cardinality :db.cardinality/many}
   :card/types        {:db/cardinality :db.cardinality/many}
   :card/subtypes     {:db/cardinality :db.cardinality/many}
   :card/supertypes   {:db/cardinality :db.cardinality/many}
   :card/text         {}
   :card/effects      {}  ; EDN data structure
   :card/abilities    {}  ; EDN for activated/triggered
   :card/keywords     {:db/cardinality :db.cardinality/many}
   
   ;; === Game Objects (instances of cards in a game) ===
   :object/id         {:db/unique :db.unique/identity}
   :object/card       {:db/valueType :db.type/ref}
   :object/zone       {}  ; :library, :hand, :battlefield, :graveyard, :stack, :exile
   :object/owner      {:db/valueType :db.type/ref}
   :object/controller {:db/valueType :db.type/ref}
   :object/tapped     {}
   :object/counters   {}  ; Map of counter-type -> count
   :object/position   {}  ; Position in zone (for library order)
   :object/targets    {:db/cardinality :db.cardinality/many
                       :db/valueType :db.type/ref}
   
   ;; === Players ===
   :player/id         {:db/unique :db.unique/identity}
   :player/name       {}
   :player/life       {}
   :player/is-opponent {}
   :player/mana-pool  {}  ; Map of color -> amount
   :player/storm-count {}
   :player/land-plays-remaining {}
   
   ;; === Game State ===
   :game/id           {:db/unique :db.unique/identity}
   :game/turn         {}
   :game/phase        {}  ; :beginning, :main1, :combat, :main2, :end
   :game/step         {}  ; :untap, :upkeep, :draw, etc.
   :game/active-player {:db/valueType :db.type/ref}
   :game/priority     {:db/valueType :db.type/ref}
   :game/winner       {:db/valueType :db.type/ref}})
```

### Card Definition Format (EDN)

Cards are defined as pure data. The engine interprets this data to execute game actions.

```clojure
;; Example: Dark Ritual
{:card/id :dark-ritual
 :card/name "Dark Ritual"
 :card/cmc 1
 :card/colors #{:black}
 :card/types #{:instant}
 :card/text "Add {B}{B}{B}."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}}]}

;; Example: Cabal Ritual
{:card/id :cabal-ritual
 :card/name "Cabal Ritual"
 :card/cmc 2
 :card/colors #{:black}
 :card/types #{:instant}
 :card/text "Add {B}{B}{B}. Threshold — Add {B}{B}{B}{B}{B} instead."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}
                 :effect/condition {:condition/type :threshold-not-met}}
                {:effect/type :add-mana
                 :effect/mana {:black 5}
                 :effect/condition {:condition/type :threshold}}]}

;; Example: Lion's Eye Diamond
{:card/id :lions-eye-diamond
 :card/name "Lion's Eye Diamond"
 :card/cmc 0
 :card/types #{:artifact}
 :card/text "Sacrifice LED, Discard your hand: Add three mana of any one color."
 :card/abilities [{:ability/type :activated
                   :ability/timing :mana-ability
                   :ability/cost {:cost/sacrifice :self
                                  :cost/discard-hand true}
                   :ability/effects [{:effect/type :add-mana
                                      :effect/mana {:choice 3}
                                      :effect/choice-type :one-color}]}]}

;; Example: Ill-Gotten Gains
{:card/id :ill-gotten-gains
 :card/name "Ill-Gotten Gains"
 :card/cmc 4
 :card/colors #{:black}
 :card/types #{:sorcery}
 :card/text "Exile IGG. Each player discards hand, then returns up to 3 cards from GY to hand."
 :card/effects [{:effect/type :exile-self}
                {:effect/type :each-player
                 :effect/player-effects [{:effect/type :discard-hand}
                                         {:effect/type :return-from-graveyard
                                          :effect/count {:up-to 3}
                                          :effect/choice :player}]}]}

;; Example: Brain Freeze
{:card/id :brain-freeze
 :card/name "Brain Freeze"
 :card/cmc 2
 :card/colors #{:blue}
 :card/types #{:instant}
 :card/keywords #{:storm}
 :card/text "Target player mills 3. Storm."
 :card/effects [{:effect/type :mill
                 :effect/count 3
                 :effect/target :chosen-player}]}
```

### Mana Representation

```clojure
;; Mana pool is a map
{:white 0
 :blue 2
 :black 5
 :red 0
 :green 0
 :colorless 0}

;; Mana costs use the same format with :generic for generic mana
{:black 2 :generic 2}  ; 2BB

;; For choices (e.g., LED "any one color"), we use:
{:choice 3 :choice-type :one-color}
```

### Game State Snapshot (for fork/replay)

```clojure
{:snapshot/id (random-uuid)
 :snapshot/name "Before Intuition"
 :snapshot/timestamp (js/Date.)
 :snapshot/db-value @conn  ; Datascript db value (immutable!)
 :snapshot/action-index 15  ; Position in action history
 :snapshot/tags #{:decision-point :intuition}}
```

---

## 5. Game Engine

### Simplified Rules

We implement a subset of MTG rules sufficient for goldfishing storm:

#### Phases & Steps (Simplified)

```
Turn Structure:
├── Beginning Phase
│   ├── Untap (automatic)
│   ├── Upkeep (trigger check)
│   └── Draw (automatic, skipped turn 1)
├── First Main Phase
│   └── [PRIMARY PLAY AREA - most actions happen here]
├── Combat Phase (simplified - just attack declaration)
├── Second Main Phase
└── End Phase
    └── Cleanup (discard to 7, clear mana pool)
```

**Simplifications:**
- No combat damage calculation (creatures just deal their power)
- No blocking (opponent creatures always get through, we just track life)
- No instants during combat (main phase focus)
- Mana abilities don't use the stack

#### The Stack (Simplified)

```clojure
;; Stack is a vector of pending spells/abilities
;; For storm, we mostly just need to track:
;; 1. Spell goes on stack (increment storm count)
;; 2. Spell resolves (execute effects)
;; 3. Counterspells can target things on stack

;; We skip:
;; - Split second
;; - Complex targeting restrictions
;; - Most triggered abilities
```

#### Zones

```
Zones:
├── library     ; Ordered, face-down
├── hand        ; Private to player
├── battlefield ; Permanents in play
├── graveyard   ; Public, ordered
├── stack       ; Spells being cast
└── exile       ; Removed from game
```

### Core Engine Functions

```clojure
(ns fizzle.engine.rules)

(defn can-cast?
  "Check if player can cast this card from hand."
  [db player-id object-id]
  (let [card (get-card db object-id)
        cost (:card/cmc card)
        pool (get-mana-pool db player-id)
        in-hand? (= :hand (get-zone db object-id))
        is-sorcery-timing? (sorcery-timing? db player-id)]
    (and in-hand?
         (can-pay? pool cost)
         (or (instant? card) is-sorcery-timing?))))

(defn sorcery-timing?
  "Can we cast sorceries? (Main phase, stack empty, our turn)"
  [db player-id]
  (and (#{:main1 :main2} (get-phase db))
       (empty? (get-stack db))
       (= player-id (get-active-player db))))

(defn cast-spell
  "Move card from hand to stack, pay costs."
  [db player-id object-id & {:keys [targets mana-payment]}]
  (-> db
      (pay-mana player-id mana-payment)
      (move-to-zone object-id :stack)
      (increment-storm-count player-id)))

(defn resolve-top-of-stack
  "Resolve the top spell/ability on the stack."
  [db]
  (let [spell (peek-stack db)
        card (get-card db spell)
        effects (:card/effects card)]
    (-> db
        (execute-effects spell effects)
        (move-to-zone spell (destination-zone card))
        (check-storm-trigger spell))))
```

### Effect Interpreter

```clojure
(ns fizzle.engine.effects)

(defmulti execute-effect
  "Execute a single effect. Dispatches on :effect/type"
  (fn [db caster effect] (:effect/type effect)))

(defmethod execute-effect :add-mana
  [db caster {:keys [effect/mana effect/condition]}]
  (if (check-condition db caster condition)
    (add-mana db (:object/controller caster) mana)
    db))

(defmethod execute-effect :draw
  [db caster {:keys [effect/count]}]
  (draw-cards db (:object/controller caster) count))

(defmethod execute-effect :discard-hand
  [db caster _]
  (let [player (:object/controller caster)
        hand (get-hand db player)]
    (reduce #(move-to-zone %1 %2 :graveyard) db hand)))

(defmethod execute-effect :mill
  [db caster {:keys [effect/count effect/target]}]
  (let [target-player (resolve-target db caster target)]
    (mill db target-player count)))

(defmethod execute-effect :return-from-graveyard
  [db caster {:keys [effect/count effect/choice]}]
  ;; This requires player interaction - we dispatch an event
  ;; that waits for player to choose cards
  (-> db
      (assoc :pending-choice 
             {:type :choose-cards-from-graveyard
              :count count
              :player (:object/controller caster)})))

;; Condition checking
(defmulti check-condition (fn [db caster condition] (:condition/type condition)))

(defmethod check-condition nil [_ _ _] true)

(defmethod check-condition :threshold
  [db caster _]
  (>= (count-graveyard db (:object/controller caster)) 7))

(defmethod check-condition :threshold-not-met
  [db caster _]
  (< (count-graveyard db (:object/controller caster)) 7))
```

---

## 6. Card Effect System

### Effect Types

| Effect Type | Parameters | Description |
|-------------|------------|-------------|
| `:add-mana` | `:mana`, `:condition` | Add mana to pool |
| `:draw` | `:count` | Draw cards |
| `:discard` | `:count`, `:choice` | Discard cards |
| `:discard-hand` | — | Discard entire hand |
| `:mill` | `:count`, `:target` | Mill cards |
| `:tutor` | `:zone`, `:destination`, `:reveal?` | Search library |
| `:return-from-graveyard` | `:count`, `:card-type` | Return cards from GY |
| `:exile-self` | — | Exile the spell |
| `:sacrifice` | `:target` | Sacrifice permanent |
| `:drain` | `:amount`, `:target` | Drain life |
| `:each-player` | `:player-effects` | Apply effects to each player |

### Targeting

```clojure
;; Target types
:self           ; The card itself
:chosen-player  ; Player chosen on cast
:controller     ; Controller of the source
:opponent       ; Opponent (for 1v1)
:any-permanent  ; Chosen permanent

;; Target resolution happens at cast time for spells
;; and at resolution time for abilities
```

### Keyword Abilities

```clojure
;; Storm implementation
(defn check-storm-trigger
  "After a storm spell resolves, copy it storm-count times."
  [db spell]
  (let [card (get-card db spell)
        storm-count (get-storm-count db)]
    (if (contains? (:card/keywords card) :storm)
      (create-storm-copies db spell storm-count)
      db)))

;; Threshold is handled via conditions on effects (see Cabal Ritual)
```

### Choice Resolution

Some effects require player choices (Intuition, IGG, LED mana color). We handle this with a pending-choice system:

```clojure
;; When an effect needs a choice, we pause and wait
{:pending-choice 
 {:id (random-uuid)
  :type :choose-cards-from-graveyard
  :player :player-1
  :count {:up-to 3}
  :source object-id
  :continuation effect-continuation}}

;; UI shows the choice interface
;; Player makes selection
;; We dispatch [:resolve-choice choice-id selections]
;; Engine continues with the continuation
```

---

## 7. Bot System

### Bot Protocol

```clojure
(ns fizzle.bots.protocol)

(defprotocol IBot
  (get-archetype [this])
  (get-clock [this db])
  (should-act? [this db trigger])
  (choose-action [this db available-actions])
  (get-deck [this]))

;; Triggers that bots respond to:
;; :on-spell-cast - opportunity to counter
;; :on-turn-start - discard, play threats
;; :on-main-phase - play lands, develop
;; :on-priority   - any instant-speed action
```

### Goldfish Bot

```clojure
(ns fizzle.bots.goldfish)

(defrecord GoldfishBot [clock-turns]
  IBot
  (get-archetype [_] :goldfish)
  (get-clock [_ db] 
    ;; Opponent "wins" on turn N
    (let [current-turn (get-turn db)]
      (- clock-turns current-turn)))
  (should-act? [_ _ _] false)
  (choose-action [_ _ _] nil)
  (get-deck [_] nil))

;; Usage: pure goldfish, win by turn 4
(def fast-goldfish (->GoldfishBot 4))
```

### Burn Bot

```clojure
(ns fizzle.bots.burn)

;; Decklist: 20 Mountain, 40 Lightning Bolt
(def burn-deck
  (concat
    (repeat 20 {:card/name "Mountain" :card/types #{:land} :card/subtypes #{:mountain}})
    (repeat 40 {:card/name "Lightning Bolt" 
                :card/cmc 1 
                :card/colors #{:red}
                :card/types #{:instant}
                :card/effects [{:effect/type :damage
                                :effect/amount 3
                                :effect/target :any}]})))

(defrecord BurnBot [aggression]  ; 0.0-1.0, higher = bolts face more
  IBot
  (get-archetype [_] :burn)
  
  (get-clock [_ db]
    ;; Calculate turns to kill based on lands + bolts in hand
    (let [player (get-opponent db)
          lands (count-lands-in-play db player)
          bolts-in-hand (count-cards-in-hand db player)]
      (estimate-burn-clock lands bolts-in-hand)))
  
  (should-act? [this db trigger]
    (case trigger
      :on-main-phase true  ; Always plays lands
      :on-priority (and (has-mana? db (get-opponent db) {:red 1})
                        (has-bolt-in-hand? db))
      false))
  
  (choose-action [this db actions]
    (cond
      ;; Always play land if possible
      (contains-action? actions :play-land)
      {:action :play-land :card (first-land-in-hand db)}
      
      ;; Bolt logic
      (contains-action? actions :cast-spell)
      (if (> (rand) (- 1 aggression))
        {:action :cast-spell :card :lightning-bolt :target :player}
        {:action :pass})
      
      :else {:action :pass})))
```

### Control Bot

```clojure
(ns fizzle.bots.control)

;; Configurable counterspell density
(defn control-deck [counterspell-count]
  (concat
    (repeat 24 {:card/name "Island" :card/types #{:land} :card/subtypes #{:island}})
    (repeat counterspell-count 
            {:card/name "Counterspell"
             :card/cmc 2
             :card/colors #{:blue}
             :card/types #{:instant}
             :card/effects [{:effect/type :counter-spell
                             :effect/target :spell-on-stack}]})
    (repeat (- 36 counterspell-count)
            {:card/name "Air Elemental"
             :card/cmc 5
             :card/colors #{:blue}
             :card/types #{:creature}
             :card/power 4
             :card/toughness 4
             :card/keywords #{:flying}})))

(defrecord ControlBot [counter-targets priority-threshold]
  ;; counter-targets: set of card types/names to always counter
  ;; priority-threshold: mana cost above which to counter
  IBot
  
  (should-act? [this db trigger]
    (and (= trigger :on-spell-cast)
         (has-mana? db (get-opponent db) {:blue 2})
         (has-counterspell? db)))
  
  (choose-action [this db actions]
    (let [spell-on-stack (peek-stack db)
          card (get-card db spell-on-stack)]
      (cond
        ;; Always counter high-priority targets
        (contains? counter-targets (:card/name card))
        {:action :cast-spell :card :counterspell :target spell-on-stack}
        
        ;; Counter expensive spells
        (>= (:card/cmc card) priority-threshold)
        {:action :cast-spell :card :counterspell :target spell-on-stack}
        
        :else {:action :pass}))))

;; Example: Counter IGG, Intuition, and anything CMC 4+
(def anti-storm-control 
  (->ControlBot 
    #{"Ill-Gotten Gains" "Intuition" "Cunning Wish"}
    4))
```

### Discard Bot

```clojure
(ns fizzle.bots.discard)

(defn discard-deck [duress-count]
  (concat
    (repeat 24 {:card/name "Swamp" :card/types #{:land} :card/subtypes #{:swamp}})
    (repeat duress-count
            {:card/name "Duress"
             :card/cmc 1
             :card/colors #{:black}
             :card/types #{:sorcery}
             :card/effects [{:effect/type :targeted-discard
                             :effect/target :opponent
                             :effect/card-type :nonland-noncreature}]})
    (repeat (- 36 duress-count)
            {:card/name "Hypnotic Specter"
             :card/cmc 3
             :card/colors #{:black}
             :card/types #{:creature}
             :card/power 2
             :card/toughness 2
             :card/keywords #{:flying}
             :card/abilities [{:ability/type :triggered
                               :ability/trigger :deals-combat-damage
                               :ability/effects [{:effect/type :random-discard
                                                  :effect/target :opponent}]}]})))

(defrecord DiscardBot [priority-cards]
  ;; priority-cards: ordered list of cards to take with Duress
  IBot
  
  (choose-action [this db actions]
    (cond
      (contains-action? actions :play-land)
      {:action :play-land :card (first-land-in-hand db)}
      
      ;; Cast Duress turn 1 if possible
      (and (contains-action? actions :cast-spell)
           (has-mana? db (get-opponent db) {:black 1})
           (has-duress-in-hand? db))
      {:action :cast-spell :card :duress}
      
      :else {:action :pass}))
  
  ;; When resolving Duress, pick from priority list
  (resolve-duress [this db hand]
    (or (first (filter #(contains? (set priority-cards) (:card/name %)) hand))
        (first (filter #(ritual? %) hand))
        (first (filter #(tutor? %) hand))
        (first hand))))

;; Example: Prioritize taking LED, IGG, rituals
(def storm-hate-discard
  (->DiscardBot 
    ["Lion's Eye Diamond" "Ill-Gotten Gains" "Dark Ritual" "Cabal Ritual"]))
```

### Manual Override Mode

For any bot, the player can choose to manually make decisions:

```clojure
;; In UI, toggle "Manual Bot Control"
;; When bot would act, instead show:
;; "Bot wants to [action]. Allow? [Yes] [No] [Choose different]"

;; This enables:
;; - Testing specific disruption patterns
;; - "What if they had the Force?"
;; - Learning what disruption does to your lines
```

---

## 8. Fork/Replay System

### Core Concept

Every game action is an event. The game state is a pure reduction over the event history. This enables:

1. **Replay**: Reduce events 0..N to reconstruct any state
2. **Fork**: Branch the event list at any point
3. **Undo**: Pop events off the history

### Event History Structure

```clojure
{:history/events 
 [{:event/id 0
   :event/type :game-start
   :event/timestamp #inst "..."
   :event/data {:deck deck-list}}
  
  {:event/id 1
   :event/type :draw-opening-hand
   :event/data {:count 7}}
  
  {:event/id 2
   :event/type :keep-hand
   :event/data {}}
  
  {:event/id 3
   :event/type :cast-spell
   :event/data {:card :lotus-petal :from :hand}}
  
  {:event/id 4
   :event/type :activate-ability
   :event/data {:card :lotus-petal :ability 0 :choice {:blue 1}}}
  
  ...]
 
 :history/forks
 [{:fork/id (uuid)
   :fork/name "Try different Intuition pile"
   :fork/branch-point 15  ; Event ID where we branched
   :fork/events [...]}]   ; Events from branch point forward
 
 :history/current-branch nil}  ; nil = main, or fork ID
```

### Fork Operations

```clojure
(ns fizzle.history.fork)

(defn create-fork
  "Create a new branch at the given event index."
  [history event-index name]
  (let [fork-id (random-uuid)
        fork {:fork/id fork-id
              :fork/name name
              :fork/branch-point event-index
              :fork/events []}]
    (-> history
        (update :history/forks conj fork)
        (assoc :history/current-branch fork-id))))

(defn get-effective-events
  "Get the event sequence for the current branch."
  [history]
  (if-let [branch-id (:history/current-branch history)]
    (let [fork (get-fork history branch-id)
          main-events (take (:fork/branch-point fork) 
                            (:history/events history))]
      (concat main-events (:fork/events fork)))
    (:history/events history)))

(defn rewind-to
  "Rewind to a specific event, creating a fork if needed."
  [history event-index]
  (if (< event-index (count (get-effective-events history)))
    (create-fork history event-index (str "Fork at event " event-index))
    history))
```

### UI Integration

```
Action History Panel:
┌─────────────────────────────────────┐
│ ▼ Turn 1                            │
│   • Drew 7 cards                    │
│   • Kept hand                       │
│   • Played Polluted Delta           │
│   • Cast Lotus Petal                │
│   • Sacrificed Petal for U         │
│   • Cast Careful Study              │
│     └─ [Fork here] [Rewind to]      │  <- Actions on hover
│   • Discarded: IGG, Dark Ritual     │
│ ▼ Turn 2                            │
│   • Drew Lion's Eye Diamond         │
│   ...                               │
├─────────────────────────────────────┤
│ Forks:                              │
│   • main (current)                  │
│   • "Keep 2nd ritual" @ event 7     │
│   • "Different IGG targets" @ 23    │
└─────────────────────────────────────┘
```

---

## 9. UI Components

### Layout

```
┌──────────────────────────────────────────────────────────────────┐
│  Fizzle                    [Setup] [Tactics] [Settings]   │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────┐  ┌─────────────────────────────────┐   │
│  │     OPPONENT        │  │         ACTION LOG              │   │
│  │  Life: 20           │  │  • Cast Dark Ritual            │   │
│  │  Clock: 3 turns     │  │  • Add BBB                      │   │
│  │  [Bolt] [Counter]   │  │  • Cast Cabal Ritual           │   │
│  │                     │  │  • Add BBBBB (threshold)        │   │
│  └─────────────────────┘  │  • Cast Ill-Gotten Gains       │   │
│                           │  ← [Fork] [Rewind]              │   │
│  ┌─────────────────────┐  └─────────────────────────────────┘   │
│  │   BATTLEFIELD       │                                        │
│  │  [Gemstone Mine]    │  ┌─────────────────────────────────┐   │
│  │  [LED] [LED]        │  │       GRAVEYARD (12)            │   │
│  └─────────────────────┘  │  Dark Ritual, Dark Ritual,      │   │
│                           │  Careful Study, Mental Note...   │   │
│  ┌─────────────────────┐  │  [View All]                      │   │
│  │   MANA POOL         │  └─────────────────────────────────┘   │
│  │  BBB UU (5 total)   │                                        │
│  │  Storm Count: 7     │                                        │
│  └─────────────────────┘                                        │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                         HAND (4)                          │   │
│  │   [Intuition]  [IGG]  [Brain Freeze]  [City of Brass]    │   │
│  │                                                          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  [Untap] [Upkeep] [Draw] [Main] ●  [Combat] [Main 2] [End]      │
└──────────────────────────────────────────────────────────────────┘
```

### Component Hierarchy

```clojure
;; views/app.cljs
[app]
├── [header]
│   ├── [logo]
│   └── [nav-buttons]  ; Setup, Tactics, Settings
├── [game-view]  ; when in game
│   ├── [opponent-panel]
│   │   ├── [life-display]
│   │   ├── [clock-display]
│   │   └── [bot-actions]  ; Manual trigger buttons
│   ├── [battlefield]
│   │   └── [permanent-card]*
│   ├── [mana-display]
│   │   ├── [mana-pool]
│   │   └── [storm-counter]
│   ├── [action-log]
│   │   ├── [log-entry]*
│   │   └── [fork-controls]
│   ├── [graveyard-panel]
│   │   └── [card-list]
│   ├── [hand]
│   │   └── [card]*  ; Clickable to cast/play
│   └── [phase-bar]
│       └── [phase-button]*
├── [setup-view]  ; Deck selection, hand sculpting
│   ├── [deck-selector]
│   ├── [hand-sculptor]
│   └── [opponent-selector]
└── [tactics-view]  ; Puzzle browser
    ├── [puzzle-list]
    └── [puzzle-detail]
```

### Reagent Component Examples

```clojure
(ns fizzle.views.hand
  (:require [re-frame.core :as rf]))

(defn card-in-hand [{:keys [object-id]}]
  (let [card @(rf/subscribe [:card-for-object object-id])
        castable? @(rf/subscribe [:can-cast? object-id])
        selected? @(rf/subscribe [:selected? object-id])]
    [:div.card
     {:class [(when castable? "castable")
              (when selected? "selected")]
      :on-click #(rf/dispatch [:select-card object-id])}
     [:div.card-name (:card/name card)]
     [:div.card-cost (format-mana (:card/cmc card))]
     (when castable?
       [:button.cast-btn
        {:on-click #(rf/dispatch [:cast-spell object-id])}
        "Cast"])]))

(defn hand []
  (let [hand-objects @(rf/subscribe [:hand])]
    [:div.hand
     [:h3 (str "Hand (" (count hand-objects) ")")]
     [:div.hand-cards
      (for [obj hand-objects]
        ^{:key (:object/id obj)}
        [card-in-hand {:object-id (:object/id obj)}])]]))
```

### Key UX Decisions

Based on the specific needs of storm practice, here are the UX patterns for complex cards:

#### Intuition Pile Selection

```
┌─────────────────────────────────────────────────────────────┐
│  INTUITION - Search for 3 cards                             │
├─────────────────────────────────────────────────────────────┤
│  Search your library...                                     │
│                                                             │
│  Selected (3/3):                                            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐                       │
│  │  IGG    │ │  IGG    │ │  IGG    │    ← 3x same card     │
│  └─────────┘ └─────────┘ └─────────┘                       │
│                                                             │
│  [Search Library...]                                        │
│                                                             │
│  Which card goes to hand?                                   │
│  ○ Card 1 (IGG)                                            │
│  ○ Card 2 (IGG)                                            │
│  ○ Card 3 (IGG)                                            │
│                                                             │
│  [You Choose]  [Random]  [All Same → Auto-pick]            │
│                                                             │
│  Note: "All Same" shortcut available when 3 identical cards │
└─────────────────────────────────────────────────────────────┘
```

**UX Rules:**
- Player always picks all 3 cards (no bot involvement in selection)
- Player chooses which goes to hand OR clicks "Random" for opponent simulation
- When all 3 cards are identical (e.g., 3x IGG), show shortcut button that auto-completes
- Remaining 2 cards go to graveyard automatically

#### Ill-Gotten Gains Opponent Returns

```
┌─────────────────────────────────────────────────────────────┐
│  ILL-GOTTEN GAINS - Opponent returns up to 3 cards          │
├─────────────────────────────────────────────────────────────┤
│  Opponent's graveyard (after discarding hand):              │
│  [Counterspell] [Counterspell] [Force of Will] [Island]    │
│                                                             │
│  Opponent returns to hand:                                  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ○ Disabled (pure goldfish)                           │  │
│  │ ○ Random 3 cards                                     │  │
│  │ ○ You choose (practice against worst case)    ← sel  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  If "You choose": Select up to 3 cards for opponent:       │
│  [x] Counterspell                                          │
│  [x] Counterspell                                          │
│  [ ] Force of Will                                         │
│  [ ] Island                                                │
│                                                             │
│  [Confirm]                                                  │
└─────────────────────────────────────────────────────────────┘
```

**UX Rules:**
- Three modes: Disabled (opponent returns nothing), Random, You Choose
- Default to "Disabled" for pure goldfishing
- "You Choose" lets player practice worst-case (opponent gets back all their interaction)
- Only matters when opponent has interaction in their graveyard
- Setting persists per session

#### Lion's Eye Diamond Timing

```
┌─────────────────────────────────────────────────────────────┐
│  LION'S EYE DIAMOND                                         │
├─────────────────────────────────────────────────────────────┤
│  Timing: Instant speed only (not while announcing spells)   │
│                                                             │
│  [Sacrifice for BBB]  [Sacrifice for UUU]  [Sacrifice ...]  │
│                                                             │
│  ⚠️ Cannot activate while casting a spell from hand.        │
│  Common patterns:                                           │
│  • Cast IGG, hold priority, crack LED in response           │
│  • Cast Infernal Tutor, hold priority, crack LED            │
└─────────────────────────────────────────────────────────────┘
```

**UX Rules:**
- Model LED as instant-speed activated ability (not mana ability)
- LED button is disabled during spell announcement phase
- After spell goes on stack, LED can be activated before resolution
- UI shows "Hold Priority" hint when relevant spells are cast
- Trust player to use correctly — this is a practice tool, not a rules enforcer

#### Scryfall Integration for Card Images

```clojure
;; Fetch card image from Scryfall
(defn scryfall-image-url [card-name]
  (str "https://api.scryfall.com/cards/named?exact=" 
       (js/encodeURIComponent card-name)
       "&format=image&version=normal"))

;; Usage in component
(defn card-image [{:keys [card-name]}]
  [:img.card-image
   {:src (scryfall-image-url card-name)
    :alt card-name
    :loading "lazy"  ; Don't block initial load
    :on-error #(reset! show-fallback? true)}])

;; Fallback to text-only display if image fails
(defn card-display [{:keys [card]}]
  (let [show-fallback? (r/atom false)]
    (fn []
      (if @show-fallback?
        [card-text-only card]
        [card-image {:card-name (:card/name card)}]))))
```

**Scryfall API Notes:**
- Free for non-commercial use
- Rate limit: 10 requests/second (we'll cache aggressively)
- Use `version=small` for hand/graveyard, `version=normal` for focused view
- Cache images in localStorage for offline access
- Premodern cards are all available

---

## 10. Future: Persistence & Sharing

### Phase 1: Local Storage (v1)

For offline-first MVP, use browser localStorage:

```clojure
(ns fizzle.tactics.storage)

(defn save-puzzle! [puzzle]
  (let [puzzles (get-all-puzzles)
        updated (assoc puzzles (:puzzle/id puzzle) puzzle)]
    (.setItem js/localStorage "fizzle-puzzles" 
              (pr-str updated))))

(defn load-puzzle [puzzle-id]
  (let [puzzles (edn/read-string 
                  (.getItem js/localStorage "fizzle-puzzles"))]
    (get puzzles puzzle-id)))

(defn export-puzzle-edn [puzzle]
  ;; Returns EDN string for sharing via copy/paste
  (pr-str puzzle))

(defn import-puzzle-edn [edn-string]
  (edn/read-string edn-string))
```

### Phase 2: Cloud Persistence (Future)

Options analysis for when we add cloud sync:

| Option | Pros | Cons | Cost |
|--------|------|------|------|
| **Firebase Firestore** | Real-time sync, good free tier, auth included | Google lock-in, proprietary | Free tier generous |
| **Supabase** | PostgreSQL, open source, self-hostable | Newer, smaller community | Free tier decent |
| **PocketBase** | Single binary, SQLite, self-host easy | Less scalable, newer | Free (self-host) |
| **Plain S3 + Auth0** | Maximum control | More work to build | Pay per use |

**Recommendation: Start with Supabase.**

Rationale:
- PostgreSQL gives us Datalog-like querying capability
- Open source means we can self-host if needed
- Good ClojureScript interop via JS SDK
- Row-level security for user puzzles vs. public puzzles
- Easier to migrate away from than Firebase

### Data Model for Sharing

```clojure
;; Puzzle stored in cloud
{:puzzle/id (uuid)
 :puzzle/name "IGG Line vs Empty Hand"
 :puzzle/author "storm_player_42"
 :puzzle/created #inst "2026-01-15"
 :puzzle/tags #{"iggy-pop" "igg-line" "tutorial"}
 :puzzle/difficulty :intermediate
 :puzzle/description "Practice the basic IGG loop..."
 :puzzle/initial-state {...}  ; Serialized Datascript db
 :puzzle/solution-hint "LED in response to IGG"
 :puzzle/public? true
 :puzzle/play-count 142
 :puzzle/success-rate 0.67}
```

### Community Features (Future)

- Public puzzle library with search/filter
- Upvoting/favoriting puzzles
- "Daily puzzle" feature
- Puzzle collections/courses
- Success rate tracking per user

---

## 11. Implementation Phases

### Phase 1: Core Engine (Week 1-2)

**Goal:** Goldfish a storm deck to completion

- [ ] Project setup (shadow-cljs, deps, folder structure)
- [ ] Datascript schema and initial state
- [ ] Basic card definitions (lands, rituals, LED)
- [ ] Mana system (pool, payment, floating)
- [ ] Zone transitions (library → hand → battlefield/graveyard)
- [ ] Spell casting (costs, stack, resolution)
- [ ] Storm keyword implementation
- [ ] Minimal UI (hand, battlefield, mana pool, cast button)

**Milestone:** Cast Dark Ritual, Cabal Ritual, Brain Freeze with storm copies

### Phase 2: Iggy Pop Complete (Week 3-4)

**Goal:** All Iggy Pop cards implemented and playable

- [ ] Implement all maindeck cards (see Section 12)
- [ ] Implement key sideboard cards
- [ ] Intuition with pile selection UI
- [ ] Ill-Gotten Gains with card selection UI
- [ ] Cunning Wish with wishboard UI
- [ ] Threshold tracking
- [ ] Graveyard view
- [ ] Action log

**Milestone:** Complete a full IGG loop and win with Brain Freeze

### Phase 3: Fork/Replay (Week 5-6)

**Goal:** Branch decision trees and rewind

- [ ] Event history tracking
- [ ] Fork creation
- [ ] Rewind to event
- [ ] Branch switching
- [ ] Fork visualization in action log
- [ ] Save snapshot as puzzle

**Milestone:** Fork at Intuition, try two different piles, compare results

### Phase 4: Hand Sculpting & Setup (Week 7)

**Goal:** Configure starting conditions

- [ ] Setup screen UI
- [ ] Deck selection (load from EDN)
- [ ] Hand sculpting interface
- [ ] "Must contain" card selection
- [ ] Sideboard integration
- [ ] Mulligan decisions

**Milestone:** Start game with LED + IGG + Ritual in hand

### Phase 5: Bot System (Week 8-9)

**Goal:** Practice against simplified opponents

- [ ] Bot protocol implementation
- [ ] Goldfish bot (clock only)
- [ ] Burn bot (bolts + clock)
- [ ] Control bot (counterspells)
- [ ] Discard bot (Duress effects)
- [ ] Manual override mode
- [ ] Bot action UI integration

**Milestone:** Win through 2 Duress effects from discard bot

### Phase 6: Tactics & Polish (Week 10-11)

**Goal:** Save, load, and drill puzzles

- [ ] Puzzle data structure
- [ ] localStorage persistence
- [ ] Puzzle browser UI
- [ ] EDN export/import
- [ ] Success tracking
- [ ] PWA setup (service worker, offline)
- [ ] UI polish and mobile responsiveness

**Milestone:** Create puzzle, close browser, reload, solve puzzle

### Phase 7: Cloud Sync (Future)

- [ ] Supabase integration
- [ ] User authentication
- [ ] Puzzle upload/download
- [ ] Public puzzle library
- [ ] Community features

---

## 12. Starter Deck: Iggy Pop

### Decklist

```
Maindeck (60):
4 Dark Ritual
4 Cabal Ritual
4 Lotus Petal
4 Lion's Eye Diamond
4 Ill-Gotten Gains
4 Careful Study
3 Mental Note
1 Deep Analysis
3 Intuition
3 Merchant Scroll
3 Cunning Wish
1 Brain Freeze
1 Recoup
1 Vision Charm
4 Urza's Bauble
4 Polluted Delta
4 Gemstone Mine
3 City of Brass
1 City of Traitors
3 Island
1 Swamp

Sideboard (15):
1 Brain Freeze
1 Intuition
1 Meditate
1 Chain of Vapor
1 Hurkyl's Recall
1 Hunting Pack
1 Tormod's Crypt
1 Abeyance
2 Orim's Chant
3 Cabal Therapy
2 Xantid Swarm
```

### Card Implementations

#### Mana Sources

```clojure
;; Lands
(def island
  {:card/id :island
   :card/name "Island"
   :card/types #{:land}
   :card/subtypes #{:island}
   :card/supertypes #{:basic}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:blue 1}}]}]})

(def swamp
  {:card/id :swamp
   :card/name "Swamp"
   :card/types #{:land}
   :card/subtypes #{:swamp}
   :card/supertypes #{:basic}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:black 1}}]}]})

(def polluted-delta
  {:card/id :polluted-delta
   :card/name "Polluted Delta"
   :card/types #{:land}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:cost/tap true
                                    :cost/pay-life 1
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :tutor
                                        :effect/zone :library
                                        :effect/destination :battlefield
                                        :effect/filter {:or [{:subtype :island}
                                                             {:subtype :swamp}]}
                                        :effect/tapped true}]}]})

(def gemstone-mine
  {:card/id :gemstone-mine
   :card/name "Gemstone Mine"
   :card/types #{:land}
   :card/enters-with {:counters {:mining 3}}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true
                                    :cost/remove-counter {:type :mining :count 1}}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:choice 1}
                                        :effect/choice-type :any-color}]}]
   :card/triggers [{:trigger/type :state-based
                    :trigger/condition {:no-counters :mining}
                    :trigger/effects [{:effect/type :sacrifice :effect/target :self}]}]})

(def city-of-brass
  {:card/id :city-of-brass
   :card/name "City of Brass"
   :card/types #{:land}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :damage
                                        :effect/amount 1
                                        :effect/target :controller}
                                       {:effect/type :add-mana
                                        :effect/mana {:choice 1}
                                        :effect/choice-type :any-color}]}]})

(def city-of-traitors
  {:card/id :city-of-traitors
   :card/name "City of Traitors"
   :card/types #{:land}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:colorless 2}}]}]
   :card/triggers [{:trigger/type :on-land-play
                    :trigger/condition {:controller-played-another-land true}
                    :trigger/effects [{:effect/type :sacrifice :effect/target :self}]}]})

;; Artifacts
(def lotus-petal
  {:card/id :lotus-petal
   :card/name "Lotus Petal"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:cost/sacrifice :self}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:choice 1}
                                        :effect/choice-type :any-color}]}]})

(def lions-eye-diamond
  {:card/id :lions-eye-diamond
   :card/name "Lion's Eye Diamond"
   :card/cmc 0
   :card/types #{:artifact}
   :card/text "Sacrifice LED, Discard your hand: Add three mana of any one color. Activate only as an instant."
   :card/abilities [{:ability/type :mana
                     :ability/timing :instant-speed  ; Special LED timing
                     :ability/cost {:cost/sacrifice :self
                                    :cost/discard-hand true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:choice 3}
                                        :effect/choice-type :one-color}]}]})

(def urzas-bauble
  {:card/id :urzas-bauble
   :card/name "Urza's Bauble"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:cost/tap true
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :look-at-hand
                                        :effect/target :chosen-player}
                                       {:effect/type :draw-delayed
                                        :effect/count 1
                                        :effect/timing :next-upkeep}]}]})
```

#### Rituals

```clojure
(def dark-ritual
  {:card/id :dark-ritual
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})

(def cabal-ritual
  {:card/id :cabal-ritual
   :card/name "Cabal Ritual"
   :card/cmc 2
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}. Threshold — Add {B}{B}{B}{B}{B} instead."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}
                   :effect/condition {:condition/type :threshold-not-met}}
                  {:effect/type :add-mana
                   :effect/mana {:black 5}
                   :effect/condition {:condition/type :threshold}}]})
```

#### Card Selection / Draw

```clojure
(def careful-study
  {:card/id :careful-study
   :card/name "Careful Study"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards."
   :card/effects [{:effect/type :draw :effect/count 2}
                  {:effect/type :discard :effect/count 2 :effect/choice :controller}]})

(def mental-note
  {:card/id :mental-note
   :card/name "Mental Note"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Mill 2, then draw a card."
   :card/effects [{:effect/type :mill :effect/count 2 :effect/target :controller}
                  {:effect/type :draw :effect/count 1}]})

(def deep-analysis
  {:card/id :deep-analysis
   :card/name "Deep Analysis"
   :card/cmc 4
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards. Flashback—{1}{U}, Pay 3 life."
   :card/effects [{:effect/type :draw :effect/count 2}]
   :card/flashback {:cost {:generic 1 :blue 1}
                    :additional-cost {:pay-life 3}}})

(def intuition
  {:card/id :intuition
   :card/name "Intuition"
   :card/cmc 3
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Search your library for 3 cards. Opponent chooses 1 for your hand, rest go to graveyard."
   :card/effects [{:effect/type :tutor-intuition
                   :effect/search-count 3
                   :effect/opponent-chooses 1
                   :effect/remainder-to :graveyard}]})

(def merchant-scroll
  {:card/id :merchant-scroll
   :card/name "Merchant Scroll"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Search your library for a blue instant and put it in your hand."
   :card/effects [{:effect/type :tutor
                   :effect/zone :library
                   :effect/destination :hand
                   :effect/filter {:color :blue :type :instant}
                   :effect/reveal true}]})

(def cunning-wish
  {:card/id :cunning-wish
   :card/name "Cunning Wish"
   :card/cmc 3
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose an instant from outside the game, put it in hand. Exile Cunning Wish."
   :card/effects [{:effect/type :wish
                   :effect/filter {:type :instant}
                   :effect/destination :hand}
                  {:effect/type :exile-self}]})
```

#### Engine Card

```clojure
(def ill-gotten-gains
  {:card/id :ill-gotten-gains
   :card/name "Ill-Gotten Gains"
   :card/cmc 4
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Exile IGG. Each player discards their hand, then returns up to 3 cards from their graveyard to their hand."
   :card/effects [{:effect/type :exile-self}
                  {:effect/type :each-player
                   :effect/player-effects 
                   [{:effect/type :discard-hand}
                    {:effect/type :return-from-graveyard
                     :effect/count {:up-to 3}
                     :effect/destination :hand
                     :effect/choice :that-player}]}]})
```

#### Win Conditions

```clojure
(def brain-freeze
  {:card/id :brain-freeze
   :card/name "Brain Freeze"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Target player mills 3. Storm."
   :card/effects [{:effect/type :mill
                   :effect/count 3
                   :effect/target :chosen-player}]})
```

#### Utility

```clojure
(def recoup
  {:card/id :recoup
   :card/name "Recoup"
   :card/cmc 2
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Target sorcery card in your graveyard gains flashback until end of turn. Flashback {3}{R}."
   :card/effects [{:effect/type :grant-flashback
                   :effect/target :sorcery-in-graveyard
                   :effect/duration :end-of-turn}]
   :card/flashback {:cost {:generic 3 :red 1}}})

(def vision-charm
  {:card/id :vision-charm
   :card/name "Vision Charm"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one: Target player mills 4; or phase out target artifact; or choose land type A and B, all A become B until end of turn."
   :card/effects [{:effect/type :modal
                   :effect/modes
                   [{:mode/name "Mill"
                     :mode/effects [{:effect/type :mill
                                     :effect/count 4
                                     :effect/target :chosen-player}]}
                    {:mode/name "Phase Out"
                     :mode/effects [{:effect/type :phase-out
                                     :effect/target :chosen-artifact}]}
                    {:mode/name "Land Type"
                     :mode/effects [{:effect/type :change-land-types
                                     :effect/duration :end-of-turn}]}]}]})
```

#### Key Sideboard Cards

```clojure
(def orims-chant
  {:card/id :orims-chant
   :card/name "Orim's Chant"
   :card/cmc 1
   :card/colors #{:white}
   :card/types #{:instant}
   :card/text "Kicker {W}. Target player can't cast spells this turn. If kicked, creatures can't attack."
   :card/effects [{:effect/type :silence
                   :effect/target :chosen-player
                   :effect/duration :end-of-turn}]
   :card/kicker {:cost {:white 1}
                 :effects [{:effect/type :prevent-attacks
                            :effect/duration :end-of-turn}]}})

(def abeyance
  {:card/id :abeyance
   :card/name "Abeyance"
   :card/cmc 2
   :card/colors #{:white}
   :card/types #{:instant}
   :card/text "Until end of turn, target player can't cast instants or sorceries or activate non-mana abilities. Draw a card."
   :card/effects [{:effect/type :restrict-spells
                   :effect/target :chosen-player
                   :effect/spell-types #{:instant :sorcery}
                   :effect/duration :end-of-turn}
                  {:effect/type :restrict-abilities
                   :effect/target :chosen-player
                   :effect/ability-types #{:activated}
                   :effect/except #{:mana}
                   :effect/duration :end-of-turn}
                  {:effect/type :draw :effect/count 1}]})

(def chain-of-vapor
  {:card/id :chain-of-vapor
   :card/name "Chain of Vapor"
   :card/cmc 1
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Return target nonland permanent to its owner's hand. Then that permanent's controller may sacrifice a land. If they do, they may copy this spell and choose a new target."
   :card/effects [{:effect/type :bounce
                   :effect/target :chosen-nonland-permanent}
                  {:effect/type :chain-offer
                   :effect/cost {:sacrifice-land true}
                   :effect/copy-spell true}]})

(def hurkyls-recall
  {:card/id :hurkyls-recall
   :card/name "Hurkyl's Recall"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Return all artifacts target player owns to their hand."
   :card/effects [{:effect/type :mass-bounce
                   :effect/filter {:type :artifact}
                   :effect/target :chosen-player}]})

(def cabal-therapy
  {:card/id :cabal-therapy
   :card/name "Cabal Therapy"
   :card/cmc 1
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Name a nonland card. Target player reveals hand and discards all cards with that name. Flashback—Sacrifice a creature."
   :card/effects [{:effect/type :name-card
                   :effect/restriction :nonland}
                  {:effect/type :reveal-hand
                   :effect/target :chosen-player}
                  {:effect/type :discard-named
                   :effect/target :chosen-player}]
   :card/flashback {:cost {:sacrifice-creature true}}})

(def meditate
  {:card/id :meditate
   :card/name "Meditate"
   :card/cmc 3
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Draw 4 cards. Skip your next turn."
   :card/effects [{:effect/type :draw :effect/count 4}
                  {:effect/type :skip-turn :effect/target :controller}]})

(def hunting-pack
  {:card/id :hunting-pack
   :card/name "Hunting Pack"
   :card/cmc 7
   :card/colors #{:green}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Create a 4/4 green Beast creature token. Storm."
   :card/effects [{:effect/type :create-token
                   :effect/token {:name "Beast"
                                  :power 4
                                  :toughness 4
                                  :colors #{:green}
                                  :types #{:creature}
                                  :subtypes #{:beast}}}]})

(def tormods-crypt
  {:card/id :tormods-crypt
   :card/name "Tormod's Crypt"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:cost/tap true
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :exile-graveyard
                                        :effect/target :chosen-player}]}]})

(def xantid-swarm
  {:card/id :xantid-swarm
   :card/name "Xantid Swarm"
   :card/cmc 1
   :card/colors #{:green}
   :card/types #{:creature}
   :card/subtypes #{:insect}
   :card/power 0
   :card/toughness 1
   :card/keywords #{:flying}
   :card/triggers [{:trigger/type :on-attack
                    :trigger/effects [{:effect/type :silence
                                       :effect/target :defending-player
                                       :effect/duration :end-of-turn}]}]})

;; === Bot Deck Cards (Opponent Interaction) ===

(def gaeas-blessing
  {:card/id :gaeas-blessing
   :card/name "Gaea's Blessing"
   :card/cmc 2
   :card/colors #{:green}
   :card/types #{:sorcery}
   :card/text "Target player shuffles up to 3 cards from graveyard into library. Draw a card. When GB is put into graveyard from library, shuffle graveyard into library."
   :card/effects [{:effect/type :shuffle-cards-into-library
                   :effect/count {:up-to 3}
                   :effect/from :graveyard
                   :effect/target :chosen-player}
                  {:effect/type :draw :effect/count 1}]
   :card/triggers [{:trigger/type :zone-change
                    :trigger/from :library
                    :trigger/to :graveyard
                    :trigger/effects [{:effect/type :shuffle-graveyard-into-library
                                       :effect/target :owner}]}]})

(def counterspell
  {:card/id :counterspell
   :card/name "Counterspell"
   :card/cmc 2
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Counter target spell."
   :card/effects [{:effect/type :counter-spell
                   :effect/target :chosen-spell}]})

(def duress
  {:card/id :duress
   :card/name "Duress"
   :card/cmc 1
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Target opponent reveals hand. You choose a noncreature, nonland card. They discard it."
   :card/effects [{:effect/type :reveal-hand
                   :effect/target :opponent}
                  {:effect/type :choose-and-discard
                   :effect/target :opponent
                   :effect/filter {:not #{:creature :land}}}]})

(def lightning-bolt
  {:card/id :lightning-bolt
   :card/name "Lightning Bolt"
   :card/cmc 1
   :card/colors #{:red}
   :card/types #{:instant}
   :card/text "Deal 3 damage to any target."
   :card/effects [{:effect/type :damage
                   :effect/amount 3
                   :effect/target :any}]})
```

---

## 13. Testing Strategy

The architecture—pure functions, immutable state, event sourcing—lends itself to straightforward testing. This section outlines the testing approach.

### Testing Philosophy

**Test the engine, trust the UI.** The game engine, effect interpreter, and subscriptions are pure functions that deserve thorough testing. Reagent components are thin wrappers over subscriptions; if subscriptions are correct, the UI follows.

**Leverage immutability.** Deterministic replay is a feature *and* a testing tool. If the same events produce different states, something is broken.

**Data-driven tests for data-driven cards.** Cards are data; card tests should be data too. Define scenarios as EDN, let a test runner interpret them.

### Test Categories

#### 1. Effect Interpreter Tests

The effect interpreter is the core of the engine. Each effect type gets unit tests verifying the state transformation:

```clojure
(deftest add-mana-effect
  (let [db (-> (empty-db)
               (set-mana-pool :player-1 {:black 0}))
        result (execute-effect db :player-1
                 {:effect/type :add-mana
                  :effect/mana {:black 3}})]
    (is (= 3 (get-in result [:players :player-1 :mana-pool :black])))))

(deftest threshold-condition
  (let [db-below (set-graveyard-count (empty-db) :player-1 6)
        db-at    (set-graveyard-count (empty-db) :player-1 7)]
    (is (false? (check-condition db-below :player-1 {:condition/type :threshold})))
    (is (true?  (check-condition db-at :player-1 {:condition/type :threshold})))))
```

#### 2. Property-Based Tests

Use `test.check` to verify invariants hold across random game sequences:

| Invariant | Description |
|-----------|-------------|
| Zone exclusivity | Each card exists in exactly one zone |
| Mana non-negative | Mana pool values ≥ 0 |
| Storm monotonic | Storm count only increases within a turn |
| Card conservation | Total cards across all zones = starting deck size |
| Replay determinism | Same events → same final state |

```clojure
(defspec replay-is-deterministic 100
  (prop/for-all [events (gen-game-events)]
    (let [state-1 (reduce apply-event (initial-db) events)
          state-2 (reduce apply-event (initial-db) events)]
      (= state-1 state-2))))
```

Property-based tests catch edge cases that example-based tests miss—especially around zone transitions, mana payment edge cases, and stack interactions.

#### 3. Card Scenario Tests

Define card tests as data. Each scenario specifies setup, action, and expected outcome:

```clojure
(def card-scenarios
  [{:name "Dark Ritual adds BBB"
    :setup {:mana-pool {:black 1}}
    :action [:cast :dark-ritual]
    :expect {:mana-pool {:black 3}}}  ; paid 1, gained 3

   {:name "Cabal Ritual without threshold"
    :setup {:mana-pool {:black 2}
            :graveyard-count 4}
    :action [:cast :cabal-ritual]
    :expect {:mana-pool {:black 3}}}  ; paid 2, gained 3

   {:name "Cabal Ritual with threshold"
    :setup {:mana-pool {:black 2}
            :graveyard-count 7}
    :action [:cast :cabal-ritual]
    :expect {:mana-pool {:black 5}}}])  ; paid 2, gained 5
```

A single test runner interprets all scenarios. Adding a new card means adding scenario data, not writing test code.

#### 4. Subscription Tests

re-frame subscriptions are pure functions from app-db to derived values. Test them directly:

```clojure
(deftest castable-cards-sub
  (let [db (-> (empty-db)
               (add-to-hand :player-1 :dark-ritual)
               (add-to-hand :player-1 :cabal-ritual)
               (set-mana-pool :player-1 {:black 1}))]
    ;; Can cast Dark Ritual (B), cannot cast Cabal Ritual (1B)
    (is (= #{:dark-ritual} (subs/castable-cards db :player-1)))))

(deftest storm-count-sub
  (let [db (-> (empty-db)
               (apply-event [:cast-spell :lotus-petal])
               (apply-event [:cast-spell :dark-ritual]))]
    (is (= 2 (subs/storm-count db)))))
```

#### 5. Fork/Replay Tests

The fork/replay system is core functionality. Test it explicitly:

```clojure
(deftest fork-preserves-history
  (let [game (-> (new-game)
                 (apply-events [...ten events...])
                 (create-fork "test fork" 5))]
    ;; Original history intact
    (is (= 10 (count (get-main-branch-events game))))
    ;; Fork branches at event 5
    (is (= 5 (:fork/branch-point (current-fork game))))))

(deftest rewind-and-diverge
  (let [game-a (-> (new-game)
                   (apply-events [e1 e2 e3 e4 e5]))
        game-b (-> game-a
                   (rewind-to 3)
                   (apply-events [e4' e5']))]
    ;; Different final states
    (is (not= (get-state game-a) (get-state game-b)))
    ;; But shared history up to branch point
    (is (= (take 3 (get-events game-a))
           (take 3 (get-events game-b))))))
```

### What We Don't Test

- **UI component rendering** — Reagent components are thin; testing subscriptions covers the logic.
- **End-to-end browser tests** — High maintenance, low signal for a single-player tool.
- **Visual regression** — Overkill for an MVP. Consider later if UI churn becomes a problem.

### Test Infrastructure

| Tool | Purpose |
|------|---------|
| `cljs.test` | Built-in test framework |
| `test.check` | Property-based testing |
| `shadow-cljs` | Test runner integration (`shadow-cljs watch test`) |

### Testing Phases

Testing effort should match implementation phases:

| Phase | Testing Focus |
|-------|---------------|
| Phase 1 (Core Engine) | Effect interpreter, mana system, zone transitions |
| Phase 2 (Iggy Pop) | Card scenarios for all implemented cards |
| Phase 3 (Fork/Replay) | Fork/replay determinism, branch operations |
| Phase 4 (Hand Sculpting) | Setup state generation |
| Phase 5 (Bots) | Bot decision logic |
| Phase 6 (Tactics) | Puzzle serialization/deserialization |

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| **Goldfish** | Practice against a non-interactive opponent |
| **Storm** | Keyword that copies a spell for each spell cast before it this turn |
| **Threshold** | Having 7+ cards in your graveyard |
| **IGG** | Ill-Gotten Gains |
| **LED** | Lion's Eye Diamond |
| **Fork** | Create a branch in the game history to try a different line |
| **Flores Technique** | Starting practice games with specific cards in hand |

---

## Appendix B: References

- [The EPIC Storm](https://theepicstorm.com/) - Legacy storm resources
- [Premodern](https://premodernmagic.com/) - Format rules and resources
- [re-frame](https://day8.github.io/re-frame/) - ClojureScript framework docs
- [Datascript](https://github.com/tonsky/datascript) - In-memory database docs

---

## Appendix C: Firebase vs Supabase Deep Dive

Since cloud sync is a future feature, we have time to make this decision carefully. Here's the analysis:

### The Core Question for Fizzle

Our use case is specific:
- **Primary mode**: Offline-first local practice tool
- **Future feature**: Share puzzles/tactics with community
- **Data shape**: Game states (complex nested structures), puzzles, user preferences
- **Query patterns**: Mostly key-value lookups, occasional "list puzzles by tag"

### Comparison Matrix

| Factor | Firebase | Supabase | Winner for Us |
|--------|----------|----------|---------------|
| **Offline Support** | Built-in, battle-tested. One line: `enablePersistence()`. Automatic sync. | No native offline. Requires PowerSync or custom solution. | 🏆 Firebase |
| **Data Model Fit** | NoSQL documents. Game state as JSON blob works well. | PostgreSQL tables. Would need JSONB column for game state. | Firebase (slight edge) |
| **ClojureScript Integration** | Several wrappers exist (re-frame-firebase, firemore). Direct JS interop works fine. | No wrappers. Direct JS interop via `@supabase/supabase-js`. | Tie |
| **Query Power** | Limited. No joins. Would need denormalization. | Full SQL. Complex puzzle queries trivial. | 🏆 Supabase |
| **Vendor Lock-in** | High. Proprietary. Migration painful. | Low. Open source. Self-hostable. Standard Postgres. | 🏆 Supabase |
| **Pricing Predictability** | Pay per read/write. Can spike unexpectedly. | Tiered. More predictable. | 🏆 Supabase |
| **Free Tier** | Generous: 1GB storage, 50K reads/day | Decent: 500MB, unlimited API | Firebase (slight edge) |
| **Maturity** | 10+ years. Very stable. | 5 years. Rapidly improving. | Firebase |
| **Community/Ecosystem** | Huge. Tons of examples. | Growing fast. Less ClojureScript-specific content. | Firebase |

### The Offline Problem

This is the critical differentiator. Firebase's offline support is genuinely excellent:

```javascript
// Firebase: One line enables offline
firebase.firestore().enablePersistence()

// Now all reads/writes work offline automatically
// Syncs when back online. Conflict resolution built-in.
```

Supabase has **no equivalent**. Their official stance is "use PowerSync" (a third-party service) or build your own with IndexedDB + custom sync logic. For a practice tool where offline is the primary use case, this is a significant gap.

**However**, for Fizzle specifically:

1. **v1 is offline-only anyway** — We're using localStorage/IndexedDB directly
2. **Cloud sync is for sharing puzzles** — Not for core gameplay
3. **Puzzle data is small** — We can cache aggressively on the client
4. **No real-time collaboration needed** — Users don't edit puzzles simultaneously

This means Supabase's offline weakness matters less for our use case than it would for, say, a collaborative note-taking app.

### ClojureScript Integration Reality

**Firebase:**
- `re-frame-firebase` exists but is dated (last updated 2021)
- `firemore` is more modern and has nice Clojure idioms
- Direct JS interop via shadow-cljs works well
- Example:

```clojure
(ns fizzle.firebase
  (:require ["firebase/app" :as firebase]
            ["firebase/firestore" :as firestore]))

(defn init! [config]
  (firebase/initializeApp (clj->js config)))

(defn save-puzzle! [puzzle]
  (-> (firestore/getFirestore)
      (firestore/collection "puzzles")
      (firestore/addDoc (clj->js puzzle))))
```

**Supabase:**
- No ClojureScript wrappers exist
- Direct JS interop works identically well:

```clojure
(ns fizzle.supabase
  (:require ["@supabase/supabase-js" :refer [createClient]]))

(def client (createClient supabase-url supabase-key))

(defn save-puzzle! [puzzle]
  (-> client
      (.from "puzzles")
      (.insert (clj->js puzzle))))
```

**Verdict**: Both work fine with shadow-cljs. No meaningful difference.

### Recommendation

**Start with localStorage + EDN files for v1.**

This gives us:
- Zero external dependencies
- True offline-first
- Portable puzzle format (EDN files can be shared via gist, email, etc.)
- Time to evaluate as both platforms evolve

**For v2 cloud sync, lean toward Supabase because:**

1. **Open source alignment** — Clojure community values this
2. **SQL for puzzle queries** — "Find puzzles tagged 'igg-line' with difficulty > 3" is trivial
3. **Self-hosting option** — Community could run their own instance
4. **Data portability** — Easy to migrate if needed
5. **We can solve offline ourselves** — Since v1 is localStorage-based anyway, we already have offline. Cloud sync just needs to push/pull puzzle EDN blobs.

**Practical architecture for v2:**

```
┌─────────────────────────────────────────────────────┐
│                    Browser                          │
├─────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────────────────┐    │
│  │ Datascript  │    │  localStorage / IDB     │    │
│  │ (game state)│    │  (puzzle cache)         │    │
│  └─────────────┘    └───────────┬─────────────┘    │
│                                 │                   │
│                      ┌──────────▼──────────┐       │
│                      │   Sync Manager      │       │
│                      │   - Pull new puzzles│       │
│                      │   - Push my puzzles │       │
│                      │   - Conflict: last  │       │
│                      │     write wins      │       │
│                      └──────────┬──────────┘       │
└─────────────────────────────────┼───────────────────┘
                                  │ (when online)
                                  ▼
                         ┌────────────────┐
                         │   Supabase     │
                         │  - puzzles     │
                         │  - users       │
                         │  - puzzle_tags │
                         └────────────────┘
```

This hybrid approach gives us Firebase-quality offline (because we built it) with Supabase's query power and openness for the cloud layer.

---

## Appendix D: Complex Stack Interactions (Gaea's Blessing)

### The Problem

Gaea's Blessing is a common sideboard card against storm. When milled from library to graveyard, it triggers and shuffles the graveyard back into the library. This creates a critical interaction:

```
Storm player casts Brain Freeze (storm count = 20)
→ 21 copies on stack, each targeting opponent
→ Copy 1 resolves: mill 3 (Blessing is card #2)
→ Blessing trigger goes on stack!
→ Trigger resolves: opponent shuffles GY into library
→ Copy 2 resolves: mill 3 (might hit Blessing again!)
→ ...infinite loop potential
```

To beat Gaea's Blessing, the storm player must either:
1. **Build enough storm** that total mill > library size in one resolution
2. **Tormod's Crypt in response** to the Blessing trigger
3. **Win through Hunting Pack** instead of Brain Freeze

This requires modeling:
- Triggered abilities going on the stack
- Players responding to triggers
- Stack ordering (LIFO)
- The shuffle resolving mid-storm-copies

### Updated Stack Model

The simplified stack from the original design won't work. We need:

```clojure
;; Stack entry types
(def stack-entry-types
  #{:spell          ; Cast from hand
    :spell-copy     ; Storm copies, Fork effects
    :activated-ability
    :triggered-ability})

;; Stack entry structure
{:entry/id (uuid)
 :entry/type :triggered-ability
 :entry/source object-id        ; Gaea's Blessing in graveyard
 :entry/controller player-id
 :entry/effects [...]
 :entry/targets [...]
 :entry/can-be-responded-to? true}  ; false for mana abilities

;; Triggered ability definition (Gaea's Blessing)
{:trigger/type :zone-change
 :trigger/from :library
 :trigger/to :graveyard
 :trigger/effects [{:effect/type :shuffle-graveyard-into-library
                    :effect/target :owner}]}
```

### Stack Resolution Flow

```clojure
(defn resolve-stack-top
  "Resolve the top item on the stack, checking for triggers."
  [db]
  (let [top-entry (peek-stack db)
        db-after-resolve (execute-stack-entry db top-entry)
        new-triggers (check-triggers db db-after-resolve)]
    (if (seq new-triggers)
      ;; Triggers go on stack, APNAP order (Active Player, Non-Active Player)
      (reduce #(push-stack %1 %2) db-after-resolve new-triggers)
      db-after-resolve)))

(defn check-triggers
  "Check for any triggered abilities from state change."
  [db-before db-after]
  (let [zone-changes (diff-zones db-before db-after)]
    (for [change zone-changes
          :let [obj (get-object db-after (:object-id change))
                card (get-card db-after obj)
                triggers (:card/triggers card)]
          trigger triggers
          :when (trigger-matches? trigger change)]
      (create-trigger-entry trigger obj))))

;; Gaea's Blessing trigger check
(defn trigger-matches?
  [{:keys [trigger/type trigger/from trigger/to]} zone-change]
  (and (= type :zone-change)
       (= from (:from zone-change))
       (= to (:to zone-change))))
```

### Priority System (Simplified)

We don't need full MTG priority, but we need enough for "respond to trigger":

```clojure
;; After anything goes on the stack, check if player wants to respond
(defn offer-priority
  "Offer the player a chance to respond to the stack."
  [db]
  (if (empty? (get-stack db))
    db  ; Nothing on stack, proceed
    (assoc db :pending-choice
           {:type :priority-decision
            :player (get-priority-player db)
            :options [:pass :respond]
            :stack-top (peek-stack db)})))

;; UI shows:
;; "Gaea's Blessing trigger on stack. Shuffle graveyard into library."
;; [Pass] [Respond with Tormod's Crypt]
```

### Tormod's Crypt Interaction

```clojure
(def tormods-crypt
  {:card/id :tormods-crypt
   :card/name "Tormod's Crypt"
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/timing :instant-speed  ; Can respond to triggers!
                     :ability/cost {:cost/tap true
                                    :cost/sacrifice :self}
                     :ability/effects [{:effect/type :exile-graveyard
                                        :effect/target :chosen-player}]}]})

;; When Crypt resolves BEFORE Blessing trigger:
;; 1. Blessing is exiled (it was in the graveyard)
;; 2. Blessing trigger fizzles (source no longer exists? Or still resolves but GY is empty)

;; Note: In actual MTG rules, the Blessing trigger still resolves,
;; but shuffling an empty graveyard does nothing useful.
;; Critically: Blessing itself got exiled, so future mills don't trigger it.
```

### Implementation Priority

For Phase 2 (Iggy Pop complete), we need:

1. **Basic triggered abilities** — Just Gaea's Blessing style "on zone change" triggers
2. **Stack with multiple entry types** — Spells, copies, triggers
3. **Simple priority** — "Respond? Yes/No" after each stack addition
4. **Trigger checking** — On zone changes, check for matching triggers

We explicitly **don't need** for v1:
- State-based triggers (complex)
- Replacement effects
- Full APNAP ordering (single-player tool)
- Layers (no continuous effects)

### Bot Integration for Blessing

The control/anti-storm bot should be able to represent Gaea's Blessing:

```clojure
(def anti-storm-control-deck
  (concat
    (repeat 20 island)
    (repeat 12 counterspell)
    (repeat 4 gaeas-blessing)  ; The key anti-storm tech
    (repeat 24 filler-cards)))

;; Bot behavior when Blessing triggers:
;; - Always let it resolve (good for bot)
;; - Bot doesn't need to make choices for Blessing

;; Player must decide: try to Crypt in response, or accept the shuffle
```

### Test Scenarios

```clojure
;; Test: Brain Freeze kills through single Blessing
(deftest brain-freeze-vs-single-blessing
  (let [game (-> (new-game)
                 (setup-opponent-library 
                   ;; 20 cards, Blessing at position 5
                   (concat (repeat 4 :filler)
                           [:gaeas-blessing]
                           (repeat 15 :filler)))
                 (set-storm-count 20)
                 (cast-brain-freeze :target :opponent))]
    ;; After all copies resolve...
    ;; Mill 3 * 21 = 63 cards (if no interruption)
    ;; But Blessing triggers when milled, shuffles back
    ;; With 20-card library, we mill 15 before hitting Blessing
    ;; Blessing shuffles ~15 cards back, we have 6 copies left
    ;; 6 * 3 = 18 more mills, might hit Blessing again!
    ;; This is actually a race condition / probability problem
    (is (= :lost (:game/result game)))))  ; Can't win through 1 Blessing with storm 20

(deftest crypt-beats-blessing
  (let [game (-> (new-game)
                 (put-on-battlefield :tormods-crypt)
                 (setup-opponent-library [...blessing at position 5...])
                 (set-storm-count 10)
                 (cast-brain-freeze :target :opponent)
                 ;; After first copy resolves, Blessing triggers
                 ;; Player activates Crypt in response
                 (respond-with :activate-crypt :target :opponent))]
    ;; Crypt exiles their graveyard (including Blessing)
    ;; Blessing trigger resolves but shuffles empty GY
    ;; Remaining Brain Freeze copies mill them out
    (is (= :won (:game/result game)))))
```

This is exactly the kind of interaction that makes storm practice interesting and justifies building a dedicated tool rather than just using Cockatrice.

---

*Document version: 0.1.1-draft*
*Updated: January 2026*
*For use with Claude Code to implement Fizzle*
