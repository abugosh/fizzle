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


(deftest negotiate-priority-human-not-auto-passed-when-stack-non-empty-on-bot-turn
  (testing "human is NOT auto-passed when stack is non-empty during bot turn"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db game-db}
          result (game-loop/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Human should get priority when stack is non-empty during bot turn"))))


(deftest negotiate-priority-human-auto-passed-when-stack-empty-on-bot-turn
  (testing "human IS auto-passed when stack is empty during bot turn"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (game-loop/negotiate-priority app-db)]
      (is (true? (:all-passed? result))
          "Human should be auto-passed when stack is empty during bot turn"))))


(deftest negotiate-priority-auto-mode-overrides-stack-check
  (testing "auto-mode overrides stack-non-empty check during bot turn"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (-> (rules/cast-spell db' :player-1 obj-id)
                      (priority/set-auto-mode :resolving))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db game-db}
          result (game-loop/negotiate-priority app-db)]
      (is (true? (:all-passed? result))
          "Auto-mode (F6/resolving) should still auto-pass even with stack non-empty"))))


;; === yield-impl: stack resolution ===

(deftest yield-impl-resolves-one-item-signals-continue-when-more
  (testing "yield-impl resolves one item and continues when more items remain"
    (let [db (-> (h/create-test-db {:mana {:black 2} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db {:game/db game-db2}
          result (game/yield-impl app-db)]
      (is (true? (:continue-yield? result))
          "Should signal continue when more items on stack"))))


(deftest yield-impl-clears-resolving-auto-mode-when-stack-empties
  (testing "yield-impl clears :resolving auto-mode when stack empties"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (-> (rules/cast-spell db' :player-1 obj-id)
                      (priority/set-auto-mode :resolving))
          app-db {:game/db game-db}
          result (game/yield-impl app-db)]
      (is (nil? (priority/get-auto-mode (:game/db (:app-db result))))
          "Auto-mode should be cleared when stack empties during :resolving"))))


(deftest yield-impl-pauses-with-pending-selection
  (testing "yield-impl pauses with pending-selection when interactive spell resolves"
    (let [db (-> (h/create-test-db {:mana {:blue 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :careful-study :hand :player-1)
          [db'' _] (h/add-cards-to-library db' [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          game-db (rules/cast-spell db'' :player-1 obj-id)
          app-db {:game/db game-db}
          result (game/yield-impl app-db)]
      (is (some? (:game/pending-selection (:app-db result)))
          "Should return pending-selection for interactive spell")
      (is (nil? (:continue-yield? result))
          "Should not signal continue when selection is pending"))))


;; === yield-impl: phase advancement ===

(deftest yield-impl-advances-to-next-stop
  (testing "yield-impl advances from main1 to main2 with stops at both"
    (let [app-db {:game/db (-> (h/create-test-db {:stops #{:main1 :main2}})
                               (h/add-opponent {:bot-archetype :goldfish}))}
          result (game/yield-impl app-db)]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result)))))))))


(deftest yield-impl-resolves-stack
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


(deftest yield-impl-bot-turn-advances-single-phase
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


(deftest yield-impl-turn-boundary-to-bot-continues
  (testing "crossing turn boundary to bot turn signals continue"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          result (game/yield-impl app-db)]
      ;; With no stops, advance-with-stops should cross turn boundary to opponent turn
      (is (= 2 (:game/turn (q/get-game-state (:game/db (:app-db result)))))
          "Should cross turn boundary")
      (is (true? (:continue-yield? result))
          "Should continue when entering bot turn"))))


(deftest yield-impl-cleanup-discard-returns-pending-selection
  (testing "cleanup discard returns pending-selection"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add 8 cards to hand (need to discard 1 at cleanup)
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
          result (game/yield-impl app-db)]
      (is (some? (:game/pending-selection (:app-db result)))
          "Should return pending-selection for cleanup discard"))))


(deftest yield-impl-bot-phase-does-not-cast-spells
  (testing "yield-impl during bot turn does not cast spells (only phase actions)"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]])
          app-db {:game/db game-db}
          result (game/yield-impl app-db)
          result-db (:game/db (:app-db result))]
      (is (q/stack-empty? result-db)
          "Bot phase should not put anything on stack"))))
