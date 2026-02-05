;; Seal of Cleansing - Enchantment
;; Oracle: Sacrifice this enchantment: Destroy target artifact or enchantment.
;; Scryfall verified: 2026-02-05
(ns fizzle.cards.seal-of-cleansing
  "Seal of Cleansing card definition.

   Seal of Cleansing: 1W - Enchantment
   Sacrifice this enchantment: Destroy target artifact or enchantment.")


(def seal-of-cleansing
  {:card/id :seal-of-cleansing
   :card/name "Seal of Cleansing"  ; Scryfall: Seal of Cleansing
   :card/cmc 2  ; Scryfall: 2.0
   :card/mana-cost {:colorless 1 :white 1}  ; Scryfall: {1}{W}
   :card/colors #{:white}  ; Scryfall: ["W"]
   :card/types #{:enchantment}  ; Scryfall: Enchantment
   :card/text "Sacrifice this enchantment: Destroy target artifact or enchantment."  ; Scryfall oracle_text

   ;; Activated ability: Sacrifice to destroy target artifact or enchantment
   ;; Ruling (2021-06-18): "Sacrificing Seal of Cleansing is the cost to activate its ability."
   :card/abilities
   [{:ability/type :activated
     :ability/targeting [{:target/id :target-artifact-or-enchantment
                          :target/type :object
                          :target/zone :battlefield
                          :target/controller :any
                          :target/criteria {:card/types #{:artifact :enchantment}}
                          :target/required true}]
     :ability/cost {:sacrifice-self true}
     :ability/description "Destroy target artifact or enchantment"
     :ability/effects [{:effect/type :destroy
                        :effect/target-ref :target-artifact-or-enchantment}]}]})
