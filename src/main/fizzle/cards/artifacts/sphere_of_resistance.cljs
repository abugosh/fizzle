(ns fizzle.cards.artifacts.sphere-of-resistance
  "Sphere of Resistance card definition.

   Sphere of Resistance: {2} - Artifact
   Spells cost {1} more to cast.")


(def card
  {:card/id :sphere-of-resistance
   :card/name "Sphere of Resistance"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Spells cost {1} more to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :increase}]})
