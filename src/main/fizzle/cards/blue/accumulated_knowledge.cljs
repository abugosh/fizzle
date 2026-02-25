(ns fizzle.cards.blue.accumulated-knowledge
  "Accumulated Knowledge card definition.

   Accumulated Knowledge: Instant
   Draw a card, then draw cards equal to the number of cards named
   Accumulated Knowledge in all graveyards.")


(def card
  {:card/id :accumulated-knowledge
   :card/name "Accumulated Knowledge"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Draw a card, then draw cards equal to the number of cards named Accumulated Knowledge in all graveyards."
   :card/effects [{:effect/type :draw
                   :effect/amount {:dynamic/type :count-named-in-zone
                                   :dynamic/zone :graveyard
                                   :dynamic/plus 1}}]})
