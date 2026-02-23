;; Crumble - Instant
;; Oracle: Destroy target artifact. It can't be regenerated. That artifact's
;;         controller gains life equal to its mana value.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.green.crumble
  "Crumble card definition.

   Crumble: G - Instant
   Destroy target artifact. It can't be regenerated.
   That artifact's controller gains life equal to its mana value.")


(def card
  {:card/id :crumble
   :card/name "Crumble"  ; Scryfall: Crumble
   :card/cmc 1  ; Scryfall: 1.0
   :card/mana-cost {:green 1}  ; Scryfall: {G}
   :card/colors #{:green}  ; Scryfall: ["G"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/text "Destroy target artifact. It can't be regenerated. That artifact's controller gains life equal to its mana value."  ; Scryfall oracle_text

   ;; Cast-time targeting: target artifact on the battlefield
   :card/targeting [{:target/id :target-artifact
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/types #{:artifact}}
                     :target/required true}]

   ;; Effects: destroy + controller gains life equal to CMC
   ;; :gain-life-equal-to-cmc reads the target's CMC and gives life
   ;; to the target's controller (not the caster)
   :card/effects [{:effect/type :destroy
                   :effect/target-ref :target-artifact}
                  {:effect/type :gain-life-equal-to-cmc
                   :effect/target-ref :target-artifact}]})
