(ns fizzle.views.storm-split-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.modals :as modals]))


(deftest storm-split-target-label-all
  (testing "target label mapping"
    (doseq [[target expected] [[:player-1 "You"]
                               [:player-2 "Opponent"]]]
      (is (= expected (modals/storm-split-target-label target))
          (str "Failed for " target)))))
