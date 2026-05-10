(ns fizzle.cards.black.reaping-the-graves
  "Reaping the Graves card definition.

   Reaping the Graves: {2}{B} - Instant
   Return target creature card from your graveyard to your hand.
   Storm")


(def card
  {:card/id :reaping-the-graves
   :card/name "Reaping the Graves"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Return target creature card from your graveyard to your hand.\nStorm (When you cast this spell, copy it for each spell cast before it this turn. You may choose new targets for the copies.)"
   :card/targeting [{:target/id :graveyard-creature
                     :target/type :object
                     :target/zone :graveyard
                     :target/controller :self
                     :target/criteria {:match/types #{:creature}}
                     :target/required true}]
   :card/effects [{:effect/type :bounce
                   :effect/target-ref :graveyard-creature}]})
