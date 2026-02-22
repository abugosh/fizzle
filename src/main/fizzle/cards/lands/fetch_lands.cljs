(ns fizzle.cards.lands.fetch-lands
  "Fetch land card definitions.

   All ten fetch lands. Each has one activated ability:
   {T}, Pay 1 life, Sacrifice this land: Search your library for a [Type A]
   or [Type B] card, put it onto the battlefield, then shuffle.")


(def flooded-strand
  {:card/id :flooded-strand
   :card/name "Flooded Strand"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Plains or Island card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Plains or Island"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:plains :island}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def polluted-delta
  {:card/id :polluted-delta
   :card/name "Polluted Delta"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for an Island or Swamp card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Island or Swamp"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:island :swamp}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def bloodstained-mire
  {:card/id :bloodstained-mire
   :card/name "Bloodstained Mire"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Swamp or Mountain card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Swamp or Mountain"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:swamp :mountain}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def wooded-foothills
  {:card/id :wooded-foothills
   :card/name "Wooded Foothills"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Mountain or Forest card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Mountain or Forest"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:mountain :forest}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def windswept-heath
  {:card/id :windswept-heath
   :card/name "Windswept Heath"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Forest or Plains card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Forest or Plains"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:forest :plains}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def verdant-catacombs
  {:card/id :verdant-catacombs
   :card/name "Verdant Catacombs"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Swamp or Forest card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Swamp or Forest"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:swamp :forest}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def scalding-tarn
  {:card/id :scalding-tarn
   :card/name "Scalding Tarn"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for an Island or Mountain card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Island or Mountain"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:island :mountain}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def misty-rainforest
  {:card/id :misty-rainforest
   :card/name "Misty Rainforest"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Forest or Island card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Forest or Island"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:forest :island}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def arid-mesa
  {:card/id :arid-mesa
   :card/name "Arid Mesa"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Mountain or Plains card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Mountain or Plains"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:mountain :plains}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def marsh-flats
  {:card/id :marsh-flats
   :card/name "Marsh Flats"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}, Pay 1 life, Sacrifice this land: Search your library for a Plains or Swamp card, put it onto the battlefield, then shuffle."
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true
                                    :pay-life 1}
                     :ability/description "Search for Plains or Swamp"
                     :ability/effects [{:effect/type :tutor
                                        :effect/criteria {:card/subtypes #{:plains :swamp}}
                                        :effect/target-zone :battlefield
                                        :effect/shuffle? true}]}]})


(def cards
  "All ten fetch lands as a vector."
  [flooded-strand polluted-delta bloodstained-mire wooded-foothills windswept-heath
   verdant-catacombs scalding-tarn misty-rainforest arid-mesa marsh-flats])
