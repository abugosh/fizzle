(ns fizzle.history.events
  (:require
    [fizzle.history.core :as history]
    [re-frame.core :as rf]))


(rf/reg-event-db
  ::step-back
  (fn [db _]
    (if (history/can-step-back? db)
      (history/step-to db (dec (:history/position db)))
      db)))


(rf/reg-event-db
  ::step-forward
  (fn [db _]
    (if (history/can-step-forward? db)
      (history/step-to db (inc (:history/position db)))
      db)))


(rf/reg-event-db
  ::switch-branch
  (fn [db [_ fork-id]]
    (history/switch-branch db fork-id)))


(rf/reg-event-db
  ::jump-to
  (fn [db [_ position]]
    (history/step-to db position)))


(rf/reg-event-db
  ::create-fork
  (fn [db [_ name]]
    (history/create-named-fork db name)))


(rf/reg-event-db
  ::rename-fork
  (fn [db [_ fork-id new-name]]
    (history/rename-fork db fork-id new-name)))


(rf/reg-event-db
  ::delete-fork
  (fn [db [_ fork-id]]
    (history/delete-fork db fork-id)))


(rf/reg-event-db
  ::pop-entry
  (fn [db _]
    (if (history/can-pop? db)
      (history/pop-entry db)
      db)))
