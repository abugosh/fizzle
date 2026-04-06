(ns fizzle.cards.red.fling-test
  "Tests for Fling card.
   Covers mandatory categories A-D + E targeting + F target types + G edge cases."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.fling :as fling]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.casting :as casting]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest card-definition-test
  (testing "Fling card definition has correct fields"
    (let [card fling/card]
      (is (= :fling (:card/id card)))
      (is (= "Fling" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:red 1 :colorless 1} (:card/mana-cost card)))
      (is (= #{:red} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "As an additional cost to cast this spell, sacrifice a creature.\nFling deals damage equal to the sacrificed creature's power to any target."
             (:card/text card))))))


(deftest fling-has-sacrifice-additional-cost-test
  (testing "Fling has :sacrifice-permanent additional cost with creature match criteria"
    (let [costs (:card/additional-costs fling/card)
          sac-cost (first (filter #(= :sacrifice-permanent (:cost/type %)) costs))]
      (is (= #{:creature} (get-in sac-cost [:cost/criteria :match/types]))
          "Sacrifice cost should match creatures"))))


(deftest fling-has-any-target-test
  (testing "Fling targeting uses :target/type :any"
    (let [targeting (:card/targeting fling/card)
          req (first targeting)]
      (is (= 1 (count targeting)) "Should have exactly 1 targeting requirement")
      (is (= :target (:target/id req)))
      (is (= :any (:target/type req))
          "Fling should target :any (player or creature)")
      (is (true? (:target/required req))))))


(deftest fling-has-deal-damage-effect-test
  (testing "Fling has :deal-damage effect using :sacrificed-power dynamic"
    (let [effects (:card/effects fling/card)
          dmg-effect (first effects)]
      (is (= 1 (count effects)) "Should have exactly 1 effect")
      (is (= :deal-damage (:effect/type dmg-effect)))
      (is (= {:dynamic/type :sacrificed-power} (:effect/amount dmg-effect)))
      (is (= :target (:effect/target-ref dmg-effect))))))


;; =====================================================
;; B. Cast-Resolve Happy Path — target player
;; =====================================================

(deftest fling-resolves-deal-damage-to-player-test
  (testing "Fling resolves and deals damage equal to sacrificed creature's power to a player"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          ;; Cast Fling — shows sacrifice selection
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          ;; Confirm sacrifice, chains to targeting
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})
          ;; Confirm target player-2
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          ;; Resolve Fling
          resolve-result (th/resolve-top db)
          result-db (:db resolve-result)
          opp-life (q/get-life-total result-db :player-2)]
      ;; Nimble Mongoose is 1/1, so 1 damage → 20 - 1 = 19
      (is (= 19 opp-life)
          "Fling should deal 1 damage (1/1 creature) to opponent"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest fling-cannot-cast-without-creature-test
  (testing "Fling cannot be cast when no creatures on battlefield"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 fling-id))
          "Fling should not be castable without a creature to sacrifice"))))


(deftest fling-cannot-cast-without-mana-test
  (testing "Fling cannot be cast without {1}{R}"
    (let [db (th/create-test-db {:mana {:red 0 :colorless 0}})
          db (th/add-opponent db)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 fling-id))
          "Fling should not be castable without mana"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest fling-increments-storm-count-test
  (testing "Casting Fling increments storm count"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          initial-storm (or (q/get-storm-count db :player-1) 0)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          final-storm (or (q/get-storm-count db :player-1) 0)]
      (is (= (inc initial-storm) final-storm)
          "Storm count should increment when Fling is cast"))))


;; =====================================================
;; E. Selection/Modal Tests (sacrifice selection)
;; =====================================================

(deftest fling-shows-sacrifice-selection-test
  (testing "Casting Fling shows sacrifice selection with creatures on battlefield"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          pending-sel (:game/pending-selection app-db)]
      (is (= :sacrifice-permanent-cost (:selection/type pending-sel))
          "Should show sacrifice selection")
      (is (false? (:selection/auto-confirm? pending-sel))
          "auto-confirm? should be false — player always picks"))))


(deftest fling-sacrifice-chains-to-targeting-test
  (testing "Confirming sacrifice chains to cast-time-targeting for any-target"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {selection :selection} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})]
      (is (= :cast-time-targeting (:selection/type selection))
          "Should chain to cast-time-targeting"))))


;; =====================================================
;; F. Targeting Tests — :target/type :any
;; =====================================================

