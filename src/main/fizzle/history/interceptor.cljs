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
    :fizzle.events.game/start-turn
    :fizzle.events.game/play-land
    :fizzle.events.abilities/activate-mana-ability
    :fizzle.events.abilities/activate-ability
    ;; Selection confirmations that complete a cast (targeted spells, X costs, exile costs)
    :fizzle.events.selection/confirm-cast-time-target
    :fizzle.events.selection/confirm-x-mana-selection
    :fizzle.events.selection/confirm-exile-cards-selection
    ;; Ability target confirmation (targeted activated abilities)
    :fizzle.events.abilities/confirm-ability-target})


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


(def history-interceptor
  "Global re-frame interceptor that captures Datascript db snapshots.
   Appends history entry whenever :game/db changes (excluding history events).
   Auto-forks when taking action from a rewound position."
  (rf/->interceptor
    :id :history/snapshot
    :before (fn [context]
              (let [db (get-in context [:coeffects :db])
                    game-db (:game/db db)]
                (assoc-in context [:coeffects :history/pre-game-db] game-db)))
    :after (fn [context]
             (let [event (get-in context [:coeffects :event])
                   event-id (first event)]
               (if-not (priority-events event-id)
                 context
                 (let [pre-game-db (get-in context [:coeffects :history/pre-game-db])
                       db-after (get-in context [:effects :db])
                       game-db-after (when db-after (:game/db db-after))]
                   (if (and db-after
                            game-db-after
                            (not (identical? pre-game-db game-db-after)))
                     (let [description (or (descriptions/describe-event event pre-game-db game-db-after)
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
