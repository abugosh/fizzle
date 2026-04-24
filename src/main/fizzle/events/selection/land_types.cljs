(ns fizzle.events.selection.land-types
  "Land type change selection: two-step chained selection for Vision Charm Mode 2.

   Step 1: Player chooses a source land type (one of 5 basic types).
   Step 2: Player chooses a target basic land type (excludes source type).
   Result: All lands on the battlefield (both players) with the source subtype
           receive a :land-type-override grant, overriding mana production until EOT."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.land-types :as land-types]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Builder: :change-land-types → :land-type-source selection
;; =====================================================

(defmethod core/build-selection-for-effect :change-land-types
  [_db player-id object-id _effect remaining-effects]
  {:selection/mechanism :pick-mode
   :selection/domain    :land-type-source
   :selection/lifecycle :chaining
   :selection/options land-types/basic-land-type-keys
   :selection/selected nil
   :selection/select-count 1
   :selection/exact? true
   :selection/validation :exact
   :selection/player-id player-id
   :selection/spell-id object-id
   :selection/remaining-effects remaining-effects})


;; =====================================================
;; Executor: :land-type-source
;; Stores selected source type in db; lifecycle :chaining triggers
;; build-chain-selection to produce :land-type-target selection.
;; =====================================================

(defmethod core/apply-domain-policy :land-type-source
  [game-db _selection]
  ;; No db mutation needed at this step — source type is stored on the
  ;; selection map itself and read by build-chain-selection.
  {:db game-db})


(defmethod core/execute-confirmed-selection :land-type-source
  [game-db _selection]
  ;; No db mutation needed at this step — source type is stored on the
  ;; selection map itself and read by build-chain-selection.
  {:db game-db})


;; =====================================================
;; Chain Builder: :land-type-source → :land-type-target selection
;; =====================================================

(defmethod core/build-chain-selection :land-type-source
  [_db selection]
  (let [source-type (first (:selection/selected selection))]
    {:selection/mechanism :pick-mode
     :selection/domain    :land-type-target
     :selection/lifecycle :standard
     :selection/options (vec (remove #{source-type} land-types/basic-land-type-keys))
     :selection/selected nil
     :selection/select-count 1
     :selection/exact? true
     :selection/validation :exact
     :selection/source-type source-type
     :selection/player-id (:selection/player-id selection)
     :selection/spell-id (:selection/spell-id selection)
     :selection/remaining-effects (:selection/remaining-effects selection)}))


;; =====================================================
;; Executor: :land-type-target
;; Applies :land-type-override grants to all matching lands on battlefield.
;; =====================================================

(defn- get-all-battlefield-objects
  "Get all objects on the battlefield across both players.
   player-id is the casting player — used to identify the opponent."
  [db player-id]
  (let [p1-objects (or (queries/get-objects-in-zone db player-id :battlefield) [])
        opponent-id (queries/get-other-player-id db player-id)
        p2-objects (if opponent-id
                     (or (queries/get-objects-in-zone db opponent-id :battlefield) [])
                     [])]
    (concat p1-objects p2-objects)))


(defn- land-has-subtype?
  "Check if a land object has the given subtype keyword."
  [land-obj subtype]
  (contains? (set (get-in land-obj [:object/card :card/subtypes] #{})) subtype))


(defmethod core/apply-domain-policy :land-type-target
  [game-db selection]
  (let [target-type (first (:selection/selected selection))
        source-type (:selection/source-type selection)
        spell-id (:selection/spell-id selection)
        player-id (:selection/player-id selection)
        game-state (queries/get-game-state game-db)
        current-turn (:game/turn game-state)
        all-objects (get-all-battlefield-objects game-db player-id)
        matching-lands (filterv #(land-has-subtype? % source-type) all-objects)
        new-produces (get-in land-types/basic-land-types [target-type :produces])
        db-with-grants (reduce
                         (fn [db land-obj]
                           (let [land-id (:object/id land-obj)
                                 grant {:grant/id (random-uuid)
                                        :grant/type :land-type-override
                                        :grant/source spell-id
                                        :grant/data {:original-subtype source-type
                                                     :new-subtype target-type
                                                     :new-produces new-produces}
                                        :grant/expires {:expires/turn current-turn
                                                        :expires/phase :cleanup}}]
                             (grants/add-grant db land-id grant)))
                         game-db
                         matching-lands)]
    {:db db-with-grants}))


(defmethod core/execute-confirmed-selection :land-type-target
  [game-db selection]
  (let [target-type (first (:selection/selected selection))
        source-type (:selection/source-type selection)
        spell-id (:selection/spell-id selection)
        player-id (:selection/player-id selection)
        game-state (queries/get-game-state game-db)
        current-turn (:game/turn game-state)
        ;; Find all lands on battlefield with source subtype (both players)
        all-objects (get-all-battlefield-objects game-db player-id)
        matching-lands (filterv #(land-has-subtype? % source-type) all-objects)
        new-produces (get-in land-types/basic-land-types [target-type :produces])
        ;; Apply :land-type-override grant to each matching land
        db-with-grants (reduce
                         (fn [db land-obj]
                           (let [land-id (:object/id land-obj)
                                 grant {:grant/id (random-uuid)
                                        :grant/type :land-type-override
                                        :grant/source spell-id
                                        :grant/data {:original-subtype source-type
                                                     :new-subtype target-type
                                                     :new-produces new-produces}
                                        :grant/expires {:expires/turn current-turn
                                                        :expires/phase :cleanup}}]
                             (grants/add-grant db land-id grant)))
                         game-db
                         matching-lands)]
    {:db db-with-grants}))
