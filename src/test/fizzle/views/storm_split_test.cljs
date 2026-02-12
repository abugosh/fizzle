(ns fizzle.views.storm-split-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.modals :as modals]))


(deftest storm-split-target-label-opponent
  (testing "opponent target returns 'Opponent'"
    (is (= "Opponent" (modals/storm-split-target-label :opponent)))))


(deftest storm-split-target-label-self
  (testing "player-1 target returns 'You'"
    (is (= "You" (modals/storm-split-target-label :player-1)))))


(deftest storm-split-target-label-unknown
  (testing "unknown player-id returns stringified name"
    (is (= "player-2" (modals/storm-split-target-label :player-2)))))
