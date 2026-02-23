(ns fizzle.cards.red.recoup
  "Recoup card definition.

   Recoup: 1R - Sorcery
   Target sorcery card in your graveyard gains flashback until end of turn.
   The flashback cost is equal to the card's mana cost.
   Flashback {3}{R}")


(def card
  {:card/id :recoup
   :card/name "Recoup"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Target sorcery card in your graveyard gains flashback until end of turn. The flashback cost is equal to the card's mana cost. Flashback {3}{R}"

   ;; Cast-time targeting: target sorcery in your graveyard
   :card/targeting [{:target/id :graveyard-sorcery
                     :target/type :object
                     :target/zone :graveyard
                     :target/controller :self
                     :target/criteria {:match/types #{:sorcery}}
                     :target/required true}]

   ;; Effect: grant flashback to target
   :card/effects [{:effect/type :grant-flashback
                   :effect/target-ref :graveyard-sorcery}]

   ;; Recoup's own flashback
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 3 :red 1}
                           :alternate/on-resolve :exile}]})
