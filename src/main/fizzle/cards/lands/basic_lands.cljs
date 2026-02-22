(ns fizzle.cards.lands.basic-lands
  "Basic land card definitions.

   All five basic lands: Plains, Island, Swamp, Mountain, Forest.
   Each taps for one mana of its color.")


(def plains
  {:card/id :plains
   :card/name "Plains"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/subtypes #{:plains}
   :card/supertypes #{:basic}
   :card/text "{T}: Add {W}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:white 1}}]})


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


(def mountain
  {:card/id :mountain
   :card/name "Mountain"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/subtypes #{:mountain}
   :card/supertypes #{:basic}
   :card/text "{T}: Add {R}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:red 1}}]})


(def forest
  {:card/id :forest
   :card/name "Forest"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/subtypes #{:forest}
   :card/supertypes #{:basic}
   :card/text "{T}: Add {G}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:green 1}}]})


(def cards
  "All five basic lands as a vector."
  [plains island swamp mountain forest])
