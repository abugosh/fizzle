(ns fizzle.cards.artifacts.mind-stone
  "Mind Stone card definition.

   Mind Stone: {2} - Artifact (Stronghold)
   {T}: Add {C}.
   {1}, {T}, Sacrifice this artifact: Draw a card.

   Key implementation notes:
   - Ability 0 is a plain mana ability (does not use the stack).
   - Ability 1 is an :activated ability with a composite cost
     (mana + tap + sacrifice-self). It uses the stack per MTG rules —
     not a mana ability because its effect is card draw, not mana.")


(def card
  {:card/id :mind-stone
   :card/name "Mind Stone"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "{T}: Add {C}.\n{1}, {T}, Sacrifice this artifact: Draw a card."

   :card/abilities
   [;; Ability 0: {T}: Add {C}.
    {:ability/type :mana
     :ability/cost {:tap true}
     :ability/produces {:colorless 1}}

    ;; Ability 1: {1}, {T}, Sacrifice Mind Stone: Draw a card.
    {:ability/type :activated
     :ability/cost {:mana {:colorless 1}
                    :tap true
                    :sacrifice-self true}
     :ability/description "Draw a card"
     :ability/effects [{:effect/type :draw
                        :effect/amount 1}]}]})
