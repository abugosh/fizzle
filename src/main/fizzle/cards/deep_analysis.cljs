(ns fizzle.cards.deep-analysis
  "Deep Analysis card definition.

   Deep Analysis: 3U - Sorcery
   Target player draws two cards.
   Flashback - 1U, Pay 3 life.")


(def deep-analysis
  {:card/id :deep-analysis
   :card/name "Deep Analysis"
   :card/cmc 4
   :card/mana-cost {:colorless 3 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Target player draws two cards. Flashback—{1}{U}, Pay 3 life."
   ;; Cast-time targeting: player must be chosen when casting
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options [:self :opponent :any-player]
                     :target/required true}]
   ;; Effects still use :effect/target :any-player for backwards compat during migration
   :card/effects [{:effect/type :draw
                   :effect/amount 2
                   :effect/target :any-player}]
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1 :blue 1}
                           :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                           :alternate/on-resolve :exile}]})
