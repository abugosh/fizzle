(ns fizzle.cards.artifacts.medallions
  "Medallion cycle card definitions.

   All five medallions. Each is a {2} colorless artifact that reduces
   the cost of spells of its associated color cast by its controller by {1}.")


(def emerald-medallion
  {:card/id :emerald-medallion
   :card/name "Emerald Medallion"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Green spells you cast cost {1} less to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :decrease
                            :modifier/applies-to :controller
                            :modifier/criteria {:criteria/type :spell-color
                                                :criteria/colors #{:green}}}]})


(def jet-medallion
  {:card/id :jet-medallion
   :card/name "Jet Medallion"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Black spells you cast cost {1} less to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :decrease
                            :modifier/applies-to :controller
                            :modifier/criteria {:criteria/type :spell-color
                                                :criteria/colors #{:black}}}]})


(def pearl-medallion
  {:card/id :pearl-medallion
   :card/name "Pearl Medallion"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "White spells you cast cost {1} less to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :decrease
                            :modifier/applies-to :controller
                            :modifier/criteria {:criteria/type :spell-color
                                                :criteria/colors #{:white}}}]})


(def ruby-medallion
  {:card/id :ruby-medallion
   :card/name "Ruby Medallion"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Red spells you cast cost {1} less to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :decrease
                            :modifier/applies-to :controller
                            :modifier/criteria {:criteria/type :spell-color
                                                :criteria/colors #{:red}}}]})


(def sapphire-medallion
  {:card/id :sapphire-medallion
   :card/name "Sapphire Medallion"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Blue spells you cast cost {1} less to cast."
   :card/static-abilities [{:static/type :cost-modifier
                            :modifier/amount 1
                            :modifier/direction :decrease
                            :modifier/applies-to :controller
                            :modifier/criteria {:criteria/type :spell-color
                                                :criteria/colors #{:blue}}}]})


(def cards
  "All five medallions as a vector."
  [emerald-medallion jet-medallion pearl-medallion ruby-medallion sapphire-medallion])
