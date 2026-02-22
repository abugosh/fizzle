(ns fizzle.cards.lands.island
  "Island card definition.

   Island: Basic Land
   {T}: Add {U}.")


(def card
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
