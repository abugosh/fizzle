(ns fizzle.engine.triggers-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zones :as zones]))


;; === create-trigger tests ===

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


;; === get-next-stack-order tests (unified counter) ===

(deftest test-next-stack-order-considers-objects-on-stack
  (testing "get-next-stack-order accounts for objects on the stack, not just triggers"
    (let [db (init-game-state)
          ;; Move the Dark Ritual to stack with a position
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          db (zones/move-to-zone db obj-id :stack)
          ;; Set position on the object
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/position 5]])
          ;; Next stack order should be 6 (one above object's position)
          next-order (q/get-next-stack-order db)]
      (is (= 6 next-order)
          "Should return max object position + 1"))))


(deftest test-next-stack-order-max-of-triggers-and-objects
  (testing "get-next-stack-order returns max across both triggers and objects"
    (let [db (init-game-state)
          ;; Add a trigger with stack-order 3
          trigger (triggers/create-trigger :storm :source-1 :player-1 {:count 1})
          db (triggers/add-trigger-to-stack db trigger)
          ;; Move object to stack with position 5 (higher than trigger)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          db (zones/move-to-zone db obj-id :stack)
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/position 5]])]
      (is (= 6 (q/get-next-stack-order db))
          "Should return max of trigger order and object position + 1"))))


;; === get-trigger tests ===

(deftest test-get-trigger-not-found
  (testing "get-trigger with invalid ID returns nil"
    (let [db (init-game-state)
          fake-id (random-uuid)]
      (is (nil? (q/get-trigger db fake-id))))))


;; === Test helpers for :becomes-tapped tests ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id]
   (add-permanent db player-id nil))
  ([db player-id card-triggers]
   (add-permanent db player-id card-triggers false))
  ([db player-id card-triggers tapped?]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         ;; Create a test card with optional triggers
         card-id (keyword (str "test-card-" (random-uuid)))
         card-entity (cond-> {:card/id card-id
                              :card/name "Test Card"
                              :card/types #{:land}}
                       card-triggers (assoc :card/triggers card-triggers))
         _ (d/transact! conn [card-entity])
         card-eid (d/q '[:find ?e .
                         :in $ ?cid
                         :where [?e :card/id ?cid]]
                       @conn card-id)
         object-id (random-uuid)
         entity {:object/id object-id
                 :object/card card-eid
                 :object/zone :battlefield
                 :object/owner player-eid
                 :object/controller player-eid
                 :object/tapped tapped?}]
     (d/transact! conn [entity])
     [@conn object-id])))


(defn tap-permanent
  "Set a permanent's tapped state. Returns new db."
  [db object-id tapped?]
  (let [obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)]
    (d/db-with db [[:db/add obj-eid :object/tapped tapped?]])))


;; === trigger/description tests ===

(deftest test-create-trigger-with-description
  (testing "create-trigger extracts description from data map"
    (let [trigger (triggers/create-trigger
                    :becomes-tapped
                    :source-1
                    :player-1
                    {:effects [{:effect/type :deal-damage}]
                     :description "deals 1 damage to you"})]
      (is (= "deals 1 damage to you" (:trigger/description trigger)))
      (is (= :becomes-tapped (:trigger/type trigger)))
      (is (= :source-1 (:trigger/source trigger))))))


(deftest test-create-trigger-without-description
  (testing "create-trigger works without description (backwards compatible)"
    (let [trigger (triggers/create-trigger
                    :becomes-tapped
                    :source-1
                    :player-1
                    {:effects [{:effect/type :deal-damage}]})]
      (is (nil? (:trigger/description trigger)))
      (is (= :becomes-tapped (:trigger/type trigger)))
      (is (= :source-1 (:trigger/source trigger))))))


;; === Corner case tests ===

(deftest test-trigger-with-nil-controller-graceful
  (testing "resolve-trigger with nil controller doesn't crash and returns db"
    ;; Edge case: trigger was created with nil controller (shouldn't happen
    ;; in normal flow, but defensive code should handle it)
    (let [db (init-game-state)
          ;; Create trigger with nil controller
          trigger {:trigger/id (random-uuid)
                   :trigger/type :becomes-tapped
                   :trigger/source :some-source
                   :trigger/controller nil  ; nil controller
                   :trigger/data {:effects [{:effect/type :draw
                                             :effect/amount 1}]}}
          ;; Resolve should not throw - effects handle nil player gracefully
          result-db (triggers/resolve-trigger db trigger)]
      ;; Should return db unchanged (draw effect no-ops with nil target)
      (is (some? result-db) "Should return a db value, not throw")
      ;; Verify it's still a valid db by running a query
      (is (some? (q/get-game-state result-db))
          "Returned db should be queryable"))))


(deftest test-storm-trigger-with-destroyed-source
  (testing "resolve-trigger :storm returns unchanged db when source spell was exiled"
    ;; Scenario: Storm trigger is on stack, but the source spell was exiled
    ;; (e.g., by Tormod's Crypt in response)
    (let [db (init-game-state)
          ;; Create storm trigger referencing non-existent source
          destroyed-source-id (random-uuid)
          trigger (triggers/create-trigger :storm destroyed-source-id :player-1 {:count 5})
          ;; Resolve should not crash and should return db unchanged
          result-db (triggers/resolve-trigger db trigger)]
      ;; Should return same db (no copies created since source doesn't exist)
      (is (= db result-db)
          "Storm trigger should return unchanged db when source doesn't exist")
      ;; Stack should be empty (no copies created)
      (is (= 0 (count (q/get-stack-items result-db)))
          "No spell copies should be created when source is missing"))))
