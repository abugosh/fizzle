;; Duress - Sorcery
;; Oracle: Target opponent reveals their hand. You choose a noncreature,
;;         nonland card from it. That player discards that card.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.black.duress
  "Duress card definition.

   Duress: B - Sorcery
   Target opponent reveals their hand. You choose a noncreature, nonland
   card from it. That player discards that card.")


(def card
  {:card/id :duress
   :card/name "Duress"  ; Scryfall: Duress
   :card/cmc 1  ; Scryfall: 1.0
   :card/mana-cost {:black 1}  ; Scryfall: {B}
   :card/colors #{:black}  ; Scryfall: ["B"]
   :card/types #{:sorcery}  ; Scryfall: Sorcery
   :card/text "Target opponent reveals their hand. You choose a noncreature, nonland card from it. That player discards that card."  ; Scryfall oracle_text

   ;; Cast-time targeting: target opponent only
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:opponent}
                     :target/required true}]

   ;; Effect: reveal hand + choose noncreature/nonland to discard
   ;; :discard-from-revealed-hand shows the full hand but only allows
   ;; selecting cards matching :effect/criteria
   :card/effects [{:effect/type :discard-from-revealed-hand
                   :effect/target :targeted-player
                   :effect/criteria {:match/not-types #{:creature :land}}}]})
