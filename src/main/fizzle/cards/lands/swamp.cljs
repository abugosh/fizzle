(ns fizzle.cards.lands.swamp
  "Swamp card definition.

   Swamp: Basic Land
   {T}: Add {B}.")


(def card
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
