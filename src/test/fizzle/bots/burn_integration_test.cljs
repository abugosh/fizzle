(ns fizzle.bots.burn-integration-test
  "Integration tests for burn bot game flow.

   Validates turn progression and phase advancement with a burn bot opponent."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.events.director :as director]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- setup-burn-bot-app-db
  "Create an app-db with a burn bot opponent.
   Bot has Mountains on battlefield and Bolts in hand.
   Active player is bot (:player-2).
   When :with-priority? is true, also sets priority holder to bot.
   Returns app-db map."
  [{:keys [mountains bolts with-priority?]}]
  (let [db (th/create-test-db {:stops #{:main1 :main2}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn {:bot-archetype :burn})]
    ;; Add Mountains to battlefield
    (let [[db _] (reduce (fn [[db' _] _]
                           (th/add-card-to-zone db' :mountain :battlefield :player-2))
                         [db nil]
                         (range mountains))
          ;; Add Bolts to hand
          [db _] (reduce (fn [[db' _] _]
                           (th/add-card-to-zone db' :lightning-bolt :hand :player-2))
                         [db nil]
                         (range bolts))
          ;; Set active player to bot, phase to main1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          txs (cond-> [[:db/add game-eid :game/active-player opp-eid]
                       [:db/add game-eid :game/phase :main1]]
                with-priority? (conj [:db/add game-eid :game/priority opp-eid]))
          db (d/db-with db txs)]
      (merge (history/init-history)
             {:game/db db}))))


;; === Bot protocol tests ===

(deftest burn-bot-decision-wants-to-cast-with-resources
  (testing "bot-priority-decision returns :cast-spell when bolt + mountain available"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          active-player-id (q/get-active-player-id game-db)
          archetype (bot/get-bot-archetype game-db active-player-id)
          decision (bot/bot-priority-decision archetype {:db game-db :player-id active-player-id})]
      (is (map? decision)
          "Bot should return an action map")
      (is (= :cast-spell (:action decision))
          "Bot should want to cast a spell"))))


(deftest burn-bot-decision-passes-without-resources
  (testing "bot-priority-decision returns :pass when no bolts or mana available"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 0})
          game-db (:game/db app-db)
          active-player-id (q/get-active-player-id game-db)
          archetype (bot/get-bot-archetype game-db active-player-id)
          decision (bot/bot-priority-decision archetype {:db game-db :player-id active-player-id})]
      (is (= :pass decision)
          "Bot should pass when no resources available"))))


;; === Turn progression tests ===

(deftest turn-increments-after-human-turn-ends
  (testing "turn counter increments from 1 to 2 when human turn ends"
    (let [app-db (th/create-game-scenario {:bot-archetype :burn
                                           :stops #{:main1 :main2}})
          db (:game/db app-db)
          _ (is (= 1 (:game/turn (q/get-game-state db)))
                "Precondition: game starts at turn 1")
          _ (is (= :player-1 (q/get-active-player-id db))
                "Precondition: human is active")
          ;; Director with yield-all? true advances through human turn to bot turn
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))]
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Turn should increment to 3 (F6 cascades through both turns)")
      (is (= :player-1 (q/get-active-player-id result-db))
          "Active player should be human at turn 3"))))


(deftest turn-increments-after-bot-turn-ends
  (testing "turn counter increments from 2 to 3 when bot turn ends"
    (let [app-db (th/create-game-scenario {:bot-archetype :burn
                                           :stops #{:main1 :main2}})
          ;; Director yield-all? true advances through both turns
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))]
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Turn should increment to 3 after bot turn ends")
      (is (= :player-1 (q/get-active-player-id result-db))
          "Active player should switch back to human"))))


(deftest turn-progresses-through-full-cycle-via-director
  (testing "director advances turns correctly through a full human->bot->human cycle"
    (let [app-db (th/create-game-scenario {:bot-archetype :burn
                                           :stops #{:main1 :main2}})
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))]
      (is (>= (:game/turn (q/get-game-state result-db)) 3)
          "Should reach at least turn 3 (human->bot->human)")
      (is (= :player-1 (q/get-active-player-id result-db))
          "Active player at turn 3 should be human"))))
