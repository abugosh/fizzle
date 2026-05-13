(ns fizzle.engine.validation-test
  "Tests for engine/validation.cljs — data-driven selection validation.

   Covers:
     A. :allocation-complete validation type (zero? generic-remaining guard)
     B. Other validation types for completeness"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.engine.validation :as validation]))


;; =====================================================
;; A. :allocation-complete validation tests
;; =====================================================

(deftest allocation-complete-generic-remaining-2-returns-false
  (testing ":allocation-complete with generic-remaining 2 returns false"
    (let [sel {:selection/validation :allocation-complete
               :selection/selected []
               :selection/generic-remaining 2}]
      (is (false? (validation/validate-selection sel))
          "generic-remaining 2 means allocation not complete — must return false"))))


(deftest allocation-complete-generic-remaining-0-returns-true
  (testing ":allocation-complete with generic-remaining 0 returns true"
    (let [sel {:selection/validation :allocation-complete
               :selection/selected []
               :selection/generic-remaining 0}]
      (is (true? (validation/validate-selection sel))
          "generic-remaining 0 means allocation is complete — must return true"))))


(deftest allocation-complete-generic-remaining-nil-returns-true
  (testing ":allocation-complete with generic-remaining nil returns true (treats nil as 0)"
    (let [sel {:selection/validation :allocation-complete
               :selection/selected []
               :selection/generic-remaining nil}]
      (is (true? (validation/validate-selection sel))
          "(or nil 0) = 0 — nil remaining means no generic left, allocation is complete"))))


(deftest allocation-complete-generic-remaining-absent-returns-true
  (testing ":allocation-complete with no :selection/generic-remaining key returns true"
    (let [sel {:selection/validation :allocation-complete
               :selection/selected []}]
      (is (true? (validation/validate-selection sel))
          "Missing key defaults to nil which is treated as 0 — allocation complete"))))


(deftest allocation-complete-generic-remaining-1-returns-false
  (testing ":allocation-complete with generic-remaining 1 returns false"
    (let [sel {:selection/validation :allocation-complete
               :selection/selected []
               :selection/generic-remaining 1}]
      (is (false? (validation/validate-selection sel))
          "generic-remaining 1 means still 1 generic mana unallocated"))))


;; =====================================================
;; Boundary: nil validation type returns false (safe default)
;; =====================================================

(deftest nil-validation-type-returns-false
  (testing "nil :selection/validation returns false (safe default)"
    (let [sel {:selection/selected []
               :selection/select-count 0}]
      (is (false? (validation/validate-selection sel))
          "Missing validation type falls through to false — safe default per docstring"))))


;; =====================================================
;; :exact validation type (regression guard)
;; =====================================================

(deftest exact-validation-matching-count-returns-true
  (testing ":exact validation returns true when selected count matches select-count"
    (let [sel {:selection/validation :exact
               :selection/selected [:a :b]
               :selection/select-count 2}]
      (is (true? (validation/validate-selection sel))))))


(deftest exact-validation-mismatched-count-returns-false
  (testing ":exact validation returns false when selected count mismatches select-count"
    (let [sel {:selection/validation :exact
               :selection/selected [:a]
               :selection/select-count 2}]
      (is (false? (validation/validate-selection sel))))))


;; =====================================================
;; :always validation type
;; =====================================================

(deftest always-validation-returns-true-for-any-state
  (testing ":always validation returns true regardless of selected items"
    (let [sel {:selection/validation :always
               :selection/selected []}]
      (is (true? (validation/validate-selection sel))))))
