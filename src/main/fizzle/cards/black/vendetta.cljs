;; Vendetta - Instant
;; Oracle: Destroy target nonblack creature. It can't be regenerated.
;;   You lose life equal to that creature's toughness.
;; Scryfall verified: 2026-03-31
(ns fizzle.cards.black.vendetta
  "Vendetta card definition.

   Vendetta: {B} - Instant
   Destroy target nonblack creature. It can't be regenerated.
   You lose life equal to that creature's toughness.")


(def card
  {:card/id :vendetta
   :card/name "Vendetta"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Destroy target nonblack creature. It can't be regenerated. You lose life equal to that creature's toughness."
   :card/targeting [{:target/id :target-creature
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/types #{:creature}
                                       :match/not-colors #{:black}}
                     :target/required true}]
   :card/effects [{:effect/type :destroy
                   :effect/target-ref :target-creature}
                  {:effect/type :lose-life-equal-to-toughness
                   :effect/target-ref :target-creature}]})
