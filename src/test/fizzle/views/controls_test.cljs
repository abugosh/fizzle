(ns fizzle.views.controls-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.controls :as controls]))


;; === top-stack-item-name tests ===

(deftest test-spell-on-stack
  (testing "Returns card name from spell object (has :object/card)"
    (is (= "Dark Ritual"
           (controls/top-stack-item-name
             [{:object/card {:card/name "Dark Ritual"}
               :object/position 1}])))))


(deftest test-enriched-stack-item
  (testing "Returns card name from enriched non-spell stack-item"
    (is (= "Lion's Eye Diamond"
           (controls/top-stack-item-name
             [{:stack-item/card-name "Lion's Eye Diamond"
               :stack-item/position 1}])))))


(deftest test-description-fallback
  (testing "Falls back to description when no card name"
    (is (= "Draw 3 cards"
           (controls/top-stack-item-name
             [{:stack-item/description "Draw 3 cards"
               :stack-item/position 1}])))))


(deftest test-type-fallback
  (testing "Falls back to type name when no card name or description"
    (is (= "etb"
           (controls/top-stack-item-name
             [{:stack-item/type :etb
               :stack-item/position 1}])))))


(deftest test-empty-stack
  (testing "Returns nil for empty stack"
    (is (nil? (controls/top-stack-item-name [])))))


(deftest test-nil-stack
  (testing "Returns nil for nil stack"
    (is (nil? (controls/top-stack-item-name nil)))))


(deftest test-card-name-priority-over-description
  (testing "Card name takes priority over description"
    (is (= "Brain Freeze"
           (controls/top-stack-item-name
             [{:stack-item/card-name "Brain Freeze"
               :stack-item/description "Mill 3"
               :stack-item/position 1}])))))


(deftest test-object-card-name-priority-over-stack-item-card-name
  (testing "Object card name takes priority over stack-item card-name"
    (is (= "Dark Ritual"
           (controls/top-stack-item-name
             [{:object/card {:card/name "Dark Ritual"}
               :stack-item/card-name "Something Else"
               :object/position 1}])))))


;; === controls-view inline-component parameter tests ===

(deftest test-controls-view-structure-with-nil-inline-component
  (testing "when inline-component is nil, controls-view returns div with :<> fragment for buttons"
    (let [result (controls/controls-view nil)]
      (is (vector? result)
          "controls-view should return a hiccup vector")
      (is (= :div (first result))
          "Top level should be :div")
      ;; The structure when nil is: [:div {:class "mb-4"} [:<> [buttons...]]]
      (let [children (drop 2 result)
            fragment (first children)]
        (is (vector? fragment)
            "Second child should be a hiccup vector (the fragment with buttons)")
        (is (= :<> (first fragment))
            "When inline-component is nil, should render :<> fragment with buttons")))))


(deftest test-controls-view-with-inline-component
  (testing "when inline-component is a hiccup vector, controls-view renders it directly inside div"
    (let [inline-comp [:div {:class "custom-selection"} "Custom UI"]
          result (controls/controls-view inline-comp)]
      (is (vector? result)
          "controls-view should return a hiccup vector")
      (is (= :div (first result))
          "Top level should be :div")
      ;; The structure when inline-component is provided: [:div {:class "mb-4"} inline-component]
      (let [children (drop 2 result)
            content (first children)]
        (is (= inline-comp content)
            "When inline-component is provided, it should be rendered directly")
        (is (= :div (first content))
            "Inline component should be a div")
        (is (= "custom-selection" (get-in content [1 :class]))
            "Inline component should have custom class")))))


(deftest test-controls-view-signature-with-no-args
  (testing "controls-view can be called with no arguments (arity 0)"
    (let [result (controls/controls-view)]
      (is (vector? result)
          "controls-view with no args should return a hiccup vector")
      (is (= :div (first result))
          "Should return a div structure"))))
