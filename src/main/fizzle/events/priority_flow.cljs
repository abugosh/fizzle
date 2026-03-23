(ns fizzle.events.priority-flow
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]
    [fizzle.events.casting :as casting]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.phases :as phases]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.core :as sel-core]
    [re-frame.core :as rf]))


(defn advance-with-stops
  "Advance phases until a stop is hit or a turn boundary is crossed.
   Handles cleanup and start-turn automatically.
   After crossing a turn boundary, returns immediately — the caller (yield-impl)
   handles continuing through the new turn via recursive ::yield dispatch.
   When ignore-stops? is true (F6 mode), skips player phase stop checks.
   Bot-agnostic: checks stops for any player the same way.
   Returns {:app-db app-db'} with game-db at the stopped phase.
   Pure function: (app-db, ignore-stops?) -> {:app-db app-db'}"
  [app-db ignore-stops?]
  (let [game-db (:game/db app-db)
        active-player-id (queries/get-active-player-id game-db)
        player-eid (queries/get-player-eid game-db active-player-id)]
    (loop [gdb game-db]
      (let [game-state (queries/get-game-state gdb)
            current-phase (:game/phase game-state)
            nxt (phases/next-phase current-phase)]
        (if (= nxt :cleanup)
          ;; Advancing to cleanup: advance phase, begin cleanup, then cross turn boundary
          (let [advanced-db (phases/advance-phase gdb active-player-id)
                cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
            (if (:pending-selection cleanup-result)
              ;; Cleanup needs discard — pause with pending-selection
              {:app-db (-> app-db
                           (assoc :game/db (:db cleanup-result))
                           (assoc :game/pending-selection (:pending-selection cleanup-result)))}
              ;; No discard needed — cross turn boundary (start-turn switches active player)
              (let [db-after-cleanup (:db cleanup-result)
                    db-after-turn (phases/start-turn db-after-cleanup active-player-id)]
                ;; Always return at turn boundary — yield-impl handles continuation
                ;; via recursive ::yield dispatch (re-reads active player from db)
                {:app-db (assoc app-db :game/db db-after-turn)})))
          ;; Normal phase advance
          (let [advanced-db (phases/advance-phase gdb active-player-id)
                ;; Read actual phase from advanced-db (may differ from nxt
                ;; when combat is skipped due to no creatures)
                actual-phase (:game/phase (queries/get-game-state advanced-db))]
            ;; Check if new phase triggers anything on the stack
            (if (not (queries/stack-empty? advanced-db))
              ;; Stack triggered — stop and give priority
              {:app-db (assoc app-db :game/db advanced-db)}
              ;; Check stop (skipped in F6 mode)
              (if (and (not ignore-stops?)
                       (priority/check-stop advanced-db player-eid actual-phase))
                ;; Stop hit — pause here
                {:app-db (assoc app-db :game/db advanced-db)}
                ;; No stop or F6 — continue advancing
                (recur advanced-db)))))))))


(defn- yield-resolve-stack
  "Handle yield when all passed and stack is not empty: resolve top item.
   Returns {:app-db, :continue-yield?} or {:app-db} with pending-selection."
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        result (resolution/resolve-one-item game-db)]
    (if (:pending-selection result)
      ;; Selection needed — clear auto-mode, return selection
      {:app-db (-> app-db
                   (assoc :game/db (priority/clear-auto-mode (:db result)))
                   (assoc :game/pending-selection (:pending-selection result)))}
      ;; Resolved one item
      (let [resolved-db (:db result)]
        (if (not (queries/stack-empty? resolved-db))
          ;; More items on stack — only cascade in auto-mode.
          ;; Without auto-mode (manual yield), stop after one resolution
          ;; so the player gets priority to respond to remaining items.
          (if auto-mode
            {:app-db (assoc app-db :game/db resolved-db)
             :continue-yield? true}
            {:app-db (assoc app-db :game/db resolved-db)})
          ;; Stack empty — clear :resolving auto-mode if active
          (let [resolved-db (if (= :resolving auto-mode)
                              (priority/clear-auto-mode resolved-db)
                              resolved-db)]
            {:app-db (cleanup/maybe-continue-cleanup
                       (assoc app-db :game/db resolved-db))}))))))


(defn- player-is-bot?
  "Check if a player entity has a bot archetype set.
   Uses DB data only (no bot protocol calls).
   Pure function: (db, player-eid) -> boolean"
  [db player-eid]
  (boolean (:player/bot-archetype (d/pull db [:player/bot-archetype] player-eid))))


(defn- bot-would-pass?
  "Check if bot player would pass priority in current game state.
   Returns true if bot has no action to take (should auto-pass).
   Pure function: (game-db, opponent-player-id) -> boolean"
  [game-db opponent-player-id]
  (let [archetype (bot-protocol/get-bot-archetype game-db opponent-player-id)]
    (if archetype
      (= :pass (bot-protocol/bot-priority-decision
                 archetype {:db game-db :player-id opponent-player-id}))
      true)))


(defn- bot-turn-advance-one-phase
  "Advance exactly one phase during a bot's turn, handling cleanup/turn boundary.
   Returns {:app-db} with the game state after advancing one phase.
   Pure function: (app-db) -> {:app-db app-db'}"
  [app-db]
  (let [game-db (:game/db app-db)
        active-player-id (queries/get-active-player-id game-db)
        game-state (queries/get-game-state game-db)
        current-phase (:game/phase game-state)
        nxt (phases/next-phase current-phase)]
    (if (= nxt :cleanup)
      ;; Advancing to cleanup: advance, begin cleanup, cross turn boundary
      (let [advanced-db (phases/advance-phase game-db active-player-id)
            cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
        (if (:pending-selection cleanup-result)
          {:app-db (-> app-db
                       (assoc :game/db (:db cleanup-result))
                       (assoc :game/pending-selection (:pending-selection cleanup-result)))}
          (let [db-after-cleanup (:db cleanup-result)
                db-after-turn (phases/start-turn db-after-cleanup active-player-id)]
            {:app-db (assoc app-db :game/db db-after-turn)})))
      ;; Normal: advance one phase
      {:app-db (assoc app-db :game/db (phases/advance-phase game-db active-player-id))})))


(defn- yield-advance-phase
  "Handle yield when all passed and stack is empty: advance phases.
   During a bot's turn, advances one phase at a time (returning :continue-yield?)
   so the event loop can fire the bot interceptor for actions like playing lands.
   During a human's turn, uses advance-with-stops to batch through to the next stop.
   Returns {:app-db, :continue-yield?} or {:app-db} with pending-selection."
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        f6? (= :f6 auto-mode)
        active-player-id (queries/get-active-player-id game-db)
        active-eid (queries/get-player-eid game-db active-player-id)
        bot-turn? (player-is-bot? game-db active-eid)
        priority-holder-eid (priority/get-priority-holder-eid game-db)
        bot-driving? (and bot-turn? (player-is-bot? game-db priority-holder-eid))]
    (if bot-driving?
      ;; Bot interceptor driving during bot's turn: one phase at a time
      (let [result (bot-turn-advance-one-phase app-db)
            result-db (:game/db (:app-db result))
            new-active-id (queries/get-active-player-id result-db)
            crossed-turn? (not= active-player-id new-active-id)
            new-phase (:game/phase (queries/get-game-state result-db))]
        (cond
          ;; Pending selection (e.g., cleanup discard)
          (:game/pending-selection (:app-db result))
          result

          ;; Turn boundary crossed — continue
          crossed-turn?
          (let [human-pid (queries/get-human-player-id result-db)
                landed-on-human? (= new-active-id human-pid)]
            (if (and landed-on-human? f6?)
              {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
               :continue-yield? true}
              {:app-db (:app-db result)
               :continue-yield? true}))

          ;; Stop hit on bot's turn (human's opponent-turn stops) — pause unless F6
          (and (not f6?)
               (priority/check-stop result-db active-eid new-phase))
          {:app-db (:app-db result)}

          ;; Same turn, no stop — pause for bot interceptor to dispatch ::bot-decide
          :else
          {:app-db (:app-db result)}))

      ;; Human turn: batch advance to next stop
      (let [result (advance-with-stops app-db f6?)
            result-db (:game/db (:app-db result))
            new-active-id (queries/get-active-player-id result-db)
            crossed-turn? (not= active-player-id new-active-id)]
        (cond
          ;; Pending selection (e.g., cleanup discard)
          (:game/pending-selection (:app-db result))
          result

          ;; Turn boundary crossed — continue (next yield re-reads active player)
          crossed-turn?
          (let [human-pid (queries/get-human-player-id result-db)
                landed-on-human? (= new-active-id human-pid)]
            (if (and landed-on-human? f6?)
              ;; Crossed to human turn with F6 — clear auto-mode, keep yielding to stop
              {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
               :continue-yield? true}
              ;; Crossed to other player — continue cascade
              {:app-db (:app-db result)
               :continue-yield? true}))

          ;; F6 within same turn — clear auto-mode (stop hit)
          f6?
          (update result :app-db
                  (fn [adb] (update adb :game/db priority/clear-auto-mode)))

          ;; Normal: same player's turn, stop here
          :else
          result)))))


