(ns fizzle.history.events-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.history.core :as history]
    [fizzle.history.events :as events]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- make-db-with-history
  "Create an app-db with history initialized and the given game-db."
  [game-db]
  (merge {:game/db game-db} (history/init-history)))


(defn- make-db-with-entries
  "Create an app-db with N history entries on main timeline.
   Each entry has a unique snapshot keyword :db-0, :db-1, etc."
  [n]
  (reduce (fn [db i]
            (let [snapshot (keyword (str "db-" i))
                  entry (history/make-entry snapshot (keyword (str "evt-" i))
                                            (str "Entry " i) 1)]
              (-> db
                  (assoc :game/db snapshot)
                  (history/append-entry entry))))
          (make-db-with-history :db-init)
          (range n)))


(defn- dispatch-sync-on-db
  "Simulate dispatching an event by directly calling the registered handler.
   Sets rf-db, dispatches synchronously, returns new rf-db."
  [db event]
  (reset! rf-db/app-db db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


(deftest test-step-back-decrements-position
  (testing "step-back from position 2 goes to position 1"
    (let [db (make-db-with-entries 3)
          _ (is (= 2 (:history/position db)) "precondition: at position 2")
          result (dispatch-sync-on-db db [::events/step-back])]
      (is (= 1 (:history/position result))))))


(deftest test-step-back-restores-snapshot
  (testing "step-back restores :game/db to snapshot at new position"
    (let [db (make-db-with-entries 3)
          result (dispatch-sync-on-db db [::events/step-back])
          ;; Entry at position 1 has snapshot :db-1
          expected-snapshot :db-1]
      (is (= expected-snapshot (:game/db result))))))


(deftest test-step-back-at-zero-is-noop
  (testing "step-back at position 0 returns db unchanged"
    (let [db (-> (make-db-with-entries 3)
                 (history/step-to 0))
          _ (is (= 0 (:history/position db)) "precondition: at position 0")
          result (dispatch-sync-on-db db [::events/step-back])]
      (is (= 0 (:history/position result)))
      (is (= (:game/db db) (:game/db result))))))


(deftest test-step-forward-increments-position
  (testing "step-forward from position 1 goes to position 2"
    (let [db (-> (make-db-with-entries 3)
                 (history/step-to 1))
          _ (is (= 1 (:history/position db)) "precondition: at position 1")
          result (dispatch-sync-on-db db [::events/step-forward])]
      (is (= 2 (:history/position result))))))


(deftest test-step-forward-at-tip-is-noop
  (testing "step-forward at tip returns db unchanged"
    (let [db (make-db-with-entries 3)
          _ (is (= 2 (:history/position db)) "precondition: at tip")
          result (dispatch-sync-on-db db [::events/step-forward])]
      (is (= 2 (:history/position result)))
      (is (= (:game/db db) (:game/db result))))))


(deftest test-switch-branch-changes-branch-and-restores
  (testing "switch-branch changes to fork and restores tip snapshot"
    (let [;; Create db with 3 entries, rewind to 1, auto-fork with new entry
          db (make-db-with-entries 3)
          rewound (history/step-to db 1)
          fork-entry (history/make-entry :db-fork :evt-fork "Fork entry" 1)
          forked (history/auto-fork rewound fork-entry)
          fork-id (:history/current-branch forked)
          ;; Switch back to main
          on-main (history/switch-branch forked nil)
          ;; Now switch to the fork via event
          result (dispatch-sync-on-db on-main [::events/switch-branch fork-id])]
      (is (= fork-id (:history/current-branch result)))
      (is (= :db-fork (:game/db result))))))


(deftest test-switch-branch-to-main
  (testing "switch-branch to nil returns to main timeline"
    (let [db (make-db-with-entries 3)
          rewound (history/step-to db 1)
          fork-entry (history/make-entry :db-fork :evt-fork "Fork entry" 1)
          forked (history/auto-fork rewound fork-entry)
          ;; Currently on fork, switch to main via event
          result (dispatch-sync-on-db forked [::events/switch-branch nil])]
      (is (nil? (:history/current-branch result)))
      ;; Main tip is position 2 with snapshot :db-2
      (is (= :db-2 (:game/db result))))))


(deftest test-switch-branch-invalid-fork-id-is-noop
  (testing "switch-branch with non-existent fork-id is a no-op"
    (let [db (make-db-with-entries 3)
          result (dispatch-sync-on-db db [::events/switch-branch :nonexistent])]
      (is (= (:history/position db) (:history/position result)))
      (is (= (:game/db db) (:game/db result))))))
