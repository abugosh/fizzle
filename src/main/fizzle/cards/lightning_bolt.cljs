;; Lightning Bolt - Targeted damage instant
;; Oracle: Lightning Bolt deals 3 damage to any target.
;; Scryfall verified: 2026-02-17
(ns fizzle.cards.lightning-bolt
  "Lightning Bolt card definition.

   Lightning Bolt: R - Instant
   Lightning Bolt deals 3 damage to any target.")


(def lightning-bolt
  {:card/id :lightning-bolt
   :card/name "Lightning Bolt"
   :card/cmc 1
   :card/mana-cost {:red 1}
   :card/colors #{:red}
   :card/types #{:instant}
   :card/text "Lightning Bolt deals 3 damage to any target."

   ;; Cast-time targeting: target any player
   ;; Note: :target/type is :player because creatures don't exist yet.
   ;; When creatures arrive in Epic 5, update to support object targeting.
   :card/targeting [{:target/id :target
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]

   ;; Effect: deal 3 damage to the target
   ;; :effect/target-ref :target resolves via stored-targets at resolution time
   :card/effects [{:effect/type :deal-damage
                   :effect/amount 3
                   :effect/target-ref :target}]})
