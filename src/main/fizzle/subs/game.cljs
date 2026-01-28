(ns fizzle.subs.game
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.rules :as rules]
    [re-frame.core :as rf]))


;; Layer 2: extraction subscriptions
(rf/reg-sub ::game-db (fn [db _] (:game/db db)))
(rf/reg-sub ::selected-card (fn [db _] (:game/selected-card db)))


;; Layer 3: derived subscriptions
(rf/reg-sub
  ::hand
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-hand game-db :player-1))))


(rf/reg-sub
  ::mana-pool
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-mana-pool game-db :player-1))))


(rf/reg-sub
  ::storm-count
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-storm-count game-db :player-1))))


(rf/reg-sub
  ::stack
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [triggers (queries/get-stack-items game-db)
            spells (queries/get-objects-in-zone game-db :player-1 :stack)]
        (concat triggers spells)))))


(rf/reg-sub
  ::can-cast?
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (rules/can-cast? game-db :player-1 selected))))
