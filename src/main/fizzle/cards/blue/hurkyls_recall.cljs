(ns fizzle.cards.blue.hurkyls-recall)


(def card
  {:card/id :hurkyls-recall
   :card/name "Hurkyl's Recall"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Return all artifacts target player controls to their hand."
   :card/targeting [{:target/id :target-player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]
   :card/effects [{:effect/type :bounce-all
                   :effect/target-ref :target-player
                   :effect/criteria {:match/types #{:artifact}}}]})
