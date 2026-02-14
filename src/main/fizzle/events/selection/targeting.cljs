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
    [fizzle.events.selection.costs :as sel-costs]))


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
    (cond->
      {:selection/type :cast-time-targeting
       :selection/player-id player-id
       :selection/object-id object-id
       :selection/mode mode
       :selection/target-requirement target-req
       :selection/valid-targets valid-targets
       :selection/selected #{}
       :selection/select-count 1
       :selection/validation :exact
       :selection/auto-confirm? true}
      (= :object (:target/type target-req))
      (assoc :selection/card-source :valid-targets))))


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
   3. Stores the selected target on the stack-item as :stack-item/targets

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
      ;; Cast spell and store target on stack-item
      (let [;; Cast via rules/cast-spell-mode (pays costs, moves to stack)
            db-after-cast (rules/cast-spell-mode game-db player-id object-id mode)
            ;; Find object EID to locate stack-item
            obj-eid (d/q '[:find ?e .
                           :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-after-cast object-id)
            ;; Find the stack-item by object reference
            stack-item (when obj-eid
                         (d/q '[:find (pull ?e [:db/id]) .
                                :in $ ?obj-eid
                                :where [?e :stack-item/object-ref ?obj-eid]]
                              db-after-cast obj-eid))
            stack-item-eid (:db/id stack-item)]
        ;; Store targets on stack-item
        (if stack-item-eid
          (d/db-with db-after-cast
                     [[:db/add stack-item-eid :stack-item/targets {target-id selected-target}]])
          db-after-cast))
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
        ;; Execute remaining effects via reduce-effects — automatically detects
        ;; interactive effects via tagged return values from execute-effect-impl
        result (effects/reduce-effects db-after-effect player-id remaining-effects)]
    (if (:needs-selection result)
      ;; Chain to next selection (e.g., discard after draw)
      (let [sel-effect (:needs-selection result)
            next-selection (when (= :player (:effect/selection sel-effect))
                             (cond-> {:selection/type :discard
                                      :selection/card-source :hand
                                      :selection/player-id selected-target
                                      :selection/select-count (:effect/count sel-effect)
                                      :selection/selected #{}
                                      :selection/source-type source-type
                                      :selection/remaining-effects (vec (:remaining-effects result))}
                               spell-id (assoc :selection/spell-id spell-id)
                               (:selection/stack-item-eid selection)
                               (assoc :selection/stack-item-eid (:selection/stack-item-eid selection))))]
        {:db (:db result) :pending-selection next-selection})
      ;; No more selections - all remaining executed (wrapper will handle cleanup)
      {:db (:db result)})))


(defmethod core/execute-confirmed-selection :cast-time-targeting
  [game-db selection]
  (let [mode (:selection/mode selection)
        cost (:mode/mana-cost mode)]
    (if (sel-costs/has-generic-mana-cost? cost)
      ;; Chain to mana allocation, carry target info
      (let [player-id (:selection/player-id selection)
            object-id (:selection/object-id selection)
            target-req (:selection/target-requirement selection)
            selected-target (first (:selection/selected selection))
            target-id (:target/id target-req)
            sel (sel-costs/build-mana-allocation-selection game-db player-id object-id mode cost)]
        (if sel
          {:db game-db
           :pending-selection (assoc sel :selection/pending-targets {target-id selected-target})}
          ;; Defensive: builder returned nil
          {:db (confirm-cast-time-target game-db selection) :finalized? true}))
      ;; No generic: cast directly with target storage
      {:db (confirm-cast-time-target game-db selection) :finalized? true})))
