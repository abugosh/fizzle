;; Hunting Pack - Instant
;; Oracle: Create a 4/4 green Beast creature token. Storm.
;; Scryfall verified: 2026-03-09
(ns fizzle.cards.green.hunting-pack
  "Hunting Pack card definition.

   Hunting Pack: {5}{G}{G} - Instant
   Create a 4/4 green Beast creature token. Storm.")


(def card
  {:card/id :hunting-pack
   :card/name "Hunting Pack"  ; Scryfall: Hunting Pack
   :card/cmc 7  ; Scryfall: 7.0
   :card/mana-cost {:colorless 5 :green 2}  ; Scryfall: {5}{G}{G}
   :card/colors #{:green}  ; Scryfall: ["G"]
   :card/types #{:instant}  ; Scryfall: Instant
   :card/keywords #{:storm}  ; Scryfall: Storm
   :card/text "Create a 4/4 green Beast creature token. Storm."  ; Scryfall oracle_text

   ;; Effect: create a 4/4 green Beast token
   :card/effects [{:effect/type :create-token
                   :effect/token {:token/name "Beast"
                                  :token/types #{:creature}
                                  :token/subtypes #{:beast}
                                  :token/colors #{:green}
                                  :token/power 4
                                  :token/toughness 4}}]})
