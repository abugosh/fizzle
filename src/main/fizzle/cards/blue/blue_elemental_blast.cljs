;; Blue Elemental Blast - Modal Instant
;; Oracle: Choose one — Counter target red spell; or destroy target red permanent.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.blue.blue-elemental-blast
  "Blue Elemental Blast card definition.

   Blue Elemental Blast: U - Instant
   Choose one —
   * Counter target red spell.
   * Destroy target red permanent.")


(def card
  {:card/id :blue-elemental-blast
   :card/name "Blue Elemental Blast"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one —\n• Counter target red spell.\n• Destroy target red permanent."

   ;; Modal spell: player chooses one mode at cast time
   ;; Each mode has its own targeting requirements and effects
   :card/modes [{:mode/label "Counter target red spell"
                 :mode/targeting [{:target/id :target-spell
                                   :target/type :object
                                   :target/zone :stack
                                   :target/controller :any
                                   :target/criteria {:match/colors #{:red}}
                                   :target/required true}]
                 :mode/effects [{:effect/type :counter-spell
                                 :effect/target-ref :target-spell}]}
                {:mode/label "Destroy target red permanent"
                 :mode/targeting [{:target/id :target-permanent
                                   :target/type :object
                                   :target/zone :battlefield
                                   :target/controller :any
                                   :target/criteria {:match/colors #{:red}}
                                   :target/required true}]
                 :mode/effects [{:effect/type :destroy
                                 :effect/target-ref :target-permanent}]}]})
