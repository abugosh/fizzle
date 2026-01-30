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
                  (= v :self) (= (get event k) (:trigger/source trigger))
                  :else (= (get event k) v)))
              f)
      true)))


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
          (reduce (fn [d t] (triggers/resolve-trigger d t)) db' (or immediate []))
          ;; Add stacked triggers to stack (card triggers)
          (reduce (fn [d t] (triggers/add-trigger-to-stack d t)) db' (or stacked [])))))
