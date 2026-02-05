(ns fizzle.cards.iggy-pop
  "Card definitions for the Iggy Pop storm deck.

   Cards are pure data - the engine interprets their effects.
   This namespace will grow to contain all Iggy Pop maindeck cards."
  (:require
    [fizzle.cards.cephalid-coliseum :as cephalid-coliseum]
    [fizzle.cards.deep-analysis :as deep-analysis]
    [fizzle.cards.ill-gotten-gains :as ill-gotten-gains]
    [fizzle.cards.recoup :as recoup]))


;; Dark Ritual - The foundation of black storm
;; B -> BBB (net +2 black mana)
(def dark-ritual
  {:card/id :dark-ritual
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})


;; Brain Freeze - The storm finisher
;; 1U -> Target player mills 3. Storm.
(def brain-freeze
  {:card/id :brain-freeze
   :card/name "Brain Freeze"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Target player mills 3. Storm."
   :card/effects [{:effect/type :mill
                   :effect/amount 3
                   :effect/target :opponent}]})


;; Cabal Ritual - Threshold-enabled mana ritual
;; 1B -> BBB normally, BBBBB with threshold
(def cabal-ritual
  {:card/id :cabal-ritual
   :card/name "Cabal Ritual"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}. Threshold - Add {B}{B}{B}{B}{B} instead."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]
   :card/conditional-effects {:threshold [{:effect/type :add-mana
                                           :effect/mana {:black 5}}]}})


;; City of Brass - Rainbow land with damage
;; T: Add one mana of any color. City of Brass deals 1 damage to you.
(def city-of-brass
  {:card/id :city-of-brass
   :card/name "City of Brass"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "Whenever City of Brass becomes tapped, it deals 1 damage to you. {T}: Add one mana of any color."

   ;; Trigger: fires when this permanent becomes tapped (ANY tap, not just mana ability)
   :card/triggers [{:trigger/type :becomes-tapped
                    :trigger/description "deals 1 damage to you"
                    :trigger/effects [{:effect/type :deal-damage
                                       :effect/amount 1
                                       :effect/target :controller}]}]

   ;; Mana ability: T: Add one mana of any color
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:any 1}}]}]})


;; City of Traitors - High-yield colorless mana with sacrifice trigger
;; T: Add {C}{C}. When you play another land, sacrifice City of Traitors.
(def city-of-traitors
  {:card/id :city-of-traitors
   :card/name "City of Traitors"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "When you play another land, sacrifice City of Traitors. {T}: Add {C}{C}."

   ;; Trigger: When ANOTHER land enters (not self)
   ;; Uses :exclude-self filter to prevent triggering on own entry
   :card/triggers [{:trigger/type :land-entered
                    :trigger/description "sacrifice City of Traitors"
                    :trigger/filter {:exclude-self true}
                    :trigger/effects [{:effect/type :sacrifice
                                       :effect/target :self}]}]

   ;; Mana ability: T: Add {C}{C}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 2}}]})


;; Gemstone Mine - Rainbow land with counter depletion
;; T, Remove a mining counter: Add one mana of any color.
(def gemstone-mine
  {:card/id :gemstone-mine
   :card/name "Gemstone Mine"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "Gemstone Mine enters the battlefield with three mining counters on it. {T}, Remove a mining counter from Gemstone Mine: Add one mana of any color. If there are no mining counters on Gemstone Mine, sacrifice it."

   ;; ETB effect: add 3 mining counters to this permanent
   :card/etb-effects [{:effect/type :add-counters
                       :effect/counters {:mining 3}
                       :effect/target :self}]

   ;; Mana ability: T, remove mining counter: Add one mana of any color
   ;; Mana production is handled by activate-mana-ability via :ability/produces.
   ;; The conditional sacrifice is an :ability/effects that only fires when no counters remain.
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true
                                    :remove-counter {:mining 1}}
                     :ability/produces {:any 1}
                     :ability/effects [{:effect/type :sacrifice
                                        :effect/target :self
                                        :effect/condition {:condition/type :no-counters
                                                           :condition/counter-type :mining}}]}]})


;; Island - Basic land producing blue mana
;; T: Add {U}.
(def island
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
                     :ability/produces {:blue 1}}]})


