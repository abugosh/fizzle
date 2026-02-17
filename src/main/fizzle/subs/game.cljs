(ns fizzle.subs.game
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.sorting :as sorting]
    [fizzle.engine.validation :as validation]
    [re-frame.core :as rf]))


;; Layer 2: extraction subscriptions
(rf/reg-sub ::game-db (fn [db _] (:game/db db)))
(rf/reg-sub ::selected-card (fn [db _] (:game/selected-card db)))
(rf/reg-sub ::active-screen (fn [db _] (:active-screen db)))
(rf/reg-sub ::game-over-dismissed (fn [db _] (:game/game-over-dismissed db)))
(rf/reg-sub ::stack-collapsed (fn [db _] (:ui/stack-collapsed db)))
(rf/reg-sub ::gy-collapsed (fn [db _] (:ui/gy-collapsed db)))
(rf/reg-sub ::history-collapsed (fn [db _] (:ui/history-collapsed db)))


;; Layer 3: derived subscriptions
(rf/reg-sub
  ::hand
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (sorting/sort-cards (queries/get-hand game-db (queries/get-human-player-id game-db))))))


(rf/reg-sub
  ::mana-pool
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-mana-pool game-db (queries/get-human-player-id game-db)))))


(rf/reg-sub
  ::storm-count
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-storm-count game-db (queries/get-human-player-id game-db)))))


(rf/reg-sub
  ::stack
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            stack-items (queries/get-all-stack-items game-db)
            ;; Exclude stack-items with object-refs (spells/copies) — the game object already represents them
            non-spell-items (remove :stack-item/object-ref stack-items)
            ;; Enrich with source card name for display
            enriched-items (mapv (fn [si]
                                   (if-let [source-id (:stack-item/source si)]
                                     (if-let [obj (queries/get-object game-db source-id)]
                                       (assoc si :stack-item/card-name
                                              (get-in obj [:object/card :card/name]))
                                       si)
                                     si))
                                 non-spell-items)
            spells (queries/get-objects-in-zone game-db human-pid :stack)
            order-key (fn [item]
                        (or (:stack-item/position item)
                            (:object/position item)
                            0))]
        (->> (concat enriched-items spells)
             (sort-by order-key >)
             vec)))))


(rf/reg-sub
  ::can-cast?
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (let [human-pid (queries/get-human-player-id game-db)]
        (and (rules/can-cast? game-db human-pid selected)
             ;; Exclude lands - they use play-land, not cast
             (not (rules/land-card? game-db selected)))))))


(rf/reg-sub
  ::can-play-land?
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (rules/can-play-land? game-db (queries/get-human-player-id game-db) selected))))


(rf/reg-sub
  ::selected-card-info
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (let [obj (queries/get-object game-db selected)]
        (when obj
          {:name (get-in obj [:object/card :card/name])
           :land? (rules/land-card? game-db selected)})))))


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
  ::player-stops
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            player-eid (queries/get-player-eid game-db human-pid)]
        (or (:player/stops (d/pull game-db [:player/stops] player-eid)) #{})))))


(rf/reg-sub
  ::opponent-stops
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            opponent-id (queries/get-opponent-id game-db human-pid)]
        (if opponent-id
          (let [opponent-eid (queries/get-player-eid game-db opponent-id)]
            (or (:player/stops (d/pull game-db [:player/stops] opponent-eid)) #{}))
          #{})))))


;; === Game Over Subscriptions ===

(rf/reg-sub
  ::game-over?
  :<- [::game-db]
  (fn [game-db _]
    (if game-db
      (some? (:game/winner (queries/get-game-state game-db)))
      false)))


(rf/reg-sub
  ::game-result
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            game-state (queries/get-game-state game-db)
            winner-ref (:game/winner game-state)]
        (when winner-ref
          (let [winner-pid (:player/id (d/pull game-db [:player/id] (:db/id winner-ref)))
                outcome (if (= human-pid winner-pid) :win :loss)]
            {:outcome outcome
             :turn (:game/turn game-state)
             :condition (:game/loss-condition game-state)
             :storm-count (queries/get-storm-count game-db human-pid)
             :opponent-life (queries/get-life-total game-db :opponent)
             :opponent-library-size (count (queries/get-objects-in-zone game-db :opponent :library))}))))))


