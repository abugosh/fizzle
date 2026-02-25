(ns fizzle.cards.blue.impulse
  "Impulse card definition.

   Impulse: Instant
   Look at the top four cards of your library. Put one of them into your
   hand and the rest on the bottom of your library in any order.")


(def card
  {:card/id :impulse
   :card/name "Impulse"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Look at the top four cards of your library. Put one of them into your hand and the rest on the bottom of your library in any order."
   :card/effects [{:effect/type :peek-and-select
                   :effect/count 4
                   :effect/select-count 1
                   :effect/selected-zone :hand
                   :effect/remainder-zone :bottom-of-library
                   :effect/order-remainder? true}]})
