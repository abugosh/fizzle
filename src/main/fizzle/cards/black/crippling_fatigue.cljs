;; Crippling Fatigue - Sorcery
;; Oracle: Target creature gets -2/-2 until end of turn.
;;         Flashback—{1}{B}, Pay 3 life.
;; Scryfall verified: 2026-03-09
(ns fizzle.cards.black.crippling-fatigue
  "Crippling Fatigue card definition.

   Crippling Fatigue: {1}{B}{B} - Sorcery
   Target creature gets -2/-2 until end of turn.
   Flashback—{1}{B}, Pay 3 life.")


(def card
  {:card/id :crippling-fatigue
   :card/name "Crippling Fatigue"  ; Scryfall: Crippling Fatigue
   :card/cmc 3  ; Scryfall: 3.0
   :card/mana-cost {:colorless 1 :black 2}  ; Scryfall: {1}{B}{B}
   :card/colors #{:black}  ; Scryfall: ["B"]
   :card/types #{:sorcery}  ; Scryfall: Sorcery
   :card/text "Target creature gets -2/-2 until end of turn. Flashback\u2014{1}{B}, Pay 3 life."  ; Scryfall oracle_text

   ;; Cast-time targeting: target creature on the battlefield
   :card/targeting [{:target/id :target-creature
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/types #{:creature}}
                     :target/required true}]

   ;; Effect: apply -2/-2 modifier until EOT
   :card/effects [{:effect/type :apply-pt-modifier
                   :effect/target-ref :target-creature
                   :effect/power -2
                   :effect/toughness -2}]

   ;; Flashback: {1}{B} + pay 3 life
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1 :black 1}
                           :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                           :alternate/on-resolve :exile}]})
