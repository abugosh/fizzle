(ns fizzle.engine.state-based
  "State-based actions for Fizzle.

   SBAs are checked after every game action. Uses multimethod pattern
   for extensibility - add new SBA types by adding defmethods.

   All functions are pure: (db, args) -> db")


(defmulti check-sba
  "Check for a specific type of state-based action.

   Arguments:
     db - Datascript database value
     sba-type - Keyword identifying the SBA type

   Returns:
     Seq of SBA events: {:sba/type keyword :sba/target object-id}
     Empty seq if no SBAs of this type apply."
  (fn [_db sba-type] sba-type))


(defmethod check-sba :default [_db _type] [])


(defn check-all-sbas
  "Run all registered SBA checks.

   Arguments:
     db - Datascript database value

   Returns:
     Seq of SBA events: {:sba/type keyword :sba/target object-id}"
  [db]
  (let [sba-types (keys (dissoc (methods check-sba) :default))]
    (mapcat #(check-sba db %) sba-types)))


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
