(ns fizzle.engine.game-loop
  "Derived state machine for the game loop.

   Decomposes yield-impl's concerns into:
   - derive-loop-state: compute current game loop state from game-db
   - negotiate-priority: handle priority passing between players
   - handle-loop-state: multimethod dispatching on derived state

   All functions are pure — no re-frame dispatch, no side effects.
   Event-layer functions (resolve-one-item, advance-with-stops, etc.)
   are received via opts map to avoid circular dependencies."
  (:require
    [fizzle.bots.protocol :as bot]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]))


(defn should-create-history-entry?
  "Returns true if a history entry should be created for this game-db change.
   Filters out bot phase advances where the bot is active, stack is empty
   in both states, and the turn number hasn't changed.
   Returns true (create entry) if either db is not a valid Datascript db."
  [pre-db post-db]
  (try
    (let [active-pid (queries/get-active-player-id post-db)
          is-bot (boolean (bot/get-bot-archetype post-db active-pid))
          pre-stack-empty (queries/stack-empty? pre-db)
          post-stack-empty (queries/stack-empty? post-db)
          pre-turn (:game/turn (queries/get-game-state pre-db))
          post-turn (:game/turn (queries/get-game-state post-db))]
      (not (and is-bot pre-stack-empty post-stack-empty (= pre-turn post-turn))))
    (catch :default _
      true)))


(defn derive-loop-state
  "Derive the current game loop state from game-db.
   Returns one of:
     :stack-resolution   — stack has items (always takes precedence)
     :bot-phase          — active player is a bot, stack empty
     :phase-advancement  — stack empty, human (or single) player's turn

   Pure function: (game-db) -> keyword"
  [game-db]
  (cond
    (not (queries/stack-empty? game-db))
    :stack-resolution

    (boolean (bot/get-bot-archetype game-db (queries/get-active-player-id game-db)))
    :bot-phase

    :else
    :phase-advancement))


(defn negotiate-priority
  "Handle priority passing between players.
   Returns {:app-db updated-app-db, :all-passed? bool}.

   When all-passed? is true, passes are reset in the returned app-db.
   When all-passed? is false, priority has been transferred to the opponent.

   Pure function: (app-db) -> {:app-db app-db', :all-passed? bool}"
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        is-bot-turn (boolean (bot/get-bot-archetype game-db (queries/get-active-player-id game-db)))
        holder-eid (priority/get-priority-holder-eid game-db)
        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        ;; Step 2: auto-pass non-active player
        active-player-id (queries/get-active-player-id gdb)
        active-eid (queries/get-player-eid gdb active-player-id)
        opponent-player-id (queries/get-other-player-id gdb active-player-id)
        gdb (if opponent-player-id
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (if (or auto-mode (and is-bot-turn (queries/stack-empty? gdb)))
                  ;; Auto-pass both players (bot turn with empty stack, or auto-mode)
                  (-> gdb
                      (priority/yield-priority active-eid)
                      (priority/yield-priority opp-eid))
                  (let [archetype (bot/get-bot-archetype gdb opponent-player-id)]
                    (if (and archetype
                             (= :pass (bot/bot-priority-decision
                                        archetype {:db gdb :player-id opponent-player-id})))
                      (priority/yield-priority gdb opp-eid)
                      gdb))))
              gdb)
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      {:app-db (assoc app-db :game/db (priority/reset-passes gdb))
       :all-passed? true}
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))
       :all-passed? false})))


(defmulti handle-loop-state
  "Dispatch on derived game loop state.
   Each handler returns {:app-db updated-app-db, :continue-yield? bool}.

   state — one of :stack-resolution, :bot-phase, :phase-advancement
   app-db — the current app-db (with passes already reset)
   opts — map of event-layer functions to avoid circular deps:
     :resolve-one-item          (game-db, player-id) -> {:db, :pending-selection}
     :advance-with-stops        (app-db, ignore-stops?) -> {:app-db}
     :execute-bot-phase-action  (db, archetype, phase, player-id) -> db
     :maybe-continue-cleanup    (app-db) -> app-db"
  (fn [state _app-db _opts] state))


