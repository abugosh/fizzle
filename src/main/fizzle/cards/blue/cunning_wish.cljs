(ns fizzle.cards.blue.cunning-wish
  "Cunning Wish card definition.

   Cunning Wish: 2U - Instant
   You may reveal an instant card you own from outside the game
   and put it into your hand. Exile Cunning Wish.")


(def card
  {:card/id :cunning-wish
   :card/name "Cunning Wish"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "You may reveal an instant card you own from outside the game and put it into your hand. Exile Cunning Wish."
   :card/effects [{:effect/type :exile-self}
                  {:effect/type :tutor
                   :effect/source-zone :sideboard
                   :effect/criteria {:match/types #{:instant}}
                   :effect/target-zone :hand
                   :effect/shuffle? false}]})
