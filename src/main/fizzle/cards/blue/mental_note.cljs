(ns fizzle.cards.blue.mental-note
  "Mental Note card definition.

   Mental Note: U - Instant
   Put the top two cards of your library into your graveyard, then draw a card.")


(def card
  {:card/id :mental-note
   :card/name "Mental Note"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Put the top two cards of your library into your graveyard, then draw a card."
   :card/effects [{:effect/type :mill
                   :effect/amount 2}
                  {:effect/type :draw
                   :effect/amount 1}]})
