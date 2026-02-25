(ns fizzle.cards.blue.sleight-of-hand
  "Sleight of Hand card definition.

   Sleight of Hand: Sorcery
   Look at the top two cards of your library. Put one of them into your
   hand and the other on the bottom of your library.")


(def card
  {:card/id :sleight-of-hand
   :card/name "Sleight of Hand"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Look at the top two cards of your library. Put one of them into your hand and the other on the bottom of your library."
   :card/effects [{:effect/type :peek-and-select
                   :effect/count 2
                   :effect/select-count 1
                   :effect/selected-zone :hand
                   :effect/remainder-zone :bottom-of-library}]})
