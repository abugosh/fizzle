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
    (is (contains? (priority/get-passed-eids db') p1-eid)
        "Player 1 should be in the passed set after yielding")))


(deftest yield-priority-allows-both-players
  (let [[db p1-eid p2-eid _] (setup-two-player-db)
        db' (-> db
                (priority/yield-priority p1-eid)
                (priority/yield-priority p2-eid))]
    (is (contains? (priority/get-passed-eids db') p1-eid))
    (is (contains? (priority/get-passed-eids db') p2-eid))))


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


;; === check-stop ===

(deftest check-stop-true-for-stopped-phase
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/set-player-stops db p1-eid #{:main1 :main2})]
    (is (true? (priority/check-stop db' p1-eid :main1)))
    (is (true? (priority/check-stop db' p1-eid :main2)))))


(deftest check-stop-false-for-unstopped-phase
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/set-player-stops db p1-eid #{:main1})]
    (is (false? (priority/check-stop db' p1-eid :combat)))
    (is (false? (priority/check-stop db' p1-eid :end)))))


(deftest check-stop-false-when-no-stops-set
  (let [[db p1-eid _ _] (setup-two-player-db)]
    (is (false? (priority/check-stop db p1-eid :main1)))))


;; === auto-mode ===

(deftest auto-mode-initially-nil
  (let [[db _ _ _] (setup-two-player-db)]
    (is (nil? (priority/get-auto-mode db)))))


(deftest set-auto-mode-resolving
  (let [[db _ _ _] (setup-two-player-db)
        db' (priority/set-auto-mode db :resolving)]
    (is (= :resolving (priority/get-auto-mode db')))))


(deftest set-auto-mode-f6
  (let [[db _ _ _] (setup-two-player-db)
        db' (priority/set-auto-mode db :f6)]
    (is (= :f6 (priority/get-auto-mode db')))))


(deftest clear-auto-mode
  (let [[db _ _ _] (setup-two-player-db)
        db' (-> db
                (priority/set-auto-mode :resolving)
                priority/clear-auto-mode)]
    (is (nil? (priority/get-auto-mode db')))))


(deftest clear-auto-mode-when-already-nil
  (let [[db _ _ _] (setup-two-player-db)]
    (is (nil? (priority/get-auto-mode (priority/clear-auto-mode db))))))


;; === check-opponent-stop ===

(deftest check-opponent-stop-true-when-phase-in-set
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/set-opponent-stops db p1-eid #{:upkeep :main1})]
    (is (true? (priority/check-opponent-stop db' p1-eid :upkeep)))
    (is (true? (priority/check-opponent-stop db' p1-eid :main1)))))


(deftest check-opponent-stop-false-when-phase-not-in-set
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/set-opponent-stops db p1-eid #{:upkeep})]
    (is (false? (priority/check-opponent-stop db' p1-eid :main1)))
    (is (false? (priority/check-opponent-stop db' p1-eid :end)))))


(deftest check-opponent-stop-false-when-nil
  ;; Bot entity will have no :player/opponent-stops attribute — must not throw
  (let [[db _ p2-eid _] (setup-two-player-db)]
    (is (false? (priority/check-opponent-stop db p2-eid :main1)))))


(deftest check-opponent-stop-false-when-empty-set
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/set-opponent-stops db p1-eid #{})]
    (is (false? (priority/check-opponent-stop db' p1-eid :main1)))))


;; === set-opponent-stops ===

(deftest set-opponent-stops-persists-to-entity
  (let [[db p1-eid _ _] (setup-two-player-db)
        db' (priority/set-opponent-stops db p1-eid #{:upkeep :main2})]
    (is (= #{:upkeep :main2}
           (:player/opponent-stops (d/pull db' [:player/opponent-stops] p1-eid))))))
