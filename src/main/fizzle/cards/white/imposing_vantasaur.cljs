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
   :card/cycling {:colorless 1}
   :card/text "Vigilance\nCycling {1} ({1}, Discard this card: Draw a card.)"})
