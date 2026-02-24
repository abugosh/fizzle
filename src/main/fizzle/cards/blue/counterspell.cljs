;; Counterspell - Instant
;; Oracle: Counter target spell.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.blue.counterspell
  "Counterspell card definition.

   Counterspell: UU - Instant
   Counter target spell.")


(def card
  {:card/id :counterspell
   :card/name "Counterspell"
   :card/cmc 2
   :card/mana-cost {:blue 2}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Counter target spell."

   ;; Cast-time targeting: target any spell on the stack
   :card/targeting [{:target/id :target-spell
                     :target/type :object
                     :target/zone :stack
                     :target/controller :any
                     :target/required true}]

   ;; Effects: counter the targeted spell
   :card/effects [{:effect/type :counter-spell
                   :effect/target-ref :target-spell}]})
