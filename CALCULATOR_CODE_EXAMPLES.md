# Hypergeometric Calculator — Code Examples & Data Shapes

This document shows the exact data shapes and APIs you'll use when building the calculator.

---

## Deck Data: Setup Phase

### How Decks Are Stored

```clojure
;; From src/main/fizzle/events/setup.cljs:39-74
;; This is the Iggy Pop reference deck

(def iggy-pop-decklist
  {:deck/id :iggy-pop
   :deck/name "Iggy Pop"
   :deck/main [{:card/id :dark-ritual :count 4}
               {:card/id :cabal-ritual :count 4}
               {:card/id :brain-freeze :count 4}
               {:card/id :city-of-brass :count 3}
               {:card/id :island :count 2}
               ;; ... 16 more entries totaling 60 cards
               ]
   :deck/side [{:card/id :merchant-scroll :count 2}
               {:card/id :seal-of-cleansing :count 2}
               ;; ... more entries totaling 15 cards
               ]})
```

**Key Points**:
- Main deck: vector of maps, each with `:card/id :keyword` and `:count N`
- Sideboard: same structure, stored separately
- Total count: 60 main + 15 side (enforced by `::setup-subs/deck-valid?`)

### How to Access Deck Data (Subscriptions)

```clojure
;; From src/main/fizzle/subs/setup.cljs

;; Raw deck vector
@(rf/subscribe [::setup-subs/current-main])
=> [{:card/id :dark-ritual :count 4}
    {:card/id :cabal-ritual :count 4}
    {:card/id :brain-freeze :count 4}
    ;; ... etc
    ]

;; Grouped by card type (for display)
@(rf/subscribe [::setup-subs/current-main-grouped])
=> {:land [{:card/id :island :count 2} ...]
    :instant [{:card/id :opt :count 4} ...]
    :sorcery [{:card/id :brainstorm :count 3} ...]
    ;; ...
    }

;; Just the total count
@(rf/subscribe [::setup-subs/main-count])
=> 60

;; Sideboard as enriched vector
@(rf/subscribe [::setup-subs/current-side-named])
=> [{:card/id :merchant-scroll :card/name "Merchant Scroll" :count 2}
    {:card/id :seal-of-cleansing :card/name "Seal of Cleansing" :count 2}
    ;; ...
    ]
```

### Hand Sculpting: Must-Contain System

```clojure
;; From src/main/fizzle/events/setup.cljs:207-233

;; Stored as simple map: card-id -> desired-count
@(rf/subscribe [::setup-subs/must-contain])
=> {:dark-ritual 1
    :brain-freeze 2}

;; Enriched version (with card names and max copies)
@(rf/subscribe [::setup-subs/must-contain-cards])
=> [{:card/id :dark-ritual
     :card/name "Dark Ritual"
     :count 1  ;; Must-contain count
     :max-count 4}  ;; Available in deck
    {:card/id :brain-freeze
     :card/name "Brain Freeze"
     :count 2
     :max-count 4}]

;; Global constraints from toggle-must-contain-handler:
;; - Max 7 "must-contain" slots total
;; - Cycles: 0 -> 1 -> 2 -> ... -> max -> 0
```

---

## Card Definitions: Name Lookup

### Card Name Lookup Registry

```clojure
;; From src/main/fizzle/engine/deck-parser.cljs:10-14

(def name-lookup
  (into {}
        (map (fn [card]
               [(str/lower-case (:card/name card)) (:card/id card)]))
        cards/all-cards))

;; Example usage:
(get name-lookup "dark ritual") => :dark-ritual
(get name-lookup "opt") => :opt
(get name-lookup "island") => :island
```

### Full Card Definition Example

```clojure
;; Card definitions are in src/main/fizzle/cards/<color>/<card_name>.cljs

{:card/id :dark-ritual
 :card/name "Dark Ritual"
 :card/cmc 1
 :card/mana-cost {:black 1}
 :card/colors #{:black}
 :card/types #{:sorcery}
 :card/text "Add three black mana to your mana pool."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}}]}
```

---

## Game State: Library & Zones

### Zone Subscription: Player Zones

```clojure
;; From src/main/fizzle/subs/game.cljs:372-384

@(rf/subscribe [::subs/player-zone-counts])
=> {:graveyard 7
    :library 51  ;; Total cards remaining in deck
    :exile 0
    :threshold? true}  ;; GY >= 7

@(rf/subscribe [::subs/opponent-zone-counts])
=> {:graveyard 2
    :library 48
    :exile 0}
```

### Library Objects (Full Game State)