(defmethod handle-loop-state :stack-resolution
  [_ app-db opts]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        active-player-id (queries/get-active-player-id game-db)
        resolve-one-item (:resolve-one-item opts)
        maybe-continue-cleanup (:maybe-continue-cleanup opts)
        result (resolve-one-item game-db active-player-id)]
    (if (:pending-selection result)
      ;; Selection needed — clear auto-mode, return selection
      {:app-db (-> app-db
                   (assoc :game/db (priority/clear-auto-mode (:db result)))
                   (assoc :game/pending-selection (:pending-selection result)))}
      ;; Resolved one item
      (let [resolved-db (:db result)]
        (if (not (queries/stack-empty? resolved-db))
          ;; More items on stack — continue
          {:app-db (assoc app-db :game/db resolved-db)
           :continue-yield? true}
          ;; Stack empty — clear :resolving auto-mode if active
          (let [resolved-db (if (= :resolving auto-mode)
                              (priority/clear-auto-mode resolved-db)
                              resolved-db)]
            {:app-db (maybe-continue-cleanup
                       (assoc app-db :game/db resolved-db))}))))))


(defmethod handle-loop-state :bot-phase
  [_ app-db opts]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        active-player-id (queries/get-active-player-id game-db)
        advance-with-stops (:advance-with-stops opts)
        execute-bot-phase-action (:execute-bot-phase-action opts)
        ;; Advance one phase (advance-with-stops returns after one phase for bots)
        result (advance-with-stops app-db false)
        result-db (:game/db (:app-db result))
        new-active-id (queries/get-active-player-id result-db)
        crossed-turn? (not= active-player-id new-active-id)]
    (cond
      ;; Pending selection (e.g., cleanup discard during bot turn)
      (:game/pending-selection (:app-db result))
      result

      ;; Turn boundary crossed — continue (next iteration re-derives state)
      crossed-turn?
      (let [new-is-bot (boolean (bot/get-bot-archetype result-db new-active-id))
            f6? (= :f6 auto-mode)]
        (if (and (not new-is-bot) f6?)
          ;; Crossed from bot to human turn with F6 — clear auto-mode
          {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
           :continue-yield? true}
          {:app-db (:app-db result)
           :continue-yield? true}))

      ;; Same bot turn — execute bot action for the new phase, check stops
      :else
      (let [current-phase (:game/phase (queries/get-game-state result-db))
            bot-arch (bot/get-bot-archetype result-db new-active-id)
            acted-db (execute-bot-phase-action result-db bot-arch current-phase new-active-id)
            bot-eid (queries/get-player-eid acted-db new-active-id)
            has-stop? (and (not auto-mode)
                           (priority/check-stop acted-db bot-eid current-phase))]
        (if has-stop?
          {:app-db (assoc (:app-db result) :game/db acted-db)}
          {:app-db (assoc (:app-db result) :game/db acted-db)
           :continue-yield? true})))))


(defmethod handle-loop-state :phase-advancement
  [_ app-db opts]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        f6? (= :f6 auto-mode)
        active-player-id (queries/get-active-player-id game-db)
        advance-with-stops (:advance-with-stops opts)
        result (advance-with-stops app-db f6?)
        result-db (:game/db (:app-db result))
        new-active-id (queries/get-active-player-id result-db)
        crossed-turn? (not= active-player-id new-active-id)]
    (cond
      ;; Pending selection (e.g., cleanup discard)
      (:game/pending-selection (:app-db result))
      result

      ;; Turn boundary crossed
      crossed-turn?
      (let [new-is-bot (boolean (bot/get-bot-archetype result-db new-active-id))]
        (if new-is-bot
          ;; Bot turn — keep going (preserve F6)
          {:app-db (:app-db result)
           :continue-yield? true}
          ;; Player turn — clear F6 if active
          (if f6?
            {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
             :continue-yield? true}
            {:app-db (:app-db result)
             :continue-yield? true})))

      ;; F6 within same turn — clear auto-mode
      f6?
      (update result :app-db
              (fn [adb] (update adb :game/db priority/clear-auto-mode)))

      ;; Normal: same player's turn, stop here
      :else
      result)))
