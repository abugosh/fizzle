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
    [fizzle.engine.game-loop :as game-loop]
    [fizzle.engine.priority :as priority]
    [fizzle.events.game :as game]
    [fizzle.history.core :as history]
    [fizzle.history.descriptions :as descriptions]
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


;; === Priority bypass bug tests ===
;; These test that yield-impl stops for human priority when bot casts

(deftest yield-impl-stops-when-bot-casts-bolt
  (testing "yield-impl stops for human priority after bot casts bolt onto stack"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          ;; Set priority to the bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/priority opp-eid]])
          app-db (assoc app-db :game/db game-db)
          ;; First yield-impl: bot casts bolt, returns continue-yield?
          result1 (game/yield-impl app-db)
          _ (is (true? (:continue-yield? result1))
                "First yield should continue (bot just cast)")
          ;; Verify bolt is on stack
          result1-db (:game/db (:app-db result1))
          _ (is (not (q/stack-empty? result1-db))
                "Bolt should be on stack after bot cast")
          ;; Second yield-impl: should stop for human priority (not auto-resolve)
          result2 (game/yield-impl (:app-db result1))]
      (is (not (:continue-yield? result2))
          "Second yield should stop — human needs priority to respond to bolt")
      (is (not (q/stack-empty? (:game/db (:app-db result2))))
          "Bolt should still be on stack (not resolved without human input)"))))


(deftest yield-impl-full-priority-cycle-with-bolt
  (testing "full priority cycle: bot casts, human yields, bolt resolves"
    (let [app-db (setup-burn-bot-app-db {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          ;; Set priority to the bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/priority opp-eid]])
          app-db (assoc app-db :game/db game-db)
          ;; Step 1: bot casts bolt
          result1 (game/yield-impl app-db)
          _ (is (true? (:continue-yield? result1))
                "Bot cast should continue")
          ;; Step 2: priority should stop at human
          result2 (game/yield-impl (:app-db result1))
          _ (is (not (:continue-yield? result2))
                "Should stop for human priority")
          ;; Step 3: human yields (this should resolve the bolt)
          ;; Human now has priority. Calling yield-impl simulates the human pressing yield.
          ;; negotiate-priority will pass the human, bot auto-passes (stack non-empty but bot passes),
          ;; both passed -> resolve.
          result3 (game/yield-impl (:app-db result2))
          result3-db (:game/db (:app-db result3))]
      (is (q/stack-empty? result3-db)
          "Stack should be empty after human yields through bolt resolution")
      (is (= 17 (q/get-life-total result3-db :player-1))
          "Human should be at 17 life after bolt resolves"))))


;; === Turn progression tests ===

(deftest turn-increments-after-human-turn-ends
  (testing "turn counter increments from 1 to 2 when human turn ends"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :burn}))
          _ (is (= 1 (:game/turn (q/get-game-state db)))
                "Precondition: game starts at turn 1")
          _ (is (= :player-1 (q/get-active-player-id db))
                "Precondition: human is active")
          ;; Advance human through all phases to cleanup/start-turn
          result (game/advance-with-stops {:game/db db} true)
          result-db (:game/db (:app-db result))]
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Turn should increment to 2 after human turn ends")
      (is (= :player-2 (q/get-active-player-id result-db))
          "Active player should switch to bot"))))


(deftest turn-increments-after-bot-turn-ends
  (testing "turn counter increments from 2 to 3 when bot turn ends"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :burn}))
          ;; First, advance human turn to get to bot turn (turn 2)
          result1 (game/advance-with-stops {:game/db db} true)
          db-bot-turn (:game/db (:app-db result1))
          _ (is (= 2 (:game/turn (q/get-game-state db-bot-turn)))
                "Precondition: bot turn is 2")
          _ (is (= :player-2 (q/get-active-player-id db-bot-turn))
                "Precondition: bot is active")
          ;; Advance bot through all phases one at a time (advance-with-stops
          ;; only advances one phase for bots), until turn boundary
          result-db (loop [gdb db-bot-turn
                           n 20]
                      (if (zero? n)
                        gdb
                        (let [result (game/advance-with-stops {:game/db gdb} true)
                              rdb (:game/db (:app-db result))]
                          (if (not= (q/get-active-player-id rdb)
                                    (q/get-active-player-id db-bot-turn))
                            rdb
                            (recur rdb (dec n))))))]
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Turn should increment to 3 after bot turn ends")
      (is (= :player-1 (q/get-active-player-id result-db))
          "Active player should switch back to human"))))


