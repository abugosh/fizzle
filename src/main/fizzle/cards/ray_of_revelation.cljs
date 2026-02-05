;; Ray of Revelation - Instant
;; Oracle: Destroy target enchantment.
;;         Flashback {G} (You may cast this card from your graveyard for its flashback cost. Then exile it.)
;; Scryfall verified: 2026-02-05
(ns fizzle.cards.ray-of-revelation
  "Ray of Revelation card definition.

   Ray of Revelation: 1W - Instant
   Destroy target enchantment.
   Flashback {G}")


(def ray-of-revelation
  {:card/id :ray-of-revelation
   :card/name "Ray of Revelation"  ; Scryfall: Ray of Revelation
   :card/cmc 2  ; Scryfall: 2.0
   :card/mana-cost {:colorless 1 :white 1}  ; Scryfall: {1}{W}
   :card/colors #{:white}  ; Scryfall: ["W"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/text "Destroy target enchantment.\nFlashback {G}"  ; Scryfall oracle_text

   ;; Type-based targeting: any enchantment on battlefield
   :card/targeting [{:target/id :target-enchantment
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:card/types #{:enchantment}}
                     :target/required true}]

   ;; Destroy effect using target-ref to reference stored target
   :card/effects [{:effect/type :destroy
                   :effect/target-ref :target-enchantment}]

   ;; Flashback {G} from graveyard, exile on resolve
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:green 1}
                           :alternate/on-resolve :exile}]})
