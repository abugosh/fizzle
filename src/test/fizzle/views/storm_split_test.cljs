(ns fizzle.views.storm-split-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.modals :as modals]))


(deftest storm-split-target-label-self
  (testing "player-1 target returns 'You'"
    (is (= "You" (modals/storm-split-target-label :player-1)))))


(deftest storm-split-target-label-player-2
  (testing "player-2 target returns 'Opponent'"
    (is (= "Opponent" (modals/storm-split-target-label :player-2)))))
