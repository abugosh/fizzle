(ns fizzle.engine.stack-spec-test
  "Tests for engine/stack-spec.cljs and engine/spec-util.cljs.

   All spec validation tests use (binding [spec-util/*throw-on-spec-failure* true] ...)
   to convert console.error into throws for test assertions.
   Do NOT test console.error path directly — the throw path exercises the same logic."
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.stack-spec :as stack-spec]))


;; =====================================================
;; A. spec_util Tests
;; =====================================================

(deftest test-validate-valid-data-no-throw
  (testing "validate-at-chokepoint! does not throw with valid data and *throw-on-spec-failure* true"
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (nil?
            (stack-spec/validate-stack-item!
              {:stack-item/type :spell
               :stack-item/controller :player-1
               :stack-item/source (random-uuid)
               :stack-item/object-ref 42}))
          "Valid spell should not throw"))))


(deftest test-validate-invalid-data-throws
  (testing "validate-at-chokepoint! throws when *throw-on-spec-failure* is true and data is invalid"
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (thrown? js/Error
            (stack-spec/validate-stack-item!
              {:stack-item/type :spell
               :stack-item/controller :player-1
               ;; Missing :stack-item/source and :stack-item/object-ref
               }))
          "Spell missing required fields should throw"))))


(deftest test-validate-default-no-throw
  (testing "validate-at-chokepoint! does NOT throw when *throw-on-spec-failure* is false (default)"
    ;; Default is false — invalid data should NOT throw, only console.error
    (is (nil?
          (stack-spec/validate-stack-item!
            {:stack-item/type :spell
             ;; Missing everything except type
             }))
        "Default mode should not throw even on invalid data")))


;; =====================================================
;; B. Stack Item Spec Completeness
;; =====================================================

(def all-stack-item-types
  [:spell :storm-copy :activated-ability :permanent-entered :storm
   :declare-attackers :declare-blockers :combat-damage :delayed-trigger
   :state-check-trigger])


(deftest test-all-types-have-defmethod
  (testing "All 10 stack-item types have a defmethod (minimal-valid-stack-item passes spec)"
    (doseq [t all-stack-item-types]
      (let [minimal (stack-spec/minimal-valid-stack-item t)]
        (is (s/valid? ::stack-spec/stack-item minimal)
            (str "Failed for type " t ": "
                 (s/explain-str ::stack-spec/stack-item minimal)))))))


(deftest test-all-types-in-minimal-valid-map
  (testing "minimal-valid-stack-items map has exactly 10 entries covering all types"
    (is (= 10 (count stack-spec/minimal-valid-stack-items))
        "Should have exactly 10 types")
    (doseq [t all-stack-item-types]
      (is (contains? stack-spec/minimal-valid-stack-items t)
          (str "Missing type in minimal-valid-stack-items: " t)))))


;; =====================================================
;; C. Per-Type Required Field Validation
;; =====================================================

(deftest test-spell-requires-object-ref
  (testing ":spell without :stack-item/object-ref fails"
    (let [item (dissoc (stack-spec/minimal-valid-stack-item :spell) :stack-item/object-ref)]
      (is (not (s/valid? ::stack-spec/stack-item item))
          "spell without object-ref should fail"))))


(deftest test-spell-requires-source
  (testing ":spell without :stack-item/source fails"
    (let [item (dissoc (stack-spec/minimal-valid-stack-item :spell) :stack-item/source)]
      (is (not (s/valid? ::stack-spec/stack-item item))
          "spell without source should fail"))))


(deftest test-activated-ability-requires-effects
  (testing ":activated-ability without :stack-item/effects fails"
    (let [item (dissoc (stack-spec/minimal-valid-stack-item :activated-ability) :stack-item/effects)]
      (is (not (s/valid? ::stack-spec/stack-item item))
          "activated-ability without effects should fail"))))


(deftest test-delayed-trigger-requires-effects
  (testing ":delayed-trigger without :stack-item/effects fails"
    (let [item (dissoc (stack-spec/minimal-valid-stack-item :delayed-trigger) :stack-item/effects)]
      (is (not (s/valid? ::stack-spec/stack-item item))
          "delayed-trigger without effects should fail"))))


(deftest test-combat-types-minimal
  (testing ":declare-attackers, :declare-blockers, :combat-damage pass with only type+controller"
    (doseq [t [:declare-attackers :declare-blockers :combat-damage]]
      (let [minimal {:stack-item/type t :stack-item/controller :player-1}]
        (is (s/valid? ::stack-spec/stack-item minimal)
            (str t " should pass with only type+controller"))))))


(deftest test-state-check-trigger-requires-source
  (testing ":state-check-trigger without :stack-item/source fails"
    (let [item (dissoc (stack-spec/minimal-valid-stack-item :state-check-trigger) :stack-item/source)]
      (is (not (s/valid? ::stack-spec/stack-item item))
          "state-check-trigger without source should fail"))))


;; =====================================================
;; D. Edge Case Validation
;; =====================================================

(deftest test-nil-type-fails
  (testing "map with :stack-item/type nil fails validation"
    (is (not (s/valid? ::stack-spec/stack-item
                       {:stack-item/type nil
                        :stack-item/controller :player-1}))
        "nil type should fail")))


(deftest test-unknown-type-fails
  (testing "map with unknown :stack-item/type fails validation"
    (is (not (s/valid? ::stack-spec/stack-item
                       {:stack-item/type :nonexistent-type
                        :stack-item/controller :player-1}))
        "unknown type should fail")))


(deftest test-extra-fields-allowed
  (testing "valid spell with extra keys still passes (s/keys allows extra keys)"
    (let [item (assoc (stack-spec/minimal-valid-stack-item :spell)
                      :foo/bar :baz
                      :some/random-key 99)]
      (is (s/valid? ::stack-spec/stack-item item)
          "Extra keys should not invalidate a valid stack item"))))


;; =====================================================
;; E. Chokepoint Integration Tests
;; =====================================================

(deftest test-create-stack-item-valid-succeeds
  (testing "create-stack-item with valid :spell attrs produces item in db"
    (let [db (d/empty-db schema)
          src-uuid (random-uuid)
          db' (stack/create-stack-item db {:stack-item/type :spell
                                           :stack-item/controller :player-1
                                           :stack-item/source src-uuid
                                           :stack-item/object-ref 1})]
      (let [items (d/q '[:find [(pull ?e [*]) ...]
                         :where [?e :stack-item/position _]]
                       db')]
        (is (= 1 (count items)))
        (is (= :spell (:stack-item/type (first items))))))))


(deftest test-create-stack-item-invalid-throws
  (testing "create-stack-item with :spell missing :object-ref throws when *throw-on-spec-failure* is true"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (d/empty-db schema)]
        (is (thrown? js/Error
              (stack/create-stack-item db {:stack-item/type :spell
                                           :stack-item/controller :player-1
                                           :stack-item/source (random-uuid)
                                           ;; Missing :stack-item/object-ref
                                           }))
            "Should throw when required field missing")))))
