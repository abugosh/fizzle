;; Annul - Instant
;; Oracle: Counter target artifact or enchantment spell.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.blue.annul
  "Annul card definition.

   Annul: U - Instant
   Counter target artifact or enchantment spell.")


(def card
  {:card/id :annul
   :card/name "Annul"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Counter target artifact or enchantment spell."

   ;; Cast-time targeting: target artifact or enchantment spell on the stack
   :card/targeting [{:target/id :target-spell
                     :target/type :object
                     :target/zone :stack
                     :target/controller :any
                     :target/criteria {:match/types #{:artifact :enchantment}}
                     :target/required true}]

   ;; Effects: counter the targeted spell
   :card/effects [{:effect/type :counter-spell
                   :effect/target-ref :target-spell}]})
