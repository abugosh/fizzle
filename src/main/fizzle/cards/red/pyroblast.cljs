;; Pyroblast - Modal Instant (conditional)
;; Oracle: Choose one — Counter target spell if it's blue; or destroy target permanent if it's blue.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.red.pyroblast
  "Pyroblast card definition.

   Pyroblast: R - Instant
   Choose one —
   * Counter target spell if it's blue.
   * Destroy target permanent if it's blue.

   Unlike Red Elemental Blast, Pyroblast can target any spell/permanent
   but the effect only resolves if the target is blue at resolution time.")


(def card
  {:card/id :pyroblast
   :card/name "Pyroblast"
   :card/cmc 1
   :card/mana-cost {:red 1}
   :card/colors #{:red}
   :card/types #{:instant}
   :card/text "Choose one —\n• Counter target spell if it's blue.\n• Destroy target permanent if it's blue."

   ;; Modal spell: player chooses one mode at cast time
   ;; Unlike REB, targeting has NO color criteria — can target anything
   ;; Color check happens at resolution via :effect/condition
   :card/modes [{:mode/label "Counter target spell if it's blue"
                 :mode/targeting [{:target/id :target-spell
                                   :target/type :object
                                   :target/zone :stack
                                   :target/controller :any
                                   :target/required true}]
                 :mode/effects [{:effect/type :counter-spell
                                 :effect/target-ref :target-spell
                                 :effect/condition {:condition/type :target-is-color
                                                    :condition/color :blue}}]}
                {:mode/label "Destroy target permanent if it's blue"
                 :mode/targeting [{:target/id :target-permanent
                                   :target/type :object
                                   :target/zone :battlefield
                                   :target/controller :any
                                   :target/required true}]
                 :mode/effects [{:effect/type :destroy
                                 :effect/target-ref :target-permanent
                                 :effect/condition {:condition/type :target-is-color
                                                    :condition/color :blue}}]}]})
