(ns fizzle.engine.triggers
  "Trigger system for Fizzle.

   Triggers are objects on the stack representing triggered abilities.
   They resolve before the spell that created them (LIFO stack order).

   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]))


(defn create-trigger
  "Create a trigger object (not yet on stack).

   Arguments:
     trigger-type  - Keyword identifying the trigger (e.g. :storm, :etb)
     source-id     - ID of the source object that created this trigger
     controller-id - Player ID who controls this trigger
     data          - Map of trigger-specific data (may include :description)

   Returns:
     Map with :trigger/id, :trigger/type, :trigger/source,
     :trigger/controller, :trigger/data, :trigger/description"
  [trigger-type source-id controller-id data]
  (let [base-trigger {:trigger/id (random-uuid)
                      :trigger/type trigger-type
                      :trigger/source source-id
                      :trigger/controller controller-id
                      :trigger/data data}]
    ;; Only include :trigger/description if present in data
    (if (:description data)
      (assoc base-trigger :trigger/description (:description data))
      base-trigger)))


(defn add-trigger-to-stack
  "Add a trigger to the stack.

   Validates that source is not nil before transacting.
   Returns unchanged db if source is nil.

   Arguments:
     db      - Datascript database value
     trigger - Trigger map from create-trigger

   Returns:
     New db with trigger on stack, or same db if invalid."
  [db trigger]
  (if (nil? (:trigger/source trigger))
    db  ; Invalid trigger, return unchanged
    (let [next-order (q/get-next-stack-order db)
          trigger-with-order (assoc trigger :trigger/stack-order next-order)]
      (d/db-with db [trigger-with-order]))))


(defmulti resolve-trigger
  "Resolve a trigger on the stack.

   Dispatches on (:trigger/type trigger).
   Each trigger type should implement its own resolution logic.

   Arguments:
     db      - Datascript database value
     trigger - Trigger map

   Returns:
     New db after trigger resolution."
  (fn [_db trigger] (:trigger/type trigger)))


(defmethod resolve-trigger :default
  [db _trigger]
  db)


