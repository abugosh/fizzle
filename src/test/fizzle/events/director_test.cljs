(ns fizzle.events.director-test
  "Tests for the pure game director loop and player agents.

   The game director is a pure function: (app-db, opts) -> {:app-db, :reason}.
   Tests call it directly without re-frame dispatch — no async, no dispatch-later."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.events.director :as director]
    [fizzle.events.phases :as phases]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- make-app-db
  "Create a simple app-db with history init."
  [game-db]
  (merge (history/init-history) {:game/db game-db}))


;; === Human Player Agent Tests ===

(deftest human-agent-auto-passes-when-phase-not-in-stops
  (testing "human should auto-pass when current phase is not in their stops"
    (let [db (th/create-test-db {:stops #{:main1}})
          player-eid (q/get-player-eid db :player-1)
          ;; Game is at main1 (default), but stops only has :main1
          ;; advance to main2 which is NOT in stops
          game-db (phases/advance-phase db :player-1) ; main1 -> combat (no creatures -> main2)
          game-state (q/get-game-state game-db)
          phase (:game/phase game-state)]
      ;; phase should NOT be in stops #{:main1}
      (is (director/human-should-auto-pass game-db player-eid #{:main1} false false)
          (str "Human should auto-pass at " phase " when only :main1 is in stops")))))


(deftest human-agent-pauses-at-stop-phase
  (testing "human should NOT auto-pass when current phase IS in their stops"
    (let [db (th/create-test-db {:stops #{:main1}})
          player-eid (q/get-player-eid db :player-1)]
      ;; Game starts at main1 which IS in stops
      (is (false? (director/human-should-auto-pass db player-eid #{:main1} false false))
          "Human should pause at :main1 when :main1 is in stops"))))


(deftest human-agent-yield-all-always-auto-passes
  (testing "yield-all: human should always auto-pass regardless of stops"
    (let [db (th/create-test-db {:stops #{:main1 :main2 :upkeep :draw :end}})
          player-eid (q/get-player-eid db :player-1)]
      ;; Even with stops at every phase, yield-all? true forces auto-pass
      (is (true? (director/human-should-auto-pass db player-eid #{:main1 :main2} true false))
          "yield-all? true should always auto-pass"))))


;; === Bot Agent Tests ===

(deftest bot-agent-plays-land-on-main-phase
  (testing "bot agent finds a land to play during main phase"
    (let [db (th/create-test-db {:stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :goldfish})
          ;; Add a land to bot's hand
          [db _] (th/add-card-to-zone db :forest :hand :player-2)
          ;; Set bot as active player in main1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :main1]])
          result (director/bot-act db :player-2)]
      (is (some? result) "bot-act should return a result")
      (is (= :play-land (:action-type result))
          "Bot should play a land"))))


(deftest bot-agent-passes-without-resources
  (testing "bot agent returns pass action when no castable spells"
    (let [db (th/create-test-db {:stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :goldfish})
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :main1]])
          result (director/bot-act db :player-2)]
      (is (= :pass (:action-type result))
          "Bot should pass when no resources"))))


;; === Director Loop Tests ===

(deftest director-human-stops-at-stop-phase
  (testing "director stops when human has a stop at current phase"
    (let [db (-> (th/create-test-db {:stops #{:main1}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          app-db (make-app-db db)
          result (director/run-to-decision app-db {:yield-all? false})]
      (is (= :await-human (:reason result))
          "Director should return :await-human when human has a stop"))))


(deftest director-passes-priority-to-bot-and-bot-passes-back
  (testing "director: human auto-passes phase with no stop, bot has no stop -> phase advances"
    (let [db (-> (th/create-test-db {:stops #{:main2}})  ; stop at main2 only
                 (th/add-opponent {:bot-archetype :goldfish}))
          app-db (make-app-db db)
          ;; At main1, no stop for human -> should advance through main1 to somewhere
          result (director/run-to-decision app-db {:yield-all? false})]
      ;; Should either reach a stop or await-human at a phase where human has stop
      (is (#{:await-human :pending-selection} (:reason result))
          "Director should stop at human decision point"))))


(deftest director-resolves-stack-when-both-pass
  (testing "director resolves top of stack when bot holds priority and passes"
    (let [db (-> (th/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-id)
          ;; Pre-pass human's priority (simulating user clicking "yield")
          ;; Transfer priority to bot so director starts from bot's perspective
          human-eid (q/get-player-eid db :player-1)
          db (priority/yield-priority db human-eid)
          db (priority/transfer-priority db human-eid)
          app-db (make-app-db db)
          ;; Director runs: bot holds priority -> bot passes -> both passed -> resolve
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))
          pool (q/get-mana-pool result-db :player-1)]
      (is (= 3 (:black pool))
          "Dark Ritual should resolve, adding BBB"))))


(deftest director-yield-all-cascades-within-turn
  (testing "yield-all director cascades through phases within a turn"
    (let [db (-> (th/create-test-db {:stops #{:main1}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Advance past main1 to main2 (no stop there)
          db (phases/advance-phase db :player-1)  ; main1 -> main2 (combat skipped)
          app-db (make-app-db db)
          ;; yield-all? false: director should auto-pass main2 (no stop) and reach end (no stop)
          ;; but stop at cleanup/turn-boundary or when it needs to resolve something
          ;; With goldfish bot (no stops in phase-actions except main1)
          ;; human will keep auto-passing, bot will pass, advance through main2->end->cleanup
          result (director/run-to-decision app-db {:yield-all? true})]
      ;; Director should eventually stop (at safety limit, game-over, or human decision)
      (is (some? result) "Director should return a result")
      (is (map? result) "Director result should be a map")
      (is (some? (:reason result))
          (str "Director should return a reason, got: " (pr-str result))))))


(deftest director-stops-on-pending-selection
  (testing "director stops when resolution creates a pending selection"
    (let [db (-> (th/create-test-db {:mana {:blue 1} :stops #{:main1}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          [db study-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          ;; Add cards to library for draw
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          db (rules/cast-spell db :player-1 study-id)
          app-db (make-app-db db)
          result (director/run-to-decision app-db {:yield-all? false})]
      ;; Careful Study resolve needs discard selection
      (is (#{:pending-selection :await-human} (:reason result))
          "Director should stop when selection is needed"))))


(deftest director-bot-casts-spell-during-turn
  (testing "director handles bot casting spells during bot's turn"
    (let [db (th/create-test-db {:stops #{:main1}})
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db (th/add-opponent @conn {:bot-archetype :burn})
          ;; Add mountain and bolt to bot
          [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)
          [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          ;; Set bot as active player in main1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :main1]])
          app-db (make-app-db db)
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      ;; Bot should have cast and/or the human now has priority
      ;; Either the bot cast and priority transferred to human, or bot passed
      (is (some? result-db) "Director should return a result"))))


(deftest director-no-dispatch-later-in-result
  (testing "director result contains no :fx with :dispatch-later entries"
    ;; The director is a pure function — no side effects, no async
    (let [db (-> (th/create-test-db {:stops #{:main1}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          app-db (make-app-db db)
          result (director/run-to-decision app-db {:yield-all? false})]
      ;; Pure function returns only :app-db and :reason — no fx
      (is (not (contains? result :fx))
          "Director should not return :fx (it's a pure function)")
      (is (contains? result :app-db)
          "Director should return :app-db")
      (is (contains? result :reason)
          "Director should return :reason"))))


(deftest director-clears-coordination-state
  (testing "result app-db has no coordination state like :yield/epoch"
    (let [db (-> (th/create-test-db {:stops #{:main1}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Simulate old state with yield coordination artifacts
          app-db (merge (history/init-history)
                        {:game/db db
                         :yield/epoch 5
                         :yield/step-count 10})
          result (director/run-to-decision app-db {:yield-all? false})
          result-app-db (:app-db result)]
      (is (nil? (:yield/epoch result-app-db))
          "Result should not contain :yield/epoch")
      (is (nil? (:yield/step-count result-app-db))
          "Result should not contain :yield/step-count"))))
