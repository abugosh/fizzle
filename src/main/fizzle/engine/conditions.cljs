(ns fizzle.engine.conditions
  "Unified condition checking for Fizzle.

   All condition checks dispatch through check-condition multimethod.
   Conditions are used by effects (:effect/condition) and abilities (:ability/condition).
   All functions are pure: (db, player-id, condition) -> boolean"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


(defn threshold?
  "Check if a player has threshold (7+ cards in their graveyard).

   Arguments:
     db - Datascript database value
     player-id - The player to check

   Returns:
     true if player has 7 or more cards in graveyard, false otherwise."
  [db player-id]
  (let [graveyard (q/get-objects-in-zone db player-id :graveyard)
        count (or (count graveyard) 0)]
    (>= count 7)))


(defn check-condition
  "Check if a condition is met. Dispatches on :condition/type.

   Arguments:
     db - Datascript database value
     player-id - The player to check
     condition - Map with :condition/type and condition-specific keys, or nil

   Callers must enrich condition with runtime context before calling:
   - :no-counters requires :condition/target (object-id of the target)

   Returns true if condition met, false otherwise.
   nil condition = no condition = always met.
   Unknown condition types return false (fail closed)."
  [db player-id condition]
  (if (nil? condition)
    true
    (case (:condition/type condition)
      :threshold (threshold? db player-id)
      :no-counters
      (let [target-id (:condition/target condition)
            counter-type (:condition/counter-type condition)]
        (if-let [obj-eid (q/get-object-eid db target-id)]
          (let [counters (or (d/q '[:find ?c .
                                    :in $ ?e
                                    :where [?e :object/counters ?c]]
                                  db obj-eid)
                             {})
                count-value (get counters counter-type 0)]
            (<= count-value 0))
          ;; Object doesn't exist - condition met (vacuously true)
          true))
      ;; Unknown condition types fail closed
      false)))