(defn negotiate-priority
  "Handle priority passing between players.
   Returns {:app-db updated-app-db, :all-passed? bool}.

   When all-passed? is true, passes are reset in the returned app-db.
   When all-passed? is false, priority has been transferred to the opponent.

   Bot auto-passing rules:
   - Bot players auto-pass when a human yields AND bot protocol returns :pass
     (consults bot-priority-decision to allow reactive archetypes to hold priority)
   - When a bot yields, the human is NEVER auto-passed
     (human must get priority to respond to bot actions)
   - During a bot's turn with empty stack, human opponent auto-passes too
     (human doesn't need priority on empty stack during bot's turn)
   - During a bot's turn with non-empty stack, human keeps priority to respond
   - In auto-mode (:resolving, :f6), both players auto-pass regardless

   Pure function: (app-db) -> {:app-db app-db', :all-passed? bool}"
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        holder-eid (priority/get-priority-holder-eid game-db)
        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        active-player-id (queries/get-active-player-id gdb)
        active-eid (queries/get-player-eid gdb active-player-id)
        opponent-player-id (queries/get-other-player-id gdb active-player-id)
        ;; Step 2: determine if opponent should auto-pass
        should-auto-pass-opponent?
        (when opponent-player-id
          (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
            (or auto-mode
                (and (not (player-is-bot? gdb holder-eid))
                     (or (and (player-is-bot? gdb opp-eid)
                              (bot-would-pass? gdb opponent-player-id))
                         (and (player-is-bot? gdb active-eid)
                              (queries/stack-empty? gdb))))
                (and (player-is-bot? gdb active-eid)
                     (queries/stack-empty? gdb)
                     (not (priority/check-stop gdb active-eid
                                               (:game/phase (queries/get-game-state gdb))))))))
        gdb (if should-auto-pass-opponent?
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (-> gdb
                    (priority/yield-priority active-eid)
                    (priority/yield-priority opp-eid)))
              gdb)
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      {:app-db (assoc app-db :game/db (priority/reset-passes gdb))
       :all-passed? true}
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))
       :all-passed? false})))


