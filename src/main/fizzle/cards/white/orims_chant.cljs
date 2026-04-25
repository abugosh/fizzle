(ns fizzle.cards.white.orims-chant
  "Orim's Chant card definition.

   Orim's Chant: W - Instant
   Kicker {W}
   Target player can't cast spells this turn.
   If this spell was kicked, creatures can't attack this turn.")


(def card
  {:card/id :orims-chant
   :card/name "Orim's Chant"
   :card/cmc 1
   :card/mana-cost {:white 1}
   :card/colors #{:white}
   :card/types #{:instant}
   :card/text "Kicker {W}. Target player can't cast spells this turn. If this spell was kicked, creatures can't attack this turn."

   ;; Cast-time targeting: target any player
   ;; Uses :card/targeting vector format (see targeting.cljs)
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]

   ;; Base effects (primary mode — not kicked)
   ;; :targeted-player resolves via :object/targets :player
   :card/effects [{:effect/type :add-restriction
                   :effect/target :targeted-player
                   :restriction/type :cannot-cast-spells}]

   ;; Kicked alternate cost: total mana cost is {WW} (base {W} + kicker {W})
   ;; :alternate/effects REPLACES :card/effects (replacement semantics per epic anti-pattern)
   ;; :alternate/targeting REPLACES :card/targeting (same for Orim's Chant)
   :card/alternate-costs [{:alternate/id :kicked
                           :alternate/kind :kicker
                           :alternate/label "Kicker — {W}"
                           :alternate/zone :hand
                           :alternate/mana-cost {:white 2}
                           :alternate/targeting [{:target/id :player
                                                  :target/type :player
                                                  :target/options #{:any-player}
                                                  :target/required true}]
                           :alternate/effects [{:effect/type :add-restriction
                                                :effect/target :targeted-player
                                                :restriction/type :cannot-cast-spells}
                                               {:effect/type :add-restriction
                                                :effect/target :targeted-player
                                                :restriction/type :cannot-attack}]
                           :alternate/on-resolve :graveyard}]})
