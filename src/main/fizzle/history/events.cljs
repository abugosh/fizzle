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
