(ns fizzle.subs.scenario
  "Re-frame subscriptions for scenario state."
  (:require
    [re-frame.core :as rf]))


(rf/reg-sub
  ::all-scenarios
  (fn [db _]
    (:scenario/library db)))


(rf/reg-sub
  ::editing
  (fn [db _]
    (:scenario/editing db)))


(rf/reg-sub
  ::editing-player
  :<- [::editing]
  (fn [editing _]
    (:scenario/player editing)))


(rf/reg-sub
  ::editing-opponent
  :<- [::editing]
  (fn [editing _]
    (:scenario/opponent editing)))
