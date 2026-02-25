(ns fizzle.cards.blue.stifle
  "Stifle card definition.

   Stifle (U): Instant
   Counter target activated or triggered ability. (Mana abilities can't be targeted.)")


(def card
  {:card/id :stifle
   :card/name "Stifle"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Counter target activated or triggered ability. (Mana abilities can't be targeted.)"
   ;; Note: Ability targeting UI not yet implemented.
   ;; For now, card definition is minimal - targeting will be added when UI is ready.
   :card/targeting [{:target/id :ability
                     :target/type :ability
                     :target/required true}]
   :card/effects [{:effect/type :counter-ability
                   :effect/target-ref :ability}]})
