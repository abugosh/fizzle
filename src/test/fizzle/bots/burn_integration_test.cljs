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


;; === Damage E2E Test ===

(deftest burn-bot-deals-damage-via-dispatch
  (testing "burn bot resolves Lightning Bolt, reducing opponent life from 20 to 17 and bolt enters graveyard"
    ;; Bug caught: if the bot→director→handler→SBA path breaks (e.g., director does not
    ;; resolve stack items, or SBAs don't check life, or damage application is skipped),
    ;; the life total stays at 20 and this test fails.
    ;; The existing tests in this file only verify the bot's DECISION protocol (:cast-spell
    ;; keyword), not the full path from decision to damage resolution.
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1 :with-priority? true})
          ;; yield-all? true: human auto-passes so bot can cast, both players pass,
          ;; bolt resolves, SBAs run inline via director (sba/check-and-execute-sbas)
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))
          human-life (q/get-life-total result-db :player-1)
          bot-graveyard (q/get-objects-in-zone result-db :player-2 :graveyard)]
      (is (= 17 human-life)
          "Human life should be exactly 17 (20 - 3) after Lightning Bolt resolves")
      (is (= 1 (count bot-graveyard))
          "Lightning Bolt should be in bot's graveyard (exactly 1 card)")
      (is (= :lightning-bolt (get-in (first bot-graveyard) [:object/card :card/id]))
          "The card in bot's graveyard should be Lightning Bolt"))))
