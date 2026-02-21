(ns fizzle.history.interceptor
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]
    [fizzle.history.core :as history]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


(def ^:private priority-events
  "Events that represent player priority actions and should create history entries.
   Mid-resolution choices (selections, targeting, mode picks) are excluded so that
   stepping back always lands on a state where the player can act."
  #{:fizzle.events.game/init-game
    :fizzle.events.game/cast-spell
    :fizzle.events.game/cast-and-yield
    :fizzle.events.game/yield
    :fizzle.events.game/yield-all
    :fizzle.events.game/start-turn
    :fizzle.events.game/play-land
    :fizzle.events.abilities/activate-mana-ability
    :fizzle.events.abilities/activate-ability})


(def ^:private priority-selection-types
  "Selection types that create history entries when confirmed via ::confirm-selection.
   These are pre-cast/ability confirmations (not mid-resolution choices)."
  #{:cast-time-targeting :x-mana-cost :exile-cards-cost :ability-targeting})


(defn- get-priority-holder-pid
  "Get the :player/id keyword of the player currently holding priority.
   Returns nil if no priority holder."
  [game-db]
  (when-let [holder-eid (priority/get-priority-holder-eid game-db)]
    (queries/get-player-id game-db holder-eid)))


(defn- determine-principal
  "Determine which player caused this event. Returns player-id keyword or nil.
   System events (init-game, start-turn) return nil.
   Returns nil gracefully if game-db is not a real Datascript db."
  [event game-db]
  (try
    (let [event-id (first event)
          args (rest event)]
      (case event-id
        ;; Cast with optional opts map
        :fizzle.events.game/cast-spell
        (let [opts (first args)]
          (or (:player-id opts) (queries/get-human-player-id game-db)))

        ;; Always human-initiated
        (:fizzle.events.game/cast-and-yield
          :fizzle.events.abilities/activate-ability)
        (queries/get-human-player-id game-db)

        ;; Explicit player-id in args: [_ object-id player-id]
        :fizzle.events.game/play-land
        (or (second args) (queries/get-human-player-id game-db))

        ;; Explicit player-id: [_ object-id color player-id]
        :fizzle.events.abilities/activate-mana-ability
        (or (nth args 2 nil) (queries/get-human-player-id game-db))

        ;; Priority holder is the actor
        (:fizzle.events.game/yield
          :fizzle.events.game/yield-all)
        (get-priority-holder-pid game-db)

        ;; Confirm selection: handled separately via pending-selection
        :fizzle.events.selection/confirm-selection
        nil

        ;; System events: no principal
        nil))
    (catch :default _
      nil)))


(defn- get-turn
  "Get current turn number from game-db. Returns 0 if not available."
  [game-db]
  (try
    (if game-db
      (let [game-state (queries/get-game-state game-db)]
        (or (:game/turn game-state) 0))
      0)
    (catch :default _
      0)))


(defn- priority-event?
  "Check if an event should create a history entry.
   Most events are checked against a static set. For ::confirm-selection,
   checks if the selection type is a priority type (pre-cast/ability)."
  [event-id selection-type]
  (or (priority-events event-id)
      (and (= event-id :fizzle.events.selection/confirm-selection)
           (priority-selection-types selection-type))))


(def history-interceptor
  "Global re-frame interceptor that captures Datascript db snapshots.
   Appends history entry whenever :game/db changes (excluding history events).
   Also captures entries when a pending-selection is created (e.g., resolve-top
   creating a peek-and-select selection without changing game-db).
   Auto-forks when taking action from a rewound position."
  (rf/->interceptor
    :id :history/snapshot
    :before (fn [context]
              (let [db (get-in context [:coeffects :db])
                    game-db (:game/db db)
                    event (get-in context [:coeffects :event])
                    selection-type (get-in db [:game/pending-selection :selection/type])
                    had-pending? (some? (:game/pending-selection db))
                    casting-spell-id (or (get-in db [:game/pending-selection :selection/spell-id])
                                         (:game/selected-card db))
                    principal (or (determine-principal event game-db)
                                  (get-in db [:game/pending-selection :selection/player-id]))]
                (-> context
                    (assoc-in [:coeffects :history/pre-game-db] game-db)
                    (assoc-in [:coeffects :history/selection-type] selection-type)
                    (assoc-in [:coeffects :history/had-pending?] had-pending?)
                    (assoc-in [:coeffects :history/casting-spell-id] casting-spell-id)
                    (assoc-in [:coeffects :history/principal] principal))))
    :after (fn [context]
             (let [event (get-in context [:coeffects :event])
                   event-id (first event)
                   selection-type (get-in context [:coeffects :history/selection-type])]
               (if-not (priority-event? event-id selection-type)
                 context
                 (let [pre-game-db (get-in context [:coeffects :history/pre-game-db])
                       db-after (get-in context [:effects :db])
                       game-db-after (when db-after (:game/db db-after))
                       game-db-changed (and game-db-after
                                            (not (identical? pre-game-db game-db-after)))
                       ;; Also trigger on pending-selection creation (e.g., resolve-top
                       ;; for spells that need player selection before changing game-db)
                       had-pending? (get-in context [:coeffects :history/had-pending?])
                       selection-created (and (not had-pending?)
                                              (some? (:game/pending-selection db-after)))
                       ;; yield/yield-all/cast-and-yield trigger on selection-created.
                       ;; cast-spell and activate-ability create selections that chain
                       ;; to confirm-selection (which has its own priority entry).
                       selection-triggers-entry (and selection-created
                                                     (#{:fizzle.events.game/yield
                                                        :fizzle.events.game/yield-all
                                                        :fizzle.events.game/cast-and-yield} event-id))
                       casting-spell-id (get-in context [:coeffects :history/casting-spell-id])]
                   (if (and db-after game-db-after
                            (or game-db-changed selection-triggers-entry))
                     (let [description (or (descriptions/describe-event
                                             event pre-game-db game-db-after
                                             selection-type casting-spell-id)
                                           (name event-id))
                           ;; Use pre-game-db for snapshot when game-db unchanged
                           ;; (selection-created case), since that's the meaningful state
                           snapshot-db (if game-db-changed game-db-after pre-game-db)
                           turn (get-turn snapshot-db)
                           principal (get-in context [:coeffects :history/principal])
                           entry (history/make-entry snapshot-db event-id description turn principal)]
                       (if (or (= -1 (:history/position db-after))
                               (history/at-tip? db-after))
                         (assoc-in context [:effects :db] (history/append-entry db-after entry))
                         (assoc-in context [:effects :db] (history/auto-fork db-after entry))))
                     context)))))))


(defn register!
  "Register the history interceptor globally. Call once during app initialization."
  []
  (rf/reg-global-interceptor history-interceptor))
