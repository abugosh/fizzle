(ns fizzle.bots.actions-test
  "Tests for bot action execution.

   Tests auto-tap-for-cost through the real engine cast path."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.actions :as actions]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.test-helpers :as th]))


;; === auto-tap-for-cost ===

(deftest auto-tap-taps-one-mountain-for-red
  (testing "auto-tap taps one Mountain to produce red mana"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db mtn-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          db-after (actions/auto-tap-for-cost db :player-2 {:red 1})
          mtn (q/get-object db-after mtn-id)]
      (is (:object/tapped mtn)
          "Mountain should be tapped")
      (is (= 1 (:red (q/get-mana-pool db-after :player-2)))
          "Player should have 1 red mana"))))


(deftest auto-tap-only-taps-enough-mountains
  (testing "auto-tap taps only as many Mountains as needed"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db mtn1-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          [db mtn2-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          db-after (actions/auto-tap-for-cost db :player-2 {:red 1})
          mtn1 (q/get-object db-after mtn1-id)
          mtn2 (q/get-object db-after mtn2-id)
          tapped-count (count (filter :object/tapped [mtn1 mtn2]))]
      (is (= 1 tapped-count)
          "Should tap exactly 1 Mountain for {R}"))))


(deftest auto-tap-no-op-when-no-lands
  (testing "auto-tap is no-op when no untapped lands exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          db-after (actions/auto-tap-for-cost db :player-2 {:red 1})]
      (is (= 0 (:red (q/get-mana-pool db-after :player-2)))
          "Should have no red mana without lands"))))