```clojure
;; From src/main/fizzle/db/queries.cljs:90-101

(def game-db (rf/subscribe [::subs/game-db]))

(queries/get-objects-in-zone @game-db :player-1 :library)
=> [{:object/id "uuid-1234"
     :object/zone :library
     :object/owner <entity-ref>
     :object/controller <entity-ref>
     :object/position 0
     :object/card {
       :card/id :dark-ritual
       :card/name "Dark Ritual"
       :card/cmc 1
       :card/types #{:sorcery}
       ;; ... all card fields
       }}
    {:object/id "uuid-5678"
     :object/zone :library
     :object/position 1
     :object/card {
       :card/id :island
       :card/name "Island"
       :card/cmc 0
       ;; ...
       }}
    ;; ... 49 more cards
    ]

;; Hand (current known cards)
(queries/get-hand @game-db :player-1)
=> [{:object/id "uuid-hand-1"
     :object/zone :hand
     :object/card {
       :card/id :dark-ritual
       :card/name "Dark Ritual"
       ;; ...
       }}
    ;; ... cards in hand
    ]

;; Graveyard (known cards)
(queries/get-objects-in-zone @game-db :player-1 :graveyard)
=> [{:object/id "uuid-gy-1"
     :object/zone :graveyard
     :object/card {
       :card/id :brain-freeze
       ;; ...
       }}
    ;; ... 6 more
    ]
```

---

## Example: Build Calculator Input State

### Pre-Game Calculator (Setup Screen)

```clojure
;; Inputs for deck-wide probability calculation

(let [deck @(rf/subscribe [::setup-subs/current-main])
      must-contain @(rf/subscribe [::setup-subs/must-contain])]

  ;; Build calculator state
  {:total-cards 60
   :deck-entries deck  ;; [{:card/id :keyword :count N} ...]
   :must-contain must-contain  ;; {:card/id -> required-count}
   :question "What's the probability I open with 2+ Dark Rituals?"
   ;; User inputs:
   :selected-card :dark-ritual
   :cards-drawn 7  ;; opening hand size
   :copies-wanted 2})

;; With this state, you can:
;; 1. Look up card in deck: (get (into {} deck) :dark-ritual) => {:card/id :dark-ritual :count 4}
;; 2. Compute C(60, 7) * ... (hypergeometric formula)
;; 3. Display result: "52.3% chance of 2+ Dark Rituals in opening hand"
```

### In-Game Calculator (Game Screen)

```clojure
;; Inputs for real-time library odds during play

(let [zones @(rf/subscribe [::subs/player-zone-counts])
      hand @(rf/subscribe [::subs/hand])
      game-db @(rf/subscribe [::subs/game-db])
      human-pid :player-1]

  ;; Build calculator state accounting for known cards
  (let [library-objs (queries/get-objects-in-zone game-db human-pid :library)
        graveyard-objs (queries/get-objects-in-zone game-db human-pid :graveyard)
        hand-objs hand

        ;; Count instances of a specific card
        count-card-in-zone (fn [objs card-id]
                             (count (filter #(= card-id (:card/id (:object/card %))) objs)))

        ;; Look up deck definition (stashed in game state)
        deck-main (:setup/main-deck game-db)
        in-deck-count (->> deck-main
                          (filter #(= :dark-ritual (:card/id %)))
                          first
                          :count)]

    {:total-cards 60
     :cards-left-in-library (count library-objs)
     :in-library (count-card-in-zone library-objs :dark-ritual)
     :in-hand (count-card-in-zone hand-objs :dark-ritual)
     :in-graveyard (count-card-in-zone graveyard-objs :dark-ritual)
     :in-deck-total in-deck-count
     ;; Remaining unknowns:
     :unknowns (- 60 (+ (count hand-objs) (count graveyard-objs) (count library-objs)))

     ;; User inputs:
     :selected-card :dark-ritual
     :draw-until-turn 4  ;; "How many more turns?"
     :copies-wanted 1}))

;; With this state, you can:
;; 1. Remaining in deck: 4 - (in-hand + in-graveyard) = 4 - 1 = 3 copies left
;; 2. Probability of drawing 1+ in next 3 draws: hypergeometric(60, 3, 3, 1+)
;; 3. Display: "85% chance to draw Dark Ritual this turn"
```

---

## Example: Card Selector Input

### How to Build Card Dropdown

```clojure
;; In your calculator view component

(defn card-selector
  []
  (let [deck @(rf/subscribe [::setup-subs/current-main])]
    [:select {:on-change #(dispatch [:calc/select-card (-> % .-target .-value keyword)])}
     [:option "Select card..."]
     (for [{:keys [card/id]} deck
           :let [card-def (engine.cards/get-by-id id)]]
       ^{:key id}
       [:option {:value (name id)}
        (str (:card/name card-def)
             " (" (:count (first (filter #(= id (:card/id %)) deck))) "x)")])]))
```

---

