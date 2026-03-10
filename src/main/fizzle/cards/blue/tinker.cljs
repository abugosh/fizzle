;; Tinker - Sorcery
;; Oracle: As an additional cost to cast this spell, sacrifice an artifact.
;;   Search your library for an artifact card, put that card onto the
;;   battlefield, then shuffle.
;; Scryfall verified: 2026-03-10
(ns fizzle.cards.blue.tinker
  "Tinker card definition.

   Tinker: {2}{U} - Sorcery
   As an additional cost to cast this spell, sacrifice an artifact.
   Search your library for an artifact card, put that card onto the
   battlefield, then shuffle.")


(def card
  {:card/id :tinker
   :card/name "Tinker"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "As an additional cost to cast this spell, sacrifice an artifact.\nSearch your library for an artifact card, put that card onto the battlefield, then shuffle."
   :card/additional-costs [{:cost/type :sacrifice-permanent
                            :cost/criteria {:match/types #{:artifact}}}]
   :card/effects [{:effect/type :tutor
                   :effect/criteria {:match/types #{:artifact}}
                   :effect/target-zone :battlefield
                   :effect/shuffle? true}]})
