;; Mana Leak - Instant
;; Oracle: Counter target spell unless its controller pays {3}.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.blue.mana-leak
  "Mana Leak card definition.

   Mana Leak: {1}{U} - Instant
   Counter target spell unless its controller pays {3}.")


(def card
  {:card/id :mana-leak
   :card/name "Mana Leak"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Counter target spell unless its controller pays {3}."

   ;; Cast-time targeting: target any spell on the stack
   :card/targeting [{:target/id :target-spell
                     :target/type :object
                     :target/zone :stack
                     :target/controller :any
                     :target/required true}]

   ;; Effects: counter unless controller pays {3}
   :card/effects [{:effect/type :counter-spell
                   :effect/target-ref :target-spell
                   :effect/unless-pay {:colorless 3}}]})
