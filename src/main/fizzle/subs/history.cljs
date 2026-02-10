(ns fizzle.subs.history
  (:require
    [fizzle.history.core :as history]
    [re-frame.core :as rf]))


;; Layer 2: extraction subscriptions
(rf/reg-sub ::entries (fn [db _] (history/effective-entries db)))
(rf/reg-sub ::position (fn [db _] (:history/position db)))
(rf/reg-sub ::current-branch (fn [db _] (:history/current-branch db)))
(rf/reg-sub ::forks (fn [db _] (history/list-forks db)))


;; Layer 2: raw map for fork-tree derivation
(rf/reg-sub ::forks-map (fn [db _] (:history/forks db)))


;; Layer 3: derived subscriptions
(rf/reg-sub
  ::can-step-back?
  :<- [::position]
  (fn [pos _]
    (and (some? pos) (> pos 0))))


(rf/reg-sub
  ::can-step-forward?
  :<- [::entries]
  :<- [::position]
  (fn [[entries pos] _]
    (and (some? pos) (seq entries) (< pos (dec (count entries))))))


(rf/reg-sub
  ::at-tip?
  :<- [::entries]
  :<- [::position]
  (fn [[entries pos] _]
    (or (and (= pos -1) (empty? entries))
        (= pos (dec (count entries))))))


(rf/reg-sub
  ::entry-count
  :<- [::entries]
  (fn [entries _]
    (count (or entries []))))


(rf/reg-sub
  ::entries-by-turn
  :<- [::entries]
  (fn [entries _]
    (history/entries-by-turn (or entries []))))


(rf/reg-sub
  ::can-pop?
  :<- [::at-tip?]
  :<- [::position]
  (fn [[at-tip pos] _]
    (and at-tip (some? pos) (> pos 0))))


(rf/reg-sub
  ::fork-tree
  :<- [::forks-map]
  (fn [forks _]
    (history/fork-tree (or forks {}))))
