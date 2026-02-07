(ns fizzle.engine.trigger-dispatch-test
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]))


;; === Test fixtures ===

(defn reset-registry
  [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))


(use-fixtures :each reset-registry)


;; === Test helpers ===

(defn make-trigger
  "Create a trigger for testing."
  ([id event-type]
   (make-trigger id event-type nil nil true))
  ([id event-type source]
   (make-trigger id event-type source nil true))
  ([id event-type source filter-map]
   (make-trigger id event-type source filter-map true))
  ([id event-type source filter-map uses-stack?]
   (cond-> {:trigger/id id
            :trigger/type :test-trigger
            :trigger/event-type event-type
            :trigger/source source
            :trigger/controller :player-1}
     filter-map (assoc :trigger/filter filter-map)
     (some? uses-stack?) (assoc :trigger/uses-stack? uses-stack?))))


(defn get-stack-items
  "Get all stack-items on the stack (migrated from trigger entities)."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :stack-item/position _]]
       db))


;; === matches-filter? tests ===

(deftest test-matches-filter-no-filter-matches-all
  (testing "trigger without filter matches any event of that type"
    (let [trigger (make-trigger :t1 :phase-entered nil nil)
          event {:event/type :phase-entered
                 :event/phase :draw
                 :event/turn 2}]
      (is (true? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-nil-filter-matches-all
  (testing "trigger with nil filter matches any event of that type"
    (let [trigger {:trigger/id :t1
                   :trigger/event-type :phase-entered
                   :trigger/filter nil}
          event {:event/type :phase-entered
                 :event/phase :upkeep}]
      (is (true? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-empty-filter-matches-all
  (testing "trigger with empty filter matches any event of that type"
    (let [trigger (make-trigger :t1 :phase-entered nil {})
          event {:event/type :phase-entered
                 :event/phase :cleanup}]
      (is (true? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-exact-match
  (testing "filter with exact value matches"
    (let [trigger (make-trigger :t1 :phase-entered nil {:event/phase :draw})
          event {:event/type :phase-entered
                 :event/phase :draw
                 :event/turn 1}]
      (is (true? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-self-matches-source
  (testing ":self in filter matches trigger's source"
    (let [obj-id (random-uuid)
          trigger (make-trigger :t1 :permanent-tapped obj-id {:event/object-id :self})
          event {:event/type :permanent-tapped
                 :event/object-id obj-id
                 :event/controller :player-1}]
      (is (true? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-self-no-match-different-object
  (testing ":self in filter doesn't match different object"
    (let [source-id (random-uuid)
          other-id (random-uuid)
          trigger (make-trigger :t1 :permanent-tapped source-id {:event/object-id :self})
          event {:event/type :permanent-tapped
                 :event/object-id other-id}]
      (is (false? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-mismatch-returns-false
  (testing "filter mismatch returns false"
    (let [trigger (make-trigger :t1 :phase-entered nil {:event/phase :draw})
          event {:event/type :phase-entered
                 :event/phase :upkeep}]
      (is (false? (dispatch/matches-filter? trigger event))))))


(deftest test-matches-filter-multiple-conditions
  (testing "all filter conditions must match"
    (let [trigger (make-trigger :t1 :phase-entered nil {:event/phase :draw
                                                        :event/turn 2})
          event-match {:event/type :phase-entered
                       :event/phase :draw
                       :event/turn 2}
          event-partial {:event/type :phase-entered
                         :event/phase :draw
                         :event/turn 1}]
      (is (true? (dispatch/matches-filter? trigger event-match)))
      (is (false? (dispatch/matches-filter? trigger event-partial))))))


;; === dispatch-event tests ===

(deftest test-dispatch-event-no-matching-triggers-returns-db-unchanged
  (testing "dispatch with no matching triggers returns db unchanged"
    (let [db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)]
      (is (= db result)))))


(deftest test-dispatch-event-stacked-goes-to-stack
  (testing "triggers with uses-stack? true go on stack"
    (let [source-id (random-uuid)
          trigger (make-trigger :t1 :phase-entered source-id nil true)
          _ (registry/register-trigger! trigger)
          db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)
          stack-items (get-stack-items result)]
      (is (= 1 (count stack-items))
          "One trigger should be on stack"))))


(deftest test-dispatch-event-default-uses-stack-true
  (testing "triggers without uses-stack? default to using stack"
    (let [source-id (random-uuid)
          trigger {:trigger/id :t1
                   :trigger/type :test-trigger
                   :trigger/event-type :phase-entered
                   :trigger/source source-id
                   :trigger/controller :player-1}
          _ (registry/register-trigger! trigger)
          db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)
          stack-items (get-stack-items result)]
      (is (= 1 (count stack-items))
          "Trigger should go on stack by default"))))


(deftest test-dispatch-event-immediate-executes-without-stack
  (testing "triggers with uses-stack? false execute immediately without going on stack"
    ;; Create a trigger that would add to stack if it used stack
    ;; Since resolve-trigger :default returns db unchanged, we verify it didn't go on stack
    (let [source-id (random-uuid)
          trigger (make-trigger :t1 :phase-entered source-id nil false)
          _ (registry/register-trigger! trigger)
          db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)
          stack-items (get-stack-items result)]
      (is (= 0 (count stack-items))
          "Immediate trigger should not be on stack"))))


(deftest test-dispatch-event-filters-matching
  (testing "only triggers matching filter are dispatched"
    (let [source-id-1 (random-uuid)
          source-id-2 (random-uuid)
          t1 (make-trigger :t1 :phase-entered source-id-1 {:event/phase :draw} true)
          t2 (make-trigger :t2 :phase-entered source-id-2 {:event/phase :upkeep} true)
          _ (registry/register-trigger! t1)
          _ (registry/register-trigger! t2)
          db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)
          stack-items (get-stack-items result)]
      (is (= 1 (count stack-items))))))


(deftest test-dispatch-event-multiple-triggers
  (testing "multiple matching triggers all get dispatched"
    (let [source-id-1 (random-uuid)
          source-id-2 (random-uuid)
          t1 (make-trigger :t1 :phase-entered source-id-1 nil true)
          t2 (make-trigger :t2 :phase-entered source-id-2 nil true)
          _ (registry/register-trigger! t1)
          _ (registry/register-trigger! t2)
          db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)
          stack-items (get-stack-items result)]
      (is (= 2 (count stack-items))))))


(deftest test-dispatch-event-order-immediate-before-stacked
  (testing "immediate triggers execute, stacked triggers go on stack"
    (let [source-id-1 (random-uuid)
          source-id-2 (random-uuid)
          immediate-trigger (make-trigger :t-immediate :phase-entered source-id-1 nil false)
          stacked-trigger (make-trigger :t-stacked :phase-entered source-id-2 nil true)
          _ (registry/register-trigger! immediate-trigger)
          _ (registry/register-trigger! stacked-trigger)
          db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)
          stack-items (get-stack-items result)]
      (is (= 1 (count stack-items))
          "Only stacked trigger should be on stack")
      (is (= source-id-2 (:stack-item/source (first stack-items)))
          "Stack item should be from the stacked trigger, not the immediate one"))))


(deftest test-dispatch-event-empty-registry
  (testing "dispatch with empty registry returns db unchanged"
    (let [db (init-game-state)
          event {:event/type :phase-entered :event/phase :draw}
          result (dispatch/dispatch-event db event)]
      (is (= db result)))))
