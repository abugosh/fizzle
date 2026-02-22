(ns fizzle.cards.lands.gemstone-mine
  "Gemstone Mine card definition.

   Gemstone Mine: Land
   Gemstone Mine enters the battlefield with three mining counters on it.
   {T}, Remove a mining counter from Gemstone Mine: Add one mana of any color.
   If there are no mining counters on Gemstone Mine, sacrifice it.")


(def card
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
