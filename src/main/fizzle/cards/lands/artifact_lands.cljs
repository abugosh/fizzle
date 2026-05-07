(ns fizzle.cards.lands.artifact-lands
  "Mirrodin artifact land cycle.

   Ancient Den: Artifact Land — {T}: Add {W}.
   Seat of the Synod: Artifact Land — {T}: Add {U}.
   Vault of Whispers: Artifact Land — {T}: Add {B}.
   Great Furnace: Artifact Land — {T}: Add {R}.
   Tree of Tales: Artifact Land — {T}: Add {G}.")


(def ancient-den
  {:card/id :ancient-den
   :card/name "Ancient Den"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact :land}
   :card/text "{T}: Add {W}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:white 1}}]}]})


(def seat-of-the-synod
  {:card/id :seat-of-the-synod
   :card/name "Seat of the Synod"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact :land}
   :card/text "{T}: Add {U}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:blue 1}}]}]})


(def vault-of-whispers
  {:card/id :vault-of-whispers
   :card/name "Vault of Whispers"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact :land}
   :card/text "{T}: Add {B}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:black 1}}]}]})


(def great-furnace
  {:card/id :great-furnace
   :card/name "Great Furnace"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact :land}
   :card/text "{T}: Add {R}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:red 1}}]}]})


(def tree-of-tales
  {:card/id :tree-of-tales
   :card/name "Tree of Tales"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact :land}
   :card/text "{T}: Add {G}."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:tap true}
                     :ability/effects [{:effect/type :add-mana
                                        :effect/mana {:green 1}}]}]})


(def cards
  "All five Mirrodin artifact lands as a vector."
  [ancient-den seat-of-the-synod vault-of-whispers great-furnace tree-of-tales])
