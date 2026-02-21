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


(deftest test-make-entry-no-bot-fields
  (testing "make-entry does not include principal or is-bot? fields"
    (let [entry (history/make-entry :db-0 :cast-spell "Cast Dark Ritual" 1)]
      (is (not (contains? entry :entry/principal))
          "Entry should not contain :entry/principal")
      (is (not (contains? entry :entry/is-bot?))
          "Entry should not contain :entry/is-bot?"))))


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


(deftest test-step-to-clears-pending-selection
  (testing "step-to clears stale :game/pending-selection"
    (let [db (-> (build-3-entry-db)
                 (assoc :game/pending-selection {:selection/type :mana-allocation}))
          db' (history/step-to db 0)]
      (is (nil? (:game/pending-selection db'))
          "Pending selection should be cleared after step-to"))))


(deftest test-step-to-clears-selected-card
  (testing "step-to clears stale :game/selected-card"
    (let [db (-> (build-3-entry-db)
                 (assoc :game/selected-card :some-card-id))
          db' (history/step-to db 0)]
      (is (nil? (:game/selected-card db'))
          "Selected card should be cleared after step-to"))))


(deftest test-step-to-clears-pending-mode-selection
  (testing "step-to clears stale :game/pending-mode-selection"
    (let [db (-> (build-3-entry-db)
                 (assoc :game/pending-mode-selection {:some :mode}))
          db' (history/step-to db 0)]
      (is (nil? (:game/pending-mode-selection db'))
          "Pending mode selection should be cleared after step-to"))))


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


;; === rename-fork tests ===

(deftest test-rename-fork
  (testing "Renames fork, other fork fields unchanged"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry (history/make-entry :db-fork :action "Fork" 2)
          db' (history/auto-fork db entry)
          fork-id (:history/current-branch db')
          fork-before (history/get-fork db' fork-id)
          db'' (history/rename-fork db' fork-id "My Fork")
          fork-after (history/get-fork db'' fork-id)]
      (is (= "My Fork" (:fork/name fork-after)))
      (is (= (:fork/id fork-before) (:fork/id fork-after)))
      (is (= (:fork/branch-point fork-before) (:fork/branch-point fork-after)))
      (is (= (:fork/parent fork-before) (:fork/parent fork-after)))
      (is (= (:fork/entries fork-before) (:fork/entries fork-after))))))


(deftest test-rename-fork-nonexistent
  (testing "Rename of nonexistent fork-id returns db unchanged"
    (let [db (build-6-entry-db)
          db' (history/rename-fork db :nonexistent "New Name")]
      (is (= db db')))))


;; === delete-fork tests ===

(defn- build-db-with-fork
  "Helper: 6 entries on main, one fork from position 3 with one entry.
   Returns [db fork-id]."
  []
  (let [db (-> (build-6-entry-db)
               (history/step-to 3))
        entry (history/make-entry :db-fork :action "Fork action" 2)
        db' (history/auto-fork db entry)
        fork-id (:history/current-branch db')]
    [db' fork-id]))


(defn- build-db-with-fork-tree
  "Helper: main with 6 entries, Fork A from pos 3, Fork B child of A, Fork C child of B.
   Returns [db fork-a-id fork-b-id fork-c-id]."
  []
  (let [db (-> (build-6-entry-db)
               (history/step-to 3))
        ;; Fork A from main at position 3
        entry-a (history/make-entry :db-fa :action "FA" 2)
        db-a (history/auto-fork db entry-a)
        fork-a-id (:history/current-branch db-a)
        ;; Fork B from Fork A at position 4
        entry-a1 (history/make-entry :db-fa1 :action "FA1" 2)
        db-a' (history/append-entry db-a entry-a1)
        db-a-at-4 (history/step-to db-a' 4)
        entry-b (history/make-entry :db-fb :action "FB" 3)
        db-b (history/auto-fork db-a-at-4 entry-b)
        fork-b-id (:history/current-branch db-b)
        ;; Fork C from Fork B at position 5
        entry-b1 (history/make-entry :db-fb1 :action "FB1" 3)
        db-b' (history/append-entry db-b entry-b1)
        db-b-at-5 (history/step-to db-b' 5)
        entry-c (history/make-entry :db-fc :action "FC" 4)
        db-c (history/auto-fork db-b-at-5 entry-c)
        fork-c-id (:history/current-branch db-c)]
    [db-c fork-a-id fork-b-id fork-c-id]))


(deftest test-delete-fork-single
  (testing "Deleting a single fork with no children removes it"
    (let [[db fork-id] (build-db-with-fork)
          db' (history/delete-fork db fork-id)]
      (is (empty? (:history/forks db')))
      (is (nil? (history/get-fork db' fork-id))))))


(deftest test-delete-fork-cascade-children
  (testing "Deleting parent fork cascades to children"
    (let [[db fork-a-id fork-b-id fork-c-id] (build-db-with-fork-tree)
          ;; Switch to main first so we're not on a deleted branch
          db-main (history/switch-branch db nil)
          db' (history/delete-fork db-main fork-a-id)]
      (is (nil? (history/get-fork db' fork-a-id)))
      (is (nil? (history/get-fork db' fork-b-id)))
      (is (nil? (history/get-fork db' fork-c-id)))
      (is (empty? (:history/forks db'))))))


(deftest test-delete-fork-cascade-deep
  (testing "Deleting root of 3-level chain removes all three"
    (let [[db fork-a-id _ _] (build-db-with-fork-tree)
          db-main (history/switch-branch db nil)
          db' (history/delete-fork db-main fork-a-id)]
      (is (= 0 (count (:history/forks db')))))))


(deftest test-delete-fork-active-branch
  (testing "Deleting current branch switches to main, restores game/db"
    (let [[db fork-id] (build-db-with-fork)
          ;; Currently on fork-id
          db' (history/delete-fork db fork-id)]
      (is (nil? (:history/current-branch db')))
      (is (= 5 (:history/position db')))
      (is (= :db-5 (:game/db db'))))))


(deftest test-delete-fork-ancestor-of-active
  (testing "Deleting ancestor of active branch switches to main"
    (let [[db fork-a-id _ _] (build-db-with-fork-tree)
          ;; Currently on Fork C (deepest) — delete Fork A (root ancestor)
          db' (history/delete-fork db fork-a-id)]
      (is (nil? (:history/current-branch db')))
      (is (= 5 (:history/position db')))
      (is (= :db-5 (:game/db db')))
      (is (empty? (:history/forks db'))))))


(deftest test-delete-fork-inactive
  (testing "Deleting inactive fork leaves current-branch and position unchanged"
    (let [[db fork-a-id fork-b-id fork-c-id] (build-db-with-fork-tree)
          ;; Switch to main, delete Fork C (a leaf)
          db-main (history/switch-branch db nil)
          db' (history/delete-fork db-main fork-c-id)]
      (is (nil? (:history/current-branch db')))
      ;; Fork A and B still exist
      (is (some? (history/get-fork db' fork-a-id)))
      (is (some? (history/get-fork db' fork-b-id)))
      (is (nil? (history/get-fork db' fork-c-id))))))


(deftest test-delete-fork-nil-noop
  (testing "delete-fork with nil fork-id (main) returns db unchanged"
    (let [[db _] (build-db-with-fork)
          db' (history/delete-fork db nil)]
      (is (= db db')))))


(deftest test-delete-fork-nonexistent
  (testing "delete-fork with nonexistent fork-id returns db unchanged"
    (let [[db _] (build-db-with-fork)
          db' (history/delete-fork db :nonexistent)]
      (is (= db db')))))


(deftest test-delete-fork-preserves-main
  (testing "Cascade delete does not affect :history/main entries"
    (let [[db fork-a-id _ _] (build-db-with-fork-tree)
          main-before (:history/main db)
          db' (history/delete-fork db fork-a-id)
          main-after (:history/main db')]
      (is (= main-before main-after)))))


;; === entries-by-turn tests ===

(deftest test-entries-by-turn-empty
  (testing "Empty entries list returns empty vector"
    (is (= [] (history/entries-by-turn [])))))


(deftest test-entries-by-turn-single-turn
  (testing "All entries same turn grouped into one group"
    (let [entries [(history/make-entry :db-0 :init "Start" 1)
                   (history/make-entry :db-1 :cast "Cast" 1)
                   (history/make-entry :db-2 :draw "Draw" 1)]
          result (history/entries-by-turn entries)]
      (is (= 1 (count result)))
      (is (= 1 (:turn (first result))))
      (is (= 3 (count (:entries (first result))))))))


(deftest test-entries-by-turn-multiple-turns
  (testing "Entries across turns 1, 2, 3 produce 3 groups in order"
    (let [entries [(history/make-entry :db-0 :init "Start" 1)
                   (history/make-entry :db-1 :cast "Cast" 1)
                   (history/make-entry :db-2 :phase "Phase" 2)
                   (history/make-entry :db-3 :draw "Draw" 3)]
          result (history/entries-by-turn entries)]
      (is (= 3 (count result)))
      (is (= [1 2 3] (mapv :turn result)))
      (is (= 2 (count (:entries (first result)))))
      (is (= 1 (count (:entries (second result)))))
      (is (= 1 (count (:entries (nth result 2))))))))


(deftest test-entries-by-turn-preserves-order
  (testing "Within a turn group, entries appear in original insertion order"
    (let [e0 (history/make-entry :db-0 :init "First" 1)
          e1 (history/make-entry :db-1 :cast "Second" 1)
          e2 (history/make-entry :db-2 :draw "Third" 1)
          result (history/entries-by-turn [e0 e1 e2])
          group-entries (:entries (first result))]
      (is (= :db-0 (:entry/snapshot (nth group-entries 0))))
      (is (= :db-1 (:entry/snapshot (nth group-entries 1))))
      (is (= :db-2 (:entry/snapshot (nth group-entries 2)))))))


(deftest test-entries-by-turn-non-consecutive
  (testing "Non-consecutive turns (1, 3, 5) produce 3 groups ordered by turn"
    (let [entries [(history/make-entry :db-0 :init "Start" 1)
                   (history/make-entry :db-1 :cast "Cast" 3)
                   (history/make-entry :db-2 :draw "Draw" 5)]
          result (history/entries-by-turn entries)]
      (is (= 3 (count result)))
      (is (= [1 3 5] (mapv :turn result))))))


;; === fork-tree tests ===

(defn- make-fork
  "Helper to create a fork map with deterministic id."
  [id name parent branch-point]
  {:fork/id id
   :fork/name name
   :fork/parent parent
   :fork/branch-point branch-point
   :fork/entries []})


(deftest test-fork-tree-empty
  (testing "Empty forks map returns empty vector"
    (is (= [] (history/fork-tree {})))))


(deftest test-fork-tree-all-roots
  (testing "Three forks with nil parent produce 3 root nodes, sorted by name"
    (let [forks {:c (make-fork :c "Charlie" nil 2)
                 :a (make-fork :a "Alpha" nil 0)
                 :b (make-fork :b "Bravo" nil 1)}
          result (history/fork-tree forks)]
      (is (= 3 (count result)))
      (is (= ["Alpha" "Bravo" "Charlie"] (mapv :fork/name result)))
      (is (every? #(= [] (:children %)) result)))))


(deftest test-fork-tree-parent-child
  (testing "Parent fork has child nested in :children"
    (let [forks {:p (make-fork :p "Parent" nil 0)
                 :c (make-fork :c "Child" :p 2)}
          result (history/fork-tree forks)]
      (is (= 1 (count result)))
      (is (= "Parent" (:fork/name (first result))))
      (is (= 1 (count (:children (first result)))))
      (is (= "Child" (:fork/name (first (:children (first result)))))))))


(deftest test-fork-tree-deep-nesting
  (testing "A→B→C chain nests correctly"
    (let [forks {:a (make-fork :a "A" nil 0)
                 :b (make-fork :b "B" :a 2)
                 :c (make-fork :c "C" :b 3)}
          result (history/fork-tree forks)]
      (is (= 1 (count result)))
      (is (= "A" (:fork/name (first result))))
      (let [b (first (:children (first result)))]
        (is (= "B" (:fork/name b)))
        (let [c (first (:children b))]
          (is (= "C" (:fork/name c)))
          (is (= [] (:children c))))))))


(deftest test-fork-tree-mixed
  (testing "2 roots, one with 2 children, produces correct tree"
    (let [forks {:r1 (make-fork :r1 "Root1" nil 0)
                 :r2 (make-fork :r2 "Root2" nil 1)
                 :c1 (make-fork :c1 "Child1" :r1 2)
                 :c2 (make-fork :c2 "Child2" :r1 3)}
          result (history/fork-tree forks)]
      (is (= 2 (count result)))
      (let [r1 (first (filter #(= "Root1" (:fork/name %)) result))
            r2 (first (filter #(= "Root2" (:fork/name %)) result))]
        (is (= 2 (count (:children r1))))
        (is (= ["Child1" "Child2"] (mapv :fork/name (:children r1))))
        (is (= [] (:children r2)))))))


;; === can-pop? tests ===

(deftest test-can-pop?-empty-history
  (testing "can-pop? returns false when position is -1 (no entries)"
    (let [db (merge {:game/db nil} (history/init-history))]
      (is (false? (history/can-pop? db))))))


(deftest test-can-pop?-at-position-zero
  (testing "can-pop? returns false when position is 0 (at init-game)"
    (let [entry-0 (history/make-entry :db-0 :init "Start" 1)
          db (-> (merge {:game/db :db-0} (history/init-history))
                 (history/append-entry entry-0))]
      (is (= 0 (:history/position db)))
      (is (false? (history/can-pop? db))))))


(deftest test-can-pop?-at-tip-position-gt-zero
  (testing "can-pop? returns true when position > 0 and at tip"
    (let [db (build-3-entry-db)]
      (is (= 2 (:history/position db)))
      (is (true? (history/can-pop? db))))))


(deftest test-can-pop?-not-at-tip
  (testing "can-pop? returns false when not at tip (stepped back)"
    (let [db (-> (build-3-entry-db)
                 (history/step-to 1))]
      (is (= 1 (:history/position db)))
      (is (false? (history/can-pop? db))))))


;; === pop-entry tests ===

(deftest test-pop-entry-main-3-entries
  (testing "Pop from main with 3 entries: count 3->2, position 2->1, game/db restored"
    (let [db (build-3-entry-db)
          db' (history/pop-entry db)]
      (is (= 2 (count (:history/main db'))))
      (is (= 1 (:history/position db')))
      (is (= :db-1 (:game/db db'))))))


(deftest test-pop-entry-main-at-position-zero-noop
  (testing "Pop from main at position 0 is a no-op (can-pop? is false)"
    (let [entry-0 (history/make-entry :db-0 :init "Start" 1)
          db (-> (merge {:game/db :db-0} (history/init-history))
                 (history/append-entry entry-0))
          db' (history/pop-entry db)]
      (is (= db db')))))


(deftest test-pop-entry-twice-in-succession
  (testing "Pop twice: count 3->2->1, position 2->1->0"
    (let [db (build-3-entry-db)
          db' (history/pop-entry db)
          db'' (history/pop-entry db')]
      (is (= 1 (count (:history/main db''))))
      (is (= 0 (:history/position db'')))
      (is (= :db-0 (:game/db db''))))))


(deftest test-pop-entry-fork-with-two-entries
  (testing "Pop from fork with 2 entries: fork/entries shrinks, position decrements"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry-a (history/make-entry :db-fa0 :action "FA0" 2)
          entry-a1 (history/make-entry :db-fa1 :action "FA1" 2)
          db-fork (-> (history/auto-fork db entry-a)
                      (history/append-entry entry-a1))
          fork-id (:history/current-branch db-fork)
          db' (history/pop-entry db-fork)
          fork' (history/get-fork db' fork-id)]
      (is (= 1 (count (:fork/entries fork'))))
      (is (= 4 (:history/position db')))
      (is (= :db-fa0 (:game/db db'))))))


(deftest test-pop-entry-fork-last-entry-auto-deletes
  (testing "Pop last entry on fork: fork deleted, returns to parent at branch-point"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          entry-a (history/make-entry :db-fa :action "FA" 2)
          db-fork (history/auto-fork db entry-a)
          fork-id (:history/current-branch db-fork)
          db' (history/pop-entry db-fork)]
      ;; Fork should be deleted
      (is (nil? (history/get-fork db' fork-id)))
      (is (empty? (:history/forks db')))
      ;; Should be back on main at branch-point
      (is (nil? (:history/current-branch db')))
      (is (= 3 (:history/position db')))
      (is (= :db-3 (:game/db db'))))))


(deftest test-pop-entry-cascade-child-fork-at-tip
  (testing "Pop entry on main that has a child fork at that position: child fork deleted"
    (let [db (build-3-entry-db)
          ;; Create a fork branching from position 2 (the tip we'll pop)
          db-at-2 (history/step-to db 2)
          entry-f (history/make-entry :db-fork :action "Fork" 2)
          db-forked (history/auto-fork db-at-2 entry-f)
          fork-id (:history/current-branch db-forked)
          ;; Switch back to main (tip is position 2)
          db-main (history/switch-branch db-forked nil)
          db' (history/pop-entry db-main)]
      ;; Child fork should be cascade-deleted
      (is (nil? (history/get-fork db' fork-id)))
      (is (empty? (:history/forks db')))
      ;; Main should have 2 entries, position 1
      (is (= 2 (count (:history/main db'))))
      (is (= 1 (:history/position db')))
      (is (= :db-1 (:game/db db'))))))


(deftest test-pop-entry-preserves-child-fork-at-earlier-position
  (testing "Pop entry on main preserves child fork branching from earlier position"
    (let [db (build-3-entry-db)
          ;; Create a fork branching from position 1 (earlier than tip)
          db-at-1 (history/step-to db 1)
          entry-f (history/make-entry :db-fork :action "Fork" 2)
          db-forked (history/auto-fork db-at-1 entry-f)
          fork-id (:history/current-branch db-forked)
          ;; Switch back to main (tip is position 2)
          db-main (history/switch-branch db-forked nil)
          db' (history/pop-entry db-main)]
      ;; Fork at position 1 should be preserved
      (is (some? (history/get-fork db' fork-id)))
      ;; Main should have 2 entries, position 1
      (is (= 2 (count (:history/main db'))))
      (is (= 1 (:history/position db'))))))


(deftest test-pop-entry-fork-last-entry-with-child-forks-cascade
  (testing "Pop last entry on fork that has child forks: all cascade-deleted"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          ;; Fork A from main at position 3
          entry-a (history/make-entry :db-fa :action "FA" 2)
          db-a (history/auto-fork db entry-a)
          fork-a-id (:history/current-branch db-a)
          ;; Fork B as child of Fork A at position 4
          entry-a1 (history/make-entry :db-fa1 :action "FA1" 2)
          db-a' (history/append-entry db-a entry-a1)
          db-a-at-4 (history/step-to db-a' 4)
          entry-b (history/make-entry :db-fb :action "FB" 3)
          db-b (history/auto-fork db-a-at-4 entry-b)
          fork-b-id (:history/current-branch db-b)
          ;; Switch back to Fork A tip and pop down to empty
          db-back-a (history/switch-branch db-b fork-a-id)
          ;; Pop second entry on Fork A
          db-pop1 (history/pop-entry db-back-a)]
      ;; Fork B branched from position 4 of Fork A — that's the first
      ;; fork entry. Since we popped the second entry (position 5),
      ;; Fork B is at position 4 which still exists, so it's preserved
      (is (some? (history/get-fork db-pop1 fork-b-id)))
      ;; Pop the last entry on Fork A
      (let [db-pop2 (history/pop-entry db-pop1)]
        ;; Now Fork A is empty and gets deleted
        ;; Fork B is a child of Fork A, so it gets cascade-deleted
        (is (nil? (history/get-fork db-pop2 fork-a-id)))
        (is (nil? (history/get-fork db-pop2 fork-b-id)))
        (is (empty? (:history/forks db-pop2)))
        ;; Should be back on main at branch-point 3
        (is (nil? (:history/current-branch db-pop2)))
        (is (= 3 (:history/position db-pop2)))
        (is (= :db-3 (:game/db db-pop2)))))))


(deftest test-pop-entry-nested-fork-returns-to-outer-fork
  (testing "Pop all entries on fork-of-fork returns to outer fork, not main"
    (let [db (-> (build-6-entry-db)
                 (history/step-to 3))
          ;; Fork A from main at position 3
          entry-a (history/make-entry :db-fa0 :action "FA0" 2)
          entry-a1 (history/make-entry :db-fa1 :action "FA1" 2)
          db-a (-> (history/auto-fork db entry-a)
                   (history/append-entry entry-a1))
          fork-a-id (:history/current-branch db-a)
          ;; Fork B from Fork A at position 5
          entry-b (history/make-entry :db-fb0 :action "FB0" 3)
          db-b (history/auto-fork (history/step-to db-a 5) entry-b)
          ;; We added entry-b at new tip position 6 via auto-fork
          ;; but auto-fork branches from step-to position 5
          ;; so Fork B has branch-point 5, entries [entry-b], position 6
          fork-b-id (:history/current-branch db-b)
          ;; Pop Fork B's only entry
          db' (history/pop-entry db-b)]
      ;; Fork B deleted, return to Fork A (not main)
      (is (nil? (history/get-fork db' fork-b-id)))
      (is (= fork-a-id (:history/current-branch db')))
      (is (= 5 (:history/position db')))
      (is (= :db-fa1 (:game/db db'))))))


(deftest test-pop-entry-noop-when-not-at-tip
  (testing "Pop is no-op when not at tip"
    (let [db (-> (build-3-entry-db)
                 (history/step-to 1))
          db' (history/pop-entry db)]
      (is (= db db')))))


(deftest test-pop-entry-noop-when-empty
  (testing "Pop is no-op when history is empty (position -1)"
    (let [db (merge {:game/db nil} (history/init-history))
          db' (history/pop-entry db)]
      (is (= db db')))))
