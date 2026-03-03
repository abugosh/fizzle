(ns fizzle.cards.artifacts.defense-grid-test
  "Tests for Defense Grid static cost modifier.

   Defense Grid: {2} - Artifact
   Each spell costs {3} more to cast except during its controller's turn."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.defense-grid :as defense-grid]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helpers
;; =====================================================

(defn- set-active-player
  "Set the active player for the game. Returns updated db."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/active-player player-eid]])))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

(deftest defense-grid-card-definition-test
  (testing "Defense Grid card definition is complete and correct"
    (let [card defense-grid/card]
      (is (= :defense-grid (:card/id card))
          "Card ID should be :defense-grid")
      (is (= "Defense Grid" (:card/name card))
          "Card name should be 'Defense Grid'")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 2} (:card/mana-cost card))
          "Mana cost should be {2}")
      (is (= #{} (:card/colors card))
          "Should be colorless")
      (is (= #{:artifact} (:card/types card))
          "Should be exactly an artifact")
      (is (= "Each spell costs {3} more to cast except during its controller's turn."
             (:card/text card))
          "Card text should match")))

  (testing "Defense Grid has exactly one static ability with condition"
    (let [abilities (:card/static-abilities defense-grid/card)]
      (is (= 1 (count abilities))
          "Should have exactly 1 static ability")
      (let [ability (first abilities)]
        (is (= :cost-modifier (:static/type ability))
            "Static type should be :cost-modifier")
        (is (= 3 (:modifier/amount ability))
            "Modifier amount should be 3")
        (is (= :increase (:modifier/direction ability))
            "Modifier direction should be :increase")
        (is (= {:condition/type :not-casters-turn}
               (:modifier/condition ability))
            "Should have :not-casters-turn condition")
        (is (nil? (:modifier/criteria ability))
            "Should have no criteria (applies to all spells)")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest defense-grid-cast-and-resolve-test
  (testing "Defense Grid enters battlefield as permanent when cast and resolved"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :defense-grid :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db obj-id))
          "Defense Grid should be on battlefield after resolution")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))
          "Mana pool should be empty after paying {2}"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest defense-grid-cannot-cast-without-mana-test
  (testing "Defense Grid not castable without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :defense-grid :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest defense-grid-cannot-cast-with-insufficient-mana-test
  (testing "Defense Grid not castable with only 1 mana"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :defense-grid :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 1 colorless"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest defense-grid-cast-increments-storm-test
  (testing "Casting Defense Grid increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :defense-grid :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db :player-1))
          "Storm count should be 1 after casting Defense Grid"))))


;; =====================================================
;; Engine Tests: modifier-applies? with condition
;; =====================================================

(deftest defense-grid-modifier-applies-not-casters-turn-test
  (testing "Modifier applies when caster is NOT the active player"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _grid-id] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          spell-card (:object/card (q/get-object db dr-id))
          modifiers (static-abilities/get-cost-modifiers db)
          modifier (first modifiers)]
      ;; Active player is player-1 (default), player-2 is casting
      (is (= :player-1 (q/get-active-player-id db))
          "Active player should be player-1")
      (is (static-abilities/modifier-applies? db :player-2 spell-card modifier)
          "Modifier should apply to player-2 casting on player-1's turn")))

  (testing "Modifier does NOT apply when caster IS the active player"
    (let [db (th/create-test-db)
          [db _grid-id] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db dr-id))
          modifiers (static-abilities/get-cost-modifiers db)
          modifier (first modifiers)]
      ;; Active player is player-1, player-1 is casting
      (is (= :player-1 (q/get-active-player-id db))
          "Active player should be player-1")
      (is (false? (static-abilities/modifier-applies? db :player-1 spell-card modifier))
          "Modifier should NOT apply to active player casting on their own turn"))))


;; =====================================================
;; Integration: can-cast? with Defense Grid
;; =====================================================

(deftest defense-grid-no-tax-on-own-turn-test
  (testing "Active player's spells are NOT taxed by Defense Grid"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      ;; player-1 is active player, so Defense Grid does not apply
      (is (rules/can-cast? db :player-1 obj-id)
          "Dark Ritual should be castable for {B} on own turn with Defense Grid"))))


(deftest defense-grid-taxes-non-active-player-test
  (testing "Non-active player's spells cost {3} more with Defense Grid"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Active player is player-1, opponent (player-2) needs {B} + {3}
          ;; With only {B}, should fail
          db (mana/add-mana db :player-2 {:black 1})]
      (is (false? (rules/can-cast? db :player-2 obj-id))
          "Opponent's Dark Ritual should need {B} + {3} on player-1's turn")))

  (testing "Non-active player can cast with enough mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Give opponent {B} + {3} = 4 mana total
          db (mana/add-mana db :player-2 {:black 1 :colorless 3})]
      (is (rules/can-cast? db :player-2 obj-id)
          "Opponent's Dark Ritual should be castable with {B}{3} on player-1's turn"))))


