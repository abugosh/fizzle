(ns fizzle.events.ui-invariants
  "Cross-cutting UI-state invariant maintenance.

   Per ADR-031: a re-frame post-event interceptor reconciles
   :game/selected-card against :game/db zone state after each
   game-mutating event. If the referenced object is no longer in a
   zone where selection is meaningful (#{:hand :graveyard}), the key
   is dissoc'd. Mirrors the history/interceptor.cljs pattern.

   Selectable zones (#{:hand :graveyard}) are the zones from which
   views read :game/selected-card for meaningful interaction:
   - :hand  — casting spells, playing lands, cycling
   - :graveyard — flashback spells

   If the referenced object moves to any other zone (e.g., :battlefield
   after play-land, :stack after cast, :exile after flashback, or nil
   if the object ceases to exist), the selection is cleared."
  (:require
    [fizzle.db.queries :as queries]
    [re-frame.core :as rf]))


(def ^:private selectable-zones
  "Zones where :game/selected-card has meaning (per ADR-031 view-consumer audit)."
  #{:hand :graveyard})


(defn- reconcile-selected-card
  "Pure: given app-db, dissoc :game/selected-card if its object-id's zone
   in :game/db is not in selectable-zones. No-op if:
   - :game/selected-card is unset
   - :game/db is nil (non-game screens like setup/calculator)
   - zone is selectable"
  [app-db]
  (let [object-id (:game/selected-card app-db)
        game-db   (:game/db app-db)]
    (if (or (nil? object-id) (nil? game-db))
      app-db
      (let [zone (queries/get-object-zone game-db object-id)]
        (if (contains? selectable-zones zone)
          app-db
          (dissoc app-db :game/selected-card))))))


(def reconcile-ui-invariants-interceptor
  "Re-frame post-event interceptor that reconciles :game/selected-card
   against :game/db zone state after each game-mutating event.

   Runs in :after position so it observes the net post-event db state.
   No-op when :effects :db is absent (event produced no db change) or
   when :game/selected-card is unset."
  (rf/->interceptor
    :id :reconcile-ui-invariants
    :after (fn [context]
             (let [db-after (get-in context [:effects :db])]
               (if (nil? db-after)
                 context
                 (assoc-in context [:effects :db]
                           (reconcile-selected-card db-after)))))))


(defn register!
  "Register the ui-invariants interceptor globally on all events.
   Call once during app initialization, after history-interceptor/register!.
   Mirrors history/interceptor.cljs pattern."
  []
  (rf/reg-global-interceptor reconcile-ui-invariants-interceptor))
