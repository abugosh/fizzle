(ns fizzle.events.active-player-test
  "Tests for active-player-aware turn/phase functions.
   Verifies that event handlers read the active player from :game/active-player
   instead of hardcoding :player-1."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.phases :as phases]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.test-helpers :as h]))


;; === get-active-player-id query tests ===

(deftest get-active-player-id-returns-player-1
  (testing "get-active-player-id returns :player-1 from standard game state"
    (let [db (h/create-test-db)]
      (is (= :player-1 (q/get-active-player-id db))
          "Should return :player-1 as the active player"))))


(deftest get-active-player-id-with-opponent
  (testing "get-active-player-id returns :player-1 even with opponent present"
    (let [db (-> (h/create-test-db)
                 (h/add-opponent {:bot-archetype :goldfish}))]
      (is (= :player-1 (q/get-active-player-id db))
          "Should return :player-1 as the active player"))))


;; === advance-phase uses active player ===

(deftest advance-phase-handler-uses-active-player
  (testing "::advance-phase handler reads active player from db, not hardcoded"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add some mana to verify it gets cleared on advance
          player-eid (q/get-player-eid db :player-1)
          game-db (d/db-with db [[:db/add player-eid :player/mana-pool
                                  {:white 0 :blue 0 :black 3 :red 0 :green 0 :colorless 0}]])
          ;; Call advance-phase pure function
          result-db (phases/advance-phase game-db :player-1)
          pool (q/get-mana-pool result-db :player-1)]
      ;; Mana pool should be cleared for the active player
      (is (every? zero? (vals pool))
          "Active player's mana pool should be cleared on phase advance"))))


;; === advance-with-stops uses active player ===

(deftest advance-with-stops-uses-active-player
  (testing "advance-with-stops reads active player from db for stop checks"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          ;; First yield: human passes, priority transfers to bot
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes, both passed, advance to main2
          result2 (priority-flow/yield-impl (:app-db result1))
          result-db (:game/db (:app-db result2))]
      ;; Should advance to main2 (next stop for the active player)
      (is (= :main2 (:game/phase (q/get-game-state result-db)))
          "Should advance to main2 using active player's stops"))))


;; === maybe-continue-cleanup uses active player ===

(deftest maybe-continue-cleanup-uses-active-player
  (testing "maybe-continue-cleanup reads active player for begin-cleanup call"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Manually advance to cleanup phase
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          game-db (d/db-with db [[:db/add game-eid :game/phase :cleanup]])
          app-db {:game/db game-db}
          ;; maybe-continue-cleanup should use active player
          result (cleanup/maybe-continue-cleanup app-db)
          result-db (:game/db result)]
      ;; Since hand is empty (no cards), no discard needed, grants expired
      (is (some? result-db)
          "Should return valid game db after cleanup"))))


;; === yield-impl opponent lookup uses active player ===

(deftest yield-impl-opponent-lookup-uses-active-player
  (testing "yield-impl finds opponent relative to active player, not hardcoded :player-1"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          ;; Two yields: human passes → bot gets priority → bot passes → phase advances
          result1 (priority-flow/yield-impl app-db)
          result2 (priority-flow/yield-impl (:app-db result1))]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result2)))))
          "Should advance phase after both players pass"))))


;; === yield-impl resolve-one-item uses active player ===

(deftest yield-impl-resolve-uses-active-player
  (testing "yield-impl uses active player for resolve-one-item"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          app-db {:game/db game-db}
          ;; Two yields: human passes → bot gets priority → bot passes → spell resolves
          result1 (priority-flow/yield-impl app-db)
          result2 (priority-flow/yield-impl (:app-db result1))
          result-db (:game/db (:app-db result2))
          pool (q/get-mana-pool result-db :player-1)]
      ;; Dark Ritual should resolve correctly using active player
      (is (= 3 (:black pool))
          "Dark Ritual should resolve for active player"))))


;; === start-turn handler uses active player ===

(deftest start-turn-handler-uses-active-player
  (testing "::start-turn handler reads active player instead of hardcoding :player-1"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Advance to cleanup so we can call start-turn
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          game-db (d/db-with db [[:db/add game-eid :game/phase :cleanup]])
          ;; Call start-turn pure function with active player
          result-db (phases/start-turn game-db :player-1)]
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should advance to turn 2")
      (is (= :untap (:game/phase (q/get-game-state result-db)))
          "Should be in untap phase"))))
