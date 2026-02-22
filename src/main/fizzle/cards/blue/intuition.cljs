(ns fizzle.cards.blue.intuition
  "Intuition card definition.

   Intuition: 2U - Instant
   Search your library for three cards and reveal them. Target opponent
   chooses one. Put that card into your hand and the rest into your
   graveyard. Then shuffle.")


(def card
  {:card/id :intuition
   :card/name "Intuition"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Search your library for three cards and reveal them. Target opponent chooses one. Put that card into your hand and the rest into your graveyard. Then shuffle."
   :card/effects [{:effect/type :tutor
                   :effect/select-count 3
                   :effect/target-zone :hand
                   :effect/pile-choice {:hand 1 :graveyard :rest}}]})
