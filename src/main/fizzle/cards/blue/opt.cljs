(ns fizzle.cards.blue.opt
  "Opt card definition.

   Opt: U - Instant
   Scry 1, then draw a card.")


(def card
  {:card/id :opt
   :card/name "Opt"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Scry 1, then draw a card."
   :card/effects [{:effect/type :scry
                   :effect/amount 1}
                  {:effect/type :draw
                   :effect/amount 1}]})
