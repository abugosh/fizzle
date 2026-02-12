(ns fizzle.events.selection-resolution-test
  "Tests for the interactive-effect? predicate and unified detection functions.

   Verifies:
   - interactive-effect? correctly identifies all interactive effect types
   - interactive-effect? correctly rejects non-interactive effects
   - has-selection-effect? delegates to interactive-effect?
   - find-selection-effect-index delegates to interactive-effect?
   - Sync test: predicate covers all build-selection-for-effect dispatch values"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.resolution :as resolution]))


;; =====================================================
;; interactive-effect? predicate tests
;; =====================================================

(deftest test-tutor-is-interactive
  (testing ":tutor is always interactive (search library)"
    (is (true? (resolution/interactive-effect? {:effect/type :tutor})))))


(deftest test-peek-and-select-is-interactive
  (testing ":peek-and-select is always interactive (choose from revealed cards)"
    (is (true? (resolution/interactive-effect? {:effect/type :peek-and-select})))))


(deftest test-scry-positive-amount-is-interactive
  (testing ":scry with positive amount requires arranging cards"
    (is (true? (resolution/interactive-effect? {:effect/type :scry :effect/amount 3})))))


(deftest test-scry-zero-amount-is-not-interactive
  (testing ":scry with amount 0 is a no-op, not interactive"
    (is (not (resolution/interactive-effect? {:effect/type :scry :effect/amount 0})))))


(deftest test-scry-nil-amount-is-not-interactive
  (testing ":scry with no amount key defaults to non-interactive"
    (is (not (resolution/interactive-effect? {:effect/type :scry})))))


(deftest test-discard-player-selection-is-interactive
  (testing ":discard with :selection :player requires player choice"
    (is (true? (resolution/interactive-effect?
                 {:effect/type :discard :effect/selection :player})))))


(deftest test-discard-random-selection-is-not-interactive
  (testing ":discard with :selection :random is handled by engine, not interactive"
    (is (not (resolution/interactive-effect?
               {:effect/type :discard :effect/selection :random})))))


(deftest test-return-from-graveyard-player-is-interactive
  (testing ":return-from-graveyard with :selection :player requires choice"
    (is (true? (resolution/interactive-effect?
                 {:effect/type :return-from-graveyard :effect/selection :player})))))


(deftest test-return-from-graveyard-random-is-not-interactive
  (testing ":return-from-graveyard with :selection :random is not interactive"
    (is (not (resolution/interactive-effect?
               {:effect/type :return-from-graveyard :effect/selection :random})))))


(deftest test-return-from-graveyard-no-selection-is-not-interactive
  (testing ":return-from-graveyard without :selection key is not interactive"
    (is (not (resolution/interactive-effect?
               {:effect/type :return-from-graveyard})))))


(deftest test-any-player-target-is-interactive
  (testing ":effect/target :any-player requires player to choose target"
    (is (true? (resolution/interactive-effect?
                 {:effect/type :draw :effect/target :any-player})))))


(deftest test-non-interactive-effect-types
  (testing "Common non-interactive effects are correctly rejected"
    (is (not (resolution/interactive-effect? {:effect/type :add-mana})))
    (is (not (resolution/interactive-effect? {:effect/type :draw})))
    (is (not (resolution/interactive-effect? {:effect/type :mill})))
    (is (not (resolution/interactive-effect? {:effect/type :exile-self})))
    (is (not (resolution/interactive-effect? {:effect/type :sacrifice})))))


(deftest test-empty-effect-map-is-not-interactive
  (testing "Empty effect map doesn't crash and returns false"
    (is (not (resolution/interactive-effect? {})))))


(deftest test-overlapping-conditions
  (testing "Effect matching multiple conditions still returns true"
    (is (true? (resolution/interactive-effect?
                 {:effect/type :tutor :effect/selection :player})))))


;; =====================================================
;; has-selection-effect? wrapper tests
;; =====================================================

(deftest test-has-selection-effect-empty-list
  (testing "Empty effects list returns falsy"
    (is (not (resolution/has-selection-effect? [])))))


(deftest test-has-selection-effect-finds-peek-and-select
  (testing "has-selection-effect? detects :peek-and-select (was missing before unification)"
    (is (resolution/has-selection-effect?
          [{:effect/type :add-mana}
           {:effect/type :peek-and-select}]))))


(deftest test-has-selection-effect-finds-any-player-target
  (testing "has-selection-effect? detects :any-player target (was missing before unification)"
    (is (resolution/has-selection-effect?
          [{:effect/type :draw :effect/target :any-player}]))))


;; =====================================================
;; find-selection-effect-index wrapper tests
;; =====================================================

(deftest test-find-selection-effect-index-returns-correct-position
  (testing "Returns index of first interactive effect in list"
    (is (= 1 (resolution/find-selection-effect-index
               [{:effect/type :add-mana}
                {:effect/type :tutor}
                {:effect/type :draw}])))))


(deftest test-find-selection-effect-index-nil-when-none
  (testing "Returns nil when no interactive effects present"
    (is (nil? (resolution/find-selection-effect-index
                [{:effect/type :add-mana}
                 {:effect/type :draw}
                 {:effect/type :mill}])))))


(deftest test-find-selection-effect-index-first-of-multiple
  (testing "Returns first interactive effect index when multiple present"
    (is (= 0 (resolution/find-selection-effect-index
               [{:effect/type :tutor}
                {:effect/type :peek-and-select}])))))


;; =====================================================
;; Sync test: predicate covers all builder dispatch values
;; =====================================================

(def ^:private dispatch-representative-effects
  "Map of build-selection-for-effect dispatch values to representative
   effect maps that should be detected as interactive.
   Excludes :default (fallback handler, not a real dispatch value)."
  {:player-target       {:effect/type :draw :effect/target :any-player}
   :tutor               {:effect/type :tutor}
   :scry                {:effect/type :scry :effect/amount 1}
   :peek-and-select     {:effect/type :peek-and-select}
   :discard             {:effect/type :discard :effect/selection :player}
   :return-from-graveyard {:effect/type :return-from-graveyard :effect/selection :player}})


(deftest test-predicate-covers-all-builder-dispatch-values
  (testing "interactive-effect? recognizes a representative effect for every builder dispatch value"
    (let [dispatch-keys (disj (set (keys (methods core/build-selection-for-effect)))
                              :default)]
      ;; Verify every dispatch key has a representative effect
      (doseq [k dispatch-keys]
        (is (contains? dispatch-representative-effects k)
            (str "Missing representative effect for dispatch value: " k
                 ". Add an entry to dispatch-representative-effects and ensure "
                 "interactive-effect? handles it.")))
      ;; Verify the predicate recognizes each representative effect
      (doseq [[k effect] dispatch-representative-effects]
        (is (true? (resolution/interactive-effect? effect))
            (str "interactive-effect? does not recognize representative effect for: " k))))))
