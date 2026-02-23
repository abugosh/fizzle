(ns fizzle.cards.blue.merchant-scroll
  "Merchant Scroll card definition.

   Merchant Scroll: 1U - Sorcery
   Search your library for a blue instant card, reveal it,
   put it into your hand, then shuffle.")


(def card
  {:card/id :merchant-scroll
   :card/name "Merchant Scroll"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Search your library for a blue instant card, reveal it, put it into your hand, then shuffle."
   :card/effects [{:effect/type :tutor
                   :effect/criteria {:match/types #{:instant}
                                     :match/colors #{:blue}}
                   :effect/target-zone :hand
                   :effect/shuffle? true}]})
