(ns fizzle.engine.triggers-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.triggers :as triggers]))


;; === create-trigger tests ===

(deftest test-create-trigger-structure
  (testing "create-trigger returns map with all required keys"
    (let [trigger (triggers/create-trigger :storm :source-1 :player-1 {:count 3})]
      (is (contains? trigger :trigger/id))
      (is (contains? trigger :trigger/type))
      (is (contains? trigger :trigger/source))
      (is (contains? trigger :trigger/controller))
      (is (contains? trigger :trigger/data))
      (is (= :storm (:trigger/type trigger)))
      (is (= :source-1 (:trigger/source trigger)))
      (is (= :player-1 (:trigger/controller trigger)))
      (is (= {:count 3} (:trigger/data trigger))))))


(deftest test-create-trigger-unique-id
  (testing "Each call to create-trigger returns unique :trigger/id"
    (let [trigger1 (triggers/create-trigger :storm :source-1 :player-1 {})
          trigger2 (triggers/create-trigger :storm :source-1 :player-1 {})
          trigger3 (triggers/create-trigger :storm :source-1 :player-1 {})]
      (is (uuid? (:trigger/id trigger1)))
      (is (uuid? (:trigger/id trigger2)))
      (is (uuid? (:trigger/id trigger3)))
      (is (not= (:trigger/id trigger1) (:trigger/id trigger2)))
      (is (not= (:trigger/id trigger2) (:trigger/id trigger3)))
      (is (not= (:trigger/id trigger1) (:trigger/id trigger3))))))


;; === add-trigger-to-stack tests ===

(deftest test-add-trigger-to-stack-transacts
  (testing "Trigger appears in db after add-trigger-to-stack"
    (let [db (init-game-state)
          trigger (triggers/create-trigger :storm :source-1 :player-1 {:count 2})
          db' (triggers/add-trigger-to-stack db trigger)]
      (is (some? (q/get-trigger db' (:trigger/id trigger))))
      (is (= :storm (:trigger/type (q/get-trigger db' (:trigger/id trigger))))))))


(deftest test-add-trigger-with-nil-source-fails
  (testing "add-trigger-to-stack with nil source returns unchanged db"
    (let [db (init-game-state)
          ;; Create trigger with nil source (invalid)
          trigger {:trigger/id (random-uuid)
                   :trigger/type :storm
                   :trigger/source nil
                   :trigger/controller :player-1
                   :trigger/data {}}
          db' (triggers/add-trigger-to-stack db trigger)]
      ;; Should return unchanged db - trigger should NOT be in db
      (is (nil? (q/get-trigger db' (:trigger/id trigger)))))))


;; === resolve-trigger tests ===

(deftest test-resolve-trigger-dispatches-storm
  (testing "resolve-trigger with :storm type dispatches to storm handler"
    ;; Note: The storm handler itself is implemented in a later task.
    ;; This test verifies the dispatch mechanism works.
    ;; For now, we test that calling resolve-trigger on a storm trigger
    ;; returns db (possibly modified by storm handler when implemented).
    (let [db (init-game-state)
          trigger (triggers/create-trigger :storm :source-1 :player-1 {:count 2})
          db' (triggers/add-trigger-to-stack db trigger)
          db'' (triggers/resolve-trigger db' trigger)]
      ;; Should return a db (not throw)
      (is (some? db'')))))


(deftest test-resolve-trigger-default-noop
  (testing "Unknown :trigger/type returns db unchanged"
    (let [db (init-game-state)
          trigger (triggers/create-trigger :unknown-type :source-1 :player-1 {})
          db' (triggers/add-trigger-to-stack db trigger)
          db'' (triggers/resolve-trigger db' trigger)]
      ;; db should be unchanged (or at least not throw)
      (is (= db' db'')))))


;; === remove-trigger tests ===

(deftest test-remove-trigger-retracts
  (testing "Trigger not in db after remove-trigger"
    (let [db (init-game-state)
          trigger (triggers/create-trigger :storm :source-1 :player-1 {:count 1})
          trigger-id (:trigger/id trigger)
          db' (triggers/add-trigger-to-stack db trigger)
          ;; Verify trigger is in db
          _ (is (some? (q/get-trigger db' trigger-id)))
          ;; Remove it
          db'' (triggers/remove-trigger db' trigger-id)]
      (is (nil? (q/get-trigger db'' trigger-id))))))


;; === get-stack-items tests ===

(deftest test-get-stack-items-empty
  (testing "get-stack-items returns [] on empty stack"
    (let [db (init-game-state)]
      (is (= [] (q/get-stack-items db))))))


(deftest test-get-stack-items-ordered
  (testing "Triggers added later appear first (LIFO stack order)"
    (let [db (init-game-state)
          trigger1 (triggers/create-trigger :storm :source-1 :player-1 {:order 1})
          trigger2 (triggers/create-trigger :storm :source-2 :player-1 {:order 2})
          trigger3 (triggers/create-trigger :storm :source-3 :player-1 {:order 3})
          db' (-> db
                  (triggers/add-trigger-to-stack trigger1)
                  (triggers/add-trigger-to-stack trigger2)
                  (triggers/add-trigger-to-stack trigger3))
          stack-items (q/get-stack-items db')]
      ;; Last added should be first (LIFO)
      (is (= 3 (count stack-items)))
      ;; Verify order: trigger3 first, then trigger2, then trigger1
      (is (= {:order 3} (:trigger/data (first stack-items))))
      (is (= {:order 1} (:trigger/data (last stack-items)))))))


;; === get-trigger tests ===

(deftest test-get-trigger-not-found
  (testing "get-trigger with invalid ID returns nil"
    (let [db (init-game-state)
          fake-id (random-uuid)]
      (is (nil? (q/get-trigger db fake-id))))))
