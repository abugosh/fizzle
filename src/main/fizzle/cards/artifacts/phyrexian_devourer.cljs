;; Phyrexian Devourer - Artifact Creature
;; Oracle: When Phyrexian Devourer's power is 7 or greater, sacrifice it.
;;   {Exile the top card of your library}: Put X +1/+1 counters on Phyrexian Devourer,
;;   where X is the exiled card's mana value.
;; Scryfall verified: 2026-03-10
(ns fizzle.cards.artifacts.phyrexian-devourer
  "Phyrexian Devourer card definition.

   Phyrexian Devourer: {6} - Artifact Creature — Phyrexian Construct 1/1
   When Phyrexian Devourer's power is 7 or greater, sacrifice it.
   {Exile the top card of your library}: Put X +1/+1 counters on Phyrexian Devourer,
   where X is the exiled card's mana value.")


(def card
  {:card/id :phyrexian-devourer
   :card/name "Phyrexian Devourer"
   :card/cmc 6
   :card/mana-cost {:colorless 6}
   :card/colors #{}
   :card/types #{:artifact :creature}
   :card/subtypes #{:phyrexian :construct}
   :card/power 1
   :card/toughness 1
   :card/text "When Phyrexian Devourer's power is 7 or greater, sacrifice it.\n{Exile the top card of your library}: Put X +1/+1 counters on Phyrexian Devourer, where X is the exiled card's mana value."

   ;; Activated ability: exile top card as cost, add +1/+1 counters equal to its CMC
   :card/abilities [{:ability/type :activated
                     :ability/cost {:exile-library-top 1}
                     :ability/description "Add +1/+1 counters equal to exiled card's mana value"
                     :ability/effects [{:effect/type :add-counters
                                        :effect/target :self
                                        :effect/counters {:+1/+1 {:dynamic/type :cost-exiled-card-mana-value}}}]}]

   ;; State trigger: when power >= 7, sacrifice (goes on stack, respondable)
   :card/state-triggers [{:state/condition {:condition/type :power-gte
                                            :condition/threshold 7}
                          :state/effects [{:effect/type :sacrifice
                                           :effect/target :self}]
                          :state/description "Sacrifice Phyrexian Devourer (power >= 7)"}]})
