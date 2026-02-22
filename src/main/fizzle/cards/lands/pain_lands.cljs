(ns fizzle.cards.lands.pain-lands
  "Pain land card definitions.

   All ten pain lands. Each has three mana abilities:
   {T}: Add {C}. {T}: Add {X} or {Y}. This land deals 1 damage to you.")


(def adarkar-wastes
  {:card/id :adarkar-wastes
   :card/name "Adarkar Wastes"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {W} or {U}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:white 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:blue 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def battlefield-forge
  {:card/id :battlefield-forge
   :card/name "Battlefield Forge"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {R} or {W}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:red 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:white 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def brushland
  {:card/id :brushland
   :card/name "Brushland"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {G} or {W}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:green 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:white 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def caves-of-koilos
  {:card/id :caves-of-koilos
   :card/name "Caves of Koilos"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {W} or {B}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:white 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def karplusan-forest
  {:card/id :karplusan-forest
   :card/name "Karplusan Forest"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {R} or {G}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:red 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:green 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def llanowar-wastes
  {:card/id :llanowar-wastes
   :card/name "Llanowar Wastes"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {B} or {G}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:green 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def shivan-reef
  {:card/id :shivan-reef
   :card/name "Shivan Reef"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {U} or {R}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:blue 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:red 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def sulfurous-springs
  {:card/id :sulfurous-springs
   :card/name "Sulfurous Springs"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {B} or {R}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:red 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def underground-river
  {:card/id :underground-river
   :card/name "Underground River"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {U} or {B}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:blue 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:black 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def yavimaya-coast
  {:card/id :yavimaya-coast
   :card/name "Yavimaya Coast"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}. {T}: Add {G} or {U}. This land deals 1 damage to you."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:colorless 1}}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:green 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}
                    {:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/produces {:blue 1}
                     :ability/effects [{:effect/type :deal-damage
                                        :effect/amount 1
                                        :effect/target :controller}]}]})


(def cards
  "All ten pain lands as a vector."
  [adarkar-wastes battlefield-forge brushland caves-of-koilos karplusan-forest
   llanowar-wastes shivan-reef sulfurous-springs underground-river yavimaya-coast])
