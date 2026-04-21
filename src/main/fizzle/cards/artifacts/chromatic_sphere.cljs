(ns fizzle.cards.artifacts.chromatic-sphere
  "Chromatic Sphere card definition.

   Chromatic Sphere: {1} - Artifact
   {1}, {T}, Sacrifice this artifact: Add one mana of any color. Draw a card.

   Key implementation notes:
   - The activated ability is a MANA ABILITY (2008-08-01 ruling): it says
     'Add one mana of any color' and does not target, so despite also
     drawing a card the entire ability is a mana ability and does not use
     the stack.
   - Cost is composite: mana {1} + tap + sacrifice-self.
   - :ability/produces {:any 1} — caller chooses color at activation time.
   - Draw effect runs as part of ability resolution. In real MTG the drawn
     card is not seen until the triggering spell/ability finishes
     (2008-08-01 ruling), but that distinction does not matter in a
     single-player practice tool, so the draw is materialized immediately.")


(def card
  {:card/id :chromatic-sphere
   :card/name "Chromatic Sphere"
   :card/cmc 1
   :card/mana-cost {:colorless 1}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "{1}, {T}, Sacrifice this artifact: Add one mana of any color. Draw a card."
   :card/abilities [{:ability/type :mana
                     :ability/cost {:mana {:colorless 1}
                                    :tap true
                                    :sacrifice-self true}
                     :ability/produces {:any 1}
                     :ability/effects [{:effect/type :draw
                                        :effect/amount 1}]}]})
