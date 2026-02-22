(ns fizzle.cards.lands.city-of-traitors
  "City of Traitors card definition.

   City of Traitors: Land
   When you play another land, sacrifice City of Traitors.
   {T}: Add {C}{C}.")


(def card
  {:card/id :city-of-traitors
   :card/name "City of Traitors"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "When you play another land, sacrifice City of Traitors. {T}: Add {C}{C}."

   ;; Trigger: When ANOTHER land enters (not self)
   ;; Uses :exclude-self filter to prevent triggering on own entry
   :card/triggers [{:trigger/type :land-entered
                    :trigger/description "sacrifice City of Traitors"
                    :trigger/filter {:exclude-self true}
                    :trigger/effects [{:effect/type :sacrifice
                                       :effect/target :self}]}]

   ;; Mana ability: T: Add {C}{C}
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 2}}]})
