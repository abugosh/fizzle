(ns fizzle.events.db-effect
  "Custom :db effect handler — the SBA and bot check chokepoint.

   Overrides re-frame's default :db effect handler to run
   check-and-execute-sbas and bot-should-act? whenever the game-db
   changes. This is the structural chokepoint: every game-db mutation
   passes through here, so SBAs and bot checks fire unconditionally
   without any event whitelist to maintain.

   NOTE: This handler runs ALONGSIDE existing interceptors during the
   transition. SBAs are idempotent so parallel operation is safe.
   The interceptors are removed in a later epic task.

   Design principles applied:
   - identical? check is O(1) due to structural sharing
   - rf/dispatch (not dispatch-sync) for ::bot-decide to avoid re-entry
   - nil game-db handled gracefully (setup/calculator screens)"
  (:require
    [fizzle.bots.interceptor :as bot-interceptor]
    [fizzle.engine.state-based :as sba]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]
    [re-frame.registrar :as registrar]))


(defn game-db-effect-handler
  "Custom :db effect handler. Runs SBAs and bot checks on every game-db mutation.

   Algorithm:
   1. Extract old and new game-db values
   2. If new game-db is nil OR identical to old: pass through (update app-db, skip SBAs)
   3. If game-db changed: run SBAs on new game-db, store result, check bot

   Guard on bot dispatch:
   - No pending selection in app-db (selection is in-progress)
   - bot-should-act? returns true for the SBA-resolved game-db"
  [new-app-db]
  (let [old-game-db (:game/db @rf-db/app-db)
        new-game-db (:game/db new-app-db)]
    (if (or (nil? new-game-db)
            (identical? new-game-db old-game-db))
      ;; No game-db change — pass through unchanged, update app-db if needed
      (when-not (identical? @rf-db/app-db new-app-db)
        (reset! rf-db/app-db new-app-db))
      ;; Game-db changed — run SBA pipeline, then check bot
      (let [sba-db      (sba/check-and-execute-sbas new-game-db)
            final-app-db (assoc new-app-db :game/db sba-db)]
        (reset! rf-db/app-db final-app-db)
        ;; Queue bot action if bot should act (after SBAs applied)
        ;; Guards: no pending selection, bot holds priority
        (when (and (not (:game/pending-selection final-app-db))
                   (not (:bot/action-pending? final-app-db))
                   (bot-interceptor/bot-should-act? sba-db))
          (rf/dispatch [::bot-interceptor/bot-decide]))))))


(defn register!
  "Register the custom :db effect handler. Call once during app initialization.
   Overrides re-frame's default :db effect with the SBA+bot chokepoint."
  []
  (registrar/clear-handlers :fx :db)
  (rf/reg-fx :db game-db-effect-handler))
