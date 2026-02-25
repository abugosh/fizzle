(ns fizzle.cards.blue.portent
  "Portent card definition.

   Portent (U): Sorcery
   Look at the top three cards of target player's library, then put them
   back in any order. You may have that player shuffle their library.
   Draw a card at the beginning of the next turn's upkeep.")


(def card
  {:card/id :portent
   :card/name "Portent"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Look at the top three cards of target player's library, then put them back in any order. You may have that player shuffle their library. Draw a card at the beginning of the next turn's upkeep."
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/required true}]
   :card/effects [{:effect/type :peek-and-reorder
                   :effect/count 3
                   :effect/target-ref :player}
                  {:effect/type :grant-delayed-draw
                   :effect/target :controller}]})
