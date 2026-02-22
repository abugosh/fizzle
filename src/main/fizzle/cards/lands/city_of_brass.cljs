(ns fizzle.cards.lands.city-of-brass
  "City of Brass card definition.

   City of Brass: Land
   Whenever City of Brass becomes tapped, it deals 1 damage to you.
   {T}: Add one mana of any color.")


(def card
  {:card/id :city-of-brass
   :card/name "City of Brass"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "Whenever City of Brass becomes tapped, it deals 1 damage to you. {T}: Add one mana of any color."

   ;; Trigger: fires when this permanent becomes tapped (ANY tap, not just mana ability)
   :card/triggers [{:trigger/type :becomes-tapped
                    :trigger/description "deals 1 damage to you"
                    :trigger/effects [{:effect/type :deal-damage
                                       :effect/amount 1
                                       :effect/target :controller}]}]

   ;; Mana ability: T: Add one mana of any color
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:any 1}}]}]})
