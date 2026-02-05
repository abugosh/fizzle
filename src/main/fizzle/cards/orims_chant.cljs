(ns fizzle.cards.orims-chant
  "Orim's Chant card definition.

   Orim's Chant: W - Instant
   Kicker {W}
   Target player can't cast spells this turn.
   If this spell was kicked, creatures can't attack this turn.")


(def orims-chant
  {:card/id :orims-chant
   :card/name "Orim's Chant"
   :card/cmc 1
   :card/mana-cost {:white 1}
   :card/colors #{:white}
   :card/types #{:instant}
   :card/text "Kicker {W}. Target player can't cast spells this turn. If this spell was kicked, creatures can't attack this turn."

   ;; Kicker cost - enables kicked mode via get-casting-modes
   :card/kicker {:white 1}

   ;; Cast-time targeting: target any player
   ;; Uses :card/targeting vector format (see targeting.cljs)
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]

   ;; Base effects (when not kicked)
   ;; :targeted-player resolves via :object/targets :player
   :card/effects [{:effect/type :add-restriction
                   :effect/target :targeted-player
                   :restriction/type :cannot-cast-spells}]

   ;; Kicked effects (replaces base effects when kicked)
   ;; Must include BOTH restrictions - kicked effects replace, not extend
   :card/kicked-effects [{:effect/type :add-restriction
                          :effect/target :targeted-player
                          :restriction/type :cannot-cast-spells}
                         {:effect/type :add-restriction
                          :effect/target :targeted-player
                          :restriction/type :cannot-attack}]})
