;; Chain of Vapor - Instant
;; Oracle: Return target nonland permanent to its owner's hand. Then that
;;         permanent's controller may sacrifice a land. If the player does,
;;         they may copy this spell and may choose a new target for the copy.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.blue.chain-of-vapor
  "Chain of Vapor card definition.

   Chain of Vapor: U - Instant
   Return target nonland permanent to its owner's hand. Then that
   permanent's controller may sacrifice a land. If the player does,
   they may copy this spell and may choose a new target for the copy.")


(def card
  {:card/id :chain-of-vapor
   :card/name "Chain of Vapor"  ; Scryfall: Chain of Vapor
   :card/cmc 1  ; Scryfall: 1.0
   :card/mana-cost {:blue 1}  ; Scryfall: {U}
   :card/colors #{:blue}  ; Scryfall: ["U"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/text "Return target nonland permanent to its owner's hand. Then that permanent's controller may sacrifice a land. If the player does, they may copy this spell and may choose a new target for the copy."  ; Scryfall oracle_text

   ;; Cast-time targeting: target nonland permanent on the battlefield
   :card/targeting [{:target/id :target-permanent
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/not-types #{:land}}
                     :target/required true}]

   ;; Effects: bounce + chain mechanic
   ;; 1. Bounce target to owner's hand
   ;; 2. Chain choice: target's controller may sacrifice a land to copy
   ;;    (interactive effect — pauses for selection via reduce-effects)
   :card/effects [{:effect/type :bounce
                   :effect/target-ref :target-permanent}
                  {:effect/type :chain-bounce
                   :effect/target-ref :target-permanent}]})
