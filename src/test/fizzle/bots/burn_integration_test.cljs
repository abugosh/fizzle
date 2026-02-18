(ns fizzle.bots.burn-integration-test
  "Integration tests for burn bot game flow.

   Validates that burn bot games work end-to-end:
   - Bot casts bolts via the real engine path
   - Damage accumulates across multiple priority windows
   - Game ends when life reaches 0"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.actions :as bot-actions]
    [fizzle.bots.protocol :as bot]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.events.game :as game]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- setup-burn-bot-app-db
  "Create an app-db with a burn bot opponent.
   Bot has Mountains on battlefield and Bolts in hand.
   Active player is bot (:player-2).
   Returns app-db map."
  [{:keys [mountains bolts]}]
  (let [db (th/create-test-db {:stops #{:main1 :main2}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/lightning-bolt])
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
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/phase :main1]])]
      (merge (history/init-history)
             {:game/db db}))))


(defn- run-bot-priority-loop
  "Run the bot priority decision + execution loop.
   Returns updated game-db after bot finishes casting.
   Safety limit of 50 iterations."
  [game-db]
  (let [active-player-id (q/get-active-player-id game-db)
        archetype (bot/get-bot-archetype game-db active-player-id)]
    (loop [db game-db
           n 50]
      (if (zero? n)
        db
        (let [decision (bot/bot-priority-decision archetype {:db db :player-id active-player-id})]
          (if (and (map? decision) (= :cast-spell (:action decision)))
            (let [db-cast (bot-actions/execute-bot-priority-action db decision)
                  ;; Resolve the bolt on stack
                  result (game/resolve-one-item db-cast active-player-id)
                  db-resolved (:db result)]
              (recur db-resolved (dec n)))
            ;; Bot passes
            db))))))


(deftest burn-bot-casts-one-bolt-deals-3-damage
  (testing "burn bot with 1 Mountain and 1 Bolt deals 3 damage"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          _ (is (= 20 (q/get-life-total game-db :player-1))
                "Precondition: human starts at 20 life")
          db-after (run-bot-priority-loop game-db)]
      (is (= 17 (q/get-life-total db-after :player-1))
          "Human should be at 17 life after one bolt"))))


(deftest burn-bot-casts-multiple-bolts-damage-accumulates
  (testing "burn bot with 3 Mountains and 3 Bolts deals 9 damage"
    (let [app-db (setup-burn-bot-app-db {:mountains 3 :bolts 3})
          game-db (:game/db app-db)
          db-after (run-bot-priority-loop game-db)]
      (is (= 11 (q/get-life-total db-after :player-1))
          "Human should be at 11 life after three bolts (20 - 9)"))))


(deftest burn-bot-limited-by-mana
  (testing "burn bot with 2 Mountains and 5 Bolts only casts 2"
    (let [app-db (setup-burn-bot-app-db {:mountains 2 :bolts 5})
          game-db (:game/db app-db)
          db-after (run-bot-priority-loop game-db)]
      (is (= 14 (q/get-life-total db-after :player-1))
          "Human should be at 14 life (2 bolts cast, 3 remaining in hand)")
      ;; 3 bolts should remain in hand
      (let [hand (q/get-objects-in-zone db-after :player-2 :hand)
            bolts-in-hand (filter #(= :lightning-bolt (get-in % [:object/card :card/id])) hand)]
        (is (= 3 (count bolts-in-hand))
            "3 bolts should remain in hand")))))


(deftest burn-bot-kills-at-zero-life
  (testing "burn bot kills opponent when life reaches 0"
    (let [app-db (setup-burn-bot-app-db {:mountains 7 :bolts 7})
          game-db (:game/db app-db)
          _ (is (= 20 (q/get-life-total game-db :player-1))
                "Precondition: human starts at 20 life")
          db-after (run-bot-priority-loop game-db)]
      (is (= -1 (- 20 (* 3 7)))
          "Math check: 7 bolts = 21 damage")
      (is (= -1 (q/get-life-total db-after :player-1))
          "Human should be at -1 life after 7 bolts")
      (is (= :life-zero (:game/loss-condition (q/get-game-state db-after)))
          "Game should end with :life-zero loss condition"))))


(deftest burn-bot-passes-when-no-resources
  (testing "burn bot passes when no bolts or mana available"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 0})
          game-db (:game/db app-db)
          db-after (run-bot-priority-loop game-db)]
      (is (= 20 (q/get-life-total db-after :player-1))
          "Human should still be at 20 life"))))


(deftest burn-bot-uses-full-engine-path
  (testing "bot-cast bolts go through stack and resolve via production path"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          active-player-id (q/get-active-player-id game-db)
          archetype (bot/get-bot-archetype game-db active-player-id)
          decision (bot/bot-priority-decision archetype {:db game-db :player-id active-player-id})
          _ (is (= :cast-spell (:action decision))
                "Bot should want to cast")
          ;; Execute the cast
          db-cast (bot-actions/execute-bot-priority-action game-db decision)]
      ;; Verify bolt is on stack (went through rules/cast-spell-mode)
      (is (not (q/stack-empty? db-cast))
          "Bolt should be on stack after cast")
      ;; Resolve via resolve-one-item (production path)
      (let [result (game/resolve-one-item db-cast active-player-id)
            db-resolved (:db result)
            bolt-obj-id (:object-id decision)
            obj (q/get-object db-resolved bolt-obj-id)]
        (is (= :graveyard (:object/zone obj))
            "Bolt should be in graveyard after resolution")
        (is (= 17 (q/get-life-total db-resolved :player-1))
            "Human should take 3 damage")))))
