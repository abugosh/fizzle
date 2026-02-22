(ns fizzle.cards.artifacts.lions-eye-diamond
  "Lion's Eye Diamond card definition.

   Lion's Eye Diamond: 0 - Artifact
   {T}, Sacrifice Lion's Eye Diamond, Discard your hand: Add three mana
   of any one color. Activate only as an instant.")


(def card
  {:card/id :lions-eye-diamond
   :card/name "Lion's Eye Diamond"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "{T}, Sacrifice Lion's Eye Diamond, Discard your hand: Add three mana of any one color. Activate only as an instant."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :discard-hand true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:any 3}}]}]})
