;; Nimble Mongoose - Creature
;; Oracle: Shroud. Threshold — Nimble Mongoose gets +2/+2.
;; Scryfall verified: 2026-03-04
(ns fizzle.cards.green.nimble-mongoose
  "Nimble Mongoose card definition.

   Nimble Mongoose: G - 1/1 Creature — Mongoose
   Shroud
   Threshold — Nimble Mongoose gets +2/+2.")


(def card
  {:card/id :nimble-mongoose
   :card/name "Nimble Mongoose"
   :card/cmc 1
   :card/mana-cost {:green 1}
   :card/colors #{:green}
   :card/types #{:creature}
   :card/subtypes #{:mongoose}
   :card/power 1
   :card/toughness 1
   :card/keywords #{:shroud}
   :card/text "Shroud\nThreshold — Nimble Mongoose gets +2/+2."
   :card/static-abilities [{:static/type :pt-modifier
                            :modifier/power 2
                            :modifier/toughness 2
                            :modifier/condition {:condition/type :threshold}
                            :modifier/applies-to :self}]})
