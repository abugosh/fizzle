;; Fling - Sacrifice creature instant
;; Oracle: As an additional cost to cast this spell, sacrifice a creature.
;;   Fling deals damage equal to the sacrificed creature's power to any target.
;; Scryfall verified: 2026-03-25
(ns fizzle.cards.red.fling
  "Fling card definition.

   Fling: 1R - Instant
   As an additional cost to cast this spell, sacrifice a creature.
   Fling deals damage equal to the sacrificed creature's power to any target.

   Note: :target/type is :player because :any (player + creature) targeting
   is added in the next task. The card data will be updated when :any targeting
   lands.")


(def card
  {:card/id :fling
   :card/name "Fling"
   :card/cmc 2
   :card/mana-cost {:red 1 :colorless 1}
   :card/colors #{:red}
   :card/types #{:instant}
   :card/text "As an additional cost to cast this spell, sacrifice a creature.\nFling deals damage equal to the sacrificed creature's power to any target."

   ;; Additional cost: sacrifice a creature
   :card/additional-costs [{:cost/type :sacrifice-permanent
                            :cost/criteria {:match/types #{:creature}}}]

   ;; Cast-time targeting: target any player
   ;; TODO: update to :target/type :any when any-target infrastructure lands
   :card/targeting [{:target/id :target
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]

   ;; Effect: deal damage equal to sacrificed creature's power to target
   :card/effects [{:effect/type :deal-damage
                   :effect/amount {:dynamic/type :sacrificed-power}
                   :effect/target-ref :target}]})
