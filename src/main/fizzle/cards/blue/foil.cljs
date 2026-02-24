;; Foil - Instant
;; Oracle: You may discard an Island card and another card rather than
;;   pay this spell's mana cost.
;;   Counter target spell.
;; Scryfall verified: 2026-02-24
(ns fizzle.cards.blue.foil
  "Foil card definition.

   Foil: {2}{U}{U} - Instant
   You may discard an Island card and another card rather than pay
   this spell's mana cost.
   Counter target spell.")


(def card
  {:card/id :foil
   :card/name "Foil"
   :card/cmc 4
   :card/mana-cost {:colorless 2 :blue 2}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "You may discard an Island card and another card rather than pay this spell's mana cost.\nCounter target spell."

   ;; Cast-time targeting: target any spell on the stack
   :card/targeting [{:target/id :target-spell
                     :target/type :object
                     :target/zone :stack
                     :target/controller :any
                     :target/required true}]

   ;; Alternate cost: discard an Island card and another card
   :card/alternate-costs [{:alternate/id :foil-free
                           :alternate/zone :hand
                           :alternate/mana-cost {}
                           :alternate/additional-costs [{:cost/type :discard-specific
                                                         :cost/groups [{:criteria {:match/subtypes #{:island}}
                                                                        :count 1}
                                                                       {:count 1}]
                                                         :cost/total 2}]
                           :alternate/on-resolve :graveyard}]

   ;; Effects: hard counter (no unless-pay)
   :card/effects [{:effect/type :counter-spell
                   :effect/target-ref :target-spell}]})
