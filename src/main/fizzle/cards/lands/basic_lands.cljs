(ns fizzle.cards.lands.basic-lands
  "Basic land card definitions.

   Plains, Mountain, Forest — basic lands not already in individual files.
   Each taps for one mana of its color.
   Island and Swamp are in their own files.")


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
  "All basic lands in this file as a vector."
  [plains mountain forest])
