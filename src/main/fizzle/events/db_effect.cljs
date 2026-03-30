(ns fizzle.events.db-effect
  "Custom :db effect handler — the SBA chokepoint.

   Overrides re-frame's default :db effect handler to run
   check-and-execute-sbas whenever the game-db changes. This is the
   structural chokepoint: every game-db mutation passes through here,
   so SBAs fire unconditionally without any event whitelist to maintain.

   Bot decisions are now handled inline by the game director
   (events/director.cljs), not dispatched from here.

   Design principles applied:
   - identical? check is O(1) due to structural sharing
   - nil game-db handled gracefully (setup/calculator screens)"
  (:require
    [fizzle.engine.state-based :as sba]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]
    [re-frame.registrar :as registrar]))


(defn game-db-effect-handler
  "Custom :db effect handler. Runs SBAs on every game-db mutation.

   Algorithm:
   1. Extract old and new game-db values
   2. If new game-db is nil OR identical to old: pass through (update app-db, skip SBAs)
   3. If game-db changed: run SBAs on new game-db, store result

   Bot decisions are now handled inline by the game director — no bot dispatch here."
  [new-app-db]
  (let [old-game-db (:game/db @rf-db/app-db)
        new-game-db (:game/db new-app-db)]
    (if (or (nil? new-game-db)
            (identical? new-game-db old-game-db))
      ;; No game-db change — pass through unchanged, update app-db if needed
      (when-not (identical? @rf-db/app-db new-app-db)
        (reset! rf-db/app-db new-app-db))
      ;; Game-db changed — run SBA pipeline
      (let [sba-db (sba/check-and-execute-sbas new-game-db)
            final-app-db (assoc new-app-db :game/db sba-db)]
        (reset! rf-db/app-db final-app-db)))))


(defn register!
  "Register the custom :db effect handler. Call once during app initialization.
   Overrides re-frame's default :db effect with the SBA+bot chokepoint."
  []
  (registrar/clear-handlers :fx :db)
  (rf/reg-fx :db game-db-effect-handler))
