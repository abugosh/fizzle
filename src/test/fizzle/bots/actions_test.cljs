(ns fizzle.bots.actions-test
  "Tests for bot action execution.

   Tests auto-tap-for-cost and execute-bot-priority-action
   through the real engine cast path."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.actions :as actions]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.game :as game]
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


;; === execute-bot-priority-action ===

(deftest execute-bot-cast-spell-puts-bolt-on-stack
  (testing "execute-bot-priority-action casts bolt via engine path"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          [db _mtn-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          action {:action :cast-spell
                  :object-id obj-id
                  :target :player-1}
          db-after (actions/execute-bot-priority-action db action)]
      ;; Bolt should be on stack
      (is (= :stack (:object/zone (q/get-object db-after obj-id)))
          "Lightning Bolt should be on stack after bot casts")
      ;; Stack should not be empty
      (is (not (q/stack-empty? db-after))
          "Stack should have an item"))))


(deftest execute-bot-cast-spell-stores-target-on-stack-item
  (testing "bot cast stores target on stack-item for resolution"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          [db _mtn-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          action {:action :cast-spell
                  :object-id obj-id
                  :target :player-1}
          db-after (actions/execute-bot-priority-action db action)
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-after obj-id)
          stack-item (stack/get-stack-item-by-object-ref db-after obj-eid)]
      (is (some? stack-item)
          "Stack-item should exist")
      (is (= {:target :player-1} (:stack-item/targets stack-item))
          "Stack-item should have target :player-1"))))


(deftest execute-bot-cast-spell-resolves-for-damage
  (testing "bot-cast bolt resolves via engine and deals 3 damage"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          [db _mtn-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          action {:action :cast-spell
                  :object-id obj-id
                  :target :player-1}
          db-cast (actions/execute-bot-priority-action db action)
          ;; Resolve through production path
          result (game/resolve-one-item db-cast :player-2)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Bolt should be in graveyard after resolution")
      (is (= 17 (q/get-life-total db-resolved :player-1))
          "Human player should take 3 damage (20 -> 17)"))))


(deftest execute-bot-cast-spell-increments-storm
  (testing "bot casting increments storm count"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          [db _mtn-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          _ (is (= 0 (q/get-storm-count db :player-2))
                "Precondition: storm count starts at 0")
          action {:action :cast-spell
                  :object-id obj-id
                  :target :player-1}
          db-after (actions/execute-bot-priority-action db action)]
      (is (= 1 (q/get-storm-count db-after :player-2))
          "Storm count should be 1 after bot casts"))))


(deftest execute-bot-cast-spell-auto-taps-mountains
  (testing "bot auto-taps Mountain before casting"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          [db mtn-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          action {:action :cast-spell
                  :object-id obj-id
                  :target :player-1}
          db-after (actions/execute-bot-priority-action db action)
          mtn (q/get-object db-after mtn-id)]
      (is (:object/tapped mtn)
          "Mountain should be tapped after bot casts"))))
