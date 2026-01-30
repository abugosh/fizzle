(ns fizzle.engine.state-based
  "State-based actions for Fizzle.

   SBAs are checked after every game action. Uses registry pattern
   for extensibility - add new SBA types without modifying existing code.

   All functions are pure: (db, args) -> db")


;; Registry of SBA check functions
;; Each check-fn: (db) -> seq of {:sba/type keyword :sba/target object-id}
(defonce sba-registry (atom {}))


(defn register-sba!
  "Register a state-based action check function.

   Arguments:
     sba-type - Keyword identifying the SBA (e.g. :zero-counters)
     check-fn - Function (db) -> seq of SBA events to execute

   Returns:
     nil (side effect: updates registry)"
  [sba-type check-fn]
  (swap! sba-registry assoc sba-type check-fn)
  nil)


(defn check-all-sbas
  "Run all registered SBA checks.

   Arguments:
     db - Datascript database value

   Returns:
     Seq of SBA events: {:sba/type keyword :sba/target object-id}"
  [db]
  (mapcat (fn [[_type check-fn]] (check-fn db))
          @sba-registry))


(defmulti execute-sba
  "Execute a single state-based action.

   Arguments:
     db - Datascript database value
     sba - Map with :sba/type and :sba/target

   Returns:
     New db after SBA executed."
  (fn [_db sba] (:sba/type sba)))


(defmethod execute-sba :default [db _sba] db)


(defn check-and-execute-sbas
  "Check all SBAs and execute any that apply.

   Runs checks repeatedly until no more SBAs fire (loop handles cascading SBAs).

   Arguments:
     db - Datascript database value

   Returns:
     New db after all SBAs executed."
  [db]
  (loop [db' db]
    (let [sbas (check-all-sbas db')]
      (if (empty? sbas)
        db'
        (recur (reduce execute-sba db' sbas))))))
