(ns fizzle.history.integration-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.events.game :as game]))


(deftest test-init-game-includes-history-keys
  (testing "init-game-state result includes history initialization keys"
    (let [app-db (game/init-game-state)]
      (is (contains? app-db :history/main)
          "Should have :history/main key")
      (is (contains? app-db :history/forks)
          "Should have :history/forks key")
      (is (contains? app-db :history/current-branch)
          "Should have :history/current-branch key")
      (is (contains? app-db :history/position)
          "Should have :history/position key")
      (is (vector? (:history/main app-db))
          ":history/main should be a vector")
      (is (= {} (:history/forks app-db))
          ":history/forks should be empty map")
      (is (nil? (:history/current-branch app-db))
          ":history/current-branch should be nil")
      (is (= -1 (:history/position app-db))
          ":history/position should be -1"))))
