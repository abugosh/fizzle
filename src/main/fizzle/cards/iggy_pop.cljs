(ns fizzle.cards.iggy-pop
  "Card definitions for the Iggy Pop storm deck.

   Cards are pure data - the engine interprets their effects.
   This namespace will grow to contain all Iggy Pop maindeck cards.")


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


;; All cards in this namespace for easy import
(def all-cards
  [dark-ritual brain-freeze cabal-ritual city-of-brass gemstone-mine island swamp lotus-petal lions-eye-diamond])
