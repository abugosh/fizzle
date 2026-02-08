(ns fizzle.history.interceptor
  (:require
    [clojure.string :as str]
    [fizzle.db.queries :as queries]
    [fizzle.history.core :as history]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


(def ^:private history-event-ns
  "Namespace prefix for history navigation events that should NOT be recorded."
  "fizzle.history")


(defn- history-event?
  "Returns true if this event is a history navigation event (step, switch, etc.)
   that should not create new history entries."
  [event-id]
  (and (keyword? event-id)
       (some-> (namespace event-id)
               (str/starts-with? history-event-ns))))


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
               (if (history-event? event-id)
                 context
                 (let [pre-game-db (get-in context [:coeffects :history/pre-game-db])
                       db-after (get-in context [:effects :db])
                       game-db-after (when db-after (:game/db db-after))]
                   (if (and db-after
                            game-db-after
                            (not (identical? pre-game-db game-db-after)))
                     (let [description (or (descriptions/describe-event event)
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
