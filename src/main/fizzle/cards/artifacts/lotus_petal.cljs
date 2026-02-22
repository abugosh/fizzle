(ns fizzle.cards.artifacts.lotus-petal
  "Lotus Petal card definition.

   Lotus Petal: 0 - Artifact
   {T}, Sacrifice Lotus Petal: Add one mana of any color.")


(def card
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
