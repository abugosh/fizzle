(ns fizzle.engine.trigger-dispatch
  "Event dispatch and trigger matching.

   Handles both immediate (turn-based) and stacked (card) triggers.
   Order: immediate triggers first, then stacked triggers."
  (:require
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.triggers :as triggers]))


(defn matches-filter?
  "Check if event matches trigger's filter criteria.

   :self in filter value matches trigger's source object.
   :exclude-self true means trigger source must NOT match event object.
   Returns true if no filter (matches all events of type).

   Arguments:
     trigger - Trigger map with optional :trigger/filter
     event   - Event map to match against

   Returns:
     Boolean - true if event matches filter"
  [trigger event]
  (let [f (:trigger/filter trigger)]
    (if (and f (seq f))
      (every? (fn [[k v]]
                (cond
                  ;; :self in value means trigger's source must match event's key
                  (= v :self) (= (get event k) (:trigger/source trigger))
                  ;; :exclude-self true means trigger source must NOT match event object
                  (= k :exclude-self) (not= (:event/object-id event) (:trigger/source trigger))
                  :else (= (get event k) v)))
              f)
      true)))


(defn- registry-trigger->stack-trigger
  "Convert a registry trigger to stack trigger format.

   Registry triggers have :trigger/event-type and :trigger/effects.
   Stack triggers need :trigger/type and :trigger/data {:effects ...}.

   Arguments:
     registry-trigger - Trigger from the registry

   Returns:
     Trigger formatted for the stack (nil values filtered out)"
  [registry-trigger]
  (let [effects (or (:trigger/effects registry-trigger) [])
        base {:trigger/id (:trigger/id registry-trigger)
              :trigger/type (:trigger/event-type registry-trigger)
              :trigger/source (:trigger/source registry-trigger)
              :trigger/controller (:trigger/controller registry-trigger)
              :trigger/data {:effects effects}}]
    ;; Only add description if present (Datascript doesn't allow nil values)
    (if-let [desc (:trigger/description registry-trigger)]
      (assoc base :trigger/description desc)
      base)))


(defn dispatch-event
  "Dispatch event to all matching triggers. Returns new db.

   Turn-based actions (uses-stack? false) execute immediately.
   Card triggers (uses-stack? true) go on stack.
   Order: immediate triggers first, then stacked triggers.

   Arguments:
     db    - Datascript database value
     event - Event map to dispatch

   Returns:
     New db after all triggers processed"
  [db event]
  (let [all-triggers (registry/get-triggers-for-event event)
        matching (filter #(matches-filter? % event) all-triggers)
        ;; Group by uses-stack? - note: false = immediate, true = stacked
        ;; Default to true (card triggers use stack) if not specified
        {stacked true, immediate false} (group-by #(get % :trigger/uses-stack? true) matching)]
    (as-> db db'
          ;; Execute immediate triggers first (turn-based actions)
          ;; Immediate triggers use registry format (already have :trigger/type from turn-based)
          (reduce (fn [d t] (triggers/resolve-trigger d t)) db' (or immediate []))
          ;; Add stacked triggers to stack (card triggers)
          ;; Convert registry format to stack format
          (reduce (fn [d t]
                    (triggers/add-trigger-to-stack d (registry-trigger->stack-trigger t)))
                  db'
                  (or stacked [])))))