(deftest defense-grid-switches-with-active-player-test
  (testing "Defense Grid tax switches based on whose turn it is"
    (let [db (th/create-test-db {:mana {:black 1}})
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      ;; On player-1's turn, no tax
      (is (rules/can-cast? db :player-1 obj-id)
          "Player-1 should cast Dark Ritual for {B} on own turn")
      ;; Switch active player to player-2
      (let [db (set-active-player db :player-2)]
        (is (false? (rules/can-cast? db :player-1 obj-id))
            "Player-1's Dark Ritual should need extra mana on opponent's turn")))))


;; =====================================================
;; Integration: Full cast flow with Defense Grid
;; =====================================================

(deftest cast-spell-with-defense-grid-pays-extra-test
  (testing "Casting on opponent's turn pays {3} extra"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1 :colorless 3})
          db-cast (rules/cast-spell db :player-2 obj-id)]
      (is (= :stack (th/get-object-zone db-cast obj-id))
          "Dark Ritual should be on stack")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db-cast :player-2))
          "Opponent should have used all mana ({B} + {3})"))))


(deftest cast-spell-on-own-turn-no-extra-cost-test
  (testing "Casting on own turn pays normal cost"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= :stack (th/get-object-zone db-cast obj-id))
          "Dark Ritual should be on stack")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db-cast :player-1))
          "Should have used only {B}"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest defense-grid-in-graveyard-no-effect-test
  (testing "Defense Grid in graveyard does not modify costs"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :graveyard :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})]
      (is (rules/can-cast? db :player-2 obj-id)
          "Opponent's Dark Ritual castable with {B} when Defense Grid is in graveyard"))))


(deftest defense-grid-in-hand-no-effect-test
  (testing "Defense Grid in hand does not modify costs"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :hand :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})]
      (is (rules/can-cast? db :player-2 obj-id)
          "Opponent's Dark Ritual castable with {B} when Defense Grid is in hand"))))


(deftest multiple-defense-grids-stack-test
  (testing "Two Defense Grids add {6} to off-turn spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:black 1})]
      (is (= {:black 1 :colorless 6} result)
          "Two Defense Grids should add {6} to opponent's Dark Ritual cost"))))


(deftest defense-grid-stacks-with-sphere-test
  (testing "Defense Grid + Sphere of Resistance stack for off-turn casting"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:black 1})]
      ;; Defense Grid adds {3}, Sphere adds {1} = {4} total
      (is (= {:black 1 :colorless 4} result)
          "Defense Grid ({3}) + Sphere ({1}) should add {4} for off-turn casting")))

  (testing "On own turn, only Sphere applies (not Defense Grid)"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      ;; Only Sphere adds {1}, Defense Grid does not apply on own turn
      (is (= {:black 1 :colorless 1} result)
          "Only Sphere ({1}) should apply on own turn, not Defense Grid"))))


(deftest defense-grid-zero-cost-artifact-off-turn-test
  (testing "Zero-cost artifact costs {3} on opponent's turn with Defense Grid"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)]
      (is (false? (rules/can-cast? db :player-2 obj-id))
          "Lotus Petal should NOT be free on opponent's turn with Defense Grid"))
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (mana/add-mana db :player-2 {:colorless 3})]
      (is (rules/can-cast? db :player-2 obj-id)
          "Lotus Petal should be castable with {3} on opponent's turn"))))


(deftest defense-grid-zero-cost-artifact-own-turn-test
  (testing "Zero-cost artifact is free on own turn despite Defense Grid"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Lotus Petal should be free on own turn with Defense Grid"))))


(deftest defense-grid-does-not-affect-activated-abilities-test
  (testing "Lotus Petal mana ability is unaffected by Defense Grid"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db (mana/add-mana db :player-2 {})
          db (engine-mana/activate-mana-ability db :player-2 petal-id :black)]
      (is (= 1 (get (q/get-mana-pool db :player-2) :black))
          "Lotus Petal should still produce 1 mana despite Defense Grid"))))


(deftest defense-grid-symmetric-test
  (testing "Defense Grid owned by opponent also taxes player on opponent's turn"
    (let [db (th/create-test-db {:mana {:black 1}})
          db (th/add-opponent db)
          ;; Defense Grid on opponent's battlefield
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-2)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Active player is player-1, so player-1 is on their own turn - no tax
          ]
      (is (rules/can-cast? db :player-1 obj-id)
          "Player-1 should cast Dark Ritual for {B} on own turn despite opponent's Defense Grid"))
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Defense Grid on opponent's battlefield
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-2)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Switch to opponent's turn
          db (set-active-player db :player-2)
          db (mana/add-mana db :player-1 {:black 1})]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Player-1's Dark Ritual should need extra mana on opponent's turn with opponent's Defense Grid"))))


(deftest defense-grid-flashback-off-turn-test
  (testing "Flashback cost is also modified by Defense Grid on opponent's turn"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-2)
          ;; Flashback cost is {1}{U}, with Defense Grid on opponent's turn: {4}{U}
          ;; With only {1}{U} it should fail
          db (mana/add-mana db :player-2 {:colorless 1 :blue 1})]
      (is (false? (rules/can-cast? db :player-2 obj-id))
          "Flashback should need extra mana with Defense Grid on opponent's turn"))
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-2)
          ;; With {4}{U} it should succeed
          db (mana/add-mana db :player-2 {:colorless 4 :blue 1})]
      (is (rules/can-cast? db :player-2 obj-id)
          "Flashback should be castable with {4}{U} when Defense Grid adds {3}"))))


(deftest defense-grid-flashback-own-turn-test
  (testing "Flashback cost is NOT modified by Defense Grid on own turn"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Flashback should cost normal {1}{U} on own turn with Defense Grid"))))
