(ns fizzle.cards.artifacts.helm-of-awakening
  "Helm of Awakening card definition.

   Helm of Awakening: {2} - Artifact
   Spells cost {1} less to cast.")


(def card
  {:card/id :helm-of-awakening
   :card/name "Helm of Awakening"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Spells cost {1} less to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :decrease
                            :modifier/applies-to :all}]})
