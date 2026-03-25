;; Altar of Dementia - Artifact
;; Oracle: Sacrifice a creature: Target player mills cards equal to the sacrificed creature's power.
;; Scryfall verified: 2026-03-25
(ns fizzle.cards.artifacts.altar-of-dementia
  "Altar of Dementia card definition.

   Altar of Dementia: 2 - Artifact
   Sacrifice a creature: Target player mills cards equal to the sacrificed creature's power.")


(def card
  {:card/id :altar-of-dementia
   :card/name "Altar of Dementia"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Sacrifice a creature: Target player mills cards equal to the sacrificed creature's power."

   ;; Activated ability: sacrifice a creature to mill equal to its power
   :card/abilities
   [{:ability/type :activated
     :ability/targeting [{:target/id :player
                          :target/type :player
                          :target/options #{:any-player}
                          :target/required true}]
     :ability/cost {:sacrifice-permanent {:match/types #{:creature}}}
     :ability/description "Mill cards equal to sacrificed creature's power"
     :ability/effects [{:effect/type :mill
                        :effect/amount {:dynamic/type :sacrificed-power}
                        :effect/target-ref :player}]}]})
