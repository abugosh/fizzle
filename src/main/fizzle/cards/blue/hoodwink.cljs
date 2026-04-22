;; Hoodwink - Instant
;; Oracle: Return target artifact, enchantment, or land to its owner's hand.
;; Scryfall verified: 2026-04-22
(ns fizzle.cards.blue.hoodwink
  "Hoodwink card definition.

   Hoodwink: 1U - Instant
   Return target artifact, enchantment, or land to its owner's hand.")


(def card
  {:card/id :hoodwink
   :card/name "Hoodwink"  ; Scryfall: Hoodwink
   :card/cmc 2  ; Scryfall: 2.0
   :card/mana-cost {:colorless 1 :blue 1}  ; Scryfall: {1}{U}
   :card/colors #{:blue}  ; Scryfall: ["U"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/text "Return target artifact, enchantment, or land to its owner's hand."  ; Scryfall oracle_text

   ;; Cast-time targeting: target artifact, enchantment, or land on the battlefield.
   ;; :match/types uses OR semantics (object has ANY of the listed types).
   :card/targeting [{:target/id :target-permanent
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/types #{:artifact :enchantment :land}}
                     :target/required true}]

   ;; Effect: bounce the target to its owner's hand.
   :card/effects [{:effect/type :bounce
                   :effect/target-ref :target-permanent}]})
