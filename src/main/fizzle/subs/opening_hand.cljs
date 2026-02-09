(ns fizzle.subs.opening-hand
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.subs.game :as game-subs]
    [re-frame.core :as rf]))


;; Layer 2: extraction subscriptions
(rf/reg-sub ::mulligan-count (fn [db _] (:opening-hand/mulligan-count db)))
(rf/reg-sub ::sculpted-ids (fn [db _] (:opening-hand/sculpted-ids db)))
(rf/reg-sub ::phase (fn [db _] (:opening-hand/phase db)))
(rf/reg-sub ::bottom-selection (fn [db _] (:opening-hand/bottom-selection db)))


;; Layer 3: derived subscriptions

(rf/reg-sub
  ::library-count
  :<- [::game-subs/game-db]
  (fn [game-db _]
    (when game-db
      (count (queries/get-objects-in-zone game-db :player-1 :library)))))


(rf/reg-sub
  ::bottom-selection-valid?
  :<- [::bottom-selection]
  :<- [::mulligan-count]
  (fn [[selection mulligan-count] _]
    (and selection mulligan-count
         (= (count selection) mulligan-count))))
