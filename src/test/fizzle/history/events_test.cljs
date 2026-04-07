(ns fizzle.history.events-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.history.core :as history]
    [fizzle.history.events :as events]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register the custom :db effect handler so SBAs run on game-db mutations.
;; Required because dispatch-sync triggers the :db effect handler which calls
;; sba/check-and-execute-sbas — this fails if game/db is not a real Datascript db.
(db-effect/register!)


(defn- make-db-with-history
  "Create an app-db with history initialized and the given game-db."
  [game-db]
  (merge {:game/db game-db} (history/init-history)))


(defn- make-db-with-entries
  "Create an app-db with N history entries on main timeline using real Datascript
   db values as snapshots. Returns [app-db snapshots-vec] where snapshots-vec
   contains the N snapshot dbs (indices 0..N-1)."
  [n]
  (reduce (fn [[app-db snapshots] i]
            (let [snapshot (th/create-test-db)
                  entry (history/make-entry snapshot (keyword (str "evt-" i))
                                            (str "Entry " i) 1)]
              [(-> app-db
                   (assoc :game/db snapshot)
                   (history/append-entry entry))
               (conj snapshots snapshot)]))
          [(make-db-with-history (th/create-test-db)) []]
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
    (let [[db _] (make-db-with-entries 3)
          _ (is (= 2 (:history/position db)) "precondition: at position 2")
          result (dispatch-sync-on-db db [::events/step-back])]
      (is (= 1 (:history/position result))))))


(deftest test-step-back-restores-snapshot
  (testing "step-back restores :game/db to snapshot at new position"
    (let [[db snapshots] (make-db-with-entries 3)
          result (dispatch-sync-on-db db [::events/step-back])
          ;; Entry at position 1 has snapshot at index 1
          expected-snapshot (nth snapshots 1)]
      (is (identical? expected-snapshot (:game/db result))))))


(deftest test-step-back-at-zero-is-noop
  (testing "step-back at position 0 returns db unchanged"
    (let [[db-3 _] (make-db-with-entries 3)
          db (history/step-to db-3 0)
          _ (is (= 0 (:history/position db)) "precondition: at position 0")
          result (dispatch-sync-on-db db [::events/step-back])]
      (is (= 0 (:history/position result)))
      (is (identical? (:game/db db) (:game/db result))))))


(deftest test-step-forward-increments-position
  (testing "step-forward from position 1 goes to position 2"
    (let [[db-3 _] (make-db-with-entries 3)
          db (history/step-to db-3 1)
          _ (is (= 1 (:history/position db)) "precondition: at position 1")
          result (dispatch-sync-on-db db [::events/step-forward])]
      (is (= 2 (:history/position result))))))


(deftest test-step-forward-at-tip-is-noop
  (testing "step-forward at tip returns db unchanged"
    (let [[db _] (make-db-with-entries 3)
          _ (is (= 2 (:history/position db)) "precondition: at tip")
          result (dispatch-sync-on-db db [::events/step-forward])]
      (is (= 2 (:history/position result)))
      (is (identical? (:game/db db) (:game/db result))))))


(deftest test-switch-branch-changes-branch-and-restores
  (testing "switch-branch changes to fork and restores tip snapshot"
    (let [;; Create db with 3 entries, rewind to 1, auto-fork with new entry
          [db _] (make-db-with-entries 3)
          rewound (history/step-to db 1)
          fork-snap (th/create-test-db)
          fork-entry (history/make-entry fork-snap :evt-fork "Fork entry" 1)
          forked (history/auto-fork rewound fork-entry)
          fork-id (:history/current-branch forked)
          ;; Switch back to main
          on-main (history/switch-branch forked nil)
          ;; Now switch to the fork via event
          result (dispatch-sync-on-db on-main [::events/switch-branch fork-id])]
      (is (= fork-id (:history/current-branch result)))
      (is (identical? fork-snap (:game/db result))))))


(deftest test-switch-branch-to-main
  (testing "switch-branch to nil returns to main timeline"
    (let [[db snapshots] (make-db-with-entries 3)
          rewound (history/step-to db 1)
          fork-entry (history/make-entry (th/create-test-db) :evt-fork "Fork entry" 1)
          forked (history/auto-fork rewound fork-entry)
          ;; Currently on fork, switch to main via event
          result (dispatch-sync-on-db forked [::events/switch-branch nil])]
      (is (nil? (:history/current-branch result)))
      ;; Main tip is position 2 with snapshot at index 2
      (is (identical? (nth snapshots 2) (:game/db result))))))


(deftest test-switch-branch-invalid-fork-id-is-noop
  (testing "switch-branch with non-existent fork-id is a no-op"
    (let [[db _] (make-db-with-entries 3)
          result (dispatch-sync-on-db db [::events/switch-branch :nonexistent])]
      (is (= (:history/position db) (:history/position result)))
      (is (identical? (:game/db db) (:game/db result))))))


