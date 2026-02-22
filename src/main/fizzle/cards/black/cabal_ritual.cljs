(ns fizzle.cards.black.cabal-ritual
  "Cabal Ritual card definition.

   Cabal Ritual: 1B - Instant
   Add {B}{B}{B}. Threshold - Add {B}{B}{B}{B}{B} instead.")


(def card
  {:card/id :cabal-ritual
   :card/name "Cabal Ritual"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}. Threshold - Add {B}{B}{B}{B}{B} instead."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]
   :card/conditional-effects {:threshold [{:effect/type :add-mana
                                           :effect/mana {:black 5}}]}})
