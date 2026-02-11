(ns fizzle.events.selection
  "Re-frame event handlers for the selection system.

   These handlers live here (not in core.cljs) because the keyword namespace
   :fizzle.events.selection/* is referenced by views and tests via ::selection/
   aliases. Moving them to core.cljs would change all dispatch keywords.

   Domain modules:
     selection/core.cljs      — mechanism (multimethods, validation, confirm/toggle)
     selection/resolution.cljs — resolution bridge (spell/ability selection detection)
     selection/library.cljs   — tutor, scry, peek, pile-choice domains
     selection/targeting.cljs — cast-time and player-target domains
     selection/costs.cljs     — X-cost detection, exile-cards cost
     selection/zone_ops.cljs  — discard (unified), graveyard-return"
  (:require
    [fizzle.events.selection.core :as core]
    [re-frame.core :as rf]))


(rf/reg-event-db
  ::set-pending-selection
  (fn [db [_ selection-state]]
    (assoc db :game/pending-selection selection-state)))


(rf/reg-event-db
  ::toggle-selection
  (fn [db [_ id]]
    (core/toggle-selection-impl db id)))


(rf/reg-event-db
  ::confirm-selection
  (fn [db _]
    (core/confirm-selection-handler db)))


(rf/reg-event-db
  ::cancel-selection
  (fn [db _]
    ;; Cancel clears selection but keeps the pending state
    ;; Player must still make a valid selection
    (assoc-in db [:game/pending-selection :selection/selected] #{})))
