(ns fizzle.cards.lands.cycling-lands
  "Onslaught cycling land cycle.

   Barren Moor: Land — This land enters tapped. {T}: Add {B}. Cycling {B}
   Lonely Sandbar: Land — This land enters tapped. {T}: Add {U}. Cycling {U}
   Secluded Steppe: Land — This land enters tapped. {T}: Add {W}. Cycling {W}
   Forgotten Cave: Land — This land enters tapped. {T}: Add {R}. Cycling {R}
   Tranquil Thicket: Land — This land enters tapped. {T}: Add {G}. Cycling {G}")


(def barren-moor
  {:card/id :barren-moor
   :card/name "Barren Moor"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/enters-tapped true
   :card/cycling {:black 1}
   :card/text "This land enters tapped.\n{T}: Add {B}.\nCycling {B} ({B}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:black 1}}]}]})


(def lonely-sandbar
  {:card/id :lonely-sandbar
   :card/name "Lonely Sandbar"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/enters-tapped true
   :card/cycling {:blue 1}
   :card/text "This land enters tapped.\n{T}: Add {U}.\nCycling {U} ({U}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:blue 1}}]}]})


(def secluded-steppe
  {:card/id :secluded-steppe
   :card/name "Secluded Steppe"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/enters-tapped true
   :card/cycling {:white 1}
   :card/text "This land enters tapped.\n{T}: Add {W}.\nCycling {W} ({W}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:white 1}}]}]})


(def forgotten-cave
  {:card/id :forgotten-cave
   :card/name "Forgotten Cave"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/enters-tapped true
   :card/cycling {:red 1}
   :card/text "This land enters tapped.\n{T}: Add {R}.\nCycling {R} ({R}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:red 1}}]}]})


(def tranquil-thicket
  {:card/id :tranquil-thicket
   :card/name "Tranquil Thicket"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/enters-tapped true
   :card/cycling {:green 1}
   :card/text "This land enters tapped.\n{T}: Add {G}.\nCycling {G} ({G}, Discard this card: Draw a card.)"
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:green 1}}]}]})


(def cards
  "All five Onslaught cycling lands as a vector."
  [barren-moor lonely-sandbar secluded-steppe forgotten-cave tranquil-thicket])
