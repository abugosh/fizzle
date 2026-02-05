(ns fizzle.cards.flash-of-insight
  "Flash of Insight card definition.

   Oracle text: Look at the top X cards of your library. Put one of them into
   your hand and the rest on the bottom of your library in any order.
   Flashback—{1}{U}, Exile X blue cards from your graveyard.

   Note: 'in any order' simplified to 'random order' per epic anti-pattern.")


(def flash-of-insight
  {:card/id :flash-of-insight
   :card/name "Flash of Insight"
   :card/cmc 2  ; X + 1U where X=0 gives CMC 2
   :card/mana-cost {:colorless 1 :blue 1 :x true}  ; {X}{1}{U}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Look at the top X cards of your library. Put one into your hand and the rest on the bottom in a random order. Flashback {1}{U}—exile X blue cards from your graveyard."

   ;; Normal cast effect: peek X, select 1 for hand
   :card/effects [{:effect/type :peek-and-select
                   :effect/count :x  ; Resolved from spell's X value
                   :effect/select-count 1
                   :effect/selected-zone :hand
                   :effect/remainder-zone :bottom-of-library
                   :effect/shuffle-remainder? true}]

   ;; Flashback alternate cost
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1 :blue 1}
                           :alternate/additional-costs [{:cost/type :exile-cards
                                                         :cost/zone :graveyard
                                                         :cost/criteria {:card/colors #{:blue}}
                                                         :cost/count :x}]
                           :alternate/on-resolve :exile}]})
