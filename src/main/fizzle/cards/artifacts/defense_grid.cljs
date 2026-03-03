(ns fizzle.cards.artifacts.defense-grid
  "Defense Grid card definition.

   Defense Grid: {2} - Artifact
   Each spell costs {3} more to cast except during its controller's turn.")


(def card
  {:card/id :defense-grid
   :card/name "Defense Grid"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Each spell costs {3} more to cast except during its controller's turn."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 3
                            :modifier/direction :increase
                            :modifier/condition {:condition/type :not-casters-turn}}]})
