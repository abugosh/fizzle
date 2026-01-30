(ns fizzle.engine.state-based
  "State-based actions for Fizzle.

   SBAs are checked after every game action. Uses registry pattern
   for extensibility - add new SBA types without modifying existing code.

   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.engine.zones :as zones]))


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


;; === Zero Counters SBA (Gemstone Mine) ===

(defn- check-zero-counters
  "Find permanents that have counter requirements but 0 counters.

   Checks :object/counters map - if any counter type has 0 or negative value
   and the card requires counters (has :card/abilities with :remove-counter cost),
   the permanent should be sacrificed."
  [db]
  (let [battlefield-objects (d/q '[:find [(pull ?obj [* {:object/card [*]}]) ...]
                                   :where [?obj :object/zone :battlefield]]
                                 db)]
    (for [obj battlefield-objects
          :let [counters (:object/counters obj)
                card (:object/card obj)
                abilities (:card/abilities card)
                ;; Check if any ability has remove-counter cost
                has-counter-cost? (some #(get-in % [:ability/cost :remove-counter]) abilities)]
          ;; Only sacrifice if: has counter-using ability AND all counters depleted
          :when (and has-counter-cost?
                     counters
                     (every? (fn [[_type amt]] (<= amt 0)) counters))]
      {:sba/type :zero-counters
       :sba/target (:object/id obj)})))


(defmethod execute-sba :zero-counters
  [db sba]
  (let [object-id (:sba/target sba)]
    ;; Move to graveyard (sacrifice)
    (zones/move-to-zone db object-id :graveyard)))


;; Register built-in SBAs
(register-sba! :zero-counters check-zero-counters)


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
