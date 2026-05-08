(ns fizzle.cards.white.drannith-healer
  "Drannith Healer card definition.

   Drannith Healer: {1}{W} - 2/2 Creature — Human Cleric
   Whenever you cycle another card, you gain 1 life.
   Cycling {1} ({1}, Discard this card: Draw a card.)")


(def card
  {:card/id :drannith-healer
   :card/name "Drannith Healer"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :white 1}
   :card/colors #{:white}
   :card/types #{:creature}
   :card/subtypes #{:human :cleric}
   :card/power 2
   :card/toughness 2
   :card/text "Whenever you cycle another card, you gain 1 life.\nCycling {1} ({1}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :mana {:colorless 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling {1}"}]
   :card/triggers [{:trigger/type :card-cycled
                    :trigger/filter {:event/controller :self-controller}
                    :trigger/description "you gain 1 life"
                    :trigger/effects [{:effect/type :gain-life :effect/amount 1}]}]})