(defmethod resolve-trigger :permanent-tapped [db trigger]
  "Resolve a :permanent-tapped trigger from the event-based system.

   Executes effects stored in :trigger/data :effects.
   Resolves :controller target to actual player-id.

   This is the event-based replacement for :becomes-tapped."
  (let [controller (:trigger/controller trigger)
        effects-list (get-in trigger [:trigger/data :effects] [])]
    (reduce (fn [db' effect]
              ;; Resolve :controller target to actual player-id
              (let [resolved-effect (if (= :controller (:effect/target effect))
                                      (assoc effect :effect/target controller)
                                      effect)]
                (effects/execute-effect db' controller resolved-effect)))
            db
            effects-list)))


(defmethod resolve-trigger :land-entered [db trigger]
  "Resolve a :land-entered trigger from the event-based system.

   Executes effects stored in :trigger/data :effects.
   Resolves :self target to trigger source, :controller to player-id.

   Used by City of Traitors sacrifice trigger."
  (let [controller (:trigger/controller trigger)
        source (:trigger/source trigger)
        effects-list (get-in trigger [:trigger/data :effects] [])]
    (reduce (fn [db' effect]
              ;; Resolve :self target to source object, :controller to player-id
              (let [resolved-effect (cond
                                      (= :self (:effect/target effect))
                                      (assoc effect :effect/target source)

                                      (= :controller (:effect/target effect))
                                      (assoc effect :effect/target controller)

                                      :else effect)]
                (effects/execute-effect db' controller resolved-effect)))
            db
            effects-list)))


(defmethod resolve-trigger :activated-ability [db trigger]
  "Resolve an :activated-ability trigger from the stack.

   Executes effects stored in :trigger/data :effects.

   Note: This method only handles abilities WITHOUT selection effects.
   Abilities with selection effects (like tutors) are handled by
   resolve-activated-ability-with-selection in game.cljs.

   Used by non-mana activated abilities on permanents."
  (let [controller (:trigger/controller trigger)
        source (:trigger/source trigger)
        effects-list (get-in trigger [:trigger/data :effects] [])]
    (reduce (fn [db' effect]
              ;; Resolve :self target to source object, :controller to player-id
              (let [resolved-effect (cond
                                      (= :self (:effect/target effect))
                                      (assoc effect :effect/target source)

                                      (= :controller (:effect/target effect))
                                      (assoc effect :effect/target controller)

                                      :else effect)]
                (effects/execute-effect db' controller resolved-effect)))
            db
            effects-list)))


(defn create-spell-copy
  "Create a copy of a spell on the stack.

   The copy inherits the card reference from the source object but has
   :object/is-copy set to true. Copies are not cast (don't trigger storm).

   Arguments:
     db               - Datascript database value
     source-object-id - ID of the object to copy
     controller-id    - Player ID who controls this copy

   Returns:
     New db with copy on stack, or unchanged db if source not found."
  [db source-object-id controller-id]
  (if-let [source-obj (q/get-object db source-object-id)]
    (let [;; Get owner/controller entity IDs
          owner-eid (:db/id (:object/owner source-obj))
          controller-eid (q/get-player-eid db controller-id)
          card-eid (:db/id (:object/card source-obj))
          stack-order (q/get-next-stack-order db)]
      (d/db-with db [{:object/id (random-uuid)
                      :object/card card-eid
                      :object/zone :stack
                      :object/owner owner-eid
                      :object/controller controller-eid
                      :object/tapped false
                      :object/is-copy true
                      :object/position stack-order}]))
    db))


(defmethod resolve-trigger :storm
  [db trigger]
  "Resolve a storm trigger.

   Creates N copies of the source spell on the stack, where N is stored
   in (:trigger/data trigger) as :count.

   If source object no longer exists, returns db unchanged (defensive)."
  (let [source-id (:trigger/source trigger)
        controller-id (:trigger/controller trigger)
        copy-count (get-in trigger [:trigger/data :count] 0)]
    ;; Check if source still exists
    (if (q/get-object db source-id)
      ;; Create copies one at a time
      (loop [db' db
             remaining copy-count]
        (if (pos? remaining)
          (recur (create-spell-copy db' source-id controller-id)
                 (dec remaining))
          db'))
      ;; Source missing, return unchanged
      db)))


;; === Turn-Based Action Triggers ===

(defn- untap-all-permanents
  "Untap all permanents controlled by a player on the battlefield.
   Pure function: (db, player-id) -> db"
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        ;; Find all tapped permanents controlled by player on battlefield
        tapped-permanents (d/q '[:find ?e
                                 :in $ ?controller
                                 :where [?e :object/controller ?controller]
                                 [?e :object/zone :battlefield]
                                 [?e :object/tapped true]]
                               db player-eid)]
    (if (seq tapped-permanents)
      (d/db-with db (mapv (fn [[eid]] [:db/add eid :object/tapped false])
                          tapped-permanents))
      db)))


(defmethod resolve-trigger :draw-step
  [db trigger]
  (let [game-state (q/get-game-state db)
        turn (:game/turn game-state)
        active-player (:trigger/controller trigger)]
    ;; Skip draw on turn 1 (MTG rules: play/draw rule)
    (if (and turn (> turn 1))
      (effects/execute-effect db active-player {:effect/type :draw :effect/amount 1})
      db)))


(defmethod resolve-trigger :untap-step
  [db trigger]
  (let [active-player (:trigger/controller trigger)]
    (untap-all-permanents db active-player)))


(defmethod resolve-trigger :expire-grants
  [db _trigger]
  "Expire all grants that have reached their expiration turn/phase.

   Called during cleanup phase to remove 'until end of turn' effects.
   Uses current turn from game state and :cleanup phase."
  (let [game-state (q/get-game-state db)
        current-turn (:game/turn game-state)]
    (grants/expire-grants db current-turn :cleanup)))


(defn remove-trigger
  "Remove a trigger from the database.

   Arguments:
     db         - Datascript database value
     trigger-id - The :trigger/id of the trigger to remove

   Returns:
     New db with trigger entity retracted."
  [db trigger-id]
  (if-let [eid (d/q '[:find ?e .
                      :in $ ?tid
                      :where [?e :trigger/id ?tid]]
                    db trigger-id)]
    (d/db-with db [[:db/retractEntity eid]])
    db))
