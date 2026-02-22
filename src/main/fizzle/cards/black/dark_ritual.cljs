(ns fizzle.cards.black.dark-ritual
  "Dark Ritual card definition.

   Dark Ritual: B - Instant
   Add {B}{B}{B}.")


(def card
  {:card/id :dark-ritual
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})
