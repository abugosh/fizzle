(ns fizzle.cards.ill-gotten-gains
  "Card definition for Ill-Gotten Gains.

   Oracle text: Exile Ill-Gotten Gains. Each player discards their hand,
   then returns up to three cards from their graveyard to their hand.

   Key combo piece for Iggy Pop storm - enables recycling combo pieces
   from graveyard while disrupting opponent.")


;; Ill-Gotten Gains - Graveyard recursion with hand disruption
;; 2BB - Sorcery
;; Exile Ill-Gotten Gains. Each player discards their hand, then returns
;; up to three cards from their graveyard to their hand.
;;
;; Effects execute in APNAP order (active player, then non-active player):
;; 1. Exile this spell (instead of going to graveyard)
;; 2. Caster discards entire hand
;; 3. Opponent discards entire hand
;; 4. Caster selects 0-3 cards from their graveyard (player choice via UI)
;; 5. Opponent returns 0-3 random cards from their graveyard
(def ill-gotten-gains
  {:card/id :ill-gotten-gains
   :card/name "Ill-Gotten Gains"
   :card/cmc 4
   :card/mana-cost {:colorless 2 :black 2}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Exile Ill-Gotten Gains. Each player discards their hand, then returns up to three cards from their graveyard to their hand."
   :card/effects [;; Step 1: Exile the spell itself (not to graveyard)
                  {:effect/type :exile-self}

                  ;; Step 2: Caster discards their entire hand
                  {:effect/type :discard-hand
                   :effect/target :self}

                  ;; Step 3: Opponent discards their entire hand
                  {:effect/type :discard-hand
                   :effect/target :opponent}

                  ;; Step 4: Caster returns up to 3 cards from graveyard to hand
                  ;; :selection :player triggers UI for player to choose
                  {:effect/type :return-from-graveyard
                   :effect/target :self
                   :effect/count 3
                   :effect/selection :player}

                  ;; Step 5: Opponent returns up to 3 random cards from graveyard to hand
                  ;; :selection :random means no choice - random selection
                  {:effect/type :return-from-graveyard
                   :effect/target :opponent
                   :effect/count 3
                   :effect/selection :random}]})
