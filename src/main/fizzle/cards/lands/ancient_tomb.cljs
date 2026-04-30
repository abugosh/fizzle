(ns fizzle.cards.lands.ancient-tomb
  "Ancient Tomb card definition.

   Ancient Tomb: Land
   {T}: Add {C}{C}. Ancient Tomb deals 2 damage to you.")


(def card
  {:card/id :ancient-tomb
   :card/name "Ancient Tomb"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}{C}. Ancient Tomb deals 2 damage to you."

   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 2}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 2
                                        :effect/target :controller}]}]})
