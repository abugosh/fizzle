;; Abeyance - Instant
;; Oracle: Until end of turn, target player can't cast instant or sorcery spells,
;;         and that player can't activate abilities that aren't mana abilities.
;;         Draw a card.
;; Scryfall verified: 2026-02-23
(ns fizzle.cards.white.abeyance
  "Abeyance card definition.

   Abeyance: 1W - Instant
   Until end of turn, target player can't cast instant or sorcery spells,
   and that player can't activate abilities that aren't mana abilities.
   Draw a card.")


(def card
  {:card/id :abeyance
   :card/name "Abeyance"  ; Scryfall: Abeyance
   :card/cmc 2  ; Scryfall: 2.0
   :card/mana-cost {:colorless 1 :white 1}  ; Scryfall: {1}{W}
   :card/colors #{:white}  ; Scryfall: ["W"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/text "Until end of turn, target player can't cast instant or sorcery spells, and that player can't activate abilities that aren't mana abilities.\nDraw a card."  ; Scryfall oracle_text

   ;; Cast-time targeting: target any player
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]

   ;; Effects: two restrictions + draw a card
   :card/effects [{:effect/type :add-restriction
                   :effect/target :targeted-player
                   :restriction/type :cannot-cast-instants-sorceries}
                  {:effect/type :add-restriction
                   :effect/target :targeted-player
                   :restriction/type :cannot-activate-non-mana-abilities}
                  {:effect/type :draw
                   :effect/amount 1}]})
