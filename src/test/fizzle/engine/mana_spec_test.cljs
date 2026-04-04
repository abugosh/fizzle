(ns fizzle.engine.mana-spec-test
  "Tests for mana pool validation chokepoints in engine/mana.cljs.

   All chokepoint tests use (binding [spec-util/*throw-on-spec-failure* true] ...)
   to convert console.error into throws for test assertions.
   Do NOT test console.error path directly — the throw path exercises the same logic."
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.mana-spec :as mana-spec]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]))


;; =====================================================
;; A. :game/mana-map spec (already in spec-common; tested here for mana context)
;; =====================================================

(deftest test-mana-map-valid-partial
  (testing "partial mana maps with valid color keys pass :game/mana-map"
    (is (s/valid? :game/mana-map {:black 3}) "single-color partial map")
    (is (s/valid? :game/mana-map {:white 1 :blue 2 :black 3}) "multi-color partial map")
    (is (s/valid? :game/mana-map {}) "empty map is valid partial map")))


(deftest test-mana-map-rejects-generic-key
  (testing ":generic key is rejected — would cause silent nil in merge-with +"
    (is (not (s/valid? :game/mana-map {:generic 1})) ":generic key rejected")))


(deftest test-mana-map-rejects-negative
  (testing "negative mana values are rejected"
    (is (not (s/valid? :game/mana-map {:black -1})) "negative value rejected")))


;; =====================================================
;; B. :fizzle.engine.mana-spec/mana-add-arg — add-mana argument spec
;; =====================================================

(deftest test-mana-add-arg-valid
  (testing "valid add-mana arguments pass :fizzle.engine.mana-spec/mana-add-arg"
    (is (s/valid? ::mana-spec/mana-add-arg {:black 3}) "partial mana map")
    (is (s/valid? ::mana-spec/mana-add-arg {}) "empty map is valid (no-op)")))


(deftest test-mana-add-arg-rejects-bad-keys
  (testing "invalid mana keys fail :fizzle.engine.mana-spec/mana-add-arg"
    (is (not (s/valid? ::mana-spec/mana-add-arg {:generic 1})) ":generic rejected")
    (is (not (s/valid? ::mana-spec/mana-add-arg {:any 1})) ":any rejected")
    (is (not (s/valid? ::mana-spec/mana-add-arg {:x 1})) ":x rejected — :x is not a pool color")))


;; =====================================================
;; B2. :fizzle.engine.mana-spec/mana-pay-arg — pay-mana cost argument spec
;; =====================================================

(deftest test-mana-pay-arg-valid
  (testing "valid pay-mana cost arguments pass ::mana-pay-arg"
    (is (s/valid? ::mana-spec/mana-pay-arg {:black 3}) "colored cost valid")
    (is (s/valid? ::mana-spec/mana-pay-arg {:colorless 2}) "generic cost valid")
    (is (s/valid? ::mana-spec/mana-pay-arg {:x 1 :black 1}) ":x cost valid — pay-mana can receive X costs")
    (is (s/valid? ::mana-spec/mana-pay-arg {}) "empty cost valid")))


(deftest test-mana-pay-arg-rejects-bad-keys
  (testing "invalid mana keys fail ::mana-pay-arg"
    (is (not (s/valid? ::mana-spec/mana-pay-arg {:generic 1})) ":generic rejected")
    (is (not (s/valid? ::mana-spec/mana-pay-arg {:any 1})) ":any rejected")))


;; =====================================================
;; C. add-mana chokepoint: valid map does NOT throw
;; =====================================================

(deftest test-add-mana-valid-does-not-throw
  (testing "add-mana with valid mana map does not trigger chokepoint error"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (init-game-state)]
        (is (some? (mana/add-mana db :player-1 {:black 3}))
            "Valid add-mana should not throw")
        (is (some? (mana/add-mana db :player-1 {:white 1 :blue 1 :colorless 2}))
            "Multi-color valid add-mana should not throw")
        (is (some? (mana/add-mana db :player-1 {}))
            "Empty map add-mana should not throw")))))


;; =====================================================
;; D. add-mana chokepoint: invalid map DOES throw in dev mode
;; =====================================================

(deftest test-add-mana-invalid-key-throws
  (testing "add-mana with :generic key triggers chokepoint error in dev mode"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (init-game-state)]
        (is (thrown? js/Error
              (mana/add-mana db :player-1 {:generic 3}))
            "add-mana with :generic key should throw — catches silent merge-with nil bug")))))


(deftest test-add-mana-negative-value-throws
  (testing "add-mana with negative value triggers chokepoint error in dev mode"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (init-game-state)]
        (is (thrown? js/Error
              (mana/add-mana db :player-1 {:black -1}))
            "add-mana with negative value should throw")))))


;; =====================================================
;; E. pay-mana chokepoint: valid mana cost does NOT throw
;; =====================================================

(deftest test-pay-mana-valid-does-not-throw
  (testing "pay-mana with valid mana cost does not trigger chokepoint error"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (-> (init-game-state)
                   (mana/add-mana :player-1 {:black 3 :blue 2}))]
        (is (some? (mana/pay-mana db :player-1 {:black 1}))
            "Valid colored pay-mana should not throw")
        (is (some? (mana/pay-mana db :player-1 {:colorless 2}))
            "Valid generic pay-mana should not throw")
        (is (some? (mana/pay-mana db :player-1 {}))
            "Empty cost pay-mana should not throw")
        (is (some? (mana/pay-mana db :player-1 {:x 1 :black 1} 2))
            "X cost pay-mana should not throw — :x is valid in pay-mana arg")))))


;; =====================================================
;; F. pay-mana chokepoint: invalid mana cost DOES throw in dev mode
;; =====================================================

(deftest test-pay-mana-invalid-key-throws
  (testing "pay-mana with :generic key triggers chokepoint error in dev mode"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (-> (init-game-state)
                   (mana/add-mana :player-1 {:black 3}))]
        (is (thrown? js/Error
              (mana/pay-mana db :player-1 {:generic 1}))
            "pay-mana with :generic key should throw")))))
