;; Tormod's Crypt - Artifact
;; Oracle: {T}, Sacrifice this artifact: Exile target player's graveyard.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.artifacts.tormods-crypt
  "Tormod's Crypt card definition.

   Tormod's Crypt: 0 - Artifact
   {T}, Sacrifice this artifact: Exile target player's graveyard.")


(def card
  {:card/id :tormods-crypt
   :card/name "Tormod's Crypt"  ; Scryfall: Tormod's Crypt
   :card/cmc 0  ; Scryfall: 0.0
   :card/mana-cost {}  ; Scryfall: {0}
   :card/colors #{}  ; Scryfall: colorless
   :card/types #{:artifact}  ; Scryfall: Artifact
   :card/text "{T}, Sacrifice this artifact: Exile target player's graveyard."  ; Scryfall oracle_text

   ;; Activated ability: Tap + sacrifice to exile target player's graveyard
   :card/abilities
   [{:ability/type :activated
     :ability/targeting [{:target/id :player
                          :target/type :player
                          :target/options #{:any-player}
                          :target/required true}]
     :ability/cost {:tap true
                    :sacrifice-self true}
     :ability/description "Exile target player's graveyard"
     :ability/effects [{:effect/type :exile-zone
                        :effect/target-ref :player
                        :effect/zone :graveyard}]}]})
