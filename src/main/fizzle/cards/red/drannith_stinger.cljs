(ns fizzle.cards.red.drannith-stinger
  "Drannith Stinger card definition.

   Drannith Stinger: {1}{R} - 2/2 Creature — Human Wizard
   Whenever you cycle another card, this creature deals 1 damage to each opponent.
   Cycling {1} ({1}, Discard this card: Draw a card.)")


(def card
  {:card/id :drannith-stinger
   :card/name "Drannith Stinger"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:creature}
   :card/subtypes #{:human :wizard}
   :card/power 2
   :card/toughness 2
   :card/text "Whenever you cycle another card, Drannith Stinger deals 1 damage to each opponent.\nCycling {1} ({1}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :mana {:colorless 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling {1}"}]
   :card/triggers [{:trigger/type :card-cycled
                    :trigger/filter {:event/controller :self-controller}
                    :trigger/description "deals 1 damage to each opponent"
                    :trigger/effects [{:effect/type :deal-damage
                                       :effect/amount 1
                                       :effect/target :opponent}]}]})
