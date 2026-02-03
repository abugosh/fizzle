(ns fizzle.cards.cephalid-coliseum
  "Cephalid Coliseum card definition.

   Cephalid Coliseum: Land
   {T}: Add {U}. Cephalid Coliseum deals 1 damage to you.
   Threshold — {U}, {T}, Sacrifice Cephalid Coliseum: Target player draws
   three cards, then discards three cards. Activate only if seven or more
   cards are in your graveyard.

   Key implementation notes:
   - Damage is part of the mana ability (:ability/effects), NOT a trigger
   - This differs from City of Brass which uses :trigger/type :becomes-tapped
   - Threshold ability uses :ability/condition {:condition/type :threshold}")


(def cephalid-coliseum
  {:card/id :cephalid-coliseum
   :card/name "Cephalid Coliseum"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/keywords #{:threshold}
   :card/text "{T}: Add {U}. Cephalid Coliseum deals 1 damage to you.\nThreshold — {U}, {T}, Sacrifice Cephalid Coliseum: Target player draws three cards, then discards three cards. Activate only if seven or more cards are in your graveyard."

   :card/abilities
   [;; Mana ability: T: Add {U}. Deals 1 damage to you.
    ;; IMPORTANT: Damage is part of the ability via :ability/effects,
    ;; NOT a trigger like City of Brass. This ensures damage only happens
    ;; when the mana ability is activated, not when tapped by other effects.
    {:ability/type :mana
     :ability/cost {:tap true}
     :ability/produces {:blue 1}
     :ability/effects [{:effect/type :deal-damage
                        :effect/amount 1
                        :effect/target :controller}]}

    ;; Threshold ability: {U}, T, Sacrifice: Target player draws 3, discards 3
    ;; Only activatable with 7+ cards in graveyard
    {:ability/type :activated
     :ability/cost {:tap true
                    :sacrifice-self true
                    :mana {:blue 1}}
     :ability/condition {:condition/type :threshold}
     :ability/description "Target player draws 3 cards, then discards 3 cards"
     :ability/effects [{:effect/type :draw
                        :effect/amount 3
                        :effect/target :any-player}
                       {:effect/type :discard
                        :effect/count 3
                        :effect/selection :player}]}]})