(defn yield-impl
  "Core priority passing logic. Pure function on app-db.
   Returns map with:
     :app-db          — updated app-db (always present)
     :continue-yield? — true if ::yield should re-dispatch

   1. Negotiate priority (pass for current player, auto-pass opponent if applicable)
   2. If all passed and stack not empty → resolve top item
   3. If all passed and stack empty → advance phases
   4. If not all passed → priority transferred, wait"
  [app-db]
  (let [result (negotiate-priority app-db)]
    (if (:all-passed? result)
      (let [negotiated-app-db (:app-db result)
            game-db (:game/db negotiated-app-db)]
        (if (not (queries/stack-empty? game-db))
          (yield-resolve-stack negotiated-app-db)
          (yield-advance-phase negotiated-app-db)))
      {:app-db (:app-db result)})))


(def ^:private max-yield-steps
  "Safety limit: maximum number of yield steps per auto-mode cascade.
   Prevents infinite loops from bugs in phase/priority logic."
  200)


(defn- yield-handler
  [db]
  (if (:game/pending-selection db)
    {:db db}
    (let [result (yield-impl db)
          auto-mode (priority/get-auto-mode (:game/db (:app-db result)))
          step-count (or (:yield/step-count (:app-db result)) 0)]
      (cond
        ;; Safety limit reached — stop cascade, clear auto-mode
        (and auto-mode (:continue-yield? result) (>= step-count max-yield-steps))
        {:db (-> (:app-db result)
                 (update :game/db priority/clear-auto-mode)
                 (dissoc :yield/step-count))}

        ;; Continue yielding with auto-mode — animated cascade via dispatch-later
        (and auto-mode (:continue-yield? result))
        {:db (update (:app-db result) :yield/step-count (fnil inc 0))
         :fx [[:dispatch-later {:ms 100 :dispatch [::yield]}]]}

        ;; Continue yielding without auto-mode — immediate dispatch
        (:continue-yield? result)
        {:db (:app-db result)
         :fx [[:dispatch [::yield]]]}

        ;; Auto-mode active but paused (bot turn at priority phase) — keep
        ;; step counter so cascade resumes after bot interceptor fires
        auto-mode
        {:db (update (:app-db result) :yield/step-count (fnil inc 0))}

        ;; Done — clear step counter
        :else
        {:db (dissoc (:app-db result) :yield/step-count)}))))


