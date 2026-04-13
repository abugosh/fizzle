(ns fizzle.views.mana-pool-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.mana-pool :as mana-pool]))


;; === Mana color class mapping ===

(deftest mana-color-class-all-colors
  (testing "each mana color returns correct text class"
    (doseq [[color expected] [[:white    "text-mana-white"]
                              [:blue     "text-mana-blue"]
                              [:black    "text-mana-black"]
                              [:red      "text-mana-red"]
                              [:green    "text-mana-green"]
                              [:colorless "text-mana-colorless"]]]
      (is (= expected (mana-pool/mana-color-class color))
          (str "Failed for " color)))))


(deftest mana-color-class-unknown
  (testing "unknown mana type falls back to text-mana-colorless"
    (is (= "text-mana-colorless" (mana-pool/mana-color-class :phyrexian)))))
