(ns fizzle.subs.game
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as events]
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
            spells (queries/get-objects-in-zone game-db :player-1 :stack)
            order-key (fn [item]
                        (or (:trigger/stack-order item)
                            (:object/position item)
                            0))]
        (->> (concat triggers spells)
             (sort-by order-key >)
             vec)))))


(rf/reg-sub
  ::can-cast?
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (and (rules/can-cast? game-db :player-1 selected)
           ;; Exclude lands - they use play-land, not cast
           (not (events/land-card? game-db selected))))))


(rf/reg-sub
  ::can-play-land?
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (events/can-play-land? game-db :player-1 selected))))


(rf/reg-sub
  ::current-phase
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (:game/phase (queries/get-game-state game-db)))))


(rf/reg-sub
  ::current-turn
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (:game/turn (queries/get-game-state game-db)))))


(rf/reg-sub
  ::life-total
  :<- [::game-db]
  (fn [game-db [_ player-id]]
    (when game-db (queries/get-life-total game-db (or player-id :player-1)))))


(rf/reg-sub
  ::player-life
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-life-total game-db :player-1))))


(rf/reg-sub
  ::opponent-life
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-life-total game-db :opponent))))
