(ns fizzle.cards.black.infernal-contract
  "Infernal Contract card definition.

   Infernal Contract: BBB - Sorcery
   Draw four cards. You lose half your life total, rounded up.")


(def card
  {:card/id :infernal-contract
   :card/name "Infernal Contract"
   :card/cmc 3
   :card/mana-cost {:black 3}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Draw four cards. You lose half your life total, rounded up."
   :card/effects [{:effect/type :draw
                   :effect/amount 4}
                  {:effect/type :lose-life
                   :effect/amount {:dynamic/type :half-life-rounded-up}}]})
