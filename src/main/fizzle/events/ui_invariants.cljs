(ns fizzle.events.ui-invariants
  "Cross-cutting UI-state invariant maintenance.

   Per ADR-031 (revised 2026-04-26): a re-frame post-event interceptor
   reconciles :game/selected-card after each game-mutating event using an
   actionability predicate — not a static zone set.

   The predicate mirrors the controls.cljs button-gating logic:
   :game/selected-card is cleared when the referenced card is no longer
   eligible for any user action (cast a non-land spell, play a land, or cycle).
   This matches the existing UX rule: selection is permitted only for cards
   the user can act on right now.

   Why actionability, not zones:
   - :hand alone would break the graveyard flashback flow.
   - #{:hand :graveyard} is too coarse: non-flashback graveyard cards
     (Lotus Petal post-sacrifice, Rain-of-Filth-granted lands post-sacrifice)
     are not actionable, so highlighting them recreates the gr9a/ktba bug class.
   - Graveyard flashback cards are preserved because rules/can-cast? returns
     true for them (flashback modes are graveyard-zone modes).

   Pattern mirrors history/interceptor.cljs."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.rules :as rules]
    [re-frame.core :as rf]))


(defn- still-actionable?
  "True if the referenced object is still eligible for some user action
   (cast a non-land spell, play a land, or cycle). Mirrors the gating
   logic of the controls.cljs Cast/Play/Cycle buttons. Per ADR-031."
  [game-db object-id]
  (let [pid (queries/get-human-player-id game-db)]
    (or (and (rules/can-cast? game-db pid object-id)
             (not (rules/land-card? game-db object-id)))
        (rules/can-play-land? game-db pid object-id)
        (rules/can-cycle? game-db pid object-id))))


(defn- reconcile-selected-card
  "Pure: given app-db, dissoc :game/selected-card if its referenced object
   is no longer eligible for any user action. No-op if:
   - :game/selected-card is unset
   - :game/db is nil (non-game screens like setup/calculator)
   - card is still actionable (can-cast?, can-play-land?, or can-cycle?)"
  [app-db]
  (let [object-id (:game/selected-card app-db)
        game-db   (:game/db app-db)]
    (if (or (nil? object-id) (nil? game-db))
      app-db
      (if (still-actionable? game-db object-id)
        app-db
        (dissoc app-db :game/selected-card)))))


(def reconcile-ui-invariants-interceptor
  "Re-frame post-event interceptor that reconciles :game/selected-card
   against post-event actionability after each game-mutating event.

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
