(ns fizzle.events.routing-test
  "Tests for screen routing - set-active-screen event."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.init :as game-init]
    [fizzle.events.setup :as setup]
    [fizzle.events.ui :as ui-events]))


(deftest test-set-active-screen-preserves-game-state
  (testing "switching screens preserves game db"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})
          game-db-before (:game/db app-db)
          updated (ui-events/set-active-screen-handler app-db :setup)]
      (is (= game-db-before (:game/db updated))
          "Game db should be unchanged after screen switch"))))