(rf/reg-sub
  ::show-game-over-modal?
  :<- [::game-over?]
  :<- [::game-over-dismissed]
  :<- [::active-screen]
  (fn [[game-over? dismissed screen] _]
    (and (true? game-over?)
         (not dismissed)
         (= :game screen))))


(rf/reg-sub
  ::life-total
  :<- [::game-db]
  (fn [game-db [_ player-id]]
    (when game-db
      (queries/get-life-total game-db (or player-id (queries/get-human-player-id game-db))))))


(rf/reg-sub
  ::player-life
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-life-total game-db (queries/get-human-player-id game-db)))))


(rf/reg-sub
  ::opponent-life
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-life-total game-db :opponent))))


(rf/reg-sub
  ::battlefield
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            all (queries/get-objects-in-zone game-db human-pid :battlefield)
            {:keys [creatures other lands]} (sorting/group-by-type all)]
        {:creatures (sorting/sort-cards creatures)
         :other (sorting/sort-cards other)
         :lands (sorting/sort-cards lands)}))))


(rf/reg-sub
  ::opponent-battlefield
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [all (queries/get-objects-in-zone game-db :opponent :battlefield)
            {:keys [creatures other lands]} (sorting/group-by-type all)]
        {:creatures (sorting/sort-cards creatures)
         :other (sorting/sort-cards other)
         :lands (sorting/sort-cards lands)}))))


(rf/reg-sub
  ::graveyard-sort-mode
  (fn [db _]
    (get db :graveyard/sort-mode :entry)))


(defn- has-flashback?
  "Check if a game object has flashback — either from card definition or granted."
  [game-db obj]
  (let [card-alternates (get-in obj [:object/card :card/alternate-costs] [])
        granted-alternates (->> (queries/get-grants game-db (:object/id obj))
                                (filter #(= :alternate-cost (:grant/type %)))
                                (map :grant/data))
        all-alternates (concat card-alternates granted-alternates)]
    (some #(and (#{:flashback :granted-flashback} (:alternate/id %))
                (= :graveyard (:alternate/zone %)))
          all-alternates)))


;; Set of object-ids in graveyard that can currently be cast (have mana + valid mode)
(rf/reg-sub
  ::flashback-castable-ids
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            gy-cards (queries/get-objects-in-zone game-db human-pid :graveyard)]
        (->> gy-cards
             (filter #(rules/can-cast? game-db human-pid (:object/id %)))
             (map :object/id)
             set)))))


;; Set of object-ids in graveyard that have flashback (static or granted, regardless of mana)
(rf/reg-sub
  ::flashback-ids
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            gy-cards (queries/get-objects-in-zone game-db human-pid :graveyard)]
        (->> gy-cards
             (filter #(has-flashback? game-db %))
             (map :object/id)
             set)))))


(rf/reg-sub
  ::graveyard
  :<- [::game-db]
  :<- [::graveyard-sort-mode]
  (fn [[game-db sort-mode] _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            all (queries/get-objects-in-zone game-db human-pid :graveyard)]
        (if (= sort-mode :sorted)
          (let [flashback (filterv #(has-flashback? game-db %) all)
                remainder (filterv #(not (has-flashback? game-db %)) all)]
            {:castable (sorting/sort-cards flashback)
             :remainder (sorting/sort-cards remainder)})
          {:castable []
           :remainder all})))))


;; === Zone Count Subscriptions ===

(rf/reg-sub
  ::player-zone-counts
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            gy-count (count (queries/get-objects-in-zone game-db human-pid :graveyard))
            lib-count (count (queries/get-objects-in-zone game-db human-pid :library))
            exile-count (count (queries/get-objects-in-zone game-db human-pid :exile))]
        {:graveyard gy-count
         :library lib-count
         :exile exile-count
         :threshold? (>= gy-count 7)}))))


