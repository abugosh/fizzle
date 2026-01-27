(ns fizzle.cards.iggy-pop
  "Card definitions for the Iggy Pop storm deck.

   Cards are pure data - the engine interprets their effects.
   This namespace will grow to contain all Iggy Pop maindeck cards.")


;; Dark Ritual - The foundation of black storm
;; B -> BBB (net +2 black mana)
(def dark-ritual
  {:card/id :dark-ritual
   :card/name "Dark Ritual"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B}{B}{B}."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})


;; All cards in this namespace for easy import
(def all-cards
  [dark-ritual])
