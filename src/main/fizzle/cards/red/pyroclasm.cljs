(ns fizzle.cards.red.pyroclasm
  "Pyroclasm card definition.

   Pyroclasm: {1}{R} - Sorcery
   Pyroclasm deals 2 damage to each creature.")


(def card
  {:card/id :pyroclasm
   :card/name "Pyroclasm"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Pyroclasm deals 2 damage to each creature."
   :card/effects [{:effect/type :deal-damage-each-creature
                   :effect/amount 2}]})
