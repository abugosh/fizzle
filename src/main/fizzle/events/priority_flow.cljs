(ns fizzle.events.priority-flow
  "Priority passing event handlers.

   All game flow is orchestrated by the game director (events/director.cljs),
   a pure synchronous function that runs the game loop until a human decision
   point. Event handlers call the director and apply the result to app-db.

   UX/orchestration accessors for stops are kept here (moved from engine/priority
   in fizzle-psno). Auto-mode is removed — the director uses yield-all? flag."
  (:require
    [datascript.core :as d]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]
    [fizzle.events.casting :as casting]
    [fizzle.events.director :as director]
    [fizzle.events.selection.core :as sel-core]
    [re-frame.core :as rf]))


;; === UX/Orchestration accessors (moved from engine/priority in fizzle-psno) ===

(defn check-stop
  "Check if a player has a stop set for a given phase.
   Returns true if the player's :player/stops contains the phase."
  [db player-eid phase]
  (let [stops (:player/stops (d/pull db [:player/stops] player-eid))]
    (boolean (and stops (contains? stops phase)))))


(defn set-player-stops
  "Set the phase stops for a player.
   Pure function: (db, player-eid, stops-set) -> db"
  [db player-eid stops]
  (d/db-with db [[:db/add player-eid :player/stops stops]]))


(defn check-opponent-stop
  "Check if a player has an opponent-stop set for a given phase.
   Returns true if the player's :player/opponent-stops contains the phase.
   Returns false when :player/opponent-stops is nil (e.g., bot entity)."
  [db player-eid phase]
  (let [stops (:player/opponent-stops (d/pull db [:player/opponent-stops] player-eid))]
    (boolean (and stops (contains? stops phase)))))


(defn set-opponent-stops
  "Set the opponent-turn phase stops for a player.
   Pure function: (db, player-eid, stops-set) -> db"
  [db player-eid stops]
  (d/db-with db [[:db/add player-eid :player/opponent-stops stops]]))


;; === Event Handlers ===

(defn- apply-director-result
  "Apply a director result to app-db and return re-frame effects map.
   Always returns {:db updated-app-db} — no dispatch-later, no cascades."
  [result]
  {:db (:app-db result)})


(defn- yield-human-priority
  "Record the human player's priority pass in game-db.
   When the human explicitly yields (clicking pass), we must record the pass
   before running the director, since the director pauses at human stops
   and only auto-passes when not at a stop. This pre-pass tells the director
   the human has already decided to pass for this priority window.
   Returns updated app-db."
  [app-db]
  (let [game-db (:game/db app-db)
        human-eid (queries/get-player-eid game-db game-state/human-player-id)
        holder-eid (priority/get-priority-holder-eid game-db)]
    (if (and human-eid (= holder-eid human-eid))
      (let [passed-db (priority/yield-priority game-db human-eid)
            all-passed? (priority/both-passed? passed-db)]
        (if all-passed?
          (let [active-pid (queries/get-active-player-id passed-db)
                active-eid (queries/get-player-eid passed-db active-pid)
                reset-db (-> passed-db
                             priority/reset-passes
                             (priority/set-priority-holder active-eid))]
            (assoc app-db :game/db reset-db))
          (assoc app-db :game/db (priority/transfer-priority passed-db human-eid))))
      app-db)))


(rf/reg-event-fx
  ::yield
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (apply-director-result
        (director/run-to-decision (yield-human-priority db) {:yield-all? false})))))


(rf/reg-event-fx
  ::yield-all
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (apply-director-result
        (director/run-to-decision db {:yield-all? true})))))


(defn- resolve-one-and-stop
  "Resolve the top stack item, letting the director handle priority and resolution.
   Returns updated app-db. Used by cast-and-yield and the :resolve-one-and-stop
   continuation."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    ;; Run director with yield-all? false but stack is non-empty:
    ;; director will auto-pass (stack non-empty), bot passes, resolve one item, stop.
    (:app-db (director/run-to-decision app-db {:yield-all? false}))))


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
