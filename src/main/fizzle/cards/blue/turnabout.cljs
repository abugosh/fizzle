(ns fizzle.cards.blue.turnabout
  "Turnabout card definition.

   Turnabout: {2}{U}{U} - Instant
   Choose artifact, creature, or land. Tap all untapped permanents of the
   chosen type target player controls, or untap all tapped permanents of
   that type that player controls.")


(def ^:private player-targeting
  "Shared targeting requirement for all Turnabout modes."
  {:target/id :player
   :target/type :player
   :target/options [:self :opponent]
   :target/required true})


(def card
  {:card/id :turnabout
   :card/name "Turnabout"
   :card/cmc 4
   :card/mana-cost {:colorless 2 :blue 2}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose artifact, creature, or land. Tap all untapped permanents of the chosen type target player controls, or untap all tapped permanents of that type that player controls."

   :card/modes
   [{:mode/id :tap-artifacts
     :mode/label "Tap all untapped artifacts target player controls"
     :mode/targeting [player-targeting]
     :mode/effects [{:effect/type :tap-all
                     :effect/permanent-type :artifact
                     :effect/target :targeted-player}]}
    {:mode/id :untap-artifacts
     :mode/label "Untap all tapped artifacts target player controls"
     :mode/targeting [player-targeting]
     :mode/effects [{:effect/type :untap-all
                     :effect/permanent-type :artifact
                     :effect/target :targeted-player}]}
    {:mode/id :tap-creatures
     :mode/label "Tap all untapped creatures target player controls"
     :mode/targeting [player-targeting]
     :mode/effects [{:effect/type :tap-all
                     :effect/permanent-type :creature
                     :effect/target :targeted-player}]}
    {:mode/id :untap-creatures
     :mode/label "Untap all tapped creatures target player controls"
     :mode/targeting [player-targeting]
     :mode/effects [{:effect/type :untap-all
                     :effect/permanent-type :creature
                     :effect/target :targeted-player}]}
    {:mode/id :tap-lands
     :mode/label "Tap all untapped lands target player controls"
     :mode/targeting [player-targeting]
     :mode/effects [{:effect/type :tap-all
                     :effect/permanent-type :land
                     :effect/target :targeted-player}]}
    {:mode/id :untap-lands-all
     :mode/label "Untap all tapped lands target player controls"
     :mode/targeting [player-targeting]
     :mode/effects [{:effect/type :untap-all
                     :effect/permanent-type :land
                     :effect/target :targeted-player}]}]})
