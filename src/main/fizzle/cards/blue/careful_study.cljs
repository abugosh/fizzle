(ns fizzle.cards.blue.careful-study
  "Careful Study card definition.

   Careful Study: U - Sorcery
   Draw two cards, then discard two cards.")


(def card
  {:card/id :careful-study
   :card/name "Careful Study"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards."
   :card/effects [{:effect/type :draw
                   :effect/amount 2}
                  {:effect/type :discard
                   :effect/count 2
                   :effect/selection :player}]})
