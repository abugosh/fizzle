(ns fizzle.cards.multicolor.diabolic-vision
  "Diabolic Vision card definition.

   Diabolic Vision: UB - Sorcery
   Look at the top five cards of your library. Put one of them into your
   hand and the rest on top of your library in any order.")


(def card
  {:card/id :diabolic-vision
   :card/name "Diabolic Vision"
   :card/cmc 2
   :card/mana-cost {:blue 1 :black 1}
   :card/colors #{:blue :black}
   :card/types #{:sorcery}
   :card/text "Look at the top five cards of your library. Put one of them into your hand and the rest on top of your library in any order."
   :card/effects [{:effect/type :peek-and-select
                   :effect/count 5
                   :effect/select-count 1
                   :effect/selected-zone :hand
                   :effect/remainder-zone :top-of-library
                   :effect/order-remainder? true}]})
