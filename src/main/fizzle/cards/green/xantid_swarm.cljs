;; Xantid Swarm - Creature
;; Oracle: Flying. Whenever Xantid Swarm attacks, defending player can't cast spells this turn.
;; Scryfall verified: 2026-03-09
(ns fizzle.cards.green.xantid-swarm
  "Xantid Swarm card definition.

   Xantid Swarm: G - 0/1 Creature — Insect
   Flying
   Whenever Xantid Swarm attacks, defending player can't cast spells this turn.")


(def card
  {:card/id :xantid-swarm
   :card/name "Xantid Swarm"
   :card/cmc 1
   :card/mana-cost {:green 1}
   :card/colors #{:green}
   :card/types #{:creature}
   :card/subtypes #{:insect}
   :card/power 0
   :card/toughness 1
   :card/keywords #{:flying}
   :card/text "Flying\nWhenever Xantid Swarm attacks, defending player can't cast spells this turn."
   :card/triggers [{:trigger/type :creature-attacks
                    :trigger/description "defending player can't cast spells this turn"
                    :trigger/effects [{:effect/type :add-restriction
                                       :effect/target :opponent
                                       :restriction/type :cannot-cast-spells}]}]})
