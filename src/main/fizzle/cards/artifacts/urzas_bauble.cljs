;; Urza's Bauble - Artifact
;; Oracle: {T}, Sacrifice this artifact: Look at a card at random in target
;; player's hand. You draw a card at the beginning of the next turn's upkeep.
;; Scryfall verified: 2026-03-02
(ns fizzle.cards.artifacts.urzas-bauble
  "Urza's Bauble card definition.

   Urza's Bauble: 0 - Artifact
   {T}, Sacrifice this artifact: Look at a card at random in target player's
   hand. You draw a card at the beginning of the next turn's upkeep.")


(def card
  {:card/id :urzas-bauble
   :card/name "Urza's Bauble"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "{T}, Sacrifice this artifact: Look at a card at random in target player's hand. You draw a card at the beginning of the next turn's upkeep."

   ;; Activated ability: Tap + sacrifice to peek random card + delayed draw
   :card/abilities
   [{:ability/type :activated
     :ability/targeting [{:target/id :player
                          :target/type :player
                          :target/options #{:any-player}
                          :target/required true}]
     :ability/cost {:tap true
                    :sacrifice-self true}
     :ability/description "Look at a card at random in target player's hand. You draw a card at the beginning of the next turn's upkeep."
     :ability/effects [{:effect/type :peek-random-hand
                        :effect/target-ref :player}
                       {:effect/type :grant-delayed-draw
                        :effect/target :controller}]}]})
