(ns fizzle.engine.opponent-sba-test
  "Tests for SBA checking on opponent player.
   Validates that state-based actions fire for the bot opponent
   when drawing from empty library."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.interceptors.sba :as sba-interceptor]
    [fizzle.events.phases :as phases]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- clear-library
  "Remove all cards from a player's library."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        library-eids (d/q '[:find [?e ...]
                            :in $ ?owner
                            :where [?e :object/owner ?owner]
                            [?e :object/zone :library]]
                          @conn player-eid)]
    (d/transact! conn (mapv (fn [eid] [:db/retractEntity eid]) library-eids))
    @conn))


(defn- get-loss-condition
  [db]
  (:game/loss-condition (q/get-game-state db)))


(deftest opponent-draw-empty-library-sets-flag
  (testing "opponent drawing from empty library sets drew-from-empty flag"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, draw phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          ;; Advance from upkeep to draw
          db-after-advance (phases/advance-phase db :player-2)
          ;; Check flag
          flag (d/q '[:find ?f .
                      :where [?e :player/id :player-2]
                      [?e :player/drew-from-empty ?f]]
                    db-after-advance)]
      (is (true? flag)
          "Opponent should have drew-from-empty flag after drawing from empty library"))))


(deftest opponent-sba-detects-empty-library-draw
  (testing "SBA engine detects opponent drew-from-empty flag"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, draw phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          ;; Advance from upkeep to draw
          db-after-advance (phases/advance-phase db :player-2)
          ;; Run SBA checks
          db-after-sba (sba/check-and-execute-sbas db-after-advance)]
      (is (= :empty-library (get-loss-condition db-after-sba))
          "SBA should detect opponent's empty library draw and set loss condition"))))


(deftest opponent-sba-via-yield-impl
  (testing "yield-impl advances bot turn through draw step and SBA detects empty library"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, upkeep phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          app-db (merge (history/init-history) {:game/db db})
          ;; Run yield-impl to advance phase (both pass → advance)
          result (priority-flow/yield-impl app-db)
          result-db (:game/db (:app-db result))
          ;; The SBA interceptor would normally fire here. Let's check manually.
          result-db-after-sba (sba/check-and-execute-sbas result-db)]
      ;; SBA should detect the opponent drew from empty library
      (is (= :empty-library (get-loss-condition result-db-after-sba))
          "SBA should detect opponent's empty library draw after yield-impl"))))


(deftest yield-impl-iterates-through-bot-draw-with-sba
  (testing "Iterating yield-impl for full bot turn detects empty library via SBA"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, untap phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db db})]
      ;; Simulate the event loop: yield-impl + SBA check repeatedly
      ;; Each yield advances one phase during bot turn
      (loop [adb app-db
             iterations 0]
        (if (>= iterations 10)
          (is false "Safety limit reached — game should have ended")
          (let [result (priority-flow/yield-impl adb)
                result-adb (:app-db result)
                result-game-db (:game/db result-adb)
                ;; Simulate SBA interceptor
                sba-db (sba/check-and-execute-sbas result-game-db)
                loss (get-loss-condition sba-db)]
            (if loss
              (is (= :empty-library loss)
                  "Loss should be from empty library draw")
              ;; Continue iterating if no loss detected yet
              (recur (assoc result-adb :game/db sba-db)
                     (inc iterations)))))))))


(deftest sba-interceptor-detects-opponent-empty-library-on-yield
  (testing "SBA global interceptor fires and sets loss condition after yield advances through bot draw"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, upkeep phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          app-db (merge (history/init-history) {:game/db db})
          ;; Simulate ::yield event handler (reg-event-fx)
          result (priority-flow/yield-impl app-db)
          handler-effects {:db (:app-db result)}
          ;; Simulate global SBA interceptor reading from context
          ;; Global interceptor checks event-id against sba-trigger-events
          context {:effects handler-effects
                   :coeffects {:event [:fizzle.events.priority-flow/yield]}}
          interceptor-after-fn (-> sba-interceptor/sba-interceptor
                                   :after)
          context-after (interceptor-after-fn context)
          ;; Extract the game-db after SBA interceptor
          result-game-db (get-in context-after [:effects :db :game/db])]
      ;; The SBA interceptor should have detected drew-from-empty and set loss condition
      (is (= :empty-library (get-loss-condition result-game-db))
          "SBA interceptor should detect opponent's empty library draw and set loss condition"))))


(deftest sba-interceptor-skips-non-trigger-events
  (testing "SBA interceptor does not fire for events not in trigger set"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Manually set drew-from-empty flag
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/drew-from-empty true]])
          app-db (merge (history/init-history) {:game/db db})
          ;; Simulate a non-trigger event (e.g., toggle-selection)
          context {:effects {:db app-db}
                   :coeffects {:event [:fizzle.events.selection/toggle-selection 42]}}
          interceptor-after-fn (-> sba-interceptor/sba-interceptor
                                   :after)
          context-after (interceptor-after-fn context)
          result-game-db (get-in context-after [:effects :db :game/db])]
      ;; SBA should NOT have fired — loss condition should not be set
      (is (nil? (get-loss-condition result-game-db))
          "SBA interceptor should not fire for non-trigger events"))))
