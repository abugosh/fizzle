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
            enriched-triggers (mapv (fn [t]
                                      (assoc t :trigger/card-name
                                             (queries/get-trigger-source-card-name game-db t)))
                                    triggers)
            spells (queries/get-objects-in-zone game-db :player-1 :stack)
            order-key (fn [item]
                        (or (:trigger/stack-order item)
                            (:object/position item)
                            0))]
        (->> (concat enriched-triggers spells)
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


(rf/reg-sub
  ::battlefield
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-objects-in-zone game-db :player-1 :battlefield))))


(rf/reg-sub
  ::graveyard
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-objects-in-zone game-db :player-1 :graveyard))))


;; Set of object-ids in graveyard that have castable flashback modes
(rf/reg-sub
  ::flashback-castable-ids
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [gy-cards (queries/get-objects-in-zone game-db :player-1 :graveyard)]
        (->> gy-cards
             (filter #(rules/can-cast? game-db :player-1 (:object/id %)))
             (map :object/id)
             set)))))


;; === Selection System Subscriptions ===

(rf/reg-sub
  ::pending-selection
  (fn [db _]
    (:game/pending-selection db)))


;; === Mode Selection Subscriptions ===
;; For spells with multiple casting modes from the same zone

(rf/reg-sub
  ::pending-mode-selection
  (fn [db _]
    (:game/pending-mode-selection db)))


(rf/reg-sub
  ::selection-hand
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection)
      (let [player-id (:selection/player-id selection)]
        (queries/get-hand game-db player-id)))))


;; Returns the cards available for selection based on selection type.
;; For :discard -> hand cards
;; For :tutor -> library cards filtered to candidates
;; For :cast-time-targeting -> objects from valid-targets
(rf/reg-sub
  ::selection-cards
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection)
      (let [player-id (:selection/player-id selection)
            selection-type (:selection/type selection)
            effect-type (:selection/effect-type selection)
            zone (:selection/zone selection)]
        (cond
          ;; Pile choice: cards from candidates (still in library)
          (= selection-type :pile-choice)
          (let [candidates (:selection/candidates selection)]
            (->> candidates
                 (map #(queries/get-object game-db %))
                 (filterv some?)))

          ;; Cast-time targeting with object targets (e.g., Recoup targeting graveyard sorcery)
          (and (= selection-type :cast-time-targeting)
               (= :object (get-in selection [:selection/target-requirement :target/type])))
          (let [valid-target-ids (set (:selection/valid-targets selection))]
            (->> valid-target-ids
                 (map #(queries/get-object game-db %))
                 (filterv some?)))

          ;; Ability targeting with object targets (e.g., Seal of Cleansing)
          (and (= selection-type :ability-targeting)
               (= :object (get-in selection [:selection/target-requirement :target/type])))
          (let [valid-target-ids (set (:selection/valid-targets selection))]
            (->> valid-target-ids
                 (map #(queries/get-object game-db %))
                 (filterv some?)))

          ;; Tutor: library cards filtered to candidates
          (= effect-type :tutor)
          (let [candidates (:selection/candidates selection)
                library (queries/get-objects-in-zone game-db player-id :library)]
            (filterv #(contains? candidates (:object/id %)) library))

          ;; Discard: hand cards
          (= effect-type :discard)
          (queries/get-hand game-db player-id)

          ;; Default: use the zone from selection
          :else
          (queries/get-objects-in-zone game-db player-id (or zone :hand)))))))


;; Subscription for scry card objects
;; Returns full card objects for IDs in :selection/cards, :selection/top-pile, :selection/bottom-pile
(rf/reg-sub
  ::scry-cards
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :scry (:selection/type selection)))
      (let [card-ids (:selection/cards selection)
            top-pile (:selection/top-pile selection)
            bottom-pile (:selection/bottom-pile selection)
            get-obj (fn [oid] (queries/get-object game-db oid))]
        {:cards (mapv get-obj card-ids)
         :top-pile (mapv get-obj top-pile)
         :bottom-pile (mapv get-obj bottom-pile)
         :assigned (into (set top-pile) bottom-pile)}))))
