;; Page, Loose Leaf - Legendary Artifact Creature
;; Oracle: {T}: Add {C}.
;;   Grandeur — Discard another card named Page, Loose Leaf: Reveal cards from the top
;;   of your library until you reveal an instant or sorcery card. Put that card into your
;;   hand and the rest on the bottom of your library in a random order.
;; Scryfall verified: 2026-05-11
(ns fizzle.cards.artifacts.page-loose-leaf
  "Page, Loose Leaf card definition.

   Page, Loose Leaf: {2} - Legendary Artifact Creature — Construct 0/2
   {T}: Add {C}.
   Grandeur — Discard another card named Page, Loose Leaf: Reveal cards from the top
   of your library until you reveal an instant or sorcery card. Put that card into your
   hand and the rest on the bottom of your library in a random order.")


(def card
  {:card/id :page-loose-leaf
   :card/name "Page, Loose Leaf"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact :creature}
   :card/subtypes #{:construct}
   :card/supertypes #{:legendary}
   :card/power 0
   :card/toughness 2
   :card/keywords #{:grandeur}
   :card/text "{T}: Add {C}.\nGrandeur — Discard another card named Page, Loose Leaf: Reveal cards from the top of your library until you reveal an instant or sorcery card. Put that card into your hand and the rest on the bottom of your library in a random order."

   :card/abilities
   [;; Mana ability: {T}: Add {C}
    {:ability/type :mana
     :ability/cost {:tap true}
     :ability/produces {:colorless 1}}

    ;; Grandeur activated ability: discard another copy → reveal until instant/sorcery
    {:ability/type :activated
     :ability/cost {:discard-specific {:groups [{:criteria {:match/card-ids #{:page-loose-leaf}}
                                                 :count 1}]
                                       :total 1}}
     :ability/description "Grandeur — Reveal until instant or sorcery"
     :ability/effects [{:effect/type :reveal-until
                        :effect/criteria {:match/types #{:instant :sorcery}}
                        :effect/found-zone :hand
                        :effect/remainder :bottom-random}]}]})
