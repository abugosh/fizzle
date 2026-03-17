(ns fizzle.events.calculator-test
  "Tests for calculator query CRUD events.
   Tests operate directly on handler functions, not via re-frame dispatch,
   so they are pure unit tests with no side effects."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.storage :as storage]
    [fizzle.events.calculator :as calc]))


;; === Mock localStorage for persistence tests ===

(defn- create-mock-storage
  []
  (let [store (atom {})]
    #js {:getItem (fn [key] (get @store key nil))
         :setItem (fn [key value] (swap! store assoc key value) nil)
         :removeItem (fn [key] (swap! store dissoc key) nil)}))


(set! js/localStorage (create-mock-storage))


;; === Default state helpers ===

(def ^:private default-db
  "App-db after init-calculator — clean baseline."
  {:calculator/visible? false
   :calculator/next-id  1
   :calculator/queries  []})


;; === ::init-calculator ===

(deftest test-init-calculator-sets-defaults
  (testing "init-calculator sets default state on empty db"
    (let [result (calc/init-calculator-handler {} nil)]
      (is (= false (:calculator/visible? result))
          "visible? should default to false")
      (is (= 1 (:calculator/next-id result))
          "next-id should start at 1")
      (is (= [] (:calculator/queries result))
          "queries should start empty"))))


(deftest test-init-calculator-is-idempotent
  (testing "init-calculator does not overwrite existing calculator state"
    (let [existing-db {:calculator/visible? true
                       :calculator/next-id  5
                       :calculator/queries  [{:query/id 1}]}
          result (calc/init-calculator-handler existing-db nil)]
      (is (= true (:calculator/visible? result))
          "visible? should be unchanged")
      (is (= 5 (:calculator/next-id result))
          "next-id should be unchanged")
      (is (= [{:query/id 1}] (:calculator/queries result))
          "queries should be unchanged"))))


;; === ::toggle-calculator ===

(deftest test-toggle-calculator-flips-visible
  (testing "toggle-calculator toggles :calculator/visible?"
    (let [db {:calculator/visible? false}
          result (calc/toggle-calculator-handler db nil)]
      (is (= true (:calculator/visible? result))
          "should flip false -> true"))
    (let [db {:calculator/visible? true}
          result (calc/toggle-calculator-handler db nil)]
      (is (= false (:calculator/visible? result))
          "should flip true -> false"))))


;; === ::add-query ===

(deftest test-add-query-creates-correct-shape
  (testing "add-query appends a query with correct fields"
    (let [result (calc/add-query-handler default-db nil)
          queries (:calculator/queries result)]
      (is (= 1 (count queries))
          "should have one query")
      (let [q (first queries)]
        (is (= 1 (:query/id q))
            "query id should be 1 (first next-id)")
        (is (= "Query 1" (:query/label q))
            "label should be 'Query N' where N is the query number")
        (is (= false (:query/collapsed? q))
            "collapsed? should be false")
        (is (= [] (:query/steps q))
            "steps should be empty vector")))))


(deftest test-add-query-increments-next-id
  (testing "add-query increments :calculator/next-id"
    (let [result (calc/add-query-handler default-db nil)]
      (is (= 2 (:calculator/next-id result))
          "next-id should be bumped from 1 to 2"))))


(deftest test-add-query-multiple-queries-use-sequential-ids
  (testing "adding two queries gives them distinct incrementing ids"
    (let [db1 (calc/add-query-handler default-db nil)
          db2 (calc/add-query-handler db1 nil)
          [q1 q2] (:calculator/queries db2)]
      (is (= 1 (:query/id q1)) "first query id should be 1")
      (is (= 2 (:query/id q2)) "second query id should be 2")
      (is (= 3 (:calculator/next-id db2)) "next-id should be 3 after two adds"))))


;; === ::remove-query ===

(deftest test-remove-query-removes-by-id
  (testing "remove-query filters out the query with matching id"
    (let [db (calc/add-query-handler default-db nil)
          result (calc/remove-query-handler db [nil 1])]
      (is (= [] (:calculator/queries result))
          "queries should be empty after removing the only query"))))