(rf/reg-sub
  ::opponent-zone-counts
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [gy-count (count (queries/get-objects-in-zone game-db :opponent :graveyard))
            lib-count (count (queries/get-objects-in-zone game-db :opponent :library))
            exile-count (count (queries/get-objects-in-zone game-db :opponent :exile))]
        {:graveyard gy-count
         :library lib-count
         :exile exile-count}))))


;; === Selection System Subscriptions ===

(rf/reg-sub
  ::pending-selection
  (fn [db _]
    (:game/pending-selection db)))


(rf/reg-sub
  ::selection-valid?
  :<- [::pending-selection]
  (fn [selection _]
    (when selection
      (validation/validate-selection selection))))


(rf/reg-sub
  ::mana-allocation-state
  :<- [::pending-selection]
  (fn [selection _]
    (when (= :mana-allocation (:selection/type selection))
      selection)))


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


;; Returns the cards available for selection based on :selection/card-source.
;; Card-source is set by selection builders to decouple card retrieval from selection type.
;; Non-card selection types (scry, storm-split, order-bottom, etc.) use their own subscriptions.
(rf/reg-sub
  ::selection-cards
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection)
      (let [player-id (:selection/player-id selection)
            card-source (:selection/card-source selection)
            zone (:selection/zone selection)]
        (sorting/sort-cards
          (case card-source
            ;; Map candidate IDs to card objects (pile-choice, exile-cards-cost, peek-and-select)
            :candidates
            (->> (:selection/candidates selection)
                 (map #(queries/get-object game-db %))
                 (filterv some?))

            ;; Map valid-target IDs to card objects (cast-time-targeting, ability-targeting)
            :valid-targets
            (->> (set (:selection/valid-targets selection))
                 (map #(queries/get-object game-db %))
                 (filterv some?))

            ;; Intersect candidates with library (tutor)
            :library
            (let [candidates (:selection/candidates selection)
                  library (queries/get-objects-in-zone game-db player-id :library)]
              (filterv #(contains? candidates (:object/id %)) library))

            ;; Query hand directly (discard)
            :hand
            (queries/get-hand game-db player-id)

            ;; Query by zone from selection metadata (graveyard-return, default)
            :zone
            (queries/get-objects-in-zone game-db player-id (or zone :hand))

            ;; Fallback for selections without card-source metadata
            (queries/get-objects-in-zone game-db player-id (or zone :hand))))))))


;; Storm split source spell name
(rf/reg-sub
  ::storm-split-source-name
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :storm-split (:selection/type selection)))
      (when-let [source-id (:selection/source-object-id selection)]
        (when-let [obj (queries/get-object game-db source-id)]
          (get-in obj [:object/card :card/name]))))))


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
        {:cards (sorting/sort-cards (mapv get-obj card-ids))
         :top-pile (mapv get-obj top-pile)
         :bottom-pile (mapv get-obj bottom-pile)
         :assigned (into (set top-pile) bottom-pile)}))))


;; Subscription for order-bottom card objects
;; Returns ordered and unsequenced card objects for the order-bottom modal
(rf/reg-sub
  ::order-bottom-cards
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :order-bottom (:selection/type selection)))
      (let [candidates (:selection/candidates selection)
            ordered (:selection/ordered selection)
            ordered-set (set ordered)
            unsequenced-ids (remove ordered-set candidates)
            get-obj (fn [oid] (queries/get-object game-db oid))]
        {:ordered-cards (mapv get-obj ordered)
         :unsequenced-cards (sorting/sort-cards (mapv get-obj (vec unsequenced-ids)))
         :all-ordered? (= (count ordered) (count candidates))}))))
