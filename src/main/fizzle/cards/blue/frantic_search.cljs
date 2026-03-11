(ns fizzle.cards.blue.frantic-search
  "Frantic Search card definition.

   Frantic Search: {2}{U} - Instant
   Draw two cards, then discard two cards. Untap up to three lands.")


(def card
  {:card/id :frantic-search
   :card/name "Frantic Search"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Draw two cards, then discard two cards. Untap up to three lands."
   :card/effects [{:effect/type :draw
                   :effect/amount 2}
                  {:effect/type :discard
                   :effect/count 2
                   :effect/selection :player}
                  {:effect/type :untap-lands
                   :effect/count 3}]})
