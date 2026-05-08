(ns fizzle.cards.multicolor.monstrous-carabid
  "Monstrous Carabid card definition.

   Monstrous Carabid: {3}{B}{R} - 4/4 Creature — Insect
   This creature attacks each combat if able.
   Cycling {B/R} ({B/R}, Discard this card: Draw a card.)")


(def card
  {:card/id :monstrous-carabid
   :card/name "Monstrous Carabid"
   :card/cmc 5
   :card/mana-cost {:colorless 3 :black 1 :red 1}
   :card/colors #{:black :red}
   :card/types #{:creature}
   :card/subtypes #{:insect}
   :card/power 4
   :card/toughness 4
   :card/keywords #{:must-attack}
   :card/text "Monstrous Carabid attacks each combat if able.\nCycling {B/R} ({B/R}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :mana {:black 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling {B}"}
                    {:ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :mana {:red 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling {R}"}]})
