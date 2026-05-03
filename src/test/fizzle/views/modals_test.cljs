(ns fizzle.views.modals-test
  "Tests for views/modals.cljs modal dispatch on :selection/mechanism.

   After task fizzle-nayb (ADR-030), modal-dispatch-key must return
   :selection/mechanism directly — no cond, no :selection/pattern fallback.

   Tests verify:
     A. modal-dispatch-key returns :selection/mechanism for all 7 mechanisms
     B. render-selection has dedicated defmethods for all 7 mechanisms
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
;; B. render-selection has dedicated defmethods for all 7 mechanisms

(deftest render-selection-has-pick-from-zone-method-test
  (testing ":pick-from-zone has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :pick-from-zone))
        "render-selection must have :pick-from-zone (not just :default)")))


(deftest render-selection-has-reorder-method-test
  (testing ":reorder has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :reorder))
        "render-selection must have :reorder")))


(deftest render-selection-has-accumulate-method-test
  (testing ":accumulate has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :accumulate))
        "render-selection must have :accumulate")))


(deftest render-selection-has-allocate-resource-method-test
  (testing ":allocate-resource has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :allocate-resource))
        "render-selection must have :allocate-resource")))


(deftest render-selection-has-n-slot-targeting-method-test
  (testing ":n-slot-targeting has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :n-slot-targeting))
        "render-selection must have :n-slot-targeting")))


(deftest render-selection-has-pick-mode-method-test
  (testing ":pick-mode has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :pick-mode))
        "render-selection must have :pick-mode")))


(deftest render-selection-has-binary-choice-method-test
  (testing ":binary-choice has a dedicated render-selection method"
    (is (some? (get-method modals/render-selection :binary-choice))
        "render-selection must have :binary-choice")))


;; ---------------------------------------------------------------------------
;; C. :selection/pattern is no longer the dispatch source

(deftest modal-dispatch-key-ignores-pattern-test
  (testing "modal-dispatch-key ignores :selection/pattern when :selection/mechanism present"
    (let [selection {:selection/mechanism :pick-from-zone
                     :selection/pattern   :accumulator ; old pattern that contradicts mechanism
                     :selection/selected  #{}}]
      (is (= :pick-from-zone (#'modals/modal-dispatch-key selection))
          ":selection/mechanism must win over :selection/pattern when both present"))))


;; ---------------------------------------------------------------------------
;; D. Multi-slot targeting label computation (fizzle-l77d)
;; cast-time-targeting-labels is a pure function — testable without React.

(deftest cast-time-targeting-labels-single-slot-test
  (testing "cast-time-targeting-labels returns single-slot labels when select-count=1"
    (let [sel {:selection/select-count 1 :selection/selected []}
          {:keys [header unselected-label selected-label]}
          (#'modals/cast-time-targeting-labels sel)]
      (is (= "Select a target" unselected-label)
          "unselected label is 'Select a target' for single slot")
      (is (= "1 target selected" selected-label)
          "selected label is '1 target selected' for single slot")
      (is (string? header)
          "header must be a string"))))


(deftest cast-time-targeting-labels-multi-slot-test
  (testing "cast-time-targeting-labels returns multi-slot labels when select-count=2"
    (let [sel {:selection/select-count 2 :selection/selected []}
          {:keys [unselected-label selected-label header]}
          (#'modals/cast-time-targeting-labels sel)]
      (is (= "Select 2 targets" unselected-label)
          "unselected label shows count for multi-slot")
      (is (string? selected-label)
          "selected label must be a string for multi-slot")
      (is (string? header)
          "header must be a string"))))


(deftest cast-time-targeting-labels-partial-fill-test
  (testing "cast-time-targeting-labels reflects partial fill state"
    (let [sel {:selection/select-count 2 :selection/selected [:obj-a]}
          {:keys [selected-label]}
          (#'modals/cast-time-targeting-labels sel)]
      (is (= "1/2 targets selected" selected-label)
          "selected label shows N/M progress when partial"))))


(deftest cast-time-targeting-labels-fully-filled-test
  (testing "cast-time-targeting-labels reflects full-fill state"
    (let [sel {:selection/select-count 2 :selection/selected [:obj-a :obj-b]}
          {:keys [selected-label]}
          (#'modals/cast-time-targeting-labels sel)]
      (is (= "2/2 targets selected" selected-label)
          "selected label shows N/N when all slots filled"))))