(deftest any-target-includes-players-test
  (testing ":target/type :any returns both players as valid targets"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          target-req {:target/type :any
                      :target/required true}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (some #{:player-1} targets) "Should include self as valid target")
      (is (some #{:player-2} targets) "Should include opponent as valid target"))))


(deftest any-target-includes-battlefield-creatures-test
  (testing ":target/type :any returns battlefield creatures (without shroud) as valid targets"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Phyrexian Devourer has no shroud — valid target
          [db creature-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          target-req {:target/type :any
                      :target/required true}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (some #{creature-id} targets) "Should include battlefield creature as valid target")
      (is (some #{:player-1} targets) "Should also include players"))))


(deftest any-target-excludes-non-battlefield-creatures-test
  (testing ":target/type :any does NOT include creatures in hand/graveyard"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db hand-creature-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          [db gy-creature-id] (th/add-card-to-zone db :nimble-mongoose :graveyard :player-1)
          target-req {:target/type :any
                      :target/required true}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (not (some #{hand-creature-id} targets)) "Should NOT include hand creature")
      (is (not (some #{gy-creature-id} targets)) "Should NOT include graveyard creature"))))


(deftest fling-valid-targets-include-creature-test
  (testing "Fling targeting shows non-shroud creatures and players as valid targets"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Nimble Mongoose (shroud) for sacrifice — not targetable
          [db _sac-creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          ;; Phyrexian Devourer (no shroud) — should be a valid target
          [db creature2-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-2)
          target-req (first (:card/targeting fling/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (some #{:player-1} targets) "Players should be valid targets")
      (is (some #{:player-2} targets) "Opponent player should be valid target")
      ;; Non-shroud creatures on battlefield should also be valid
      (is (some #{creature2-id} targets) "Opponent's non-shroud creature should be a valid target"))))


;; =====================================================
;; F continued. Fling targeting creature
;; =====================================================

(deftest fling-deals-damage-to-creature-test
  (testing "Fling deals damage equal to sacrificed power to a target creature"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Creature to sacrifice (nimble mongoose 1/1, has shroud but sacrifice doesn't target)
          [db sac-creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          ;; Creature to target (phyrexian devourer, bumped to 1/5 so it survives 1 damage)
          [db target-creature-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-2)
          target-eid (q/get-object-eid db target-creature-id)
          ;; Boost toughness to 5 so creature survives 1 damage (SBA won't trigger)
          db (d/db-with db [[:db/add target-eid :object/toughness 5]])
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          ;; Cast Fling
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          ;; Sacrifice sac-creature
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{sac-creature-id})
          ;; Target the opponent's creature
          {:keys [db]} (th/confirm-selection db selection #{target-creature-id})
          ;; Resolve Fling
          result-db (:db (th/resolve-top db))
          target-creature (q/get-object result-db target-creature-id)
          damage-marked (:object/damage-marked target-creature)]
      (is (= 1 damage-marked)
          "Target creature should have 1 damage marked (Nimble Mongoose 1/1 → 1 damage)"))))


(deftest fling-with-boosted-creature-deals-more-damage-test
  (testing "Fling with +1/+1 counter creature deals effective power damage to creature target"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db sac-creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db sac-creature-id)
          db (d/db-with db [[:db/add creature-eid :object/counters {:+1/+1 1}]])
          _ (is (= 2 (creatures/effective-power db sac-creature-id))
                "Creature should be 2/2 with counter")
          ;; Phyrexian Devourer as target, bumped to 1/10 so it survives 2 damage
          [db target-creature-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-2)
          target-eid (q/get-object-eid db target-creature-id)
          db (d/db-with db [[:db/add target-eid :object/toughness 10]])
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{sac-creature-id})
          {:keys [db]} (th/confirm-selection db selection #{target-creature-id})
          result-db (:db (th/resolve-top db))
          target-creature (q/get-object result-db target-creature-id)
          damage-marked (:object/damage-marked target-creature)]
      (is (= 2 damage-marked)
          "Target creature should have 2 damage marked (2/2 with +1/+1 counter)"))))


(deftest fling-zero-power-deals-no-damage-to-creature-test
  (testing "Fling with 0-power sacrifice deals 0 damage to creature (no damage-marked change)"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db sac-creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db sac-creature-id)
          ;; Add -1/-1 counter so effective power = 0
          db (d/db-with db [[:db/add creature-eid :object/counters {:-1/-1 1}]])
          _ (is (= 0 (creatures/effective-power db sac-creature-id))
                "Creature should have 0 power")
          ;; Phyrexian Devourer (1/1, no shroud) as target
          [db target-creature-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-2)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{sac-creature-id})
          {:keys [db]} (th/confirm-selection db selection #{target-creature-id})
          result-db (:db (th/resolve-top db))
          target-creature (q/get-object result-db target-creature-id)
          damage-marked (or (:object/damage-marked target-creature) 0)]
      (is (= 0 damage-marked)
          "Target creature should have 0 damage when sacrificed 0-power creature"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest deal-damage-still-works-for-player-target-test
  (testing ":deal-damage to player target still reduces life (no regression)"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          result-db (:db (th/resolve-top db))
          opp-life (q/get-life-total result-db :player-2)]
      (is (= 19 opp-life) "Opponent's life should be 19 (took 1 from 1/1 creature)"))))


(deftest fling-target-creature-not-on-battlefield-is-invalid-test
  (testing "creature in graveyard is NOT a valid target for :any targeting"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db gy-creature-id] (th/add-card-to-zone db :nimble-mongoose :graveyard :player-2)
          target-req {:target/type :any
                      :target/required true}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (not (some #{gy-creature-id} targets))
          "Graveyard creature should not be a valid :any target"))))


;; =====================================================
;; SBA: Fling dealing lethal damage should trigger game-over
;; =====================================================

(deftest fling-lethal-damage-triggers-life-zero-sba-test
  (testing "Fling dealing lethal damage to opponent triggers :life-zero SBA inline via reduce-effects"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Give opponent only 1 life so Nimble Mongoose (1/1) is lethal
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life 1]])
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          ;; Cast Fling
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          ;; Sacrifice creature → chains to targeting
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})
          ;; Target opponent
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          ;; Resolve Fling via pure function — SBAs fire via :db effect handler in production.
          ;; In tests, manually call check-and-execute-sbas (test helpers bypass re-frame).
          result-db (:db (th/resolve-top db))
          result-db (sba/check-and-execute-sbas result-db)
          opp-life (q/get-life-total result-db :player-2)
          game-state (q/get-game-state result-db)]
      (is (= 0 opp-life) "Opponent should be at 0 life")
      (is (= :life-zero (:game/loss-condition game-state))
          "SBA should set :life-zero after lethal damage")
      (let [winner-ref (:game/winner game-state)
            winner-pid (when winner-ref
                         (d/q '[:find ?pid .
                                :in $ ?eid
                                :where [?eid :player/id ?pid]]
                              result-db (:db/id winner-ref)))]
        (is (= :player-1 winner-pid)
            "SBA should set player-1 as winner after lethal damage")))))