(deftest turn-progresses-through-full-cycle-via-yield-impl
  (testing "yield-impl advances turns correctly through a full human->bot->human cycle"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :burn})
                 (priority/set-auto-mode :f6))
          app-db (merge (history/init-history) {:game/db db})
          ;; Run yield-impl in a loop — F6 means resolve everything.
          ;; Stop when turn >= 3 (human->bot->human) or loop limit.
          final-adb (loop [adb app-db
                           n 200]
                      (let [turn (:game/turn (q/get-game-state (:game/db adb)))]
                        (if (or (zero? n) (>= turn 3))
                          adb
                          (let [result (game/yield-impl adb)
                                result-adb (:app-db result)]
                            (if (:continue-yield? result)
                              (recur result-adb (dec n))
                              result-adb)))))
          result-db (:game/db final-adb)]
      (is (>= (:game/turn (q/get-game-state result-db)) 3)
          "Should reach at least turn 3 (human->bot->human)")
      (is (= :player-1 (q/get-active-player-id result-db))
          "Active player at turn 3 should be human"))))


;; === Bot priority in handle-loop-state :bot-phase tests ===

(defn- setup-bot-at-draw
  "Create an app-db with a burn bot at :draw phase.
   Bot has Mountains on battlefield and Bolts in hand, with a stop on :main1.
   When handle-loop-state :bot-phase runs, it advances draw->main1,
   executes bot phase action (play land), then bot priority (cast bolt),
   then checks stop on main1."
  [{:keys [mountains bolts]}]
  (let [db (th/create-test-db {:stops #{}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/lightning-bolt])
        db (th/add-opponent @conn {:bot-archetype :burn :stops #{:main1}})]
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
          ;; Set active player to bot, phase to draw
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/phase :draw]])]
      (merge (history/init-history)
             {:game/db db}))))


(deftest bot-casts-before-stop
  (testing "bot priority action puts bolt on stack before human stop fires"
    (let [app-db (setup-bot-at-draw {:mountains 1 :bolts 1})
          ;; Call handle-loop-state :bot-phase directly
          ;; Advances draw->main1, executes phase action (play land),
          ;; then bot priority should cast bolt, then stop fires on main1
          result (game-loop/handle-loop-state :bot-phase app-db
                                              {:advance-with-stops game/advance-with-stops
                                               :execute-bot-phase-action game/execute-bot-phase-action
                                               :execute-bot-priority-action bot-actions/execute-bot-priority-action})
          result-db (:game/db (:app-db result))]
      ;; Bolt should be on stack before human gets control
      (is (not (q/stack-empty? result-db))
          "Bolt should be on stack after bot priority action in :bot-phase handler")
      ;; Should NOT signal continue (stop fired with bolt on stack)
      (is (nil? (:continue-yield? result))
          "Should pause at stop with bolt on stack"))))


(deftest bot-passes-at-stop-empty-stack
  (testing "bot with no resources passes, stop fires with empty stack"
    (let [app-db (setup-bot-at-draw {:mountains 0 :bolts 0})
          result (game-loop/handle-loop-state :bot-phase app-db
                                              {:advance-with-stops game/advance-with-stops
                                               :execute-bot-phase-action game/execute-bot-phase-action
                                               :execute-bot-priority-action bot-actions/execute-bot-priority-action})
          result-db (:game/db (:app-db result))]
      (is (q/stack-empty? result-db)
          "Stack should be empty when bot has no resources")
      ;; With stop on main1 on the bot entity, should pause
      (is (nil? (:continue-yield? result))
          "Should pause at stop even with empty stack"))))


