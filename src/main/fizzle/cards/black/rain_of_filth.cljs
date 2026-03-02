(ns fizzle.cards.black.rain-of-filth
  "Rain of Filth card definition.

   Rain of Filth: B - Instant
   Until end of turn, lands you control gain
   'Sacrifice this land: Add {B}.'")


(def card
  {:card/id :rain-of-filth
   :card/name "Rain of Filth"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Until end of turn, lands you control gain 'Sacrifice this land: Add {B}.'"
   :card/effects [{:effect/type :grant-mana-ability
                   :effect/target :controlled-lands
                   :effect/ability {:ability/type :mana
                                    :ability/cost {:sacrifice-self true}
                                    :ability/produces {:black 1}
                                    :ability/effects [{:effect/type :add-mana
                                                       :effect/mana {:black 1}}]}}]})
