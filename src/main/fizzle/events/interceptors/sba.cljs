(ns fizzle.events.interceptors.sba
  "State-based actions interceptor.

   Global re-frame interceptor that runs check-and-execute-sbas in the :after
   stage of game-state-changing events. Registered globally so SBAs fire
   regardless of whether the event was triggered by human or bot.

   Follows the same pattern as bots/interceptor.cljs."
  (:require
    [fizzle.engine.state-based :as sba]
    [re-frame.core :as rf]))


(def ^:private sba-trigger-events
  "Events after which SBAs should be checked. Any event that can change
   game state (life totals, library contents, drew-from-empty flag) must
   be in this set."
  #{:fizzle.events.game/yield
    :fizzle.events.game/yield-all
    :fizzle.events.game/resolve-top
    :fizzle.events.game/cast-spell
    :fizzle.events.game/cast-and-yield
    :fizzle.events.game/play-land
    :fizzle.events.game/advance-phase
    :fizzle.events.game/start-turn
    :fizzle.events.selection/confirm-selection
    :fizzle.events.abilities/activate-mana-ability})


(def sba-interceptor
  "Global re-frame interceptor that checks and executes state-based actions
   after game-state-changing events.

   Runs in the :after phase. Only fires for events in sba-trigger-events.
   Reads the app-db from [:effects :db], extracts :game/db, runs
   check-and-execute-sbas, and writes the updated game-db back."
  (rf/->interceptor
    :id :sba/check
    :after (fn [context]
             (let [event (get-in context [:coeffects :event])
                   event-id (first event)]
               (if-not (sba-trigger-events event-id)
                 context
                 (let [app-db (get-in context [:effects :db])
                       game-db (when app-db (:game/db app-db))]
                   (if-not game-db
                     context
                     (let [game-db' (sba/check-and-execute-sbas game-db)]
                       (if (identical? game-db game-db')
                         context
                         (assoc-in context [:effects :db :game/db] game-db'))))))))))


(defn register!
  "Register the SBA interceptor globally. Call once during app initialization."
  []
  (rf/reg-global-interceptor sba-interceptor))
