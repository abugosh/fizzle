(ns fizzle.engine.object-spec-test
  "Tests for engine/object-spec.cljs and the restorer.cljs object-tx chokepoint.

   All chokepoint tests use (binding [spec-util/*throw-on-spec-failure* true] ...)
   to convert console.error into throws for test assertions."
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [fizzle.engine.object-spec :as object-spec]
    [fizzle.engine.spec-util :as spec-util]))


;; =====================================================
;; A. ::object-tx spec — valid maps pass
;; =====================================================

(def ^:private valid-object-tx
  "A minimal valid object transaction map (non-creature hand card)."
  {:object/id         (random-uuid)
   :object/card       42
   :object/zone       :hand
   :object/owner      1
   :object/controller 1
   :object/tapped     false
   :object/position   0})


(deftest test-object-tx-minimal-valid
  (testing "minimal object transaction map (non-creature, non-library) passes spec"
    (is (s/valid? ::object-spec/object-tx valid-object-tx)
        (str "Failed: " (s/explain-str ::object-spec/object-tx valid-object-tx)))))


(deftest test-object-tx-all-zones-valid
  (testing "all valid zones are accepted"
    (doseq [zone [:hand :library :graveyard :exile :battlefield :stack :sideboard :phased-out]]
      (let [obj (assoc valid-object-tx :object/zone zone)]
        (is (s/valid? ::object-spec/object-tx obj)
            (str "Zone " zone " should be valid"))))))


(deftest test-object-tx-with-optional-fields
  (testing "object with optional counters and grants passes spec"
    (let [obj (assoc valid-object-tx
                     :object/counters {:+1/+1 2}
                     :object/grants [:flying])]
      (is (s/valid? ::object-spec/object-tx obj)
          "Object with counters and grants should be valid"))))


(deftest test-object-tx-battlefield-creature
  (testing "battlefield creature with power/toughness/summoning-sick/damage-marked passes spec"
    (let [creature (assoc valid-object-tx
                          :object/zone :battlefield
                          :object/power 2
                          :object/toughness 2
                          :object/summoning-sick false
                          :object/damage-marked 0)]
      (is (s/valid? ::object-spec/object-tx creature)
          "Battlefield creature with all stats should be valid"))))


;; =====================================================
;; B. ::object-tx spec — invalid maps fail
;; =====================================================

(deftest test-object-tx-missing-required-fields
  (testing "object missing required fields fails spec"
    (is (not (s/valid? ::object-spec/object-tx
                       (dissoc valid-object-tx :object/id)))
        "Missing :object/id should fail")
    (is (not (s/valid? ::object-spec/object-tx
                       (dissoc valid-object-tx :object/card)))
        "Missing :object/card should fail")
    (is (not (s/valid? ::object-spec/object-tx
                       (dissoc valid-object-tx :object/zone)))
        "Missing :object/zone should fail")
    (is (not (s/valid? ::object-spec/object-tx
                       (dissoc valid-object-tx :object/tapped)))
        "Missing :object/tapped should fail")))


(deftest test-object-tx-invalid-zone
  (testing "invalid zone keyword fails spec"
    (is (not (s/valid? ::object-spec/object-tx
                       (assoc valid-object-tx :object/zone :not-a-zone)))
        "Unknown zone keyword should fail")))


(deftest test-object-tx-non-uuid-id
  (testing "non-UUID :object/id fails spec"
    (is (not (s/valid? ::object-spec/object-tx
                       (assoc valid-object-tx :object/id :some-keyword)))
        "Keyword :object/id should fail — must be UUID")))


(deftest test-object-tx-non-int-card-eid
  (testing "non-int :object/card fails spec — must be Datascript EID"
    (is (not (s/valid? ::object-spec/object-tx
                       (assoc valid-object-tx :object/card :some-card-id)))
        "Keyword :object/card should fail — card must be Datascript EID")))


;; =====================================================
;; C. validate-object-tx! chokepoint: valid map does NOT throw
;; =====================================================

(deftest test-validate-object-tx-valid-does-not-throw
  (testing "validate-object-tx! does not throw with valid object map"
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (nil? (object-spec/validate-object-tx! valid-object-tx))
          "Valid object tx should not throw"))))


;; =====================================================
;; D. validate-object-tx! chokepoint: invalid map DOES throw in dev mode
;; =====================================================

(deftest test-validate-object-tx-missing-id-throws
  (testing "validate-object-tx! throws when :object/id is missing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (thrown? js/Error
            (object-spec/validate-object-tx!
              (dissoc valid-object-tx :object/id)))
          "Missing :object/id should throw"))))


(deftest test-validate-object-tx-invalid-zone-throws
  (testing "validate-object-tx! throws when zone is invalid"
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (thrown? js/Error
            (object-spec/validate-object-tx!
              (assoc valid-object-tx :object/zone :not-a-zone)))
          "Invalid zone should throw"))))
