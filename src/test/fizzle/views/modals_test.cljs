(ns fizzle.views.modals-test
  "Tests for views/modals.cljs modal dispatch on :selection/mechanism.

   After task fizzle-nayb (ADR-030), modal-dispatch-key must return
   :selection/mechanism directly — no cond, no :selection/pattern fallback.

   Tests verify:
     A. modal-dispatch-key returns :selection/mechanism for all 7 mechanisms
     B. render-selection-modal has dedicated defmethods for all 7 mechanisms
     C. :selection/pattern is no longer consulted for routing"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.modals :as modals]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- sel
  "Build a minimal selection map with :selection/mechanism set."
  [mechanism]
  {:selection/mechanism mechanism
   :selection/type      :discard     ; arbitrary legacy type — mechanism wins
   :selection/selected  #{}})


;; ---------------------------------------------------------------------------
;; A. modal-dispatch-key returns :selection/mechanism directly

(deftest modal-dispatch-key-pick-from-zone-test
  (testing "modal-dispatch-key returns :pick-from-zone for :pick-from-zone mechanism"
    (let [selection (sel :pick-from-zone)]
      (is (= :pick-from-zone (#'modals/modal-dispatch-key selection))
          "should return :pick-from-zone directly from :selection/mechanism"))))


(deftest modal-dispatch-key-reorder-test
  (testing "modal-dispatch-key returns :reorder for :reorder mechanism"
    (let [selection (sel :reorder)]
      (is (= :reorder (#'modals/modal-dispatch-key selection))
          "should return :reorder directly from :selection/mechanism"))))


(deftest modal-dispatch-key-accumulate-test
  (testing "modal-dispatch-key returns :accumulate for :accumulate mechanism"
    (let [selection (sel :accumulate)]
      (is (= :accumulate (#'modals/modal-dispatch-key selection))
          "should return :accumulate directly from :selection/mechanism"))))


(deftest modal-dispatch-key-allocate-resource-test
  (testing "modal-dispatch-key returns :allocate-resource for :allocate-resource mechanism"
    (let [selection (sel :allocate-resource)]
      (is (= :allocate-resource (#'modals/modal-dispatch-key selection))
          "should return :allocate-resource directly from :selection/mechanism"))))


(deftest modal-dispatch-key-n-slot-targeting-test
  (testing "modal-dispatch-key returns :n-slot-targeting for :n-slot-targeting mechanism"
    (let [selection (sel :n-slot-targeting)]
      (is (= :n-slot-targeting (#'modals/modal-dispatch-key selection))
          "should return :n-slot-targeting directly from :selection/mechanism"))))


(deftest modal-dispatch-key-pick-mode-test
  (testing "modal-dispatch-key returns :pick-mode for :pick-mode mechanism"
    (let [selection (sel :pick-mode)]
      (is (= :pick-mode (#'modals/modal-dispatch-key selection))
          "should return :pick-mode directly from :selection/mechanism"))))


(deftest modal-dispatch-key-binary-choice-test
  (testing "modal-dispatch-key returns :binary-choice for :binary-choice mechanism"
    (let [selection (sel :binary-choice)]
      (is (= :binary-choice (#'modals/modal-dispatch-key selection))
          "should return :binary-choice directly from :selection/mechanism"))))


;; ---------------------------------------------------------------------------
;; B. render-selection-modal has dedicated defmethods for all 7 mechanisms

(deftest render-modal-has-pick-from-zone-method-test
  (testing ":pick-from-zone has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :pick-from-zone))
        "render-selection-modal must have :pick-from-zone (not just :default)")))


(deftest render-modal-has-reorder-method-test
  (testing ":reorder has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :reorder))
        "render-selection-modal must have :reorder")))


(deftest render-modal-has-accumulate-method-test
  (testing ":accumulate has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :accumulate))
        "render-selection-modal must have :accumulate")))


(deftest render-modal-has-allocate-resource-method-test
  (testing ":allocate-resource has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :allocate-resource))
        "render-selection-modal must have :allocate-resource")))


(deftest render-modal-has-n-slot-targeting-method-test
  (testing ":n-slot-targeting has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :n-slot-targeting))
        "render-selection-modal must have :n-slot-targeting")))


(deftest render-modal-has-pick-mode-method-test
  (testing ":pick-mode has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :pick-mode))
        "render-selection-modal must have :pick-mode")))


(deftest render-modal-has-binary-choice-method-test
  (testing ":binary-choice has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :binary-choice))
        "render-selection-modal must have :binary-choice")))


;; ---------------------------------------------------------------------------
;; C. :selection/pattern is no longer the dispatch source

(deftest modal-dispatch-key-ignores-pattern-test
  (testing "modal-dispatch-key ignores :selection/pattern when :selection/mechanism present"
    (let [selection {:selection/mechanism :pick-from-zone
                     :selection/pattern   :accumulator ; old pattern that contradicts mechanism
                     :selection/type      :discard
                     :selection/selected  #{}}]
      (is (= :pick-from-zone (#'modals/modal-dispatch-key selection))
          ":selection/mechanism must win over :selection/pattern when both present"))))