(deftest test-remove-query-unknown-id-is-noop
  (testing "remove-query with unknown id does not change db"
    (let [db (calc/add-query-handler default-db nil)
          result (calc/remove-query-handler db [nil 999])]
      (is (= 1 (count (:calculator/queries result)))
          "query count should be unchanged for unknown id"))))


;; === ::set-query-label ===

(deftest test-set-query-label-updates-label
  (testing "set-query-label updates the label for the matching query"
    (let [db (calc/add-query-handler default-db nil)
          result (calc/set-query-label-handler db [nil 1 "My Custom Label"])
          q (first (:calculator/queries result))]
      (is (= "My Custom Label" (:query/label q))
          "label should be updated"))))


(deftest test-set-query-label-unknown-id-is-noop
  (testing "set-query-label with unknown id is a no-op"
    (let [db (calc/add-query-handler default-db nil)
          before (:calculator/queries db)
          result (calc/set-query-label-handler db [nil 999 "New Label"])]
      (is (= before (:calculator/queries result))
          "queries should be unchanged for unknown id"))))


;; === ::toggle-query-collapsed ===

(deftest test-toggle-query-collapsed-flips-flag
  (testing "toggle-query-collapsed flips :query/collapsed? for matching query"
    (let [db (calc/add-query-handler default-db nil)
          result (calc/toggle-query-collapsed-handler db [nil 1])
          q (first (:calculator/queries result))]
      (is (= true (:query/collapsed? q))
          "collapsed? should flip from false to true"))))


(deftest test-toggle-query-collapsed-unknown-id-is-noop
  (testing "toggle-query-collapsed with unknown id is a no-op"
    (let [db (calc/add-query-handler default-db nil)
          before (:calculator/queries db)
          result (calc/toggle-query-collapsed-handler db [nil 999])]
      (is (= before (:calculator/queries result))
          "queries should be unchanged for unknown id"))))


;; === ::add-step ===

(deftest test-add-step-creates-correct-shape
  (testing "add-step appends a step with correct fields"
    (let [db (calc/add-query-handler default-db nil)
          result (calc/add-step-handler db [nil 1])
          q (first (:calculator/queries result))
          s (first (:query/steps q))]
      (is (= 1 (count (:query/steps q)))
          "query should have one step")
      (is (= 2 (:step/id s))
          "step id should be 2 (next-id after query used 1)")
      (is (= 7 (:step/draw-count s))
          "draw-count should default to 7")
      (is (= [] (:step/targets s))
          "targets should be empty vector"))))


(deftest test-add-step-uses-distinct-id-from-query
  (testing "add-step uses an id distinct from the parent query id"
    (let [db (calc/add-query-handler default-db nil)
          result (calc/add-step-handler db [nil 1])
          q (first (:calculator/queries result))
          step-id (:step/id (first (:query/steps q)))]
      (is (not= 1 step-id)
          "step id should be different from query id 1")
      (is (= 2 step-id)
          "step id should be 2 (incremented from query's id 1)"))))


(deftest test-add-step-unknown-query-id-is-noop
  (testing "add-step with unknown query id is a no-op"
    (let [db (calc/add-query-handler default-db nil)
          before (:calculator/queries db)
          result (calc/add-step-handler db [nil 999])]
      (is (= before (:calculator/queries result))
          "queries should be unchanged for unknown query id"))))


;; === ::remove-step ===

(deftest test-remove-step-removes-by-id
  (testing "remove-step filters out the step with matching id"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          result (calc/remove-step-handler db [nil 1 2])
          q (first (:calculator/queries result))]
      (is (= [] (:query/steps q))
          "steps should be empty after removing the only step"))))


(deftest test-remove-step-unknown-id-is-noop
  (testing "remove-step with unknown step id is a no-op"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          before-steps (:query/steps (first (:calculator/queries db)))
          result (calc/remove-step-handler db [nil 1 999])
          after-steps (:query/steps (first (:calculator/queries result)))]
      (is (= before-steps after-steps)
          "steps should be unchanged for unknown step id"))))


;; === ::set-step-draw-count ===

