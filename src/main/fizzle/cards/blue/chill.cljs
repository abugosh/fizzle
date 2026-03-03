(ns fizzle.cards.blue.chill
  "Chill card definition.

   Chill: {1}{U} - Enchantment
   Red spells cost {2} more to cast.")


(def card
  {:card/id :chill
   :card/name "Chill"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:enchantment}
   :card/text "Red spells cost {2} more to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 2
                            :modifier/direction :increase
                            :modifier/criteria {:criteria/type :spell-color
                                                :criteria/colors #{:red}}}]})
