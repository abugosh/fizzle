(ns fizzle.cards.lands.crystal-vein
  "Crystal Vein card definition.

   Crystal Vein: Land (Mirage, CMA)
   {T}: Add {C}.
   {T}, Sacrifice this land: Add {C}{C}.

   Key implementation notes:
   - Two mana abilities, both producing :colorless.
   - Ability 0: simple tap for 1 colorless (pain-land pattern).
   - Ability 1: tap + sacrifice-self for 2 colorless (Lotus Petal-like cost, different produces).
   - Because both abilities produce the same color, callers must pass an explicit
     ability-index to engine-mana/activate-mana-ability to reach ability 1.")


(def card
  {:card/id :crystal-vein
   :card/name "Crystal Vein"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}.\n{T}, Sacrifice this land: Add {C}{C}."

   :card/abilities
   [;; Ability 0: {T}: Add {C}.
    {:ability/type :mana
     :ability/cost {:tap true}
     :ability/produces {:colorless 1}}

    ;; Ability 1: {T}, Sacrifice Crystal Vein: Add {C}{C}.
    {:ability/type :mana
     :ability/cost {:tap true
                    :sacrifice-self true}
     :ability/produces {:colorless 2}}]})
