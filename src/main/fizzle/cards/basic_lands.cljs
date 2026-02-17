(ns fizzle.cards.basic-lands
  "Basic land card definitions.

   Plains, Island, Swamp, Mountain, Forest — the five basic lands.
   Each taps for one mana of its color.

   Note: Island and Swamp are already defined in iggy-pop.cljs.
   This namespace provides Plains, Mountain, and Forest for
   bot decks (goldfish uses basic lands).")


;; Plains - Basic land producing white mana
;; T: Add {W}.
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


;; Mountain - Basic land producing red mana
;; T: Add {R}.
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


;; Forest - Basic land producing green mana
;; T: Add {G}.
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