(deftest multiple-bot-casts-before-stop
  (testing "bot casts multiple bolts before stop fires (burn bot only casts one due to stack-empty? guard)"
    (let [app-db (setup-bot-at-draw {:mountains 3 :bolts 3})
          result (game-loop/handle-loop-state :bot-phase app-db
                                              {:advance-with-stops game/advance-with-stops
                                               :execute-bot-phase-action game/execute-bot-phase-action
                                               :execute-bot-priority-action bot-actions/execute-bot-priority-action})
          result-db (:game/db (:app-db result))]
      ;; Burn bot guards on stack-empty?, so only casts one bolt per priority loop
      (is (not (q/stack-empty? result-db))
          "At least one bolt should be on stack")
      (is (nil? (:continue-yield? result))
          "Should pause at stop with bolt on stack"))))


(deftest yield-impl-no-bot-priority-check
  (testing "yield-impl delegates bot priority to bot-phase handler, not its own code"
    (let [app-db (setup-bot-at-draw {:mountains 1 :bolts 1})
          ;; Call yield-impl — it should delegate to handle-loop-state :bot-phase
          ;; which handles both phase action and priority action
          result (game/yield-impl app-db)]
      ;; yield-impl should reach handle-loop-state :bot-phase which handles the bot cast
      ;; After bot-phase handles priority + stop, the result should have bolt on stack
      (is (not (q/stack-empty? (:game/db (:app-db result))))
          "Bolt should be on stack (placed by bot-phase handler, not yield-impl)"))))


;; === History description tests for bot casts ===

(deftest bot-cast-history-description
  (testing "yield-loop produces 'Cast Lightning Bolt' description for bot cast"
    (let [app-db (setup-bot-at-draw {:mountains 1 :bolts 1})
          game-db (:game/db app-db)
          ;; Replicate ::yield-all logic (yield-loop is private)
          ;; Set F6 mode and run yield-impl loop with history entry creation
          app-db (assoc app-db :game/db (priority/set-auto-mode game-db :f6))
          final-adb (loop [adb app-db, n 200]
                      (if (zero? n)
                        adb
                        (let [pre-db (:game/db adb)
                              result (game/yield-impl adb)
                              result-adb (:app-db result)
                              post-db (:game/db result-adb)
                              ;; Create history entry like yield-loop does
                              result-adb (if (and post-db
                                                  (not (identical? pre-db post-db))
                                                  (game-loop/should-create-history-entry? pre-db post-db))
                                           (let [game-state (q/get-game-state post-db)
                                                 turn (or (:game/turn game-state) 0)
                                                 active-pid (q/get-active-player-id post-db)
                                                 active-is-bot? (boolean (bot/get-bot-archetype post-db active-pid))
                                                 desc (or (descriptions/describe-event
                                                            [:fizzle.events.game/yield] pre-db post-db nil nil active-is-bot?)
                                                          "Yield")
                                                 entry (history/make-entry post-db :fizzle.events.game/yield desc turn)]
                                             (if (or (= -1 (:history/position result-adb))
                                                     (history/at-tip? result-adb))
                                               (history/append-entry result-adb entry)
                                               (history/auto-fork result-adb entry)))
                                           result-adb)]
                          (if (:continue-yield? result)
                            (recur result-adb (dec n))
                            result-adb))))
          entries (:history/main final-adb)
          descs (mapv :entry/description entries)]
      ;; Should have at least one "Cast Lightning Bolt" entry
      (is (some #(= "Cast Lightning Bolt" %) descs)
          (str "History should contain 'Cast Lightning Bolt' entry. Got: " (pr-str descs)))
      ;; Should have at least one "Resolve Lightning Bolt" entry
      (is (some #(= "Resolve Lightning Bolt" %) descs)
          (str "History should contain 'Resolve Lightning Bolt' entry. Got: " (pr-str descs))))))
