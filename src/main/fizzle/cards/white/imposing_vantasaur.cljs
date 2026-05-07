(ns fizzle.cards.white.imposing-vantasaur
  "Imposing Vantasaur card definition.

   Imposing Vantasaur: {5}{W} - 3/6 Creature — Dinosaur
   Vigilance
   Cycling {1} ({1}, Discard this card: Draw a card.)")


(def card
  {:card/id :imposing-vantasaur
   :card/name "Imposing Vantasaur"
   :card/cmc 6
   :card/mana-cost {:colorless 5 :white 1}
   :card/colors #{:white}
   :card/types #{:creature}
   :card/subtypes #{:dinosaur}
   :card/power 3
   :card/toughness 6
   :card/keywords #{:vigilance}
   :card/text "Vigilance\nCycling {1} ({1}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :mana {:colorless 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling {1}"}]})
