;; Hydroblast - Modal Instant (conditional)
;; Oracle: Choose one — Counter target spell if it's red; or destroy target permanent if it's red.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.blue.hydroblast
  "Hydroblast card definition.

   Hydroblast: U - Instant
   Choose one —
   * Counter target spell if it's red.
   * Destroy target permanent if it's red.

   Unlike Blue Elemental Blast, Hydroblast can target any spell/permanent
   but the effect only resolves if the target is red at resolution time.")


(def card
  {:card/id :hydroblast
   :card/name "Hydroblast"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one —\n• Counter target spell if it's red.\n• Destroy target permanent if it's red."

   ;; Modal spell: player chooses one mode at cast time
   ;; Unlike BEB, targeting has NO color criteria — can target anything
   ;; Color check happens at resolution via :effect/condition
   :card/modes [{:mode/label "Counter target spell if it's red"
                 :mode/targeting [{:target/id :target-spell
                                   :target/type :object
                                   :target/zone :stack
                                   :target/controller :any
                                   :target/required true}]
                 :mode/effects [{:effect/type :counter-spell
                                 :effect/target-ref :target-spell
                                 :effect/condition {:condition/type :target-is-color
                                                    :condition/color :red}}]}
                {:mode/label "Destroy target permanent if it's red"
                 :mode/targeting [{:target/id :target-permanent
                                   :target/type :object
                                   :target/zone :battlefield
                                   :target/controller :any
                                   :target/required true}]
                 :mode/effects [{:effect/type :destroy
                                 :effect/target-ref :target-permanent
                                 :effect/condition {:condition/type :target-is-color
                                                    :condition/color :red}}]}]})
