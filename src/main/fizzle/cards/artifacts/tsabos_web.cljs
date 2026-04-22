(ns fizzle.cards.artifacts.tsabos-web
  "Tsabo's Web card definition.

   Tsabo's Web: Artifact {2}
   When this artifact enters, draw a card.
   Each land with an activated ability that isn't a mana ability
   doesn't untap during its controller's untap step.")


(def card
  {:card/id :tsabos-web
   :card/name "Tsabo's Web"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "When this artifact enters, draw a card.\nEach land with an activated ability that isn't a mana ability doesn't untap during its controller's untap step."
   :card/triggers [{:trigger/type :enters-battlefield
                    :trigger/description "draw a card"
                    :trigger/effects [{:effect/type :draw
                                       :effect/amount 1}]}]
   :card/static-abilities [{:static/type :untap-restriction
                            :modifier/criteria {:match/types #{:land}
                                                :match/has-ability-type :activated}}]})
