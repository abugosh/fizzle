(ns fizzle.cards.black.horror-of-the-broken-lands
  "Horror of the Broken Lands card definition.

   Horror of the Broken Lands: {4}{B} - 4/4 Creature — Horror
   Whenever you cycle or discard another card, Horror of the Broken Lands gets +2/+1 until end of turn.
   Cycling {B} ({B}, Discard this card: Draw a card.)")


(def card
  {:card/id :horror-of-the-broken-lands
   :card/name "Horror of the Broken Lands"
   :card/cmc 5
   :card/mana-cost {:colorless 4 :black 1}
   :card/colors #{:black}
   :card/types #{:creature}
   :card/subtypes #{:horror}
   :card/power 4
   :card/toughness 4
   :card/text "Whenever you cycle or discard another card, Horror of the Broken Lands gets +2/+1 until end of turn.\nCycling {B} ({B}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :cycling
                     :ability/zone :hand
                     :ability/cost {:discard-self true :mana {:black 1}}
                     :ability/effects [{:effect/type :draw :effect/amount 1}]
                     :ability/description "Cycling {B}"}]
   :card/triggers [{:trigger/type :card-discarded
                    :trigger/filter {:exclude-self true
                                     :event/controller :self-controller}
                    :trigger/description "gets +2/+1 until end of turn"
                    :trigger/effects [{:effect/type :apply-pt-modifier
                                       :effect/target :self
                                       :effect/power 2
                                       :effect/toughness 1}]}]})
