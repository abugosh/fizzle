(ns fizzle.cards.lands.underground-river
  "Underground River card definition.

   Underground River: Land
   {T}: Add {C}. {T}: Add {U} or {B}. Underground River deals 1 damage to you.")


(def card
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
