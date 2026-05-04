(ns fizzle.views.mana-pool-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.keyboard :as kb]
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


;; === Allocation-view / keyboard parity ===

(defn- extract-button-colors
  "Extract color keywords from allocation-view hiccup via ^{:key color} metadata.
   allocation-view returns [:div {} ... [:div.flex (lazy-seq-of-buttons)] ...].
   Each button has ^{:key color} metadata set by allocation-mana-button."
  [hiccup]
  (let [flex-div (nth hiccup 4)
        buttons (nth flex-div 2)]
    (mapv #(:key (meta %)) buttons)))


(deftest allocation-view-keyboard-parity-with-zero-amounts-test
  (testing "allocation-view renders only positive-amount colors, matching keyboard filter"
    (let [pool {:white 1 :blue 2 :black 0 :red 1 :green 0 :colorless 3}
          alloc-state {:selection/remaining-pool pool
                       :selection/allocation {}
                       :selection/generic-remaining 2
                       :selection/generic-total 2}
          hiccup (#'mana-pool/allocation-view alloc-state)
          ui-colors (extract-button-colors hiccup)
          kb-state {:pending-selection
                    {:selection/mechanism :allocate-resource
                     :selection/remaining-pool pool}}
          kb-colors (filterv some?
                             (map #(when-let [v (kb/action-dispatch % kb-state)]
                                     (second v))
                                  [:allocate-1 :allocate-2 :allocate-3
                                   :allocate-4 :allocate-5]))]
      (is (= kb-colors ui-colors)
          "Keyboard positions must match UI button positions"))))
