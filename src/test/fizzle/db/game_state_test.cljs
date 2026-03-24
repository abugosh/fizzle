(ns fizzle.db.game-state-test
  "Tests for db/game_state.cljs — identity constants and entity factories.

   These tests ensure the shared module provides exact default values and
   correct override semantics, preventing entity shape drift between consumers."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.game-state :as gs]
    [fizzle.events.init :refer [init-game-state]]))


;; === Constants ===

(deftest test-human-player-id-is-player-1
  (testing "human-player-id is :player-1"
    (is (= :player-1 gs/human-player-id))))


(deftest test-opponent-player-id-is-player-2
  (testing "opponent-player-id is :player-2 (not :opponent — ADR-016)"
    (is (= :player-2 gs/opponent-player-id))))


(deftest test-player-ids-differ
  (testing "human-player-id and opponent-player-id are distinct"
    (is (not= gs/human-player-id gs/opponent-player-id))))


;; === create-player-tx ===

(deftest test-create-player-tx-defaults
  (testing "returns all required default fields with exact values"
    (let [tx (gs/create-player-tx :player-1 {})]
      (is (vector? tx))
      (is (= 1 (count tx)))
      (let [entity (first tx)]
        (is (= :player-1 (:player/id entity)))
        (is (= 20 (:player/life entity)))
        (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
               (:player/mana-pool entity)))
        (is (= 0 (:player/storm-count entity)))
        (is (= 1 (:player/land-plays-left entity)))
        (is (= 7 (:player/max-hand-size entity)))))))


(deftest test-create-player-tx-returns-vector
  (testing "returns a vector (required shape for d/transact!)"
    (let [result (gs/create-player-tx :player-1 {})]
      (is (vector? result)))))


(deftest test-create-player-tx-overrides-merge
  (testing "overrides replace defaults while other defaults remain"
    (let [tx (gs/create-player-tx :player-2 {:player/life 10 :player/is-opponent true})
          entity (first tx)]
      (is (= :player-2 (:player/id entity)))
      (is (= 10 (:player/life entity)))
      (is (true? (:player/is-opponent entity)))
      ;; other defaults still present
      (is (= 0 (:player/storm-count entity)))
      (is (= 1 (:player/land-plays-left entity)))
      (is (= 7 (:player/max-hand-size entity))))))


(deftest test-create-player-tx-mana-override
  (testing "mana-pool override replaces entire default pool"
    (let [tx (gs/create-player-tx :player-1 {:player/mana-pool {:blue 3 :black 0 :white 0 :red 0 :green 0 :colorless 0}})
          entity (first tx)]
      (is (= {:blue 3 :black 0 :white 0 :red 0 :green 0 :colorless 0}
             (:player/mana-pool entity))))))


(deftest test-create-player-tx-bot-archetype-override
  (testing "bot-archetype and is-opponent can both be set via overrides"
    (let [tx (gs/create-player-tx :player-2 {:player/bot-archetype :burn :player/is-opponent true})
          entity (first tx)]
      (is (= :player-2 (:player/id entity)))
      (is (= :burn (:player/bot-archetype entity)))
      (is (true? (:player/is-opponent entity))))))


;; === create-game-entity-tx ===

(deftest test-create-game-entity-tx-returns-vector
  (testing "returns a vector (required shape for d/transact!)"
    (is (vector? (gs/create-game-entity-tx 42 {})))))


(deftest test-create-game-entity-tx-defaults
  (testing "contains required game entity fields with default values"
    (let [tx (gs/create-game-entity-tx 42 {})
          entity (first tx)]
      (is (= :game-1 (:game/id entity)))
      (is (= 1 (:game/turn entity)))
      (is (= :main1 (:game/phase entity)))
      (is (= gs/human-player-id (:game/human-player-id entity))))))


(deftest test-create-game-entity-tx-uses-active-player
  (testing "sets active-player and priority to the provided eid"
    (let [mock-eid 99
          tx (gs/create-game-entity-tx mock-eid {})
          entity (first tx)]
      (is (= mock-eid (:game/active-player entity)))
      (is (= mock-eid (:game/priority entity))))))


(deftest test-create-game-entity-tx-overrides
  (testing "overrides replace default values"
    (let [tx (gs/create-game-entity-tx 42 {:game/phase :combat})
          entity (first tx)]
      (is (= :combat (:game/phase entity)))
      ;; other defaults unchanged
      (is (= 1 (:game/turn entity))))))


;; === Integration: init-game-state creates opponent as :player-2 ===

(deftest test-init-game-state-creates-opponent-as-player-2
  (testing "init-game-state creates opponent with player-id :player-2"
    (let [app-db (init-game-state {:main-deck [{:card/id :mountain :count 40}]})
          game-db (:game/db app-db)
          opp-id (d/q '[:find ?id .
                        :where
                        [?e :player/is-opponent true]
                        [?e :player/id ?id]]
                      game-db)]
      (is (= :player-2 opp-id)))))
