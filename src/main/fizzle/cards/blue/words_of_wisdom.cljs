(ns fizzle.cards.blue.words-of-wisdom
  "Words of Wisdom card definition.

   Words of Wisdom: 1U - Instant
   You draw two cards, then each other player draws a card.")


(def card
  {:card/id :words-of-wisdom
   :card/name "Words of Wisdom"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "You draw two cards, then each other player draws a card."
   :card/effects [{:effect/type :draw :effect/amount 2}
                  {:effect/type :draw :effect/amount 1 :effect/target :opponent}]})
