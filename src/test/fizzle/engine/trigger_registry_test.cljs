(ns fizzle.engine.trigger-registry-test
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
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
  ([id] (make-trigger id :phase-entered nil))
  ([id event-type] (make-trigger id event-type nil))
  ([id event-type source]
   {:trigger/id id
    :trigger/event-type event-type
    :trigger/source source}))


;; === register-trigger! tests ===

(deftest test-register-trigger-adds-to-registry
  (testing "registering a trigger adds it to registry"
    (let [trigger (make-trigger :t1)]
      (registry/register-trigger! trigger)
      (is (= 1 (count (registry/get-all-triggers)))
          "Registry should have 1 trigger")
      (is (= trigger (first (registry/get-all-triggers)))
          "Trigger in registry should match registered trigger"))))


(deftest test-register-trigger-idempotent-same-id-overwrites
  (testing "registering same ID twice overwrites"
    (let [trigger1 (assoc (make-trigger :t1) :extra :first)
          trigger2 (assoc (make-trigger :t1) :extra :second)]
      (registry/register-trigger! trigger1)
      (registry/register-trigger! trigger2)
      (is (= 1 (count (registry/get-all-triggers)))
          "Registry should still have only 1 trigger")
      (is (= :second (:extra (first (registry/get-all-triggers))))
          "Trigger should be the second one registered"))))


(deftest test-register-trigger-returns-id
  (testing "register-trigger! returns the trigger ID"
    (let [trigger (make-trigger :my-id)
          result (registry/register-trigger! trigger)]
      (is (= :my-id result)))))


;; === unregister-trigger! tests ===

(deftest test-unregister-trigger-removes-from-registry
  (testing "unregistering removes trigger from registry"
    (let [trigger (make-trigger :t1)]
      (registry/register-trigger! trigger)
      (is (= 1 (count (registry/get-all-triggers))))
      (registry/unregister-trigger! :t1)
      (is (= 0 (count (registry/get-all-triggers)))
          "Registry should be empty after unregister"))))


(deftest test-unregister-nonexistent-trigger-no-op
  (testing "unregistering non-existent trigger is no-op"
    (let [trigger (make-trigger :t1)]
      (registry/register-trigger! trigger)
      (registry/unregister-trigger! :nonexistent)
      (is (= 1 (count (registry/get-all-triggers)))
          "Existing trigger should still be in registry"))))


(deftest test-unregister-trigger-returns-nil
  (testing "unregister-trigger! returns nil"
    (let [trigger (make-trigger :t1)]
      (registry/register-trigger! trigger)
      (is (nil? (registry/unregister-trigger! :t1))))))


;; === unregister-by-source! tests ===

(deftest test-unregister-by-source-removes-all-from-source
  (testing "unregister-by-source removes all triggers from that source"
    (let [source-id (random-uuid)
          t1 (make-trigger :t1 :phase-entered source-id)
          t2 (make-trigger :t2 :permanent-tapped source-id)
          t3 (make-trigger :t3 :phase-entered :other-source)]
      (registry/register-trigger! t1)
      (registry/register-trigger! t2)
      (registry/register-trigger! t3)
      (is (= 3 (count (registry/get-all-triggers))))
      (registry/unregister-by-source! source-id)
      (is (= 1 (count (registry/get-all-triggers)))
          "Only t3 should remain")
      (is (= :t3 (:trigger/id (first (registry/get-all-triggers))))))))


(deftest test-unregister-by-source-nonexistent-no-op
  (testing "unregister-by-source with non-existent source is no-op"
    (let [trigger (make-trigger :t1 :phase-entered :source-1)]
      (registry/register-trigger! trigger)
      (registry/unregister-by-source! :nonexistent-source)
      (is (= 1 (count (registry/get-all-triggers)))
          "Trigger should still be in registry"))))


(deftest test-unregister-by-source-returns-nil
  (testing "unregister-by-source! returns nil"
    (is (nil? (registry/unregister-by-source! :any-source)))))


;; === get-triggers-for-event tests ===

(deftest test-get-triggers-for-event-filters-by-type
  (testing "get-triggers-for-event returns only matching event types"
    (let [t1 (make-trigger :t1 :phase-entered)
          t2 (make-trigger :t2 :permanent-tapped)
          t3 (make-trigger :t3 :phase-entered)]
      (registry/register-trigger! t1)
      (registry/register-trigger! t2)
      (registry/register-trigger! t3)
      (let [result (registry/get-triggers-for-event {:event/type :phase-entered})]
        (is (= 2 (count result)))
        (is (every? #(= :phase-entered (:trigger/event-type %)) result))))))


(deftest test-get-triggers-for-event-empty-registry-returns-empty
  (testing "get-triggers-for-event with empty registry returns empty seq"
    (let [result (registry/get-triggers-for-event {:event/type :phase-entered})]
      (is (empty? result)))))


(deftest test-get-triggers-for-event-no-match-returns-empty
  (testing "get-triggers-for-event with no matching type returns empty"
    (let [trigger (make-trigger :t1 :permanent-tapped)]
      (registry/register-trigger! trigger)
      (let [result (registry/get-triggers-for-event {:event/type :phase-entered})]
        (is (empty? result))))))


;; === clear-registry! tests ===

(deftest test-clear-registry-removes-all
  (testing "clear-registry! removes all triggers"
    (registry/register-trigger! (make-trigger :t1))
    (registry/register-trigger! (make-trigger :t2))
    (registry/register-trigger! (make-trigger :t3))
    (is (= 3 (count (registry/get-all-triggers))))
    (registry/clear-registry!)
    (is (= 0 (count (registry/get-all-triggers))))))


(deftest test-clear-registry-returns-nil
  (testing "clear-registry! returns nil"
    (is (nil? (registry/clear-registry!)))))
