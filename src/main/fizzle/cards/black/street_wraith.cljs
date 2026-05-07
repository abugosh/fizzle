(ns fizzle.cards.black.street-wraith
  "Street Wraith card definition.

   Street Wraith: {3}{B}{B} - 3/4 Creature — Wraith
   Swampwalk
   Cycling—Pay 2 life. (Pay 2 life, Discard this card: Draw a card.)")


(def card
  {:card/id :street-wraith
   :card/name "Street Wraith"
   :card/cmc 5
   :card/mana-cost {:colorless 3 :black 2}
   :card/colors #{:black}
   :card/types #{:creature}
   :card/subtypes #{:wraith}
   :card/power 3
   :card/toughness 4
   :card/keywords #{:swampwalk}
   :card/text "Swampwalk (This creature can't be blocked as long as defending player controls a Swamp.)\nCycling—Pay 2 life. (Pay 2 life, Discard this card: Draw a card.)"
   :card/abilities [{:ability/id :cycle
                     :ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :pay-life 2}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling—Pay 2 life"}]
   :card/effects []})