(deftest test-jump-to-event
  (testing "jump-to with valid position updates position and game/db"
    (let [[db snapshots] (make-db-with-entries 3)
          _ (is (= 2 (:history/position db)) "precondition: at tip")
          result (dispatch-sync-on-db db [::events/jump-to 0])]
      (is (= 0 (:history/position result)))
      (is (identical? (nth snapshots 0) (:game/db result))))))


(deftest test-jump-to-event-out-of-bounds
  (testing "jump-to with out-of-bounds position leaves db unchanged"
    (let [[db _] (make-db-with-entries 3)
          result-neg (dispatch-sync-on-db db [::events/jump-to -1])
          result-high (dispatch-sync-on-db db [::events/jump-to 99])]
      (is (= 2 (:history/position result-neg)))
      (is (identical? (:game/db db) (:game/db result-neg)))
      (is (= 2 (:history/position result-high)))
      (is (identical? (:game/db db) (:game/db result-high))))))


(deftest test-create-fork-event
  (testing "create-fork adds a fork to :history/forks"
    (let [[db _] (make-db-with-entries 3)
          _ (is (empty? (:history/forks db)) "precondition: no forks")
          result (dispatch-sync-on-db db [::events/create-fork "Test Fork"])]
      (is (= 1 (count (:history/forks result))))
      (let [fork (first (vals (:history/forks result)))]
        (is (= "Test Fork" (:fork/name fork)))
        (is (= 2 (:fork/branch-point fork)))
        (is (nil? (:fork/parent fork)))))))


(deftest test-rename-fork-event
  (testing "rename-fork updates fork name"
    (let [[db _] (make-db-with-entries 3)
          with-fork (dispatch-sync-on-db db [::events/create-fork "Old Name"])
          fork-id (:fork/id (first (vals (:history/forks with-fork))))
          result (dispatch-sync-on-db with-fork [::events/rename-fork fork-id "New Name"])
          fork (get-in result [:history/forks fork-id])]
      (is (= "New Name" (:fork/name fork))))))


(deftest test-delete-fork-event
  (testing "delete-fork removes fork from map"
    (let [[db _] (make-db-with-entries 3)
          with-fork (dispatch-sync-on-db db [::events/create-fork "Doomed Fork"])
          fork-id (:fork/id (first (vals (:history/forks with-fork))))
          _ (is (= 1 (count (:history/forks with-fork))) "precondition: fork exists")
          result (dispatch-sync-on-db with-fork [::events/delete-fork fork-id])]
      (is (empty? (:history/forks result))))))


(deftest test-delete-fork-event-cascade
  (testing "deleting parent fork cascades to children"
    (let [[db _] (make-db-with-entries 3)
          ;; Create parent fork
          with-parent (dispatch-sync-on-db db [::events/create-fork "Parent"])
          parent-id (:fork/id (first (vals (:history/forks with-parent))))
          ;; Switch to parent fork, then create child fork
          on-parent (dispatch-sync-on-db with-parent [::events/switch-branch parent-id])
          with-child (dispatch-sync-on-db on-parent [::events/create-fork "Child"])
          _ (is (= 2 (count (:history/forks with-child))) "precondition: 2 forks")
          ;; Delete parent — should cascade to child
          result (dispatch-sync-on-db with-child [::events/delete-fork parent-id])]
      (is (empty? (:history/forks result))))))


(deftest test-pop-entry-on-main
  (testing "pop-entry removes tip entry and restores previous snapshot"
    (let [[db snapshots] (make-db-with-entries 3)
          _ (is (= 2 (:history/position db)) "precondition: at tip")
          result (dispatch-sync-on-db db [::events/pop-entry])]
      (is (= 1 (:history/position result)))
      (is (identical? (nth snapshots 1) (:game/db result)))
      (is (= 2 (count (:history/main result)))))))


(deftest test-pop-entry-guard-at-position-zero
  (testing "pop-entry at position 0 is a no-op (cannot undo past init)"
    (let [[db _] (make-db-with-entries 1)
          _ (is (= 0 (:history/position db)) "precondition: at position 0")
          result (dispatch-sync-on-db db [::events/pop-entry])]
      (is (= 0 (:history/position result)))
      (is (identical? (:game/db db) (:game/db result))))))


(deftest test-pop-entry-guard-not-at-tip
  (testing "pop-entry when not at tip is a no-op"
    (let [[db-3 _] (make-db-with-entries 3)
          db (history/step-to db-3 1)
          _ (is (= 1 (:history/position db)) "precondition: not at tip")
          result (dispatch-sync-on-db db [::events/pop-entry])]
      (is (= 1 (:history/position result)))
      (is (identical? (:game/db db) (:game/db result))))))
