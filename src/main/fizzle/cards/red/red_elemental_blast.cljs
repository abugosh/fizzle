;; Red Elemental Blast - Modal Instant
;; Oracle: Choose one — Counter target blue spell; or destroy target blue permanent.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.red.red-elemental-blast
  "Red Elemental Blast card definition.

   Red Elemental Blast: R - Instant
   Choose one —
   * Counter target blue spell.
   * Destroy target blue permanent.")


(def card
  {:card/id :red-elemental-blast
   :card/name "Red Elemental Blast"
   :card/cmc 1
   :card/mana-cost {:red 1}
   :card/colors #{:red}
   :card/types #{:instant}
   :card/text "Choose one —\n• Counter target blue spell.\n• Destroy target blue permanent."

   ;; Modal spell: player chooses one mode at cast time
   ;; Each mode has its own targeting requirements and effects
   :card/modes [{:mode/label "Counter target blue spell"
                 :mode/targeting [{:target/id :target-spell
                                   :target/type :object
                                   :target/zone :stack
                                   :target/controller :any
                                   :target/criteria {:match/colors #{:blue}}
                                   :target/required true}]
                 :mode/effects [{:effect/type :counter-spell
                                 :effect/target-ref :target-spell}]}
                {:mode/label "Destroy target blue permanent"
                 :mode/targeting [{:target/id :target-permanent
                                   :target/type :object
                                   :target/zone :battlefield
                                   :target/controller :any
                                   :target/criteria {:match/colors #{:blue}}
                                   :target/required true}]
                 :mode/effects [{:effect/type :destroy
                                 :effect/target-ref :target-permanent}]}]})
