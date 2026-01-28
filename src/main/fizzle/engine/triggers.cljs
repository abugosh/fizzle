(ns fizzle.engine.triggers
  "Trigger system for Fizzle.

   Triggers are objects on the stack representing triggered abilities.
   They resolve before the spell that created them (LIFO stack order).

   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


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
    (let [next-order (q/get-next-stack-order db)]
      (d/db-with db [(assoc trigger :trigger/stack-order next-order)]))))


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
