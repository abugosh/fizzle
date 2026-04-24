(ns fizzle.subs.game
  (:require
    [datascript.core :as d]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as queries]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.priority :as priority]
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


(rf/reg-sub
  ::human-player-id
  :<- [::game-db]
  (fn [game-db _]
    (when game-db (queries/get-human-player-id game-db))))


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
      (let [stack-items (queries/get-all-stack-items game-db)
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
            ;; Stack is shared — show ALL players' spells, not just human's
            spells (d/q '[:find [(pull ?obj [* {:object/card [*]}]) ...]
                          :where [?obj :object/zone :stack]]
                        game-db)
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
  ::can-cycle?
  :<- [::game-db]
  :<- [::selected-card]
  (fn [[game-db selected] _]
    (when (and game-db selected)
      (let [obj (queries/get-object game-db selected)
            card (:object/card obj)
            cycling-cost (:card/cycling card)
            player-id (queries/get-human-player-id game-db)]
        (and obj cycling-cost
             (= :hand (:object/zone obj))
             (mana/can-pay? game-db player-id cycling-cost))))))


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
  ::can-share?
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            human-eid (queries/get-player-eid game-db human-pid)
            holder-eid (priority/get-priority-holder-eid game-db)]
        (and (= human-eid holder-eid)
             (queries/stack-empty? game-db))))))


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
            human-eid (queries/get-player-eid game-db human-pid)]
        (if human-eid
          (or (:player/opponent-stops (d/pull game-db [:player/opponent-stops] human-eid)) #{})
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
             :opponent-life (queries/get-life-total game-db game-state/opponent-player-id)
             :opponent-library-size (count (queries/get-objects-in-zone game-db game-state/opponent-player-id :library))}))))))


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
    (when game-db (queries/get-life-total game-db game-state/opponent-player-id))))


(defn compute-creature-display
  "Compute display data for a creature object. Returns nil for non-creatures.
   Bundles effective P/T, base P/T, modification status, damage, combat state, and
   summoning sickness into a single map to avoid per-field subscription overhead."
  [game-db obj]
  (let [object-id (:object/id obj)
        eff-power (creatures/effective-power game-db object-id)]
    (when (some? eff-power)
      (let [eff-toughness (creatures/effective-toughness game-db object-id)
            base-power (or (:object/power obj) 0)
            base-toughness (or (:object/toughness obj) 0)
            power-mod (cond (> eff-power base-power) :buffed
                            (< eff-power base-power) :debuffed
                            :else nil)
            toughness-mod (cond (> eff-toughness base-toughness) :buffed
                                (< eff-toughness base-toughness) :debuffed
                                :else nil)]
        {:effective-power eff-power
         :effective-toughness eff-toughness
         :base-power base-power
         :base-toughness base-toughness
         :power-mod power-mod
         :toughness-mod toughness-mod
         :damage-marked (or (:object/damage-marked obj) 0)
         :attacking (boolean (:object/attacking obj))
         :blocking (some? (:object/blocking obj))
         :summoning-sick (creatures/summoning-sick? game-db object-id)}))))


(defn- enrich-creature
  "Assoc :creature/display onto creature objects; returns obj unchanged for non-creatures."
  [game-db obj]
  (if-let [display (compute-creature-display game-db obj)]
    (assoc obj :creature/display display)
    obj))


(rf/reg-sub
  ::battlefield
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [human-pid (queries/get-human-player-id game-db)
            all (queries/get-objects-in-zone game-db human-pid :battlefield)
            {:keys [creatures other lands]} (sorting/group-by-type all)]
        {:creatures (mapv #(enrich-creature game-db %) (sorting/sort-cards creatures))
         :other (sorting/sort-cards other)
         :lands (sorting/sort-cards lands)}))))


