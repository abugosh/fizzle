(ns fizzle.history.interceptor
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.history.core :as history]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


(def ^:private priority-events
  "Events that represent player priority actions and should create history entries
   via the inference path. Handlers that set :history/pending-entry directly are
   excluded from this set (they bypass the inference path entirely).
   cast-spell, cast-and-yield, and activate-ability use the pending-entry /
   deferred-entry mechanism, so they are not listed here."
  #{:fizzle.events.init/init-game
    :fizzle.events.cycling/cycle-card
    :fizzle.events.priority-flow/yield
    :fizzle.events.priority-flow/yield-all
    :fizzle.events.phases/start-turn
    :fizzle.events.lands/play-land
    :fizzle.events.abilities/activate-mana-ability})


(defn- determine-principal
  "Determine which player caused this event. Returns player-id keyword or nil.
   System events (init-game, start-turn) return nil.
   Returns nil gracefully if game-db is not a real Datascript db."
  [event game-db]
  (try
    (let [event-id (first event)
          args (rest event)]
      (case event-id
        ;; Explicit player-id in args: [_ object-id player-id]
        (:fizzle.events.lands/play-land
          :fizzle.events.cycling/cycle-card)
        (or (second args) (queries/get-human-player-id game-db))

        ;; Explicit player-id: [_ object-id color player-id]
        :fizzle.events.abilities/activate-mana-ability
        (or (nth args 2 nil) (queries/get-human-player-id game-db))

        ;; Priority holder is the actor
        (:fizzle.events.priority-flow/yield
          :fizzle.events.priority-flow/yield-all)
        (when-let [holder-eid (queries/get-priority-holder-eid game-db)]
          (queries/get-player-id game-db holder-eid))

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
  "Check if an event should create a history entry via inference."
  [event-id]
  (priority-events event-id))


(def history-interceptor
  "Global re-frame interceptor that captures Datascript db snapshots.
   Appends history entry whenever a pending-entry is set (explicit mechanism)
   or when a remaining priority event changes game-db (inference fallback).
   Auto-forks when taking action from a rewound position."
  (rf/->interceptor
    :id :history/snapshot
    :before (fn [context]
              (let [db (get-in context [:coeffects :db])
                    game-db (:game/db db)
                    event (get-in context [:coeffects :event])
                    had-pending? (some? (:game/pending-selection db))
                    principal (determine-principal event game-db)]
                (-> context
                    (assoc-in [:coeffects :history/pre-game-db] game-db)
                    (assoc-in [:coeffects :history/had-pending?] had-pending?)
                    (assoc-in [:coeffects :history/principal] principal))))
    :after (fn [context]
             (let [db-after (get-in context [:effects :db])]
               (if-let [pending (and db-after (:history/pending-entry db-after))]
                 ;; pending-entry mechanism: explicit entry from event handler
                 (let [{:keys [description snapshot event-type turn principal]} pending
                       entry (history/make-entry snapshot event-type description turn principal)
                       db-cleared (dissoc db-after :history/pending-entry)]
                   (if (or (= -1 (:history/position db-cleared))
                           (history/at-tip? db-cleared))
                     (assoc-in context [:effects :db] (history/append-entry db-cleared entry))
                     (assoc-in context [:effects :db] (history/auto-fork db-cleared entry))))
                 ;; Fall through to inference logic for remaining priority events
                 (let [event (get-in context [:coeffects :event])
                       event-id (first event)]
                   (if-not (priority-event? event-id)
                     context
                     (let [pre-game-db (get-in context [:coeffects :history/pre-game-db])
                           game-db-after (when db-after (:game/db db-after))
                           game-db-changed (and game-db-after
                                                (not (identical? pre-game-db game-db-after)))
                           had-pending? (get-in context [:coeffects :history/had-pending?])
                           selection-created (and (not had-pending?)
                                                  (some? (:game/pending-selection db-after)))]
                       (if (and db-after game-db-after
                                (or game-db-changed selection-created))
                         (let [description (or (descriptions/describe-event
                                                 event pre-game-db game-db-after)
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
                         context)))))))))


(defn register!
  "Register the history interceptor globally. Call once during app initialization."
  []
  (rf/reg-global-interceptor history-interceptor))
