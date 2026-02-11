(ns fizzle.events.selection.targeting
  "Targeting domain: cast-time targeting and player-target selection types.

   Selection Types:
   - :cast-time-targeting — spell has :card/targeting, player picks target before cast
   - :player-target — effect targets :any-player, player picks which player

   Registers defmethods on:
   - core/execute-confirmed-selection for :cast-time-targeting, :player-target
   - core/build-selection-for-effect for :player-target"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.resolution :as resolution]))


;; =====================================================
;; Selection Builders
;; =====================================================

(defn- build-player-target-selection
  "Build pending selection state for a player-targeted effect.
   Used when :effect/target is :any-player - player must choose target."
  [player-id object-id target-effect effects-after]
  {:selection/type :player-target
   :selection/player-id player-id
   :selection/selected #{}
   :selection/select-count 1
   :selection/valid-targets #{:player-1 :opponent}
   :selection/spell-id object-id
   :selection/target-effect target-effect  ; The effect needing a target
   :selection/remaining-effects effects-after
   :selection/validation :exact
   :selection/auto-confirm? true})


(defn build-cast-time-target-selection
  "Build pending selection state for cast-time targeting.
   Used when a spell has :card/targeting requirements.

   Arguments:
     game-db - Datascript database
     player-id - Casting player
     object-id - Spell being cast
     mode - Casting mode being used
     target-req - First targeting requirement (player target for now)

   Returns selection state map."
  [game-db player-id object-id mode target-req]
  (let [valid-targets (targeting/find-valid-targets game-db player-id target-req)]
    {:selection/type :cast-time-targeting
     :selection/player-id player-id
     :selection/object-id object-id
     :selection/mode mode
     :selection/target-requirement target-req
     :selection/valid-targets valid-targets
     :selection/selected #{}
     :selection/select-count 1
     :selection/validation :exact
     :selection/auto-confirm? true}))


;; =====================================================
;; Cast-Time Targeting Helpers
;; =====================================================

(defn cast-spell-with-targeting
  "Cast a spell, pausing for target selection if needed.

   For spells with :card/targeting:
   - Returns {:db db :pending-target-selection selection-state}
   - Spell does NOT go to stack yet
   - After target confirmed, call confirm-cast-time-target to complete

   For spells without targeting:
   - Returns {:db db :pending-target-selection nil}
   - Spell immediately goes to stack via rules/cast-spell

   Arguments:
     game-db - Datascript database
     player-id - Casting player
     object-id - Object to cast"
  [game-db player-id object-id]
  (let [obj (queries/get-object game-db object-id)
        card (:object/card obj)
        targeting-reqs (targeting/get-targeting-requirements card)
        modes (rules/get-casting-modes game-db player-id object-id)
        ;; Pick best mode (primary if available, else first)
        primary (first (filter #(= :primary (:mode/id %)) modes))
        mode (or primary (first modes))]
    (if (and (seq targeting-reqs)
             (rules/can-cast-mode? game-db player-id object-id mode))
      ;; Has targeting - pause for target selection
      (let [first-req (first targeting-reqs)
            selection (build-cast-time-target-selection game-db player-id object-id mode first-req)]
        {:db game-db
         :pending-target-selection selection})
      ;; No targeting - cast normally
      {:db (if (rules/can-cast? game-db player-id object-id)
             (rules/cast-spell game-db player-id object-id)
             game-db)
       :pending-target-selection nil})))


(defn confirm-cast-time-target
  "Complete casting a spell after target selection.

   1. Pays mana and additional costs
   2. Moves spell to stack
   3. Stores the selected target on the object as :object/targets

   Arguments:
     game-db - Datascript database
     selection - Cast-time target selection state with :selection/selected set

   Returns updated db with spell on stack and target stored."
  [game-db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        mode (:selection/mode selection)
        target-req (:selection/target-requirement selection)
        selected-target (first (:selection/selected selection))
        target-id (:target/id target-req)]
    (if selected-target
      ;; Cast spell and store target
      (let [;; Cast via rules/cast-spell-mode (pays costs, moves to stack)
            db-after-cast (rules/cast-spell-mode game-db player-id object-id mode)
            ;; Store the target on the object
            obj-eid (d/q '[:find ?e .
                           :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-after-cast object-id)
            db-with-target (d/db-with db-after-cast
                                      [[:db/add obj-eid :object/targets {target-id selected-target}]])]
        db-with-target)
      ;; No target selected - return unchanged
      game-db)))


;; =====================================================
;; Builder Multimethod Registration
;; =====================================================

(defmethod core/build-selection-for-effect :player-target
  [_db player-id object-id effect remaining]
  (build-player-target-selection player-id object-id effect remaining))


;; =====================================================
;; Confirm Selection Multimethod
;; =====================================================

(defmethod core/execute-confirmed-selection :player-target
  [game-db selection]
  (let [selected-target (first (:selection/selected selection))
        target-effect (:selection/target-effect selection)
        remaining-effects (vec (or (:selection/remaining-effects selection) []))
        player-id (:selection/player-id selection)
        source-type (:selection/source-type selection)
        spell-id (:selection/spell-id selection)
        ;; Replace :any-player with selected target
        resolved-effect (assoc target-effect :effect/target selected-target)
        db-after-effect (effects/execute-effect game-db player-id resolved-effect)
        ;; Check if remaining effects need selection
        next-selection-idx (resolution/find-selection-effect-index remaining-effects)]
    (if next-selection-idx
      ;; Chain to next selection (e.g., discard after draw)
      (let [[effects-before-next next-selection-effect effects-after-next]
            (resolution/split-effects-around-index remaining-effects next-selection-idx)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d player-id effect))
                                    db-after-effect
                                    effects-before-next)
            next-selection (when (= :player (:effect/selection next-selection-effect))
                             (cond-> {:selection/type :discard
                                      :selection/player-id selected-target
                                      :selection/select-count (:effect/count next-selection-effect)
                                      :selection/selected #{}
                                      :selection/source-type source-type
                                      :selection/remaining-effects effects-after-next}
                               spell-id (assoc :selection/spell-id spell-id)
                               (:selection/stack-item-eid selection)
                               (assoc :selection/stack-item-eid (:selection/stack-item-eid selection))))]
        {:db db-after-before :pending-selection next-selection})
      ;; No more selections - execute all remaining (wrapper will handle cleanup)
      {:db (reduce (fn [d effect]
                     (effects/execute-effect d player-id effect))
                   db-after-effect
                   remaining-effects)})))


(defmethod core/execute-confirmed-selection :cast-time-targeting
  [game-db selection]
  {:db (confirm-cast-time-target game-db selection)
   :finalized? true})
