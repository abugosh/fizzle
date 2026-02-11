(ns fizzle.db.schema-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]))


;; Schema validity tests
(deftest schema-creates-valid-connection-test
  (testing "schema creates connection that accepts transactions"
    (let [conn (d/create-conn schema)]
      (d/transact! conn [{:player/id :test-player
                          :player/life 20}])
      (is (= 20 (d/q '[:find ?life .
                       :where [?e :player/id :test-player]
                       [?e :player/life ?life]]
                     @conn))))))


;; Initial state tests
(deftest initial-state-has-player-with-empty-mana-pool-test
  (testing "player mana pool starts with all zeros"
    (let [db (init-game-state)]
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))))))


(deftest initial-state-has-dark-ritual-in-hand-test
  (testing "Dark Ritual is in player's hand"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          card (:object/card (first hand))]
      (is (= 1 (count hand)))
      (is (= "Dark Ritual" (:card/name card))))))


(deftest initial-state-has-zero-storm-count-test
  (testing "storm count starts at 0"
    (let [db (init-game-state)]
      (is (= 0 (q/get-storm-count db :player-1))))))
