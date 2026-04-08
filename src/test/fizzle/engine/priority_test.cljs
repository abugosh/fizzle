(ns fizzle.engine.priority-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.priority :as priority]
    [fizzle.test-helpers :as h]))


(defn- setup-two-player-db
  "Create a test db with two players and return [db player-1-eid player-2-eid game-eid]."
  []
  (let [db (-> (h/create-test-db)
               (h/add-opponent))
        p1-eid (q/get-player-eid db :player-1)
        p2-eid (q/get-player-eid db :player-2)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    [db p1-eid p2-eid game-eid]))


;; === yield-priority ===

(deftest yield-priority-adds-player-to-passed-set
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/yield-priority db p1-eid)]
    (is (= #{p1-eid} (priority/get-passed-eids db'))
        "Player 1 should be in the passed set after yielding")))


(deftest yield-priority-allows-both-players
  (let [[db p1-eid p2-eid _] (setup-two-player-db)
        db' (-> db
                (priority/yield-priority p1-eid)
                (priority/yield-priority p2-eid))]
    (is (= #{p1-eid p2-eid} (priority/get-passed-eids db')))))


;; === both-passed? ===

(deftest both-passed-false-with-no-passes
  (let [[db _ _ _] (setup-two-player-db)]
    (is (false? (priority/both-passed? db)))))


(deftest both-passed-false-with-one-pass
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/yield-priority db p1-eid)]
    (is (false? (priority/both-passed? db')))))


(deftest both-passed-true-with-two-passes
  (let [[db p1-eid p2-eid _] (setup-two-player-db)
        db' (-> db
                (priority/yield-priority p1-eid)
                (priority/yield-priority p2-eid))]
    (is (true? (priority/both-passed? db')))))


;; === transfer-priority ===

(deftest transfer-priority-switches-to-other-player
  (let [[db p1-eid p2-eid _] (setup-two-player-db)
        db' (priority/transfer-priority db p1-eid)]
    (is (= p2-eid (priority/get-priority-holder-eid db'))
        "Priority should transfer from player 1 to player 2")))


(deftest transfer-priority-back-and-forth
  (let [[db p1-eid p2-eid _] (setup-two-player-db)
        db' (-> db
                (priority/transfer-priority p1-eid)
                (priority/transfer-priority p2-eid))]
    (is (= p1-eid (priority/get-priority-holder-eid db'))
        "Priority should return to player 1")))


;; === reset-passes ===

(deftest reset-passes-clears-all
  (let [[db p1-eid p2-eid _] (setup-two-player-db)
        db' (-> db
                (priority/yield-priority p1-eid)
                (priority/yield-priority p2-eid)
                priority/reset-passes)]
    (is (empty? (priority/get-passed-eids db'))
        "Passed set should be empty after reset")))


(deftest reset-passes-on-empty-is-noop
  (let [[db _ _ _] (setup-two-player-db)]
    (is (empty? (priority/get-passed-eids (priority/reset-passes db))))))


;; === get-priority-holder-eid ===

(deftest get-priority-holder-returns-initial-player
  (let [[db p1-eid _ _] (setup-two-player-db)]
    (is (= p1-eid (priority/get-priority-holder-eid db))
        "Initial priority should be with player 1")))
