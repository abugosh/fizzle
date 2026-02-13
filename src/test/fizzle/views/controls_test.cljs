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
