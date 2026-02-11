(ns fizzle.events.selection.core-test
  "Tests for the selection mechanism in events/selection/core.cljs.
   Covers validate-selection (5 validation types + nil-reject + candidate membership)
   and auto-confirm behavior (data-driven via :selection/auto-confirm? flag)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; validate-selection tests
;; =====================================================

;; --- :exact validation ---

(deftest test-exact-validation-passes-when-count-equals-select-count
  (is (true? (core/validate-selection
               {:selection/selected #{:a :b}
                :selection/select-count 2
                :selection/validation :exact}))))


(deftest test-exact-validation-fails-when-count-differs
  (is (false? (core/validate-selection
                {:selection/selected #{:a}
                 :selection/select-count 2
                 :selection/validation :exact}))))


;; --- :at-most validation ---

(deftest test-at-most-validation-passes-when-count-under-limit
  (is (true? (core/validate-selection
               {:selection/selected #{:a}
                :selection/select-count 3
                :selection/validation :at-most}))))


(deftest test-at-most-validation-passes-at-exact-limit
  (is (true? (core/validate-selection
               {:selection/selected #{:a :b :c}
                :selection/select-count 3
                :selection/validation :at-most}))))


(deftest test-at-most-validation-fails-when-count-exceeds
  (is (false? (core/validate-selection
                {:selection/selected #{:a :b :c :d}
                 :selection/select-count 3
                 :selection/validation :at-most}))))


;; --- :at-least-one validation ---

(deftest test-at-least-one-validation-passes-with-one
  (is (true? (core/validate-selection
               {:selection/selected #{:a}
                :selection/validation :at-least-one}))))


(deftest test-at-least-one-validation-passes-with-multiple
  (is (true? (core/validate-selection
               {:selection/selected #{:a :b :c}
                :selection/validation :at-least-one}))))


(deftest test-at-least-one-validation-fails-when-empty
  (is (false? (core/validate-selection
                {:selection/selected #{}
                 :selection/validation :at-least-one}))))


;; --- :always validation ---

(deftest test-always-validation-passes-with-empty
  (is (true? (core/validate-selection
               {:selection/selected #{}
                :selection/validation :always}))))


(deftest test-always-validation-passes-with-items
  (is (true? (core/validate-selection
               {:selection/selected #{:a :b}
                :selection/validation :always}))))


;; --- :exact-or-zero validation ---

(deftest test-exact-or-zero-passes-with-zero
  (is (true? (core/validate-selection
               {:selection/selected #{}
                :selection/select-count 2
                :selection/validation :exact-or-zero}))))


(deftest test-exact-or-zero-passes-with-exact-count
  (is (true? (core/validate-selection
               {:selection/selected #{:a :b}
                :selection/select-count 2
                :selection/validation :exact-or-zero}))))


(deftest test-exact-or-zero-fails-with-partial
  (is (false? (core/validate-selection
                {:selection/selected #{:a}
                 :selection/select-count 2
                 :selection/validation :exact-or-zero}))))


;; --- nil validation (safety default) ---

(deftest test-nil-validation-rejects
  (is (false? (core/validate-selection
                {:selection/selected #{:a}}))))


;; --- candidate membership ---

(deftest test-candidate-membership-rejects-non-candidate
  (testing ":selection/candidates present - selected item not in candidates"
    (is (false? (core/validate-selection
                  {:selection/selected #{:a :b}
                   :selection/select-count 2
                   :selection/validation :exact
                   :selection/candidates #{:a :c}})))))


(deftest test-candidate-membership-passes-when-all-in-candidates
  (testing ":selection/candidates present - all selected items are candidates"
    (is (true? (core/validate-selection
                 {:selection/selected #{:a :c}
                  :selection/select-count 2
                  :selection/validation :exact
                  :selection/candidates #{:a :b :c}})))))


(deftest test-candidate-ids-membership-rejects-non-candidate
  (testing ":selection/candidate-ids present - selected item not in candidate-ids"
    (is (false? (core/validate-selection
                  {:selection/selected #{:a :b}
                   :selection/select-count 2
                   :selection/validation :exact
                   :selection/candidate-ids #{:a :c}})))))


(deftest test-candidate-membership-skipped-when-no-candidates
  (testing "No candidates or candidate-ids - skip membership check"
    (is (true? (core/validate-selection
                 {:selection/selected #{:a :b}
                  :selection/select-count 2
                  :selection/validation :exact})))))
