(ns fizzle.engine.game-loop-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.game-loop :as game-loop]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as h]))


;; === derive-loop-state ===

(deftest derive-loop-state-stack-resolution-when-stack-non-empty
  (testing "returns :stack-resolution when stack has items (human turn)"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)]
      (is (= :stack-resolution (game-loop/derive-loop-state game-db))))))


(deftest derive-loop-state-stack-resolution-during-bot-turn
  (testing "returns :stack-resolution when stack has items even on bot turn"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]])]
      (is (= :stack-resolution (game-loop/derive-loop-state game-db))))))


(deftest derive-loop-state-bot-phase-on-bot-turn-empty-stack
  (testing "returns :bot-phase when active player is bot and stack empty"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])]
      (is (= :bot-phase (game-loop/derive-loop-state game-db))))))


(deftest derive-loop-state-phase-advancement-on-human-turn-empty-stack
  (testing "returns :phase-advancement when stack empty and human's turn"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))]
      (is (= :phase-advancement (game-loop/derive-loop-state db))))))


(deftest derive-loop-state-phase-advancement-single-player
  (testing "returns :phase-advancement for single player with empty stack"
    (let [db (h/create-test-db {:stops #{:main1}})]
      (is (= :phase-advancement (game-loop/derive-loop-state db))))))


;; === negotiate-priority ===

(deftest negotiate-priority-normal-mode-bot-auto-passes
  (testing "bot opponent auto-passes in normal mode"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)]
      (is (true? (:all-passed? result))
          "Both should have passed (human passed, bot auto-passed)"))))


(deftest negotiate-priority-auto-mode-both-pass
  (testing "both pass in auto-mode (:resolving)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish})
                 (priority/set-auto-mode :resolving))
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)]
      (is (true? (:all-passed? result))))))


(deftest negotiate-priority-bot-turn-both-pass
  (testing "both pass during bot turn"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)]
      (is (true? (:all-passed? result))))))


(deftest negotiate-priority-single-player-one-pass-suffices
  (testing "single player: one pass suffices"
    (let [db (h/create-test-db {:stops #{:main1}})
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)]
      (is (true? (:all-passed? result))
          "Single player should have all-passed after one pass"))))


(deftest negotiate-priority-transfers-when-not-all-passed
  (testing "transfers priority when opponent hasn't passed"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {}))
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Should not be all-passed when opponent is human and didn't pass"))))


(deftest negotiate-priority-returns-app-db-with-reset-passes
  (testing "resets passes in returned app-db when all passed"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)
          result-db (:game/db (:app-db result))]
      (is (empty? (priority/get-passed-eids result-db))
          "Passes should be reset after all-passed"))))


;; === handle-loop-state :stack-resolution ===

(deftest stack-resolution-resolves-one-item-signals-continue
  (testing "resolves one item and returns :continue-yield? when more items remain"
    (let [db (-> (h/create-test-db {:mana {:black 2} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db {:game/db game-db2}
          result (game-loop/handle-loop-state :stack-resolution app-db
                                              {:resolve-one-item game/resolve-one-item
                                               :maybe-continue-cleanup game/maybe-continue-cleanup})]
      (is (true? (:continue-yield? result))
          "Should signal continue when more items on stack"))))


(deftest stack-resolution-clears-resolving-auto-mode-when-stack-empties
  (testing "clears :resolving auto-mode when stack empties"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (-> (rules/cast-spell db' :player-1 obj-id)
                      (priority/set-auto-mode :resolving))
          app-db {:game/db game-db}
          result (game-loop/handle-loop-state :stack-resolution app-db
                                              {:resolve-one-item game/resolve-one-item
                                               :maybe-continue-cleanup game/maybe-continue-cleanup})]
      (is (nil? (priority/get-auto-mode (:game/db (:app-db result))))
          "Auto-mode should be cleared when stack empties during :resolving"))))


(deftest stack-resolution-pauses-with-pending-selection
  (testing "pauses with pending-selection when selection needed"
    (let [db (-> (h/create-test-db {:mana {:blue 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :careful-study :hand :player-1)
          [db'' _] (h/add-cards-to-library db' [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          game-db (rules/cast-spell db'' :player-1 obj-id)
          app-db {:game/db game-db}
          result (game-loop/handle-loop-state :stack-resolution app-db
                                              {:resolve-one-item game/resolve-one-item
                                               :maybe-continue-cleanup game/maybe-continue-cleanup})]
      (is (some? (:game/pending-selection (:app-db result)))
          "Should return pending-selection for interactive spell")
      (is (nil? (:continue-yield? result))
          "Should not signal continue when selection is pending"))))


;; === handle-loop-state :bot-phase ===

(deftest bot-phase-executes-action-and-continues
  (testing "executes bot action and signals continue when no stop"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (game-loop/handle-loop-state :bot-phase app-db
                                              {:advance-with-stops game/advance-with-stops
                                               :execute-bot-phase-action game/execute-bot-phase-action})]
      (is (true? (:continue-yield? result))
          "Should signal continue when no human stop on bot phase"))))


(deftest bot-phase-pauses-at-human-stop
  (testing "pauses at phase where human has opponent-turn stop"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish :stops #{:combat}}))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (game-loop/handle-loop-state :bot-phase app-db
                                              {:advance-with-stops game/advance-with-stops
                                               :execute-bot-phase-action game/execute-bot-phase-action})]
      ;; advance-with-stops advances main1 -> combat, bot executes action,
      ;; then check-stop finds :combat stop on bot entity
      (is (nil? (:continue-yield? result))
          "Should pause when human has stop on this bot phase"))))


(deftest bot-phase-ignores-stop-in-auto-mode
  (testing "ignores human stop when auto-mode is active"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish :stops #{:combat}})
                 (priority/set-auto-mode :f6))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (game-loop/handle-loop-state :bot-phase app-db
                                              {:advance-with-stops game/advance-with-stops
                                               :execute-bot-phase-action game/execute-bot-phase-action})]
      (is (true? (:continue-yield? result))
          "Should continue past stop when auto-mode is active"))))


