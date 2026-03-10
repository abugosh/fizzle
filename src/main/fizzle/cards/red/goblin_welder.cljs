;; Goblin Welder - Creature
;; Oracle: {T}: Choose target artifact a player controls and target artifact card
;;   in that player's graveyard. Simultaneously sacrifice the first artifact and
;;   return the second artifact from that player's graveyard to the battlefield.
;; Scryfall verified: 2026-03-10
(ns fizzle.cards.red.goblin-welder
  "Goblin Welder card definition.

   Goblin Welder: {R} - Creature — Goblin Artificer 1/1
   {T}: Choose target artifact a player controls and target artifact card
   in that player's graveyard. Simultaneously sacrifice the first artifact
   and return the second artifact from that player's graveyard to the battlefield.")


(def card
  {:card/id :goblin-welder
   :card/name "Goblin Welder"
   :card/cmc 1
   :card/mana-cost {:red 1}
   :card/colors #{:red}
   :card/types #{:creature}
   :card/subtypes #{:goblin :artificer}
   :card/power 1
   :card/toughness 1
   :card/text "{T}: Choose target artifact a player controls and target artifact card in that player's graveyard. Simultaneously sacrifice the first artifact and return the second artifact from that player's graveyard to the battlefield."

   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true}
                     :ability/description "Simultaneously sacrifice target artifact a player controls and return target artifact from that player's graveyard to the battlefield"
                     ;; Two targeting requirements:
                     ;; 1. :welder-bf — artifact on battlefield (any controller)
                     ;; 2. :welder-gy — artifact in that player's graveyard (same controller as :welder-bf)
                     :ability/targeting [{:target/id :welder-bf
                                          :target/type :object
                                          :target/zone :battlefield
                                          :target/controller :any
                                          :target/criteria {:match/types #{:artifact}}
                                          :target/required true}
                                         {:target/id :welder-gy
                                          :target/type :object
                                          :target/zone :graveyard
                                          :target/controller :any
                                          :target/criteria {:match/types #{:artifact}}
                                          :target/same-controller-as :welder-bf
                                          :target/required true}]
                     :ability/effects [{:effect/type :welder-swap
                                        :effect/target-ref :welder-bf
                                        :effect/graveyard-ref :welder-gy}]}]})
