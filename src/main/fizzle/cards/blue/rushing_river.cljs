;; Rushing River - Instant
;; Oracle: Kicker—Sacrifice a land.
;;         Return target nonland permanent to its owner's hand.
;;         If this spell was kicked, return another target nonland permanent
;;         to its owner's hand.
;; Scryfall verified: 2026-04-24
(ns fizzle.cards.blue.rushing-river
  "Rushing River card definition.

   Rushing River: {2}{U} - Instant
   Kicker—Sacrifice a land.
   Return target nonland permanent to its owner's hand.
   If this spell was kicked, return another target nonland permanent
   to its owner's hand.")


(def card
  {:card/id :rushing-river
   :card/name "Rushing River"  ; Scryfall: Rushing River
   :card/cmc 3  ; Scryfall: 3.0
   :card/mana-cost {:colorless 2 :blue 1}  ; Scryfall: {2}{U}
   :card/colors #{:blue}  ; Scryfall: ["U"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/text "Kicker—Sacrifice a land. Return target nonland permanent to its owner's hand. If this spell was kicked, return another target nonland permanent to its owner's hand."  ; Scryfall oracle_text

   ;; Primary mode: target single nonland permanent on battlefield
   :card/targeting [{:target/id :primary
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/not-types #{:land}}
                     :target/required true}]

   ;; Primary effects: bounce primary target to owner's hand
   ;; :effect/target-ref :primary resolves via :stack-item/targets at resolution time
   :card/effects [{:effect/type :bounce
                   :effect/target-ref :primary}]

   ;; Kicked alternate cost: non-mana kicker (sacrifice a land)
   ;; :alternate/mana-cost is the TOTAL mana cost (same as primary — kicker is non-mana)
   ;; :alternate/additional-costs adds the sacrifice-permanent cost
   ;; :alternate/targeting REPLACES :card/targeting (replacement semantics — 2 distinct slots)
   ;; :alternate/effects REPLACES :card/effects (2 bounces referencing slot-a / slot-b)
   :card/alternate-costs
   [{:alternate/id :kicked
     :alternate/kind :kicker
     :alternate/zone :hand
     :alternate/mana-cost {:colorless 2 :blue 1}
     :alternate/additional-costs [{:cost/type :sacrifice-permanent
                                   :cost/criteria {:match/types #{:land}}}]
     :alternate/targeting [{:target/id :slot-a
                            :target/type :object
                            :target/zone :battlefield
                            :target/controller :any
                            :target/criteria {:match/not-types #{:land}}
                            :target/required true}
                           {:target/id :slot-b
                            :target/type :object
                            :target/zone :battlefield
                            :target/controller :any
                            :target/criteria {:match/not-types #{:land}}
                            :target/required true}]
     :alternate/effects [{:effect/type :bounce
                          :effect/target-ref :slot-a}
                         {:effect/type :bounce
                          :effect/target-ref :slot-b}]
     :alternate/on-resolve :graveyard}]})
