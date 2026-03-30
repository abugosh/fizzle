(ns fizzle.events.director
  "Game director: pure function that runs the game loop synchronously.

   The director is the single orchestration point for game flow after every
   player action. It replaces the dual-orchestrator architecture (yield-handler
   dispatch-later chain + db_effect bot-decide chain).

   Entry point: run-to-decision
     Pure function: (app-db, opts) -> {:app-db, :reason}
     Runs the loop until a human decision point.

   Component functions (also pure, also testable):
     human-should-auto-pass  -- (game-db, player-eid, stops, yield-all?) -> boolean
     bot-act                 -- (game-db, player-id) -> action-result

   Architecture:
   - No dispatch-later
   - No :yield/epoch, :yield/step-count, :bot/action-pending?, :bot/action-count
   - Bot acts applied inline via pure engine functions
   - SBAs run after each engine step"
  (:require
    [datascript.core :as d]
    [fizzle.bots.interceptor :as bot-interceptor]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as queries]
    [fizzle.engine.mana-activation :as mana-activation]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.casting :as casting]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.lands :as lands]
    [fizzle.events.phases :as phases]
    [fizzle.events.resolution :as resolution]))


(def ^:private max-director-steps 300)


;; === Human Player Agent ===

(defn human-should-auto-pass
  "Determine if human player should auto-pass at current game state.
   Returns true if auto-pass, false if await input.

   Rules:
   - yield-all? true: always auto-pass (F6 mode)
   - Stack is non-empty: always auto-pass (stops only prevent phase advancement)
   - Stack is empty: auto-pass if current phase is NOT in player's stops

   Pure function: (game-db, player-eid, stops, yield-all?) -> boolean"
  [game-db _player-eid stops yield-all?]
  (cond
    yield-all? true
    (not (queries/stack-empty? game-db)) true
    :else (not (contains? stops (:game/phase (queries/get-game-state game-db))))))


;; === Stops Accessors ===