(rf/reg-event-fx
  ::yield
  (fn [{:keys [db]} _]
    (yield-handler db)))


(defn- yield-all-handler
  [db]
  (if (:game/pending-selection db)
    {:db db}
    (let [game-db (:game/db db)
          mode (if (queries/stack-empty? game-db) :f6 :resolving)]
      {:db (-> db
               (assoc :game/db (priority/set-auto-mode game-db mode))
               (assoc :yield/step-count 0))
       :fx [[:dispatch [::yield]]]})))


(rf/reg-event-fx
  ::yield-all
  (fn [{:keys [db]} _]
    (yield-all-handler db)))


(defn- resolve-one-and-stop
  "Resolve the top stack item with temporary :resolving auto-mode (so opponent
   auto-passes), then clear auto-mode. Returns updated app-db. Used by
   cast-and-yield and the :resolve-one-and-stop continuation."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (let [adb (update app-db :game/db priority/set-auto-mode :resolving)
          result (yield-impl adb)]
      (update (:app-db result) :game/db priority/clear-auto-mode))))


;; Register continuation: :resolve-one-and-stop
;; Called by confirm-selection-impl when selection completes with this continuation.
(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_ app-db]
  (resolve-one-and-stop app-db))


(defn- cast-and-yield-handler
  [db]
  (let [new-db (casting/cast-spell-handler db)]
    (cond
      ;; Pre-cast selection needed (mana allocation, targeting, etc.)
      ;; Set on-complete continuation so auto-resolve happens after selection completes
      ;; Don't overwrite if selection already has its own continuation (e.g., spell-mode)
      (:game/pending-selection new-db)
      (if (:selection/on-complete (:game/pending-selection new-db))
        {:db new-db}
        {:db (assoc-in new-db [:game/pending-selection :selection/on-complete]
                       {:continuation/type :resolve-one-and-stop})})

      ;; Mode selection needed — no continuation (mode selection is a choice, not a cost)
      (:game/pending-mode-selection new-db)
      {:db new-db}

      ;; Cast failed or nothing on stack
      (not (seq (queries/get-all-stack-items (:game/db new-db))))
      {:db new-db}

      ;; Cast succeeded, stack has items — resolve just the top item, then stop.
      :else
      {:db (resolve-one-and-stop new-db)})))


(rf/reg-event-fx
  ::cast-and-yield
  (fn [{:keys [db]} _]
    (cast-and-yield-handler db)))


(defn- cast-and-yield-resolve-handler
  [db]
  (resolve-one-and-stop db))


(rf/reg-event-db
  ::cast-and-yield-resolve
  (fn [db _]
    (cast-and-yield-resolve-handler db)))
