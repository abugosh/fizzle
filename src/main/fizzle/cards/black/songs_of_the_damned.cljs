(ns fizzle.cards.black.songs-of-the-damned
  "Songs of the Damned card definition.

   Songs of the Damned: Instant
   Add {B} for each creature card in your graveyard.")


(def card
  {:card/id :songs-of-the-damned
   :card/name "Songs of the Damned"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add {B} for each creature card in your graveyard."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black {:dynamic/type :count-type-in-zone
                                         :dynamic/zone :graveyard
                                         :dynamic/card-type :creature}}}]})
