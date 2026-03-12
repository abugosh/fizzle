(ns fizzle.cards.blue.cloud-of-faeries
  "Cloud of Faeries card definition.

   Cloud of Faeries: {1}{U} - 1/1 Creature — Faerie
   Flying. When this creature enters, untap up to two lands.
   Cycling {2}")


(def card
  {:card/id :cloud-of-faeries
   :card/name "Cloud of Faeries"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:creature}
   :card/subtypes #{:faerie}
   :card/power 1
   :card/toughness 1
   :card/keywords #{:flying}
   :card/cycling {:colorless 2}
   :card/text "Flying. When this creature enters, untap up to two lands. Cycling {2}"
   :card/triggers [{:trigger/type :enters-battlefield
                    :trigger/effects [{:effect/type :untap-lands
                                       :effect/count 2}]
                    :trigger/description "When Cloud of Faeries enters, untap up to two lands."}]})
