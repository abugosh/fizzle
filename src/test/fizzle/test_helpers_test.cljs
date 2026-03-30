(ns fizzle.test-helpers-test
  "Tests for shared test helpers."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.test-helpers :as th]))


(deftest create-test-db-returns-valid-db-test
  (testing "create-test-db returns db with player entity, game entity, card definitions"
    (let [db (th/create-test-db)]
      ;; Player entity exists
      (is (pos-int? (q/get-player-eid db :player-1))
          "Player :player-1 should exist")
      ;; Game entity exists
      (is (map? (q/get-game-state db))
          "Game state should exist")
      ;; Card definitions loaded
      (is (pos-int? (d/q '[:find ?e .
                           :where [?e :card/id :dark-ritual]]
                         db))
          "Dark Ritual card def should be loaded")
      ;; Default mana pool is all zeros
      (let [pool (q/get-mana-pool db :player-1)]
        (is (= 0 (:black pool)))
        (is (= 0 (:blue pool)))
        (is (= 0 (:red pool)))))))


(deftest create-test-db-with-mana-opts-test
  (testing "create-test-db with mana opts sets specified colors, others zero"
    (let [db (th/create-test-db {:mana {:blue 3}})]
      (is (= 3 (:blue (q/get-mana-pool db :player-1))))
      (is (= 0 (:black (q/get-mana-pool db :player-1))))
      (is (= 0 (:red (q/get-mana-pool db :player-1)))))))


(deftest create-test-db-with-life-opts-test
  (testing "create-test-db with life opts sets life total"
    (let [db (th/create-test-db {:life 10})]
      (is (= 10 (q/get-life-total db :player-1))))))


(deftest add-card-to-zone-hand-test
  (testing "add-card-to-zone adds card to hand"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (= 1 (th/get-zone-count db' :hand :player-1)))
      (is (uuid? obj-id)))))


(deftest add-card-to-zone-library-sets-position-test
  (testing "add-card-to-zone for library sets :object/position 0"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :library :player-1)
          obj (q/get-object db' obj-id)]
      (is (= 0 (:object/position obj))))))


(deftest add-card-to-zone-invalid-card-id-test
  (testing "add-card-to-zone with invalid card-id throws"
    (let [db (th/create-test-db)]
      (is (thrown-with-msg? js/Error #"Unknown card-id"
            (th/add-card-to-zone db :nonexistent-card :hand :player-1))))))


