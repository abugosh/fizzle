(ns fizzle.cards.black.rowans-grim-search
  "Rowan's Grim Search card definition.

   Rowan's Grim Search: {2}{B} - Instant
   Bargain (You may sacrifice an artifact, enchantment, or token as you cast this spell.)
   If this spell was bargained, look at the top four cards of your library,
   then put up to two of them back on top of your library in any order
   and the rest into your graveyard.
   You draw two cards and you lose 2 life.")


(def card
  {:card/id :rowans-grim-search
   :card/name "Rowan's Grim Search"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/keywords #{:bargain}
   :card/text "Bargain (You may sacrifice an artifact, enchantment, or token as you cast this spell.)\nIf this spell was bargained, look at the top four cards of your library, then put up to two of them back on top of your library in any order and the rest into your graveyard.\nYou draw two cards and you lose 2 life."

   :card/effects [{:effect/type :draw :effect/amount 2}
                  {:effect/type :lose-life :effect/amount 2}]

   :card/alternate-costs
   [{:alternate/id :bargained
     :alternate/kind :bargain
     :alternate/label "Bargain — Sacrifice an artifact, enchantment, or token"
     :alternate/zone :hand
     :alternate/mana-cost {:colorless 2 :black 1}
     :alternate/additional-costs [{:cost/type :sacrifice-permanent
                                   :cost/criteria {:match/or [{:match/types #{:artifact :enchantment}}
                                                              {:match/is-token true}]}}]
     :alternate/effects [{:effect/type :peek-and-select
                          :effect/count 4
                          :effect/select-count 2
                          :effect/selected-zone :top-of-library
                          :effect/remainder-zone :graveyard}
                         {:effect/type :draw :effect/amount 2}
                         {:effect/type :lose-life :effect/amount 2}]
     :alternate/on-resolve :graveyard}]})