;; Swamp - Basic land producing black mana
;; T: Add {B}.
(def swamp
  {:card/id :swamp
   :card/name "Swamp"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/subtypes #{:swamp}
   :card/supertypes #{:basic}
   :card/text "{T}: Add {B}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}}]})


;; Underground River - Pain land producing blue or black mana
;; T: Add {C}. T: Add {U} or {B}. Underground River deals 1 damage to you.
(def underground-river
  {:card/id :underground-river
   :card/name "Underground River"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {U} or {B}. Underground River deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:blue 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


;; Lotus Petal - Zero-cost artifact mana acceleration
;; {T}, Sacrifice Lotus Petal: Add one mana of any color.
(def lotus-petal
  {:card/id :lotus-petal
   :card/name "Lotus Petal"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "{T}, Sacrifice Lotus Petal: Add one mana of any color."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true
                                    :sacrifice-self true}
                     :ability/produces {:any 1}}]})


;; Lion's Eye Diamond - Explosive mana acceleration
;; {T}, Sacrifice Lion's Eye Diamond, Discard your hand: Add three mana of any one color.
;; Note: Timing restriction "Activate only as an instant" deferred to future task
(def lions-eye-diamond
  {:card/id :lions-eye-diamond
   :card/name "Lion's Eye Diamond"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "{T}, Sacrifice Lion's Eye Diamond, Discard your hand: Add three mana of any one color. Activate only as an instant."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :discard-hand true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:any 3}}]}]})


;; Careful Study - Card filtering cantrip
;; U - Sorcery: Draw two cards, then discard two cards.
(def careful-study
  {:card/id :careful-study
   :card/name "Careful Study"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards."
   :card/effects [{:effect/type :draw
                   :effect/amount 2}
                  {:effect/type :discard
                   :effect/count 2
                   :effect/selection :player}]})


;; Mental Note - Self-mill cantrip
;; U - Instant: Put the top two cards of your library into your graveyard, then draw a card.
(def mental-note
  {:card/id :mental-note
   :card/name "Mental Note"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Put the top two cards of your library into your graveyard, then draw a card."
   :card/effects [{:effect/type :mill
                   :effect/amount 2}
                  {:effect/type :draw
                   :effect/amount 1}]})


;; Opt - Invasion cantrip
;; U - Instant: Scry 1, then draw a card.
(def opt
  {:card/id :opt
   :card/name "Opt"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Scry 1, then draw a card."
   :card/effects [{:effect/type :scry
                   :effect/amount 1}
                  {:effect/type :draw
                   :effect/amount 1}]})


;; Merchant Scroll - Blue instant tutor
;; 1U - Sorcery: Search your library for a blue instant card, reveal it,
;; put it into your hand, then shuffle.
(def merchant-scroll
  {:card/id :merchant-scroll
   :card/name "Merchant Scroll"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Search your library for a blue instant card, reveal it, put it into your hand, then shuffle."
   :card/effects [{:effect/type :tutor
                   :effect/criteria {:card/types #{:instant}
                                     :card/colors #{:blue}}
                   :effect/target-zone :hand
                   :effect/shuffle? true}]})


;; Intuition - The power tutor
;; 2U - Instant: Search for 3 cards, choose 1 for hand, rest to graveyard
(def intuition
  {:card/id :intuition
   :card/name "Intuition"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Search your library for three cards and reveal them. Target opponent chooses one. Put that card into your hand and the rest into your graveyard. Then shuffle."
   :card/effects [{:effect/type :tutor
                   :effect/select-count 3
                   :effect/target-zone :hand
                   :effect/pile-choice {:hand 1 :graveyard :rest}}]})


;; Polluted Delta - Fetchland
;; Land - {T}, Sacrifice Polluted Delta: Search your library for an Island or Swamp card,
;; put it onto the battlefield, then shuffle.
(def polluted-delta
  {:card/id :polluted-delta
   :card/name "Polluted Delta"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Sacrifice Polluted Delta: Search your library for an Island or Swamp card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Island or Swamp"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:island :swamp}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


;; All cards in this namespace for easy import
(def all-cards
  [dark-ritual brain-freeze cabal-ritual city-of-brass city-of-traitors
   gemstone-mine island swamp underground-river lotus-petal lions-eye-diamond
   careful-study mental-note opt merchant-scroll intuition polluted-delta
   deep-analysis/deep-analysis cephalid-coliseum/cephalid-coliseum recoup/recoup
   ill-gotten-gains/ill-gotten-gains])
