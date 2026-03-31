;; Vision Charm - Modal Instant
;; Oracle: Choose one — Target player mills four cards; or Choose a land type and a basic
;; land type. Each land of the first chosen type becomes the second chosen type until end
;; of turn; or Target artifact phases out.
;; Scryfall verified: 2026-03-31
(ns fizzle.cards.blue.vision-charm
  "Vision Charm card definition.

   Vision Charm: {U} — Instant
   Choose one —
   * Target player mills four cards.
   * Choose a land type and a basic land type. Each land of the first chosen type
     becomes the second chosen type until end of turn.
   * Target artifact phases out.")


(def card
  {:card/id :vision-charm
   :card/name "Vision Charm"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one — • Target player mills four cards. • Choose a land type and a basic land type. Each land of the first chosen type becomes the second chosen type until end of turn. • Target artifact phases out."
   :card/modes [{:mode/label "Target player mills four cards"
                 :mode/targeting [{:target/id :mill-player
                                   :target/type :player
                                   :target/options #{:any-player}
                                   :target/required true}]
                 :mode/effects [{:effect/type :mill
                                 :effect/amount 4
                                 :effect/target-ref :mill-player}]}
                {:mode/label "Change land types until end of turn"
                 :mode/effects [{:effect/type :change-land-types}]}
                {:mode/label "Target artifact phases out"
                 :mode/targeting [{:target/id :target-artifact
                                   :target/type :object
                                   :target/zone :battlefield
                                   :target/controller :any
                                   :target/criteria {:match/types #{:artifact}}
                                   :target/required true}]
                 :mode/effects [{:effect/type :phase-out
                                 :effect/target-ref :target-artifact}]}]})
