(ns fizzle.views.zone-counts-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.zone-counts :as zone-counts]))


;; === Pure helper tests ===

(deftest test-gy-class-no-threshold
  (testing "graveyard count uses default class when threshold inactive"
    (let [cls (zone-counts/gy-count-class false)]
      (is (not (re-find #"text-accent" cls)))
      (is (re-find #"text-text" cls)))))


(deftest test-gy-class-threshold-active
  (testing "graveyard count uses accent class when threshold active"
    (let [cls (zone-counts/gy-count-class true)]
      (is (re-find #"text-accent" cls))
      (is (re-find #"font-bold" cls)))))


(deftest test-format-zone-count
  (testing "format-zone-count produces label: count string"
    (is (= "GY: 7" (zone-counts/format-zone-count "GY" 7)))
    (is (= "Lib: 53" (zone-counts/format-zone-count "Lib" 53)))
    (is (= "Exile: 0" (zone-counts/format-zone-count "Exile" 0)))))
