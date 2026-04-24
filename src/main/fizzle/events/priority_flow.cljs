(ns fizzle.events.priority-flow
  "Priority passing event handlers.

   All game flow is orchestrated by the game director (events/director.cljs),
   a pure synchronous function that runs the game loop until a human decision
   point. Event handlers call the director and apply the result to app-db.

   UX/orchestration accessors for stops are kept here (moved from engine/priority
   in fizzle-psno). Auto-mode is removed — the director uses yield-all? flag."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.events.casting :as casting]
    [fizzle.events.director :as director]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.history.descriptions :as descriptions]
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


(rf/reg-event-fx
  ::yield
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (let [pre-game-db (:game/db db)
            principal (when-let [eid (queries/get-priority-holder-eid pre-game-db)]
                        (queries/get-player-id pre-game-db eid))
            result (apply-director-result
                     (director/run-to-decision db {:yield-all? false :human-yielded? true}))
            db-after (:db result)
            game-db-after (:game/db db-after)]
        (if (identical? pre-game-db game-db-after)
          result
          (let [description (descriptions/describe-yield pre-game-db game-db-after)]
            (assoc-in result [:db :history/pending-entry]
                      (descriptions/build-pending-entry game-db-after ::yield description principal))))))))


(rf/reg-event-fx
  ::yield-all
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (let [pre-game-db (:game/db db)
            principal (when-let [eid (queries/get-priority-holder-eid pre-game-db)]
                        (queries/get-player-id pre-game-db eid))
            result (apply-director-result
                     (director/run-to-decision db {:yield-all? true}))
            db-after (:db result)
            game-db-after (:game/db db-after)]
        (if (identical? pre-game-db game-db-after)
          result
          (let [description (descriptions/describe-yield-all pre-game-db game-db-after)]
            (assoc-in result [:db :history/pending-entry]
                      (descriptions/build-pending-entry game-db-after ::yield-all description principal))))))))


(defn- resolve-one-and-stop
  "Resolve the top stack item, letting the director handle priority and resolution.
   Returns updated app-db. Used by cast-and-yield and the :resolve-one-and-stop
   continuation. Passes human-yielded? true so the human auto-passes once
   (the human chose Cast & Yield, meaning they want the spell to resolve)."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (:app-db (director/run-to-decision app-db {:yield-all? false :human-yielded? true}))))


;; Register continuation: :resolve-one-and-stop
;; Called by confirm-selection-impl when selection completes with this continuation.
;; Deferred entry processing is handled by confirm-selection-impl's terminal step,
;; not here — confirm-selection-impl is the single owner of deferred-entry processing.
(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_ app-db]
  {:app-db (resolve-one-and-stop app-db)})


(defn- cast-and-yield-handler
  [db]
  (let [pre-game-db (:game/db db)
        player-id (queries/get-human-player-id pre-game-db)
        after-cast (casting/cast-spell-handler db)]
    (cond
      ;; Pre-cast selection needed (mana allocation, targeting, etc.)
      ;; Set on-complete continuation so auto-resolve happens after selection completes.
      ;; Override deferred-entry to use :cast-and-yield type with correct pre-game-db.
      (:game/pending-selection after-cast)
      (let [db-with-continuation
            (if (:selection/on-complete (:game/pending-selection after-cast))
              after-cast
              (assoc-in after-cast [:game/pending-selection :selection/on-complete]
                        {:continuation/type :resolve-one-and-stop}))]
        ;; Override or set deferred-entry for cast-and-yield semantics
        {:db (assoc db-with-continuation :history/deferred-entry
                    {:type :cast-and-yield
                     :object-id (or (get-in after-cast [:game/pending-selection :selection/spell-id])
                                    (:game/selected-card db)
                                    (get-in after-cast [:history/deferred-entry :object-id]))
                     :pre-game-db pre-game-db
                     :principal player-id
                     :event-type :fizzle.events.priority-flow/cast-and-yield})})

      ;; Mode selection needed — no continuation (mode selection is a choice, not a cost).
      ;; After ADR-023: mode selection uses :game/pending-selection with :selection/type :spell-mode.
      (= :spell-mode (:selection/type (:game/pending-selection after-cast)))
      {:db after-cast}

      ;; Cast failed or nothing on stack
      (not (seq (queries/get-all-stack-items (:game/db after-cast))))
      {:db after-cast}

      ;; Cast succeeded, stack has items — resolve just the top item, then stop.
      ;; pending-entry from cast-spell-handler needs to be overridden with cast-and-yield desc.
      :else
      (let [db-without-cast-pending (dissoc after-cast :history/pending-entry)
            object-id (or (get-in after-cast [:history/deferred-entry :object-id])
                          (:game/selected-card db))
            resolved (resolve-one-and-stop db-without-cast-pending)
            game-db-after (:game/db resolved)
            desc (descriptions/describe-cast-and-yield pre-game-db game-db-after object-id)]
        {:db (assoc resolved :history/pending-entry
                    (descriptions/build-pending-entry
                      game-db-after
                      :fizzle.events.priority-flow/cast-and-yield
                      desc
                      player-id))}))))


(rf/reg-event-fx
  ::cast-and-yield
  (fn [{:keys [db]} _]
    (cast-and-yield-handler db)))