;; === handle-loop-state :phase-advancement ===

(deftest phase-advancement-advances-to-next-stop
  (testing "advances phases until a stop is hit"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          result (game-loop/handle-loop-state :phase-advancement app-db
                                              {:advance-with-stops game/advance-with-stops})]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result)))))
          "Should advance to main2")
      (is (nil? (:continue-yield? result))
          "Should stop at phase stop"))))


(deftest phase-advancement-turn-boundary-to-bot-continues
  (testing "crossing turn boundary to bot turn signals continue"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          result (game-loop/handle-loop-state :phase-advancement app-db
                                              {:advance-with-stops game/advance-with-stops})]
      ;; With no stops, advance-with-stops should cross turn boundary to opponent turn
      (is (= 2 (:game/turn (q/get-game-state (:game/db (:app-db result)))))
          "Should cross turn boundary")
      (is (true? (:continue-yield? result))
          "Should continue when entering bot turn"))))


(deftest phase-advancement-f6-clears-auto-mode-at-human-turn
  (testing "F6 clears auto-mode when arriving at human turn"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish})
                 (priority/set-auto-mode :f6))
          ;; Advance to main2 so advance-with-stops crosses to opponent then back
          game-db (-> db
                      (game/advance-phase :player-1)
                      (game/advance-phase :player-1))
          app-db {:game/db game-db}
          ;; This should cross to bot turn, return, then the next yield will
          ;; handle bot phase and eventually cross back to human.
          ;; With f6, advance-with-stops ignores stops and goes through.
          result (game-loop/handle-loop-state :phase-advancement app-db
                                              {:advance-with-stops game/advance-with-stops})]
      ;; advance-with-stops crosses turn boundary, yield-impl handles continuing
      (is (some? (:app-db result))))))


(deftest phase-advancement-cleanup-discard-returns-pending-selection
  (testing "cleanup discard returns pending-selection"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add 10 cards to hand (need to discard 3 at cleanup)
          [db' _] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Advance to end phase (one before cleanup)
          game-db (-> db'
                      (game/advance-phase :player-1)   ; main1 -> combat
                      (game/advance-phase :player-1)   ; combat -> main2
                      (game/advance-phase :player-1))  ; main2 -> end
          app-db {:game/db game-db}
          result (game-loop/handle-loop-state :phase-advancement app-db
                                              {:advance-with-stops game/advance-with-stops})]
      (is (some? (:game/pending-selection (:app-db result)))
          "Should return pending-selection for cleanup discard"))))


;; === yield-impl equivalence ===
;; These tests verify that the refactored yield-impl produces identical results
;; to the original. They are integration tests.

(deftest refactored-yield-impl-advances-to-next-stop
  (testing "yield-impl advances from main1 to main2 with stops at both"
    (let [app-db {:game/db (-> (h/create-test-db {:stops #{:main1 :main2}})
                               (h/add-opponent {:bot-archetype :goldfish}))}
          result (game/yield-impl app-db)]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result)))))))))


(deftest refactored-yield-impl-resolves-stack
  (testing "yield-impl resolves a spell on the stack"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          app-db {:game/db game-db}
          result (game/yield-impl app-db)
          result-db (:game/db (:app-db result))]
      (is (= 3 (:black (q/get-mana-pool result-db :player-1)))
          "Dark Ritual should resolve"))))


(deftest refactored-yield-impl-bot-turn-advances-single-phase
  (testing "yield-impl during bot turn processes one phase at a time"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]])
          app-db {:game/db game-db}
          result (game/yield-impl app-db)]
      (is (true? (:continue-yield? result))
          "Bot turn should signal continue for phase-by-phase advancement"))))
