(ns fizzle.subs.history-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.history.core :as history]
    [fizzle.subs.history :as subs]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- make-db-with-entries
  "Create an app-db with N history entries on main timeline."
  [n]
  (reduce (fn [db i]
            (let [snapshot (keyword (str "db-" i))
                  entry (history/make-entry snapshot (keyword (str "evt-" i))
                                            (str "Entry " i) 1)]
              (-> db
                  (assoc :game/db snapshot)
                  (history/append-entry entry))))
          (merge {:game/db :db-init} (history/init-history))
          (range n)))


(defn- sub-value
  "Get subscription value by resetting app-db and deref'ing the subscription."
  [db sub-vec]
  (reset! rf-db/app-db db)
  @(rf/subscribe sub-vec))


(deftest test-entries-returns-effective-entries
  (testing "::entries returns entries for current branch"
    (let [db (make-db-with-entries 3)
          entries (sub-value db [::subs/entries])]
      (is (= 3 (count entries)))
      (is (= :db-0 (:entry/snapshot (first entries)))))))


(deftest test-position-returns-current-position
  (testing "::position returns current position"
    (let [db (make-db-with-entries 3)]
      (is (= 2 (sub-value db [::subs/position]))))))


(deftest test-can-step-back-false-at-zero
  (testing "::can-step-back? returns false at position 0"
    (let [db (-> (make-db-with-entries 3)
                 (history/step-to 0))]
      (is (not (sub-value db [::subs/can-step-back?]))))))


(deftest test-can-step-back-true-at-nonzero
  (testing "::can-step-back? returns true at position > 0"
    (let [db (make-db-with-entries 3)]
      (is (sub-value db [::subs/can-step-back?])))))


(deftest test-can-step-forward-false-at-tip
  (testing "::can-step-forward? returns false at tip"
    (let [db (make-db-with-entries 3)]
      (is (not (sub-value db [::subs/can-step-forward?]))))))


(deftest test-can-step-forward-true-when-not-at-tip
  (testing "::can-step-forward? returns true when not at tip"
    (let [db (-> (make-db-with-entries 3)
                 (history/step-to 1))]
      (is (sub-value db [::subs/can-step-forward?])))))


(deftest test-forks-returns-all-forks
  (testing "::forks returns all fork objects"
    (let [db (make-db-with-entries 3)
          rewound (history/step-to db 1)
          fork-entry-1 (history/make-entry :db-f1 :evt-f1 "Fork 1" 1)
          with-fork-1 (history/auto-fork rewound fork-entry-1)
          ;; Switch back to main, rewind, create second fork
          on-main (history/switch-branch with-fork-1 nil)
          rewound-2 (history/step-to on-main 0)
          fork-entry-2 (history/make-entry :db-f2 :evt-f2 "Fork 2" 1)
          with-fork-2 (history/auto-fork rewound-2 fork-entry-2)
          forks (sub-value with-fork-2 [::subs/forks])]
      (is (= 2 (count forks))))))


(deftest test-subscriptions-handle-nil-history
  (testing "Subscriptions return safe values when history keys are missing"
    (let [empty-db {}]
      ;; These should not throw — nil position is fine
      (is (nil? (sub-value empty-db [::subs/position]))
          "position should be nil when history not initialized")
      (is (not (sub-value empty-db [::subs/can-step-back?]))
          "can-step-back? should be falsy with nil history")
      (is (not (sub-value empty-db [::subs/can-step-forward?]))
          "can-step-forward? should be falsy with nil history"))))
