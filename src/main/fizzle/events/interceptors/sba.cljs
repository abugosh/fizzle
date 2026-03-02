(ns fizzle.events.interceptors.sba
  "State-based actions interceptor.

   Re-frame interceptor that runs check-and-execute-sbas in the :after stage
   of game events. Attach to events where game state changes may trigger SBAs
   (resolve, confirm-selection, yield).

   Follows the same pattern as bots/interceptor.cljs."
  (:require
    [fizzle.engine.state-based :as sba]
    [re-frame.core :as rf]))


(def sba-interceptor
  "Re-frame interceptor that checks and executes state-based actions
   after game-state-changing events.

   Runs in the :after phase. Reads the app-db from [:effects :db],
   extracts :game/db, runs check-and-execute-sbas, and writes the
   updated game-db back."
  (rf/->interceptor
    :id :sba/check
    :after (fn [context]
             (let [app-db (get-in context [:effects :db])
                   game-db (when app-db (:game/db app-db))]
               (if-not game-db
                 context
                 (let [game-db' (sba/check-and-execute-sbas game-db)]
                   (if (identical? game-db game-db')
                     context
                     (assoc-in context [:effects :db :game/db] game-db'))))))))
