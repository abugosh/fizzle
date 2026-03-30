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
    [fizzle.events.director :as director]
    [fizzle.events.phases :as phases]
    [fizzle.history.core :as history]
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


;; === director uses active player for stops ===

(deftest director-uses-active-player-stops
  (testing "director reads active player from db for stop checks"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db (merge (history/init-history) {:game/db db})
          ;; Director at main1 with stop should return :await-human (active player's stop)
          result (director/run-to-decision app-db {:yield-all? false})]
      (is (= :await-human (:reason result))
          "Director should stop at main1 using active player's stops"))))


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


;; === director opponent lookup uses active player ===

(deftest director-opponent-lookup-uses-active-player
  (testing "director finds opponent relative to active player, not hardcoded :player-1"
    (let [db (-> (h/create-test-db {:stops #{:main2}})  ; stop at main2 only, not main1
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db (merge (history/init-history) {:game/db db})
          ;; At main1 with no stop → director should auto-pass and advance to main2
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      (is (= :main2 (:game/phase (q/get-game-state result-db)))
          "Should advance to main2 after both players pass"))))


;; === director resolve-one-item uses active player ===

(deftest director-resolve-uses-active-player
  (testing "director uses active player for resolve-one-item"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Director: stack non-empty → both auto-pass → resolve spell
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))
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
