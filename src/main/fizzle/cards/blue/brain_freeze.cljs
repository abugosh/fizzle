(ns fizzle.cards.blue.brain-freeze
  "Brain Freeze card definition.

   Brain Freeze: 1U - Instant
   Target player mills 3. Storm.")


(def card
  {:card/id :brain-freeze
   :card/name "Brain Freeze"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Target player mills 3. Storm."
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]
   :card/effects [{:effect/type :mill
                   :effect/amount 3
                   :effect/target :any-player}]})
