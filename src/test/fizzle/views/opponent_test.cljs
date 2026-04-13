(ns fizzle.views.opponent-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.opponent :as opponent]))


;; === Player health color (low = bad) ===

(deftest player-health-class-boundaries
  (testing "player health class by life total"
    (doseq [[life expected] [[0  "text-health-critical"]
                             [-3 "text-health-critical"]
                             [1  "text-health-danger"]
                             [5  "text-health-danger"]
                             [6  "text-health-good"]
                             [20 "text-health-good"]]]
      (is (= expected (opponent/player-health-class life))
          (str "Failed for life=" life)))))


;; === Opponent health color (low = good for player) ===

(deftest opponent-health-class-boundaries
  (testing "opponent health class by life total (inverted)"
    (doseq [[life expected] [[-5 "text-health-good"]
                             [0  "text-health-good"]
                             [5  "text-health-danger"]
                             [20 "text-health-critical"]]]
      (is (= expected (opponent/opponent-health-class life))
          (str "Failed for life=" life)))))