(rf/reg-sub
  ::opponent-battlefield
  :<- [::game-db]
  (fn [game-db _]
    (when game-db
      (let [all (queries/get-objects-in-zone game-db game-state/opponent-player-id :battlefield)
            {:keys [creatures other lands]} (sorting/group-by-type all)]
        {:creatures (mapv #(enrich-creature game-db %) (sorting/sort-cards creatures))
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
      (let [gy-count (count (queries/get-objects-in-zone game-db game-state/opponent-player-id :graveyard))
            lib-count (count (queries/get-objects-in-zone game-db game-state/opponent-player-id :library))
            exile-count (count (queries/get-objects-in-zone game-db game-state/opponent-player-id :exile))]
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


(rf/reg-sub
  ::unless-pay-state
  :<- [::pending-selection]
  (fn [selection _]
    (when (= :unless-pay (:selection/type selection))
      selection)))


(rf/reg-sub
  ::unless-pay-can-afford?
  :<- [::game-db]
  :<- [::unless-pay-state]
  (fn [[game-db state] _]
    (when (and game-db state)
      (let [player-id (:selection/player-id state)
            cost (:selection/unless-pay-cost state)
            pool (queries/get-mana-pool game-db player-id)
            generic (get cost :colorless 0)
            colored-cost (dissoc cost :colorless)]
        (and (every? (fn [[color amount]]
                       (>= (get pool color 0) amount))
                     colored-cost)
             (let [total-pool (reduce + (vals pool))
                   total-colored (reduce + 0 (vals colored-cost))]
               (>= (- total-pool total-colored) generic)))))))


;; === Mode Selection Subscriptions ===
;; Retired (ADR-023): mode selection now uses :game/pending-selection with
;; :selection/type :spell-mode. This subscription always returns nil.

(rf/reg-sub
  ::pending-mode-selection
  (fn [_db _]
    nil))


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
            zone (:selection/zone selection)
            combat-selection? (#{:select-attackers :assign-blockers}
                               (:selection/type selection))
            raw-cards
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

              ;; Intersect candidates with sideboard (wish tutor)
              :sideboard
              (let [candidates (:selection/candidates selection)
                    sideboard (queries/get-objects-in-zone game-db player-id :sideboard)]
                (filterv #(contains? candidates (:object/id %)) sideboard))

              ;; Query hand directly (discard)
              :hand
              (queries/get-hand game-db player-id)

              ;; Query target player's hand (hand-reveal-discard: Duress)
              :opponent-hand
              (let [target-player (:selection/target-player selection)]
                (queries/get-objects-in-zone game-db target-player :hand))

              ;; Query by zone from selection metadata (graveyard-return, default)
              :zone
              (queries/get-objects-in-zone game-db player-id (or zone :hand))

              ;; Fallback for selections without card-source metadata
              (queries/get-objects-in-zone game-db player-id (or zone :hand)))]
        (sorting/sort-cards
          (if combat-selection?
            (mapv #(enrich-creature game-db %) raw-cards)
            raw-cards))))))


;; Current attacker display data for blocker assignment combat math.
;; Returns {:effective-power int :effective-toughness int :card-name string} or nil.
(rf/reg-sub
  ::blocker-attacker-display
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :assign-blockers (:selection/type selection)))
      (when-let [attacker-id (:selection/current-attacker selection)]
        (when-let [obj (queries/get-object game-db attacker-id)]
          (let [display (compute-creature-display game-db obj)]
            (when display
              (assoc display :card-name (get-in obj [:object/card :card/name])))))))))


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


;; Subscription for peek-and-reorder card objects
;; Returns ordered and unsequenced card objects for the peek-and-reorder modal
(rf/reg-sub
  ::peek-and-reorder-cards
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :peek-and-reorder (:selection/type selection)))
      (let [candidates (:selection/candidates selection)
            ordered (:selection/ordered selection)
            ordered-set (set ordered)
            unsequenced-ids (remove ordered-set candidates)
            get-obj (fn [oid] (queries/get-object game-db oid))]
        {:ordered-cards (mapv get-obj ordered)
         :unsequenced-cards (sorting/sort-cards (mapv get-obj (vec unsequenced-ids)))
         :all-ordered? (= (count ordered) (count candidates))
         :may-shuffle? (boolean (:selection/may-shuffle? selection))}))))


;; Subscription for order-top card objects
;; Returns ordered and unsequenced card objects for the order-top modal
(rf/reg-sub
  ::order-top-cards
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :order-top (:selection/type selection)))
      (let [candidates (:selection/candidates selection)
            ordered (:selection/ordered selection)
            ordered-set (set ordered)
            unsequenced-ids (remove ordered-set candidates)
            get-obj (fn [oid] (queries/get-object game-db oid))]
        {:ordered-cards (mapv get-obj ordered)
         :unsequenced-cards (sorting/sort-cards (mapv get-obj (vec unsequenced-ids)))
         :all-ordered? (= (count ordered) (count candidates))}))))


;; Returns targetable ability stack items for ability-cast-targeting selection.
;; Maps valid-target EIDs to stack item data for display in the ability targeting modal.
(rf/reg-sub
  ::ability-cast-targets
  :<- [::game-db]
  :<- [::pending-selection]
  (fn [[game-db selection] _]
    (when (and game-db selection (= :ability-cast-targeting (:selection/type selection)))
      (let [valid-eids (set (:selection/valid-targets selection))
            all-items (queries/get-all-stack-items game-db)]
        (->> all-items
             (filter #(contains? valid-eids (:db/id %)))
             (vec))))))
