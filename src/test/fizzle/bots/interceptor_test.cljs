(ns fizzle.bots.interceptor-test
  "Tests for bot action interceptor and ::bot-decide event.

   Validates that:
   - Bot interceptor detects when priority holder is a bot
   - bot-decide-action returns correct action plan with tap sequence
   - build-bot-dispatches produces correct re-frame dispatch sequence
   - Bot casts go through can-cast? validation
   - Safety limit prevents infinite bot loops"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.interceptor :as interceptor]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.game :as game]
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


;; === Bot Decide Action ===

(deftest bot-decide-produces-cast-action-for-burn-bot
  (testing "bot-decide-action returns cast action when burn bot has bolt + mountain"
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


(deftest bot-decide-passes-when-cant-afford
  (testing "bot-decide-action returns :pass when bot has bolt but no mountains"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 1})
          game-db (:game/db app-db)
          action (interceptor/bot-decide-action game-db)]
      (is (= :pass (:action action))
          "Bot should pass when it can't afford to cast"))))


;; === Build Bot Dispatches ===

(deftest build-bot-dispatches-produces-tap-then-cast-sequence
  (testing "build-bot-dispatches returns activate-mana + cast-spell dispatches"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          action (interceptor/bot-decide-action game-db)
          dispatches (interceptor/build-bot-dispatches action)]
      ;; Should have 2 dispatches: 1 tap + 1 cast
      (is (= 2 (count dispatches))
          "Should have 2 dispatches (1 tap + 1 cast)")
      ;; First dispatch should be activate-mana-ability
      (let [[tap-event] dispatches]
        (is (= ::abilities/activate-mana-ability (first (second tap-event)))
            "First dispatch should be ::activate-mana-ability"))
      ;; Last dispatch should be cast-spell with opts map
      (let [[_ cast-args] (last dispatches)]
        (is (= ::game/cast-spell (first cast-args))
            "Last dispatch should be ::cast-spell")
        (is (= :player-2 (:player-id (second cast-args)))
            "Cast dispatch opts should include bot player-id")))))


(deftest build-bot-dispatches-includes-player-id-in-tap
  (testing "tap dispatches include the bot's player-id"
    (let [app-db (setup-burn-bot-app-db {:mountains 2 :bolts 1})
          game-db (:game/db app-db)
          action (interceptor/bot-decide-action game-db)
          dispatches (interceptor/build-bot-dispatches action)
          ;; First dispatch is a tap
          [_ tap-args] (first dispatches)]
      ;; tap dispatch format: [::activate-mana-ability object-id mana-color player-id]
      (is (= :player-2 (nth tap-args 3))
          "Tap dispatch should include bot player-id"))))


;; === Bot Cast Spell Event (through standard path) ===

(deftest bot-cast-spell-via-cast-spell-handler-puts-bolt-on-stack
  (testing "cast-spell-handler with opts casts targeted spell for bot"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          ;; First tap the mountain manually (simulating the tap dispatch)
          battlefield (q/get-objects-in-zone game-db :player-2 :battlefield)
          mountain (first (filter #(= :mountain (get-in % [:object/card :card/id])) battlefield))
          game-db (abilities/activate-mana-ability game-db :player-2 (:object/id mountain) :red)
          ;; Now find the bolt
          hand (q/get-objects-in-zone game-db :player-2 :hand)
          bolt (first (filter #(= :lightning-bolt (get-in % [:object/card :card/id])) hand))
          ;; Call cast-spell-handler with opts (same path as human, with explicit args)
          app-db (assoc app-db :game/db game-db)
          result-db (game/cast-spell-handler app-db {:player-id :player-2
                                                     :object-id (:object/id bolt)
                                                     :target :player-1})
          result-game-db (:game/db result-db)]
      (is (not (q/stack-empty? result-game-db))
          "Stack should have the bolt on it")
      (let [stack-items (q/get-all-stack-items result-game-db)
            spell-items (filter #(= :spell (:stack-item/type %)) stack-items)]
        (is (= 1 (count spell-items))
            "There should be exactly 1 spell stack-item")
        ;; Verify target is stored on the stack-item
        (is (= {:target :player-1}
               (:stack-item/targets (first spell-items)))
            "Stack-item should have stored target :player-1")))))


(deftest bot-cast-spell-respects-can-cast-validation
  (testing "cast-spell-handler with opts returns unchanged db when can-cast? fails"
    (let [app-db (setup-burn-bot-app-db {:mountains 0 :bolts 1})
          game-db (:game/db app-db)
          ;; Bot has bolt but no mana (didn't tap)
          hand (q/get-objects-in-zone game-db :player-2 :hand)
          bolt (first (filter #(= :lightning-bolt (get-in % [:object/card :card/id])) hand))
          result-db (game/cast-spell-handler app-db {:player-id :player-2
                                                     :object-id (:object/id bolt)
                                                     :target :player-1})
          result-game-db (:game/db result-db)]
      (is (q/stack-empty? result-game-db)
          "Stack should be empty when bot can't cast"))))


;; === Activate Mana Ability with Player ID ===

(deftest activate-mana-ability-accepts-player-id
  (testing "activate-mana-ability works with explicit player-id for bot"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 0})
          game-db (:game/db app-db)
          battlefield (q/get-objects-in-zone game-db :player-2 :battlefield)
          mountain (first (filter #(= :mountain (get-in % [:object/card :card/id])) battlefield))
          result-db (abilities/activate-mana-ability game-db :player-2 (:object/id mountain) :red)]
      (is (true? (:object/tapped (q/get-object result-db (:object/id mountain))))
          "Mountain should be tapped")
      (is (= 1 (:red (q/get-mana-pool result-db :player-2)))
          "Bot should have 1 red mana in pool"))))


;; === Safety Limit ===

(deftest bot-decide-handler-respects-safety-limit
  (testing "::bot-decide handler checks action count and yields when limit reached"
    (let [app-db (setup-burn-bot-app-db {:mountains 3 :bolts 3})
          ;; Simulate action count at limit
          app-db (assoc app-db :bot/action-count 20)
          result (interceptor/bot-decide-handler app-db)]
      ;; Should dispatch ::yield (pass) rather than another cast
      (is (some? (:fx result))
          "Should have dispatches")
      (let [dispatches (mapv second (:fx result))]
        (is (some #(= ::game/yield (first %)) dispatches)
            "Should dispatch ::yield when safety limit reached")))))
