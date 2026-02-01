(ns fizzle.engine.costs-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.costs :as costs]))


;; === Test helpers ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id]
   (add-permanent db player-id nil))
  ([db player-id initial-counters]
   (add-permanent db player-id initial-counters false))
  ([db player-id initial-counters tapped?]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         card-eid (d/q '[:find ?e .
                         :where [?e :card/id :dark-ritual]]
                       @conn)
         object-id (random-uuid)
         base-entity {:object/id object-id
                      :object/card card-eid
                      :object/zone :battlefield
                      :object/owner player-eid
                      :object/controller player-eid
                      :object/tapped tapped?}
         entity (if initial-counters
                  (assoc base-entity :object/counters initial-counters)
                  base-entity)]
     (d/transact! conn [entity])
     [@conn object-id])))


;; === :tap cost tests ===

(deftest test-pay-tap-cost-untapped-permanent
  (testing "pay-cost with :tap on untapped permanent taps it and returns db"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:tap true}
          db' (costs/pay-cost db object-id cost)]
      ;; Should return new db (not nil)
      (is (some? db'))
      ;; Permanent should be tapped
      (is (= true (:object/tapped (q/get-object db' object-id)))))))


(deftest test-pay-tap-cost-already-tapped
  (testing "pay-cost with :tap on already tapped permanent returns nil"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 nil true)
          cost {:tap true}
          result (costs/pay-cost db object-id cost)]
      ;; Should return nil (cost cannot be paid)
      (is (nil? result)))))


(deftest test-can-pay-tap-untapped
  (testing "can-pay? :tap returns true for untapped permanent"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:tap true}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-can-pay-tap-already-tapped
  (testing "can-pay? :tap returns false for tapped permanent"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 nil true)
          cost {:tap true}]
      (is (false? (costs/can-pay? db object-id cost))))))


(deftest test-pay-tap-invalid-object-id
  (testing "pay-cost with invalid object-id returns nil, no crash"
    (let [db (init-game-state)
          cost {:tap true}
          result (costs/pay-cost db (random-uuid) cost)]
      (is (nil? result)))))


(deftest test-can-pay-tap-invalid-object-id
  (testing "can-pay? with invalid object-id returns false, no crash"
    (let [db (init-game-state)
          cost {:tap true}]
      (is (false? (costs/can-pay? db (random-uuid) cost))))))


;; === :remove-counter cost tests ===

(deftest test-pay-remove-counter-has-counters
  (testing "pay-cost removes counter from permanent with sufficient counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          cost {:remove-counter {:mining 1}}
          db' (costs/pay-cost db object-id cost)]
      ;; Should return new db
      (is (some? db'))
      ;; Counter should be decremented
      (is (= {:mining 2} (:object/counters (q/get-object db' object-id)))))))


(deftest test-pay-remove-counter-exact-amount
  (testing "pay-cost removes last counter (edge case: counter goes to 0)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          cost {:remove-counter {:mining 1}}
          db' (costs/pay-cost db object-id cost)]
      ;; Should return new db
      (is (some? db'))
      ;; Counter should be 0 (not removed entirely)
      (is (= {:mining 0} (:object/counters (q/get-object db' object-id)))))))


(deftest test-pay-remove-counter-insufficient
  (testing "pay-cost returns nil when insufficient counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          cost {:remove-counter {:mining 2}}
          result (costs/pay-cost db object-id cost)]
      ;; Should return nil
      (is (nil? result)))))


(deftest test-pay-remove-counter-no-counters-at-all
  (testing "pay-cost returns nil when permanent has no counters (nil :object/counters)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:remove-counter {:mining 1}}
          result (costs/pay-cost db object-id cost)]
      ;; Should return nil (no counters to remove)
      (is (nil? result)))))


(deftest test-can-pay-remove-counter-has-counters
  (testing "can-pay? returns true when counters sufficient"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          cost {:remove-counter {:mining 1}}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-can-pay-remove-counter-insufficient
  (testing "can-pay? returns false when counters insufficient"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          cost {:remove-counter {:mining 2}}]
      (is (false? (costs/can-pay? db object-id cost))))))


;; === Unknown cost type tests ===

(deftest test-pay-cost-unknown-type-returns-nil
  (testing "pay-cost with unknown cost type returns nil"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:sacrifice true}
          result (costs/pay-cost db object-id cost)]
      (is (nil? result)))))


(deftest test-can-pay-unknown-type-returns-false
  (testing "can-pay? with unknown cost type returns false"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:sacrifice true}]
      (is (false? (costs/can-pay? db object-id cost))))))


;; === :pay-life cost tests ===

(deftest test-pay-life-can-pay-with-sufficient-life
  (testing "can-pay? :pay-life returns true when controller has >= required life"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:pay-life 1}]
      ;; Player starts with 20 life
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-pay-life-cannot-pay-with-insufficient-life
  (testing "can-pay? :pay-life returns false when controller has < required life"
    (let [db (init-game-state)
          ;; Set player to 0 life
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/life 0]])
          [db' object-id] (add-permanent @conn :player-1)
          cost {:pay-life 1}]
      (is (false? (costs/can-pay? db' object-id cost))))))


(deftest test-pay-life-can-pay-exact-life
  (testing "can-pay? :pay-life returns true when controller has exactly required life (going to 0 is allowed)"
    (let [db (init-game-state)
          ;; Set player to 1 life
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/life 1]])
          [db' object-id] (add-permanent @conn :player-1)
          cost {:pay-life 1}]
      (is (true? (costs/can-pay? db' object-id cost))))))


(deftest test-pay-life-deducts-correctly
  (testing "pay-cost :pay-life deducts life from controller"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:pay-life 1}
          player-eid (q/get-player-eid db :player-1)
          life-before (:player/life (d/entity db player-eid))
          db' (costs/pay-cost db object-id cost)
          life-after (:player/life (d/entity db' player-eid))]
      ;; Should return new db
      (is (some? db'))
      ;; Life should be decremented by 1
      (is (= (dec life-before) life-after)))))
