(ns fizzle.cards.red.burning-wish
  "Burning Wish card definition.

   Burning Wish: 1R - Sorcery
   You may reveal a sorcery card you own from outside the game
   and put it into your hand. Exile Burning Wish.")


(def card
  {:card/id :burning-wish
   :card/name "Burning Wish"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "You may reveal a sorcery card you own from outside the game and put it into your hand. Exile Burning Wish."
   :card/effects [{:effect/type :exile-self}
                  {:effect/type :tutor
                   :effect/source-zone :sideboard
                   :effect/criteria {:match/types #{:sorcery}}
                   :effect/target-zone :hand
                   :effect/shuffle? false}]})
