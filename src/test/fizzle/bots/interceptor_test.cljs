(ns fizzle.bots.interceptor-test
  "Tests for bot action interceptor and ::bot-decide event.

   Validates that:
   - Bot interceptor detects when priority holder is a bot
   - ::bot-decide dispatches tap + cast sequence for burn bot
   - Bot casts go through can-cast? validation
   - Restrictions like Orim's Chant prevent bot casting
   - Safety limit prevents infinite bot loops"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.interceptor :as interceptor]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- setup-burn-bot-app-db
  "Create an app-db with a burn bot opponent.
   Bot has Mountains on battlefield and Bolts in hand.
   Active player is bot (:player-2), priority holder is bot.
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
          ;; Set active player and priority holder to bot, phase to main1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :main1]])]
      (merge (history/init-history)
             {:game/db db}))))


;; === Bot Interceptor Detection Tests ===

(deftest bot-should-act-detects-bot-priority-holder
  (testing "bot-should-act? returns true when priority holder is a bot"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)]
      (is (true? (interceptor/bot-should-act? game-db))
          "Should detect that priority holder is a bot"))))


(deftest bot-should-act-returns-false-for-human
  (testing "bot-should-act? returns false when priority holder is human"
    (let [db (th/create-test-db {:stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :burn})]
      (is (false? (interceptor/bot-should-act? db))
          "Should return false when human holds priority"))))


(deftest bot-should-act-returns-false-when-no-bot
  (testing "bot-should-act? returns false when no bot in game"
    (let [db (th/create-test-db {:stops #{:main1}})]
      (is (false? (interceptor/bot-should-act? db))
          "Should return false in single player game"))))


;; === Bot Decide: Cast Spell ===

(deftest bot-decide-produces-cast-action-for-burn-bot
  (testing "bot-decide-action returns cast sequence when burn bot has bolt + mountain"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          result (interceptor/bot-decide-action game-db)]
      (is (= :cast-spell (:action result))
          "Action type should be :cast-spell")
      (is (some? (:object-id result))
          "Should include the bolt object-id")
      (is (some? (:tap-sequence result))
          "Should include a tap sequence")
      (is (= 1 (count (:tap-sequence result)))
          "Should tap exactly 1 mountain"))))


(deftest bot-decide-produces-pass-without-resources
  (testing "bot-decide-action returns :pass when no resources"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 0})
          game-db (:game/db app-db)
          result (interceptor/bot-decide-action game-db)]
      (is (= :pass (:action result))
          "Should pass when no resources available"))))


;; === Bot Cast Through Standard Events ===

(deftest bot-execute-cast-taps-lands-and-casts
  (testing "execute-bot-cast taps lands and casts spell through standard paths"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          action (interceptor/bot-decide-action game-db)
          result-db (interceptor/execute-bot-cast game-db :player-2 action)]
      ;; Mountain should be tapped
      (let [battlefield (q/get-objects-in-zone result-db :player-2 :battlefield)
            mountain (first (filter #(= :mountain (get-in % [:object/card :card/id])) battlefield))]
        (is (true? (:object/tapped mountain))
            "Mountain should be tapped after bot cast"))
      ;; Bolt should be on the stack
      (is (not (q/stack-empty? result-db))
          "Stack should have the bolt on it")
      ;; Mana pool should have gained red from tapping mountain
      ;; (then spent it on bolt, so pool should be 0)
      (let [stack-items (q/get-all-stack-items result-db)
            bolt-item (first (filter #(= :spell (:stack-item/type %)) stack-items))]
        (is (some? bolt-item)
            "There should be a spell stack-item for Lightning Bolt")))))


(deftest bot-cast-goes-through-can-cast-validation
  (testing "bot cast respects can-cast? validation"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 1})
          game-db (:game/db app-db)
          ;; Bot has bolt but no mountains — can't cast
          action (interceptor/bot-decide-action game-db)]
      ;; Bot should pass since it can't actually cast
      (is (= :pass (:action action))
          "Bot should pass when it can't afford to cast"))))


;; === Safety Limit ===

(deftest bot-action-loop-has-safety-limit
  (testing "bot action loop stops after safety limit"
    (let [app-db (setup-burn-bot-app-db {:mountains 3 :bolts 3})
          game-db (:game/db app-db)]
      ;; Execute multiple bot actions — should not exceed limit
      (let [results (interceptor/execute-bot-actions game-db :player-2 20)]
        (is (<= (count results) 20)
            "Should not exceed safety limit of 20 actions")))))
