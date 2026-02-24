;; Daze - Instant
;; Oracle: You may return an Island you control to its owner's hand
;;   rather than pay this spell's mana cost.
;;   Counter target spell unless its controller pays {1}.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.blue.daze
  "Daze card definition.

   Daze: {1}{U} - Instant
   You may return an Island you control to its owner's hand rather
   than pay this spell's mana cost.
   Counter target spell unless its controller pays {1}.")


(def card
  {:card/id :daze
   :card/name "Daze"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "You may return an Island you control to its owner's hand rather than pay this spell's mana cost.\nCounter target spell unless its controller pays {1}."

   ;; Cast-time targeting: target any spell on the stack
   :card/targeting [{:target/id :target-spell
                     :target/type :object
                     :target/zone :stack
                     :target/controller :any
                     :target/required true}]

   ;; Alternate cost: return an Island you control to hand
   :card/alternate-costs [{:alternate/id :daze-free
                           :alternate/zone :hand
                           :alternate/mana-cost {}
                           :alternate/additional-costs [{:cost/type :return-land
                                                         :cost/criteria {:match/types #{:land}
                                                                         :match/subtypes #{:island}}}]
                           :alternate/on-resolve :graveyard}]

   ;; Effects: counter unless controller pays {1}
   :card/effects [{:effect/type :counter-spell
                   :effect/target-ref :target-spell
                   :effect/unless-pay {:colorless 1}}]})
