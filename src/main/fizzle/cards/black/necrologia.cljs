(ns fizzle.cards.black.necrologia
  "Necrologia card definition.

   Necrologia: 3BB - Instant
   Cast this spell only during your end step.
   As an additional cost to cast this spell, pay X life.
   Draw X cards.")


(def card
  {:card/id :necrologia
   :card/name "Necrologia"
   :card/cmc 5
   :card/mana-cost {:colorless 3 :black 2}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Cast this spell only during your end step. As an additional cost to cast this spell, pay X life. Draw X cards."
   :card/cast-restriction {:restriction/phase :end}
   :card/additional-costs [{:cost/type :pay-x-life}]
   :card/effects [{:effect/type :draw
                   :effect/amount {:dynamic/type :chosen-x}}]})