(defn get-player-stops
  "Get the stops set for a player entity. Returns #{} if none."
  [game-db player-eid]
  (if player-eid
    (or (:player/stops (d/pull game-db [:player/stops] player-eid)) #{})
    #{}))


(defn get-player-opponent-stops
  "Get the opponent-stops set for a player entity. Returns #{} if none."
  [game-db player-eid]
  (if player-eid
    (or (:player/opponent-stops (d/pull game-db [:player/opponent-stops] player-eid)) #{})
    #{}))


;; === Bot Agent ===

(defn- find-bot-land-to-play
  [game-db player-id]
  (some (fn [obj]
          (let [oid (:object/id obj)]
            (when (rules/can-play-land? game-db player-id oid) oid)))
        (queries/get-hand game-db player-id)))


(defn bot-act
  "Run one bot action cycle.
   Returns {:action-type :play-land/:cast-spell/:pass :game-db db' ...}
   Pure function: (game-db, player-id) -> action-result"
  [game-db player-id]
  (let [archetype (bot-protocol/get-bot-archetype game-db player-id)]
    (if-not archetype
      {:action-type :pass :game-db game-db}
      (let [game-state (queries/get-game-state game-db)
            current-phase (:game/phase game-state)
            phase-action (bot-protocol/bot-phase-action archetype current-phase game-db player-id)
            land-id (when (= :play-land (:action phase-action))
                      (find-bot-land-to-play game-db player-id))]
        (if land-id
          {:action-type :play-land
           :game-db (sba/check-and-execute-sbas (lands/play-land game-db player-id land-id))
           :object-id land-id}
          (let [action (bot-interceptor/bot-decide-action game-db)]
            (if (not= :cast-spell (:action action))
              {:action-type :pass :game-db game-db}
              (let [tap-seq (:tap-sequence action)
                    db-tapped (reduce (fn [d {:keys [object-id mana-color]}]
                                        (mana-activation/activate-mana-ability
                                          d player-id object-id mana-color))
                                      game-db tap-seq)
                    cast-result (casting/cast-spell-handler
                                  {:game/db db-tapped}
                                  {:player-id player-id
                                   :object-id (:object-id action)
                                   :target (:target action)})]
                (if (:game/pending-selection cast-result)
                  {:action-type :cast-spell
                   :game-db db-tapped
                   :pending-selection (:game/pending-selection cast-result)}
                  {:action-type :cast-spell
                   :game-db (sba/check-and-execute-sbas (:game/db cast-result))})))))))))


;; === Priority Helpers ===

(defn- current-holder-player-id
  [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)]
    (when holder-eid
      (some (fn [pid]
              (when (= holder-eid (queries/get-player-eid game-db pid)) pid))
            [game-state/human-player-id game-state/opponent-player-id]))))


;; === Phase Advancement ===

(defn- advance-one-phase
  [game-db active-player-id]
  (let [nxt (phases/next-phase (:game/phase (queries/get-game-state game-db)))]
    (if (= nxt :cleanup)
      (let [advanced-db (phases/advance-phase game-db active-player-id)
            cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
        (if (:pending-selection cleanup-result)
          {:game-db (:db cleanup-result)
           :pending-selection (:pending-selection cleanup-result)
           :crossed-turn? false}
          {:game-db (sba/check-and-execute-sbas
                      (phases/start-turn (:db cleanup-result) active-player-id))
           :crossed-turn? true}))
      {:game-db (sba/check-and-execute-sbas (phases/advance-phase game-db active-player-id))
       :crossed-turn? false})))


;; === Director Steps (called from main loop) ===
;; Each step returns either:
;;   {:continue new-app-db :yield-all? bool}  -- loop continues
;;   {:done {:app-db ... :reason ...}}         -- loop terminates

(declare step-resolve-stack step-advance-phase)


(defn- step-bot-action
  [app-db game-db holder-pid yield-all?]
  (let [action (bot-act game-db holder-pid)
        atype (:action-type action)]
    (cond
      (= atype :play-land)
      {:continue (assoc app-db :game/db (:game-db action)) :yield-all? yield-all?}

      (and (= atype :cast-spell) (:pending-selection action))
      {:done {:app-db (-> app-db
                          (assoc :game/db (:game-db action))
                          (assoc :game/pending-selection (:pending-selection action)))
              :reason :pending-selection}}

      (= atype :cast-spell)
      (let [passed-db (priority/yield-priority
                        (:game-db action)
                        (priority/get-priority-holder-eid (:game-db action)))]
        {:continue (assoc app-db :game/db passed-db) :yield-all? yield-all?})

      :else ; :pass
      (let [holder-eid (priority/get-priority-holder-eid game-db)
            passed-db (priority/yield-priority game-db holder-eid)
            all-passed? (priority/both-passed? passed-db)]
        (if-not all-passed?
          ;; Not all passed — transfer priority to other player, continue
          {:continue (assoc app-db :game/db (priority/transfer-priority passed-db holder-eid))
           :yield-all? yield-all?}
          ;; Both passed — reset and resolve or advance
          (let [active-pid (queries/get-active-player-id passed-db)
                active-eid (queries/get-player-eid passed-db active-pid)
                reset-db (-> passed-db
                             priority/reset-passes
                             (priority/set-priority-holder active-eid))]
            (if-not (queries/stack-empty? reset-db)
              (step-resolve-stack app-db reset-db yield-all?)
              (step-advance-phase app-db reset-db active-pid yield-all?))))))))


(defn- step-human-action
  [app-db game-db human-pid yield-all?]
  (let [human-eid (queries/get-player-eid game-db human-pid)
        human-stops (get-player-stops game-db human-eid)
        active-pid (queries/get-active-player-id game-db)
        on-opp-turn? (not= active-pid human-pid)
        opp-stops (get-player-opponent-stops game-db human-eid)
        current-phase (:game/phase (queries/get-game-state game-db))
        opp-stop-hit? (and on-opp-turn? (not yield-all?) (contains? opp-stops current-phase))
        auto-pass? (and (human-should-auto-pass game-db human-eid human-stops yield-all?)
                        (not opp-stop-hit?))]
    (if-not auto-pass?
      {:done {:app-db app-db :reason :await-human}}
      (let [holder-eid (priority/get-priority-holder-eid game-db)
            passed-db (priority/yield-priority game-db holder-eid)
            all-passed? (priority/both-passed? passed-db)]
        (if-not all-passed?
          ;; Not all passed — transfer priority to other player, continue
          {:continue (assoc app-db :game/db (priority/transfer-priority passed-db holder-eid))
           :yield-all? yield-all?}
          (let [active-pid' (queries/get-active-player-id passed-db)
                active-eid' (queries/get-player-eid passed-db active-pid')
                reset-db (-> passed-db
                             priority/reset-passes
                             (priority/set-priority-holder active-eid'))]
            (if-not (queries/stack-empty? reset-db)
              (step-resolve-stack app-db reset-db yield-all?)
              (step-advance-phase app-db reset-db active-pid' yield-all?))))))))


(defn- step-resolve-stack
  [app-db game-db yield-all?]
  (let [result (resolution/resolve-one-item game-db)]
    (if (:pending-selection result)
      {:done {:app-db (-> app-db
                          (assoc :game/db (:db result))
                          (assoc :game/pending-selection (:pending-selection result)))
              :reason :pending-selection}}
      (let [resolved-db (sba/check-and-execute-sbas (:db result))
            new-app-db (cleanup/maybe-continue-cleanup (assoc app-db :game/db resolved-db))]
        (if (:game/pending-selection new-app-db)
          {:done {:app-db new-app-db :reason :pending-selection}}
          (if yield-all?
            {:continue new-app-db :yield-all? yield-all?}
            {:done {:app-db new-app-db :reason :await-human}}))))))


(defn- step-advance-phase
  [app-db game-db active-pid yield-all?]
  (let [advance-result (advance-one-phase game-db active-pid)]
    (if (:pending-selection advance-result)
      {:done {:app-db (-> app-db
                          (assoc :game/db (:game-db advance-result))
                          (assoc :game/pending-selection (:pending-selection advance-result)))
              :reason :pending-selection}}
      (let [advanced-db (:game-db advance-result)
            crossed-turn? (:crossed-turn? advance-result)
            new-active-pid (queries/get-active-player-id advanced-db)
            human-pid game-state/human-player-id
            new-yield-all? (if (and yield-all? crossed-turn? (= new-active-pid human-pid))
                             false
                             yield-all?)]
        {:continue (assoc app-db :game/db advanced-db) :yield-all? new-yield-all?}))))


;; === Main Director Loop ===

(defn run-to-decision
  "Run the game loop until the human needs to make a decision.
   Pure function: (app-db, opts) -> {:app-db, :reason}

   opts:
     :yield-all? -- true for F6 mode: human auto-passes all stops

   Reasons:
     :await-human       -- human has a stop at current phase
     :pending-selection -- selection needed
     :game-over         -- game has a loss condition
     :safety-limit      -- loop limit reached"
  [app-db opts]
  (let [app-db (dissoc app-db :yield/epoch :yield/step-count
                       :bot/action-pending? :bot/action-count)
        human-pid game-state/human-player-id
        yield-all-init? (boolean (:yield-all? opts))]
    (loop [app-db app-db
           yield-all? yield-all-init?
           steps 0]
      (cond
        (>= steps max-director-steps)
        {:app-db app-db :reason :safety-limit}

        (:game/pending-selection app-db)
        {:app-db app-db :reason :pending-selection}

        :else
        (let [game-db (:game/db app-db)]
          (cond
            (nil? game-db)
            {:app-db app-db :reason :await-human}

            (:game/loss-condition (queries/get-game-state game-db))
            {:app-db app-db :reason :game-over}

            :else
            (let [holder-pid (current-holder-player-id game-db)
                  step-result
                  (cond
                    (bot-protocol/get-bot-archetype game-db holder-pid)
                    (step-bot-action app-db game-db holder-pid yield-all?)

                    (= holder-pid human-pid)
                    (step-human-action app-db game-db human-pid yield-all?)

                    :else
                    {:done {:app-db app-db :reason :await-human}})]
              (if (:done step-result)
                (:done step-result)
                (recur (:continue step-result)
                       (:yield-all? step-result)
                       (inc steps))))))))))