(deftest add-cards-to-library-ordering-test
  (testing "add-cards-to-library sets positions 0, 1, 2"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :dark-ritual :dark-ritual]
                                                 :player-1)]
      (is (= 3 (count obj-ids)))
      (is (= 0 (:object/position (q/get-object db' (nth obj-ids 0)))))
      (is (= 1 (:object/position (q/get-object db' (nth obj-ids 1)))))
      (is (= 2 (:object/position (q/get-object db' (nth obj-ids 2))))))))


(deftest add-cards-to-library-empty-test
  (testing "add-cards-to-library with empty returns [db []] unchanged"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-library db [] :player-1)]
      (is (= [] obj-ids))
      (is (= db db')))))


(deftest add-cards-to-graveyard-multiple-test
  (testing "add-cards-to-graveyard adds multiple cards"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-graveyard db
                                                   [:dark-ritual :dark-ritual :dark-ritual]
                                                   :player-1)]
      (is (= 3 (count obj-ids)))
      (is (= 3 (th/get-zone-count db' :graveyard :player-1))))))


(deftest get-zone-count-empty-test
  (testing "get-zone-count returns 0 when no cards in zone"
    (let [db (th/create-test-db)]
      (is (= 0 (th/get-zone-count db :hand :player-1)))
      (is (= 0 (th/get-zone-count db :graveyard :player-1))))))


(deftest add-opponent-creates-player-2-test
  (testing "add-opponent creates :player-2 with is-opponent true"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)]
      (is (pos-int? (q/get-player-eid db' :player-2))
          "Player :player-2 should exist")
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/is-opponent :player/life] player-eid)]
        (is (true? (:player/is-opponent player)))
        (is (= 20 (:player/life player)))))))


(deftest add-opponent-with-bot-archetype-test
  (testing "add-opponent with :bot-archetype sets the archetype"
    (let [db (th/create-test-db)
          db' (th/add-opponent db {:bot-archetype :goldfish})]
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/bot-archetype :player/is-opponent] player-eid)]
        (is (= :goldfish (:player/bot-archetype player)))
        (is (true? (:player/is-opponent player)))))))


(deftest add-opponent-with-stops-test
  (testing "add-opponent with :stops sets the stops"
    (let [db (th/create-test-db)
          db' (th/add-opponent db {:stops #{:main1 :end}})]
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/stops] player-eid)]
        (is (= #{:main1 :end} (:player/stops player)))))))


(deftest add-opponent-zero-arity-backward-compat-test
  (testing "add-opponent 0-arity still works without opts"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)]
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/bot-archetype :player/stops] player-eid)]
        (is (nil? (:player/bot-archetype player)))
        (is (nil? (:player/stops player)))))))


(deftest create-test-db-with-stops-test
  (testing "create-test-db with :stops applies stops to player-1"
    (let [db (th/create-test-db {:stops #{:main1 :main2 :combat}})]
      (let [player-eid (q/get-player-eid db :player-1)
            player (d/pull db '[:player/stops] player-eid)]
        (is (= #{:main1 :main2 :combat} (:player/stops player)))))))


(deftest create-game-scenario-returns-app-db-test
  (testing "create-game-scenario returns app-db with :game/db and history keys"
    (let [app-db (th/create-game-scenario {})]
      (is (some? (:game/db app-db)) "should have :game/db key")
      (is (vector? (:history/main app-db)) "should have :history/main")
      (is (map? (:history/forks app-db)) "should have :history/forks")
      (is (= -1 (:history/position app-db)) "history position should be -1"))))


(deftest create-game-scenario-both-players-exist-test
  (testing "create-game-scenario creates both player-1 and player-2"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)]
      (is (pos-int? (q/get-player-eid db :player-1)) "player-1 should exist")
      (is (pos-int? (q/get-player-eid db :player-2)) "player-2 should exist"))))


(deftest create-game-scenario-both-players-have-library-test
  (testing "create-game-scenario gives both players default 10 library cards"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)]
      (is (= 10 (th/get-zone-count db :library :player-1))
          "player-1 should have 10 library cards")
      (is (= 10 (th/get-zone-count db :library :player-2))
          "player-2 should have 10 library cards"))))


(deftest create-game-scenario-default-stops-test
  (testing "create-game-scenario sets default stops #{:main1 :main2} for human"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)
          player-eid (q/get-player-eid db :player-1)
          player (d/pull db '[:player/stops] player-eid)]
      (is (= #{:main1 :main2} (:player/stops player))))))


(deftest create-game-scenario-custom-library-count-test
  (testing "create-game-scenario respects :human-library and :bot-library opts"
    (let [app-db (th/create-game-scenario {:human-library 5 :bot-library 3})
          db (:game/db app-db)]
      (is (= 5 (th/get-zone-count db :library :player-1)))
      (is (= 3 (th/get-zone-count db :library :player-2))))))


(deftest create-game-scenario-bot-archetype-test
  (testing "create-game-scenario sets bot-archetype on player-2 (default :goldfish)"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)
          player-eid (q/get-player-eid db :player-2)
          player (d/pull db '[:player/bot-archetype] player-eid)]
      (is (= :goldfish (:player/bot-archetype player)))))
  (testing "create-game-scenario sets explicit bot-archetype when given"
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish})
          db (:game/db app-db)
          player-eid (q/get-player-eid db :player-2)
          player (d/pull db '[:player/bot-archetype] player-eid)]
      (is (= :goldfish (:player/bot-archetype player))))))


(deftest create-game-scenario-passes-mana-and-life-test
  (testing "create-game-scenario passes mana and life opts to human player"
    (let [app-db (th/create-game-scenario {:mana {:blue 2} :life 15})
          db (:game/db app-db)]
      (is (= 2 (:blue (q/get-mana-pool db :player-1))))
      (is (= 15 (q/get-life-total db :player-1))))))
