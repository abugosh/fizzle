(ns fizzle.views.opponent-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.opponent :as opponent]))


;; === Player health color (low = bad) ===

(deftest player-health-color-dead
  (testing "player at 0 life shows critical"
    (is (= "text-health-critical" (opponent/player-health-class 0)))))


(deftest player-health-color-negative
  (testing "player at negative life shows critical"
    (is (= "text-health-critical" (opponent/player-health-class -3)))))


(deftest player-health-color-low
  (testing "player at 5 life shows danger"
    (is (= "text-health-danger" (opponent/player-health-class 5)))))


(deftest player-health-color-barely-low
  (testing "player at 1 life shows danger"
    (is (= "text-health-danger" (opponent/player-health-class 1)))))


(deftest player-health-color-healthy
  (testing "player at 6+ life shows good"
    (is (= "text-health-good" (opponent/player-health-class 6)))))


(deftest player-health-color-full
  (testing "player at 20 life shows good"
    (is (= "text-health-good" (opponent/player-health-class 20)))))


;; === Opponent health color (low = good for player) ===

(deftest opponent-health-color-dead
  (testing "opponent at 0 life shows good (opponent dead = good for player)"
    (is (= "text-health-good" (opponent/opponent-health-class 0)))))


(deftest opponent-health-color-negative
  (testing "opponent at negative life shows good"
    (is (= "text-health-good" (opponent/opponent-health-class -5)))))


(deftest opponent-health-color-low
  (testing "opponent at 5 life shows danger"
    (is (= "text-health-danger" (opponent/opponent-health-class 5)))))


(deftest opponent-health-color-healthy
  (testing "opponent at 20 life shows critical (opponent healthy = bad for player)"
    (is (= "text-health-critical" (opponent/opponent-health-class 20)))))