## Module Skeleton: Math Functions

### Setup for Hypergeometric Module

```clojure
;; src/main/fizzle/math/hypergeometric.cljs

(ns fizzle.math.hypergeometric)

;; Precomputed factorials (0-60)
(def ^:private factorial-memo
  (let [facts (make-array 61)]
    (aset facts 0 1)
    (loop [i 1]
      (when (<= i 60)
        (aset facts i (* (aget facts (dec i)) i))
        (recur (inc i))))
    facts))

(defn- factorial [n]
  (when (<= 0 n 60)
    (aget factorial-memo n)))

;; Combination C(n, k) = n! / (k! * (n-k)!)
(defn combination [n k]
  (when (and (<= 0 k n) (<= n 60))
    (/ (factorial n)
       (* (factorial k) (factorial (- n k))))))

;; Hypergeometric PDF: P(X = k)
;; Total cards: N, desired cards in deck: K, cards drawn: n, want exactly k
(defn probability-exactly [total-cards in-deck drawn exactly]
  (when (and (>= in-deck exactly)
             (>= (- total-cards in-deck) (- drawn exactly)))
    (/ (* (combination in-deck exactly)
          (combination (- total-cards in-deck) (- drawn exactly)))
       (combination total-cards drawn))))

;; Hypergeometric CDF: P(X <= k) = sum of PDFs from 0 to k
(defn probability-at-most [total-cards in-deck drawn max-count]
  (reduce +
          (for [k (range (inc (min max-count in-deck)))]
            (probability-exactly total-cards in-deck drawn k))))

;; Convenience: P(X >= k) = 1 - P(X < k)
(defn probability-at-least [total-cards in-deck drawn min-count]
  (- 1 (probability-at-most total-cards in-deck drawn (dec min-count))))

;; Format for display
(defn format-percentage [decimal]
  (str (js/Math.round (* decimal 100 * 10)) "%"))
```

---

## Example: Complete Setup Calculator Component

```clojure
;; Skeleton for calculator view on setup screen

(ns fizzle.views.calculator
  (:require
    [fizzle.math.hypergeometric :as hg]
    [fizzle.subs.setup :as setup-subs]
    [re-frame.core :as rf]))

(defn setup-calculator
  "Probability calculator for deck-wide statistics (pre-game)."
  []
  (let [deck @(rf/subscribe [::setup-subs/current-main])
        main-count @(rf/subscribe [::setup-subs/main-count])
        must-contain @(rf/subscribe [::setup-subs/must-contain])

        selected-card (atom nil)
        cards-drawn (atom 7)
        copies-wanted (atom 1)]

    (fn []
      [:div {:class "p-4 border border-border rounded bg-surface-raised"}
       [:h3 "Opening Hand Odds"]

       ;; Card selector
       [:select {:on-change #(reset! selected-card (keyword (.. % -target -value)))}
        [:option "Select card..."]
        (for [{:keys [card/id count]} deck]
          ^{:key id}
          [:option {:value (name id)} (str (name id) " (" count "x)")])]

       ;; If card selected, show probability
       (when @selected-card
         (let [card-copies (->> deck
                              (filter #(= @selected-card (:card/id %)))
                              first
                              :count)
               prob (hg/probability-at-least main-count card-copies @cards-drawn @copies-wanted)]
           [:div
            [:p (str "Probability of " @copies-wanted "+ in opening " @cards-drawn ": "
                     (hg/format-percentage prob))]
            ;; Breakdown
            (for [k (range (inc card-copies))]
              ^{:key k}
              [:p (str "Exactly " k ": "
                       (hg/format-percentage
                         (hg/probability-exactly main-count card-copies @cards-drawn k)))])]))])))
```

---

## Summary: Data Access Paths

| Phase | What You Want | Subscription/Query | Returns |
|-------|---|---|---|
| Setup | Full deck entries | `::setup-subs/current-main` | `[{:card/id :kw :count N}...]` |
| Setup | Card names | `::setup-subs/current-main-grouped` | `{:land [...] :instant [...]}` |
| Setup | Must-contain | `::setup-subs/must-contain` | `{:card-id -> count}` |
| Setup | Deck size | `::setup-subs/main-count` | `60` (or < if invalid) |
| Game | Zone counts | `::subs/player-zone-counts` | `{:library N :graveyard N :exile N}` |
| Game | Hand cards | `::subs/hand` | `[game-object...]` |
| Game | Library cards | `queries/get-objects-in-zone(db, :player-1, :library)` | `[{:object/card {:card/id :kw...}...}...]` |
| Game | Graveyard | `queries/get-objects-in-zone(db, :player-1, :graveyard)` | `[game-object...]` |

All data is immutable and read-only. No events needed. Perfect for pure calculation.
