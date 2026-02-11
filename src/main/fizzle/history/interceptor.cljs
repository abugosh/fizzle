(ns fizzle.history.interceptor
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.history.core :as history]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


(def ^:private priority-events
  "Events that represent player priority actions and should create history entries.
   Mid-resolution choices (selections, targeting, mode picks) are excluded so that
   stepping back always lands on a state where the player can act."
  #{:fizzle.events.game/init-game
    :fizzle.events.game/cast-spell
    :fizzle.events.game/resolve-top
    :fizzle.events.game/advance-phase
    ;; start-turn creates its own history entries (opponent draw + turn start)
    :fizzle.events.game/play-land
    :fizzle.events.abilities/activate-mana-ability
    :fizzle.events.abilities/activate-ability})


(def ^:private priority-selection-types
  "Selection types that create history entries when confirmed via ::confirm-selection.
   These are pre-cast/ability confirmations (not mid-resolution choices)."
  #{:cast-time-targeting :x-mana-cost :exile-cards-cost :ability-targeting})


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
   Auto-forks when taking action from a rewound position."
  (rf/->interceptor
    :id :history/snapshot
    :before (fn [context]
              (let [db (get-in context [:coeffects :db])
                    game-db (:game/db db)
                    selection-type (get-in db [:game/pending-selection :selection/type])]
                (-> context
                    (assoc-in [:coeffects :history/pre-game-db] game-db)
                    (assoc-in [:coeffects :history/selection-type] selection-type))))
    :after (fn [context]
             (let [event (get-in context [:coeffects :event])
                   event-id (first event)
                   selection-type (get-in context [:coeffects :history/selection-type])]
               (if-not (priority-event? event-id selection-type)
                 context
                 (let [pre-game-db (get-in context [:coeffects :history/pre-game-db])
                       db-after (get-in context [:effects :db])
                       game-db-after (when db-after (:game/db db-after))]
                   (if (and db-after
                            game-db-after
                            (not (identical? pre-game-db game-db-after)))
                     (let [description (or (descriptions/describe-event event pre-game-db game-db-after selection-type)
                                           (name event-id))
                           turn (get-turn game-db-after)
                           entry (history/make-entry game-db-after event-id description turn)]
                       (if (or (= -1 (:history/position db-after))
                               (history/at-tip? db-after))
                         (assoc-in context [:effects :db] (history/append-entry db-after entry))
                         (assoc-in context [:effects :db] (history/auto-fork db-after entry))))
                     context)))))))


(defn register!
  "Register the history interceptor globally. Call once during app initialization."
  []
  (rf/reg-global-interceptor history-interceptor))
