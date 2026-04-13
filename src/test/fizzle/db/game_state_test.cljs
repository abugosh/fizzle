(ns fizzle.db.game-state-test
  "Tests for db/game_state.cljs — identity constants and entity factories.

   These tests ensure the shared module provides exact default values and
   correct override semantics, preventing entity shape drift between consumers."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.game-state :as gs]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.events.init :refer [init-game-state]]))


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


;; === create-complete-player ===

(defn- make-test-conn
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    conn))


(deftest test-create-complete-player-registers-draw-step-trigger
  (testing "create-complete-player registers draw-step trigger"
    (let [conn (make-test-conn)
          _ (gs/create-complete-player conn gs/human-player-id {:player/name "Player"})
          db @conn
          triggers (d/q '[:find [?type ...] :where [?e :trigger/type ?type]] db)]
      (is (contains? (set triggers) :draw-step)))))


(deftest test-create-complete-player-registers-untap-step-trigger
  (testing "create-complete-player registers untap-step trigger"
    (let [conn (make-test-conn)
          _ (gs/create-complete-player conn gs/human-player-id {:player/name "Player"})
          db @conn
          triggers (d/q '[:find [?type ...] :where [?e :trigger/type ?type]] db)]
      (is (contains? (set triggers) :untap-step)))))


(deftest test-create-complete-player-overrides-merge-correctly
  (testing "overrides are merged into player entity without clobbering defaults"
    (let [conn (make-test-conn)
          _ (gs/create-complete-player conn gs/human-player-id {:player/name "Custom" :player/life 10})
          db @conn
          player (d/pull db [:player/name :player/life :player/storm-count :player/land-plays-left]
                         (q/get-player-eid db gs/human-player-id))]
      (is (= "Custom" (:player/name player)))
      (is (= 10 (:player/life player)))
      (is (= 0 (:player/storm-count player)))
      (is (= 1 (:player/land-plays-left player))))))


(deftest test-create-complete-player-twice-on-same-conn
  (testing "create-complete-player called twice (two players) on same conn — no collision"
    (let [conn (make-test-conn)
          _ (gs/create-complete-player conn gs/human-player-id {:player/name "Player"})
          p1-eid (q/get-player-eid @conn gs/human-player-id)
          _ (gs/create-game-entity-tx p1-eid {})
          _ (d/transact! conn (gs/create-game-entity-tx p1-eid {}))
          _ (gs/create-complete-player conn gs/opponent-player-id {:player/name "Opponent" :player/is-opponent true})
          db @conn
          p1 (q/get-player-eid db gs/human-player-id)
          p2 (q/get-player-eid db gs/opponent-player-id)]
      (is (some? p1))
      (is (some? p2))
      (is (not= p1 p2))
      ;; Both players get triggers: 2 players × 2 triggers = 4
      (is (= 4 (count (d/q '[:find [?e ...] :where [?e :trigger/type _]] db)))))))


(deftest test-create-complete-player-returns-player-eid
  (testing "returns the integer entity ID of the created player"
    (let [conn (make-test-conn)
          eid (gs/create-complete-player conn gs/human-player-id {:player/name "Player"})]
      (is (integer? eid))
      (is (= eid (q/get-player-eid @conn gs/human-player-id))))))


(deftest test-create-complete-player-player-id-from-constants
  (testing "player-id must come from game-state constants, not hardcoded literals"
    (let [conn (make-test-conn)
          _ (gs/create-complete-player conn gs/human-player-id {:player/name "Player"})
          _ (gs/create-complete-player conn gs/opponent-player-id {:player/name "Opponent" :player/is-opponent true})
          db @conn
          p1-eid (q/get-player-eid db gs/human-player-id)
          p2-eid (q/get-player-eid db gs/opponent-player-id)]
      ;; Both players exist and have the expected IDs from constants
      (is (some? p1-eid) "human player entity should exist")
      (is (some? p2-eid) "opponent player entity should exist")
      ;; Verify IDs via direct pull
      (is (= gs/human-player-id (:player/id (d/pull db [:player/id] p1-eid))))
      (is (= gs/opponent-player-id (:player/id (d/pull db [:player/id] p2-eid))))
      ;; Explicitly confirm :opponent is NOT used (ADR-016)
      (is (not= :opponent (:player/id (d/pull db [:player/id] p2-eid)))))))


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