(deftest test-set-step-draw-count-updates-value
  (testing "set-step-draw-count updates the draw count for the matching step"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          result (calc/set-step-draw-count-handler db [nil 1 2 3])
          s (first (:query/steps (first (:calculator/queries result))))]
      (is (= 3 (:step/draw-count s))
          "draw-count should be updated to 3"))))


(deftest test-set-step-draw-count-clamps-negative-to-zero
  (testing "set-step-draw-count clamps negative value to 0"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          result (calc/set-step-draw-count-handler db [nil 1 2 -5])
          s (first (:query/steps (first (:calculator/queries result))))]
      (is (= 0 (:step/draw-count s))
          "draw-count should be clamped to 0 for negative input"))))


(deftest test-set-step-draw-count-allows-zero
  (testing "set-step-draw-count accepts 0 as valid (models no draw)"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          result (calc/set-step-draw-count-handler db [nil 1 2 0])
          s (first (:query/steps (first (:calculator/queries result))))]
      (is (= 0 (:step/draw-count s))
          "draw-count of 0 should be accepted"))))


;; === ::add-target-group ===

(deftest test-add-target-group-creates-correct-shape
  (testing "add-target-group appends a target with correct fields"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          result (calc/add-target-group-handler db [nil 1 2])
          s (first (:query/steps (first (:calculator/queries result))))
          t (first (:step/targets s))]
      (is (= 1 (count (:step/targets s)))
          "step should have one target")
      (is (= 3 (:target/id t))
          "target id should be 3 (next-id after query=1, step=2)")
      (is (= #{} (:target/cards t))
          "cards should be empty set")
      (is (= 1 (:target/min-count t))
          "min-count should default to 1"))))


(deftest test-add-target-group-unknown-step-id-is-noop
  (testing "add-target-group with unknown step id is a no-op"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1]))
          before-steps (:query/steps (first (:calculator/queries db)))
          result (calc/add-target-group-handler db [nil 1 999])
          after-steps (:query/steps (first (:calculator/queries result)))]
      (is (= before-steps after-steps)
          "steps should be unchanged for unknown step id"))))


;; === ::remove-target-group ===

(deftest test-remove-target-group-removes-by-id
  (testing "remove-target-group filters out the target with matching id"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          result (calc/remove-target-group-handler db [nil 1 2 3])
          s (first (:query/steps (first (:calculator/queries result))))]
      (is (= [] (:step/targets s))
          "targets should be empty after removing the only target"))))


(deftest test-remove-target-group-leaves-empty-vector-not-nil
  (testing "remove-target-group leaves empty vector when last target removed"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          result (calc/remove-target-group-handler db [nil 1 2 3])
          s (first (:query/steps (first (:calculator/queries result))))]
      (is (vector? (:step/targets s))
          "targets should remain a vector, not nil"))))


;; === ::toggle-target-card ===

(deftest test-toggle-target-card-adds-when-not-present
  (testing "toggle-target-card adds card to :target/cards when not present"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          result (calc/toggle-target-card-handler db [nil 1 2 3 :dark-ritual])
          t (first (:step/targets (first (:query/steps (first (:calculator/queries result))))))]
      (is (contains? (:target/cards t) :dark-ritual)
          "dark-ritual should be in target/cards after toggle"))))


(deftest test-toggle-target-card-removes-when-present
  (testing "toggle-target-card removes card from :target/cards when already present"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2])
                 (calc/toggle-target-card-handler [nil 1 2 3 :dark-ritual]))
          result (calc/toggle-target-card-handler db [nil 1 2 3 :dark-ritual])
          t (first (:step/targets (first (:query/steps (first (:calculator/queries result))))))]
      (is (not (contains? (:target/cards t) :dark-ritual))
          "dark-ritual should be removed from target/cards after second toggle"))))


(deftest test-toggle-target-card-unknown-target-id-is-noop
  (testing "toggle-target-card with unknown target id is a no-op"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          before-targets (:step/targets (first (:query/steps (first (:calculator/queries db)))))
          result (calc/toggle-target-card-handler db [nil 1 2 999 :dark-ritual])
          after-targets (:step/targets (first (:query/steps (first (:calculator/queries result)))))]
      (is (= before-targets after-targets)
          "targets should be unchanged for unknown target id"))))


