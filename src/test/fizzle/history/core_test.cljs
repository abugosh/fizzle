(ns fizzle.history.core-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.history.core :as history]))


;; === init-history tests ===

(deftest test-init-history
  (testing "Returns map with all four keys, empty main, empty forks, nil branch, position -1"
    (let [h (history/init-history)]
      (is (= [] (:history/main h)))
      (is (= {} (:history/forks h)))
      (is (nil? (:history/current-branch h)))
      (is (= -1 (:history/position h))))))


;; === make-entry tests ===

(deftest test-make-entry
  (testing "Creates entry map with all four fields"
    (let [entry (history/make-entry :db-0 :cast-spell "Cast Dark Ritual" 1)]
      (is (= :db-0 (:entry/snapshot entry)))
      (is (= :cast-spell (:entry/event-type entry)))
      (is (= "Cast Dark Ritual" (:entry/description entry)))
      (is (= 1 (:entry/turn entry))))))


;; === append-entry tests ===

(deftest test-append-entry-empty-history
  (testing "Append on empty history (position -1) adds to main, sets position to 0"
    (let [db (merge {:game/db :db-0} (history/init-history))
          entry (history/make-entry :db-0 :init "Game start" 1)
          db' (history/append-entry db entry)]
      (is (= 1 (count (:history/main db'))))
      (is (= 0 (:history/position db'))))))


(deftest test-append-entry-at-tip-of-main
  (testing "Append at tip of main adds entry and increments position"
    (let [entry-0 (history/make-entry :db-0 :init "Start" 1)
          entry-1 (history/make-entry :db-1 :cast "Cast" 1)
          db (-> (merge {:game/db :db-0} (history/init-history))
                 (history/append-entry entry-0)
                 (history/append-entry entry-1))]
      (is (= 2 (count (:history/main db))))
      (is (= 1 (:history/position db))))))


(deftest test-append-entry-not-at-tip-returns-unchanged
  (testing "Append when NOT at tip returns db unchanged"
    (let [entry-0 (history/make-entry :db-0 :init "Start" 1)
          entry-1 (history/make-entry :db-1 :cast "Cast" 1)
          entry-2 (history/make-entry :db-2 :draw "Draw" 1)
          db (-> (merge {:game/db :db-0} (history/init-history))
                 (history/append-entry entry-0)
                 (history/append-entry entry-1))
          ;; Rewind to position 0 (not at tip)
          db-rewound (assoc db :history/position 0)
          db' (history/append-entry db-rewound entry-2)]
      (is (= db-rewound db')))))


;; === stepping tests ===

(defn- build-3-entry-db
  "Helper: creates db with 3 entries on main, position at tip (2)"
  []
  (let [entry-0 (history/make-entry :db-0 :init "Start" 1)
        entry-1 (history/make-entry :db-1 :cast "Cast" 1)
        entry-2 (history/make-entry :db-2 :draw "Draw" 2)]
    (-> (merge {:game/db :db-0} (history/init-history))
        (history/append-entry entry-0)
        (history/append-entry entry-1)
        (history/append-entry entry-2))))


(deftest test-step-to-valid-position
  (testing "step-to updates position and swaps game/db to snapshot"
    (let [db (build-3-entry-db)
          db' (history/step-to db 0)]
      (is (= 0 (:history/position db')))
      (is (= :db-0 (:game/db db'))))))


(deftest test-step-to-rewind-to-start
  (testing "step-to position 0 from later position rewinds to start"
    (let [db (build-3-entry-db)
          db' (history/step-to db 0)]
      (is (= 0 (:history/position db')))
      (is (= :db-0 (:game/db db'))))))


(deftest test-step-to-out-of-bounds-negative
  (testing "step-to negative position returns db unchanged"
    (let [db (build-3-entry-db)
          db' (history/step-to db -1)]
      (is (= db db')))))


(deftest test-step-to-out-of-bounds-high
  (testing "step-to >= count returns db unchanged"
    (let [db (build-3-entry-db)
          db' (history/step-to db 3)]
      (is (= db db')))))


(deftest test-can-step-back
  (testing "can-step-back? false at 0, true at > 0"
    (let [db (build-3-entry-db)]
      (is (true? (history/can-step-back? db)))
      (is (false? (history/can-step-back? (history/step-to db 0)))))))


(deftest test-can-step-forward
  (testing "can-step-forward? false at tip, true before tip"
    (let [db (build-3-entry-db)]
      (is (false? (history/can-step-forward? db)))
      (is (true? (history/can-step-forward? (history/step-to db 1)))))))


(deftest test-at-tip-empty-and-full
  (testing "at-tip? true when empty, true at last index"
    (let [empty-db (merge {:game/db nil} (history/init-history))
          full-db (build-3-entry-db)]
      (is (true? (history/at-tip? empty-db)))
      (is (true? (history/at-tip? full-db)))
      (is (false? (history/at-tip? (history/step-to full-db 1)))))))


;; === forking tests ===

(defn- build-6-entry-db
  "Helper: creates db with 6 entries on main [e0..e5], position at tip (5)"
  []
  (reduce (fn [db i]
            (history/append-entry db (history/make-entry (keyword (str "db-" i))
                                                         :action
                                                         (str "Action " i)
                                                         1)))
          (merge {:game/db :db-0} (history/init-history))
          (range 6)))


(deftest test-auto-fork-creates-fork-at-position
  (testing "auto-fork at position 3 creates fork with branch-point 3"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          new-entry (history/make-entry :db-fork :fork-action "Fork action" 2)
          db' (history/auto-fork db new-entry)
          fork-id (:history/current-branch db')
          fork (history/get-fork db' fork-id)]
      (is (some? fork-id))
      (is (= 3 (:fork/branch-point fork)))
      (is (nil? (:fork/parent fork)))
      (is (= 1 (count (:fork/entries fork))))
      (is (= "Fork 1" (:fork/name fork))))))


(deftest test-auto-fork-preserves-main
  (testing "auto-fork preserves original main entries unchanged"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          new-entry (history/make-entry :db-fork :fork-action "Fork action" 2)
          db' (history/auto-fork db new-entry)]
      (is (= 6 (count (:history/main db')))))))


(deftest test-auto-fork-sets-current-branch
  (testing "auto-fork sets current-branch to new fork-id"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          new-entry (history/make-entry :db-fork :fork-action "Fork action" 2)
          db' (history/auto-fork db new-entry)]
      (is (some? (:history/current-branch db'))))))


(deftest test-auto-fork-entry-in-effective-entries
  (testing "auto-fork entry appears as last in effective entries"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          new-entry (history/make-entry :db-fork :fork-action "Fork action" 2)
          db' (history/auto-fork db new-entry)
          entries (history/effective-entries db')]
      ;; 4 from main (0..3) + 1 fork entry = 5
      (is (= 5 (count entries)))
      (is (= :db-fork (:entry/snapshot (last entries)))))))


(deftest test-auto-fork-from-fork-creates-nested
  (testing "auto-fork from a fork creates nested fork with correct parent"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          ;; First fork
          entry-a (history/make-entry :db-fa0 :action "Fork A action" 2)
          db-fork-a (history/auto-fork db entry-a)
          fork-a-id (:history/current-branch db-fork-a)
          ;; Add another entry to fork A
          entry-a1 (history/make-entry :db-fa1 :action "Fork A action 2" 2)
          db-fork-a' (history/append-entry db-fork-a entry-a1)
          ;; Now step back within fork A and auto-fork again
          db-at-4 (history/step-to db-fork-a' 4)
          entry-b (history/make-entry :db-fb0 :action "Fork B action" 2)
          db-fork-b (history/auto-fork db-at-4 entry-b)
          fork-b-id (:history/current-branch db-fork-b)
          fork-b (history/get-fork db-fork-b fork-b-id)]
      (is (= fork-a-id (:fork/parent fork-b)))
      (is (= 4 (:fork/branch-point fork-b))))))


(deftest test-effective-entries-on-main
  (testing "effective-entries on main returns :history/main"
    (let [db (build-6-entry-db)]
      (is (= (:history/main db) (history/effective-entries db))))))


(deftest test-effective-entries-on-fork
  (testing "effective-entries on fork returns parent[0..bp] ++ fork entries"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          new-entry (history/make-entry :db-fork :fork-action "Fork" 2)
          db' (history/auto-fork db new-entry)
          entries (history/effective-entries db')]
      ;; parent[0..3] = 4 entries, fork = 1 entry => 5 total
      (is (= 5 (count entries)))
      ;; First 4 match main's first 4
      (is (= (take 4 (:history/main db'))
             (take 4 entries)))
      ;; Last is fork entry
      (is (= :db-fork (:entry/snapshot (last entries)))))))


(deftest test-create-named-fork
  (testing "create-named-fork creates empty fork, does NOT switch to it"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          db' (history/create-named-fork db "Saved position")]
      ;; Should still be on main
      (is (nil? (:history/current-branch db')))
      ;; Should have one fork
      (is (= 1 (count (:history/forks db'))))
      (let [fork (first (vals (:history/forks db')))]
        (is (= "Saved position" (:fork/name fork)))
        (is (= 3 (:fork/branch-point fork)))
        (is (= [] (:fork/entries fork)))))))


;; === branch switching tests ===

(deftest test-switch-branch-to-fork
  (testing "switch-branch to fork updates current-branch, position to tip, game/db to tip snapshot"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry (history/make-entry :db-fork-tip :fork-action "Fork" 2)
          db' (history/auto-fork db entry)
          fork-id (:history/current-branch db')
          ;; Switch back to main
          db-main (history/switch-branch db' nil)
          ;; Switch to fork
          db-fork (history/switch-branch db-main fork-id)]
      (is (= fork-id (:history/current-branch db-fork)))
      (is (= 4 (:history/position db-fork)))
      (is (= :db-fork-tip (:game/db db-fork))))))


(deftest test-switch-branch-to-main
  (testing "switch-branch to nil returns to main tip"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry (history/make-entry :db-fork :fork-action "Fork" 2)
          db' (history/auto-fork db entry)
          db-main (history/switch-branch db' nil)]
      (is (nil? (:history/current-branch db-main)))
      (is (= 5 (:history/position db-main)))
      (is (= :db-5 (:game/db db-main))))))


(deftest test-switch-branch-nonexistent
  (testing "switch-branch to non-existent fork-id returns db unchanged"
    (let [db (build-6-entry-db)
          db' (history/switch-branch db :nonexistent-fork)]
      (is (= db db')))))


(deftest test-list-forks
  (testing "list-forks returns vector of all fork maps"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry-a (history/make-entry :db-fa :action "Fork A" 2)
          db' (history/auto-fork db entry-a)
          ;; Switch back to main and create another fork
          db-main (history/switch-branch db' nil)
          db-at-2 (history/step-to db-main 2)
          entry-b (history/make-entry :db-fb :action "Fork B" 2)
          db'' (history/auto-fork db-at-2 entry-b)
          forks (history/list-forks db'')]
      (is (= 2 (count forks))))))


(deftest test-current-entry
  (testing "current-entry returns entry at current position, nil when empty"
    (let [empty-db (merge {:game/db nil} (history/init-history))
          full-db (build-3-entry-db)]
      (is (nil? (history/current-entry empty-db)))
      (is (= :db-2 (:entry/snapshot (history/current-entry full-db))))
      (is (= :db-0 (:entry/snapshot (history/current-entry (history/step-to full-db 0))))))))


(deftest test-append-entry-at-tip-of-fork
  (testing "Append at tip of fork adds to fork entries and increments position"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry-a (history/make-entry :db-fa0 :action "Fork A" 2)
          db' (history/auto-fork db entry-a)
          fork-id (:history/current-branch db')
          entry-a1 (history/make-entry :db-fa1 :action "Fork A2" 2)
          db'' (history/append-entry db' entry-a1)
          fork (history/get-fork db'' fork-id)]
      (is (= 2 (count (:fork/entries fork))))
      (is (= 5 (:history/position db''))))))


(deftest test-effective-entries-nested-3-levels
  (testing "effective-entries on 3-level nested fork walks full parent chain"
    (let [;; main: 6 entries [e0..e5]
          db (build-6-entry-db)
          ;; Fork A from position 3 (branch-point 3)
          db-at-3 (history/step-to db 3)
          entry-a0 (history/make-entry :db-fa0 :action "FA0" 2)
          entry-a1 (history/make-entry :db-fa1 :action "FA1" 2)
          db-a (-> (history/auto-fork db-at-3 entry-a0)
                   (history/append-entry entry-a1))
          ;; Fork A effective: [e0,e1,e2,e3,fa0,fa1] = 6 entries (positions 0-5)
          ;; Fork B from position 4 of Fork A (branch-point 4)
          db-a-at-4 (history/step-to db-a 4)
          entry-b0 (history/make-entry :db-fb0 :action "FB0" 3)
          entry-b1 (history/make-entry :db-fb1 :action "FB1" 3)
          db-b (-> (history/auto-fork db-a-at-4 entry-b0)
                   (history/append-entry entry-b1))
          ;; Fork B effective: [e0,e1,e2,e3,fa0,fb0,fb1] = 7 entries
          ;; Fork C from position 5 of Fork B (branch-point 5)
          db-b-at-5 (history/step-to db-b 5)
          entry-c0 (history/make-entry :db-fc0 :action "FC0" 4)
          db-c (history/auto-fork db-b-at-5 entry-c0)
          ;; Fork C effective: [e0,e1,e2,e3,fa0,fb0,fc0] = 7 entries
          entries (history/effective-entries db-c)]
      (is (= 7 (count entries)))
      ;; Verify the chain: main[0..3], fork-a[0] (fa0), fork-b[0] (fb0), fork-c[0] (fc0)
      (is (= :db-0 (:entry/snapshot (nth entries 0))))
      (is (= :db-3 (:entry/snapshot (nth entries 3))))
      (is (= :db-fa0 (:entry/snapshot (nth entries 4))))
      (is (= :db-fb0 (:entry/snapshot (nth entries 5))))
      (is (= :db-fc0 (:entry/snapshot (nth entries 6)))))))
