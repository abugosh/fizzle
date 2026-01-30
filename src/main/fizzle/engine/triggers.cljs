(ns fizzle.engine.triggers
  "Trigger system for Fizzle.

   Triggers are objects on the stack representing triggered abilities.
   They resolve before the spell that created them (LIFO stack order).

   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]))


(defn create-trigger
  "Create a trigger object (not yet on stack).

   Arguments:
     trigger-type  - Keyword identifying the trigger (e.g. :storm, :etb)
     source-id     - ID of the source object that created this trigger
     controller-id - Player ID who controls this trigger
     data          - Map of trigger-specific data

   Returns:
     Map with :trigger/id, :trigger/type, :trigger/source,
     :trigger/controller, :trigger/data"
  [trigger-type source-id controller-id data]
  {:trigger/id (random-uuid)
   :trigger/type trigger-type
   :trigger/source source-id
   :trigger/controller controller-id
   :trigger/data data})


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


(defmulti check-trigger
  "Check if a trigger condition is met.

   Arguments:
     old-db  - Database state before action
     new-db  - Database state after action
     trigger - Trigger definition from card data

   Returns:
     Boolean - true if trigger should fire."
  (fn [_old-db _new-db trigger] (:trigger/type trigger)))


(defmethod check-trigger :default [_old-db _new-db _trigger] false)


(defmethod check-trigger :becomes-tapped
  [old-db new-db trigger]
  (let [source-id (:trigger/source trigger)]
    (if-let [new-obj (q/get-object new-db source-id)]
      (let [old-obj (q/get-object old-db source-id)
            was-tapped (get old-obj :object/tapped false)
            now-tapped (get new-obj :object/tapped false)]
        ;; Trigger fires only on transition: untapped -> tapped
        (and (not was-tapped) now-tapped))
      false)))


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


(defmethod resolve-trigger :becomes-tapped
  [db trigger]
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


(defn- get-battlefield-objects
  "Get all objects on the battlefield with their card and controller data."
  [db]
  (d/q '[:find [(pull ?obj [* {:object/card [*]} {:object/controller [:player/id]}]) ...]
         :where [?obj :object/zone :battlefield]]
       db))


(defn fire-matching-triggers
  "Check all triggers on battlefield and fire those whose conditions are met.

   Arguments:
     old-db - Database state before action
     new-db - Database state after action

   Returns:
     New db with matching triggers added to stack."
  [old-db new-db]
  (let [;; Get all objects on battlefield
        battlefield (get-battlefield-objects new-db)
        ;; Collect matching triggers
        matching-triggers
        (for [obj battlefield
              :let [card (:object/card obj)
                    triggers (:card/triggers card)]
              :when triggers
              trigger triggers
              :let [trigger-with-source (assoc trigger :trigger/source (:object/id obj))]
              :when (check-trigger old-db new-db trigger-with-source)]
          {:trigger trigger-with-source
           :controller (:player/id (:object/controller obj))})]
    ;; Add all matching triggers to stack
    (reduce (fn [db {:keys [trigger controller]}]
              (let [full-trigger (create-trigger
                                   (:trigger/type trigger)
                                   (:trigger/source trigger)
                                   controller
                                   {:effects (:trigger/effects trigger)})]
                (add-trigger-to-stack db full-trigger)))
            new-db
            matching-triggers)))


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


(defn fire-and-resolve-triggers
  "Fire matching triggers and resolve them immediately.

   Use this for mana abilities where triggers don't use the stack.
   For spell-based triggers that use the stack, use fire-matching-triggers.

   Arguments:
     old-db - Database state before action
     new-db - Database state after action

   Returns:
     New db with all matching triggers resolved."
  [old-db new-db]
  (let [;; Get all objects on battlefield
        battlefield (get-battlefield-objects new-db)
        ;; Collect matching triggers
        matching-triggers
        (for [obj battlefield
              :let [card (:object/card obj)
                    triggers (:card/triggers card)]
              :when triggers
              trigger triggers
              :let [trigger-with-source (assoc trigger :trigger/source (:object/id obj))]
              :when (check-trigger old-db new-db trigger-with-source)]
          {:trigger trigger-with-source
           :controller (:player/id (:object/controller obj))})]
    ;; Resolve each trigger immediately (don't add to stack)
    (reduce (fn [db {:keys [trigger controller]}]
              (let [full-trigger (create-trigger
                                   (:trigger/type trigger)
                                   (:trigger/source trigger)
                                   controller
                                   {:effects (:trigger/effects trigger)})]
                (resolve-trigger db full-trigger)))
            new-db
            matching-triggers)))