;; === ::set-target-min-count ===

(deftest test-set-target-min-count-updates-value
  (testing "set-target-min-count updates the min-count for the matching target"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          result (calc/set-target-min-count-handler db [nil 1 2 3 2])
          t (first (:step/targets (first (:query/steps (first (:calculator/queries result))))))]
      (is (= 2 (:target/min-count t))
          "min-count should be updated to 2"))))


(deftest test-set-target-min-count-clamps-negative-to-zero
  (testing "set-target-min-count clamps negative value to 0"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          result (calc/set-target-min-count-handler db [nil 1 2 3 -3])
          t (first (:step/targets (first (:query/steps (first (:calculator/queries result))))))]
      (is (= 0 (:target/min-count t))
          "min-count should be clamped to 0 for negative input"))))


(deftest test-set-target-min-count-unknown-target-id-is-noop
  (testing "set-target-min-count with unknown target id is a no-op"
    (let [db (-> default-db
                 (calc/add-query-handler nil)
                 (calc/add-step-handler [nil 1])
                 (calc/add-target-group-handler [nil 1 2]))
          before-targets (:step/targets (first (:query/steps (first (:calculator/queries db)))))
          result (calc/set-target-min-count-handler db [nil 1 2 999 5])
          after-targets (:step/targets (first (:query/steps (first (:calculator/queries result)))))]
      (is (= before-targets after-targets)
          "targets should be unchanged for unknown target id"))))


;; === max-id-in-queries helper ===

(deftest test-max-id-in-queries-finds-max-across-all-levels
  (testing "max-id-in-queries returns the highest id across queries, steps, and targets"
    (let [queries [{:query/id    1
                    :query/steps [{:step/id      5
                                   :step/targets [{:target/id 3}
                                                  {:target/id 7}]}]}
                   {:query/id    2
                    :query/steps [{:step/id      4
                                   :step/targets [{:target/id 6}]}]}]]
      (is (= 7 (calc/max-id-in-queries queries))
          "should return 7, the highest id anywhere in the structure"))))


(deftest test-max-id-in-queries-empty-returns-zero
  (testing "max-id-in-queries returns 0 for empty queries"
    (is (= 0 (calc/max-id-in-queries []))
        "should return 0 when no queries exist")))


(deftest test-max-id-in-queries-single-query-no-steps
  (testing "max-id-in-queries works with single query and no steps"
    (let [queries [{:query/id 5 :query/steps []}]]
      (is (= 5 (calc/max-id-in-queries queries))
          "should return the query's own id when it has no steps"))))


;; === init-calculator with restored queries ===

(deftest test-init-calculator-with-loaded-queries-sets-next-id-past-max
  (testing "init-calculator restores from localStorage and sets next-id past max existing id"
    ;; Save queries with known IDs to localStorage, then init from empty db
    (set! js/localStorage (create-mock-storage))
    (let [saved-queries [{:query/id    10
                          :query/label "Saved"
                          :query/collapsed? false
                          :query/steps [{:step/id      20
                                         :step/draw-count 7
                                         :step/targets [{:target/id 30
                                                         :target/cards #{}
                                                         :target/min-count 1}]}]}]]
      (storage/save-calculator-queries! saved-queries)
      (let [result (calc/init-calculator-handler {} nil)]
        (is (= saved-queries (:calculator/queries result))
            "queries should be restored from localStorage")
        (is (= 31 (:calculator/next-id result))
            "next-id should be (inc max-id) = 31 to avoid collision")))))


(deftest test-init-calculator-is-idempotent-when-queries-present
  (testing "init-calculator does not overwrite existing :calculator/queries"
    (let [existing-db {:calculator/visible? true
                       :calculator/next-id  5
                       :calculator/queries  [{:query/id 1}]}
          result      (calc/init-calculator-handler existing-db nil)]
      (is (= [{:query/id 1}] (:calculator/queries result))
          "queries should be unchanged when already in db")
      (is (= 5 (:calculator/next-id result))
          "next-id should be unchanged when queries already present"))))
