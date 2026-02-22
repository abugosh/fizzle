(ns fizzle.cards.lands.polluted-delta
  "Polluted Delta card definition.

   Polluted Delta: Land
   {T}, Pay 1 life, Sacrifice Polluted Delta: Search your library for an
   Island or Swamp card, put it onto the battlefield, then shuffle.")


(def card
  {:card/id :polluted-delta
   :card/name "Polluted Delta"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Sacrifice Polluted Delta: Search your library for an Island or Swamp card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Island or Swamp"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:island :swamp}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})
