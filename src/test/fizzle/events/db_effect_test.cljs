(ns fizzle.events.db-effect-test
  "Tests for the custom :db effect handler chokepoint.

   The handler intercepts every game-db mutation and:
   1. Runs check-and-execute-sbas when game-db changes (identical? guard)
   2. Queues ::bot-decide when bot should act after game-db change

   Tests call game-db-effect-handler directly, manipulating rf-db/app-db atom
   to simulate before/after state."
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.bots.interceptor :as bot-interceptor]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; === Fixtures ===

(use-fixtures :each
  {:before (fn []
             ;; Reset app-db to a clean state before each test
             (reset! rf-db/app-db {}))
   :after  (fn []
             (reset! rf-db/app-db {}))})


;; === Helper: game-db with player at 0 life (SBA trigger) ===

(defn- make-app-db-with-zero-life-opponent
  "Create app-db with an opponent whose life is 0 — should trigger :life-zero SBA."
  []
  (let [game-db (-> (th/create-test-db)
                    (th/add-opponent))
        opp-eid (q/get-player-eid game-db game-state/opponent-player-id)
        ;; Set opponent life to 0
        game-db' (d/db-with game-db [[:db/add opp-eid :player/life 0]])]
    {:game/db game-db'}))


;; === Test 1: SBA fires through :db effect handler ===

(deftest test-sba-fires-on-game-db-change
  (testing "SBA runs and sets loss condition when opponent life drops to 0"
    (let [app-db (make-app-db-with-zero-life-opponent)
          ;; Simulate old app-db with different (non-identical) game-db
          old-game-db (:game/db (th/create-test-db))
          old-app-db {:game/db old-game-db}]
      ;; Set old state
      (reset! rf-db/app-db old-app-db)
      ;; Call handler with new app-db (different game-db)
      (db-effect/game-db-effect-handler app-db)
      ;; After handler runs SBAs, loss condition should be set
      (let [result-game-db (:game/db @rf-db/app-db)
            game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA should have set :life-zero loss condition")))))


;; === Test 2: identical game-db skips SBAs ===

(deftest test-identical-game-db-skips-sbas
  (testing "When game-db is identical (same reference), SBAs are NOT run"
    (let [game-db (th/create-test-db)
          app-db {:game/db game-db}
          sba-called? (atom false)]
      ;; Set same app-db as current state (identical game-db)
      (reset! rf-db/app-db app-db)
      (with-redefs [sba/check-and-execute-sbas
                    (fn [db]
                      (reset! sba-called? true)
                      db)]
        ;; Call handler with same app-db (identical game-db reference)
        (db-effect/game-db-effect-handler app-db))
      (is (not @sba-called?)
          "check-and-execute-sbas should NOT be called when game-db is identical"))))


;; === Test 3: nil game-db skips SBAs (setup screen) ===

(deftest test-nil-game-db-skips-sbas
  (testing "When new app-db has no :game/db key, SBAs are not run and app-db is updated normally"
    (let [app-db-without-game {:ui/some-setting true}
          sba-called? (atom false)]
      (reset! rf-db/app-db {:ui/other-setting false})
      (with-redefs [sba/check-and-execute-sbas
                    (fn [db]
                      (reset! sba-called? true)
                      db)]
        (db-effect/game-db-effect-handler app-db-without-game))
      (is (not @sba-called?)
          "check-and-execute-sbas should NOT be called when game-db is nil")
      (is (= app-db-without-game @rf-db/app-db)
          "app-db should be updated with the new value"))))


;; === Test 4: bot queued after SBA when bot holds priority ===

(deftest test-bot-queued-after-sba-when-bot-holds-priority
  (testing "::bot-decide is dispatched when bot holds priority after game-db change"
    (let [;; Create game-db with bot opponent holding priority
          base-game-db (th/create-test-db)
          ;; Add bot opponent
          game-db-with-bot (th/add-opponent base-game-db {:bot-archetype :goldfish})
          ;; Transfer priority to bot opponent
          opp-eid (q/get-player-eid game-db-with-bot game-state/opponent-player-id)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db-with-bot)
          game-db-bot-priority (d/db-with game-db-with-bot [[:db/add game-eid :game/priority opp-eid]])
          new-app-db {:game/db game-db-bot-priority}
          ;; Old app-db has different (non-identical) game-db
          old-app-db {:game/db base-game-db}
          dispatched-events (atom [])]
      (reset! rf-db/app-db old-app-db)
      (with-redefs [rf/dispatch (fn [event] (swap! dispatched-events conj event))]
        (db-effect/game-db-effect-handler new-app-db))
      (is (some #(= (first %) ::bot-interceptor/bot-decide) @dispatched-events)
          "::bot-decide should be dispatched when bot holds priority"))))


;; === Test 5: bot NOT queued when pending selection exists ===

(deftest test-bot-not-queued-with-pending-selection
  (testing "::bot-decide is NOT dispatched when a pending selection is active"
    (let [base-game-db (th/create-test-db)
          game-db-with-bot (th/add-opponent base-game-db {:bot-archetype :goldfish})
          opp-eid (q/get-player-eid game-db-with-bot game-state/opponent-player-id)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db-with-bot)
          game-db-bot-priority (d/db-with game-db-with-bot [[:db/add game-eid :game/priority opp-eid]])
          ;; Pending selection in app-db prevents bot from acting
          new-app-db {:game/db game-db-bot-priority
                      :game/pending-selection {:selection/type :discard}}
          old-app-db {:game/db base-game-db}
          dispatched-events (atom [])]
      (reset! rf-db/app-db old-app-db)
      (with-redefs [rf/dispatch (fn [event] (swap! dispatched-events conj event))]
        (db-effect/game-db-effect-handler new-app-db))
      (is (not (some #(= (first %) ::bot-interceptor/bot-decide) @dispatched-events))
          "::bot-decide should NOT be dispatched when pending selection exists"))))


;; === Test 6: no-change pass-through when game-db identical but app-db differs ===

(deftest test-app-db-updated-when-only-ui-changes
  (testing "app-db is updated even when game-db is identical (non-game UI change)"
    (let [game-db (th/create-test-db)
          old-app-db {:game/db game-db :ui/setting-a true}
          new-app-db {:game/db game-db :ui/setting-b true}]
      (reset! rf-db/app-db old-app-db)
      (db-effect/game-db-effect-handler new-app-db)
      (is (= new-app-db @rf-db/app-db)
          "app-db should be updated even when game-db is identical"))))


;; === Test 7: SBA results are persisted to app-db ===

(deftest test-sba-results-persisted-to-app-db
  (testing "SBA-modified game-db is stored in app-db"
    (let [app-db (make-app-db-with-zero-life-opponent)
          old-app-db {:game/db (th/create-test-db)}]
      (reset! rf-db/app-db old-app-db)
      (db-effect/game-db-effect-handler app-db)
      ;; The result in app-db should have SBAs applied
      (let [final-game-db (:game/db @rf-db/app-db)]
        (is (some? final-game-db)
            "game-db should be set in app-db")
        (is (= :life-zero (:game/loss-condition (q/get-game-state final-game-db)))
            "SBA result (loss condition) should be in the final app-db")))))


;; === Test 8: first init (old app-db is {}) ===

(deftest test-first-init-empty-old-app-db
  (testing "Handler works correctly when old app-db is {} (first init)"
    (let [game-db (th/create-test-db)
          new-app-db {:game/db game-db}]
      (reset! rf-db/app-db {})
      ;; Should not throw — old game-db is nil (from {})
      (db-effect/game-db-effect-handler new-app-db)
      (is (= game-db (:game/db @rf-db/app-db))
          "game-db should be set after first init"))))
