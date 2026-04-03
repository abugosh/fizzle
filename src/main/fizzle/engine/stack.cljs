(ns fizzle.engine.stack
  "Unified stack operations for Fizzle.

   Stack items represent anything on the stack awaiting resolution:
   spells, triggers, activated abilities, and storm copies.

   All functions are pure: (db, args) -> db or value."
  (:require
    [datascript.core :as d]
    [fizzle.engine.stack-spec :as stack-spec]))


(defn get-next-stack-order
  "Get the next stack order value.
   Checks stack-items and objects on stack so they share a unified counter space.
   Returns current max + 1, or 0 if stack is empty."
  [db]
  (let [stack-item-max (d/q '[:find (max ?pos) .
                              :where [?e :stack-item/position ?pos]]
                            db)
        object-max (d/q '[:find (max ?pos) .
                          :where [?o :object/zone :stack]
                          [?o :object/position ?pos]]
                        db)
        current-max (max (or stack-item-max -1)
                         (or object-max -1))]
    (inc current-max)))


(defn create-stack-item
  "Create a stack-item entity in Datascript.

   attrs is a map with required keys: :stack-item/type, :stack-item/controller
   Optional keys: :stack-item/source, :stack-item/effects, :stack-item/targets,
                  :stack-item/description, :stack-item/is-copy, :stack-item/cast-mode,
                  :stack-item/object-ref

   Auto-assigns :stack-item/position. Caller MUST NOT set position.
   Returns updated db."
  [db attrs]
  (stack-spec/validate-stack-item! attrs)
  (d/db-with db [(merge attrs {:stack-item/position (get-next-stack-order db)})]))


(defn remove-stack-item
  "Remove a stack-item entity from the database.
   Returns updated db. Safe to call with non-existent eid."
  [db stack-item-eid]
  (d/db-with db [[:db.fn/retractEntity stack-item-eid]]))


(defn get-top-stack-item
  "Get the stack-item with the highest position (top of stack).
   Returns entity map or nil if stack is empty."
  [db]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :stack-item/position _]]
            db)
       (sort-by :stack-item/position >)
       (first)))


(defn get-stack-item-by-object-ref
  "Find the stack-item that references a given game object EID.
   Returns entity map or nil."
  [db object-eid]
  (when-let [result (d/q '[:find (pull ?e [:db/id :stack-item/type :stack-item/controller
                                           :stack-item/position :stack-item/source :stack-item/effects
                                           :stack-item/targets :stack-item/description :stack-item/is-copy :stack-item/cast-mode
                                           :stack-item/chosen-x :stack-item/object-ref]) .
                           :in $ ?obj
                           :where [?e :stack-item/object-ref ?obj]]
                         db object-eid)]
    ;; Convert the nested entity map reference back to just the EID
    (if-let [obj-ref (:stack-item/object-ref result)]
      (assoc result :stack-item/object-ref (:db/id obj-ref))
      result)))


(defn resolve-effect-target
  "Resolve symbolic targets in an effect to concrete entity/player IDs.

   Target resolution precedence:
   1. :effect/target-ref - look up in stored-targets map (cast-time targeting)
   2. :self - replace with source-id
   3. :controller - replace with controller
   4. :any-player - replace with stored player target
   5. anything else - pass through unchanged

   Also resolves :effect/graveyard-ref (secondary target ref for welder-swap and
   similar multi-target effects) from stored-targets."
  [effect source-id controller stored-targets]
  (let [target-ref (:effect/target-ref effect)
        resolved-target (when target-ref (get stored-targets target-ref))
        ;; Resolve secondary ref (e.g., :welder-gy for welder-swap)
        gy-ref (:effect/graveyard-ref effect)
        resolved-gy (when gy-ref (get stored-targets gy-ref))
        effect' (if resolved-gy
                  (assoc effect :effect/graveyard-id resolved-gy)
                  effect)]
    (cond
      resolved-target
      (assoc effect' :effect/target resolved-target)

      (= :self (:effect/target effect))
      (assoc effect' :effect/target source-id)

      (= :controller (:effect/target effect))
      (assoc effect' :effect/target controller)

      (= :any-player (:effect/target effect))
      (if-let [p (:player stored-targets)]
        (assoc effect' :effect/target p)
        effect')

      :else effect')))
