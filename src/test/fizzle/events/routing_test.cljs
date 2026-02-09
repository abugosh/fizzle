(ns fizzle.events.routing-test
  "Tests for screen routing - set-active-screen event."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.game :as game]))


(deftest test-set-active-screen-switches-to-setup
  (testing "set-active-screen event sets :active-screen in app-db"
    (let [app-db (game/init-game-state)
          updated (game/set-active-screen-handler app-db :setup)]
      (is (= :setup (:active-screen updated))
          "Should switch to setup screen"))))


(deftest test-set-active-screen-switches-back-to-game
  (testing "set-active-screen can switch back to game"
    (let [app-db (-> (game/init-game-state)
                     (game/set-active-screen-handler :setup)
                     (game/set-active-screen-handler :game))]
      (is (= :game (:active-screen app-db))
          "Should switch back to game screen"))))


(deftest test-set-active-screen-preserves-game-state
  (testing "switching screens preserves game db"
    (let [app-db (game/init-game-state)
          game-db-before (:game/db app-db)
          updated (game/set-active-screen-handler app-db :setup)]
      (is (= game-db-before (:game/db updated))
          "Game db should be unchanged after screen switch"))))
