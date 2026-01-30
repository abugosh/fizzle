(ns fizzle.engine.trigger-registry
  "Dynamic trigger registration for event-driven trigger system.

   Triggers register when cards enter active zones, unregister when leaving.
   Uses atom-based registry similar to state-based actions pattern.")


;; Registry: {trigger-id -> trigger-def}
(defonce registry (atom {}))


(defn register-trigger!
  "Register a trigger. Idempotent - registering same ID overwrites.

   Arguments:
     trigger - Map with :trigger/id and other trigger fields

   Returns:
     The trigger-id"
  [trigger]
  (let [id (:trigger/id trigger)]
    (swap! registry assoc id trigger)
    id))


(defn unregister-trigger!
  "Remove a trigger by ID. No-op if ID doesn't exist.

   Arguments:
     trigger-id - The :trigger/id to remove

   Returns:
     nil"
  [trigger-id]
  (swap! registry dissoc trigger-id)
  nil)


(defn unregister-by-source!
  "Remove all triggers from a source (when card leaves zone).
   No-op if source has no triggers.

   Arguments:
     source-id - The :trigger/source to match

   Returns:
     nil"
  [source-id]
  (swap! registry
         (fn [r]
           (->> r
                (remove (fn [[_ t]] (= source-id (:trigger/source t))))
                (into {}))))
  nil)


(defn get-triggers-for-event
  "Find all registered triggers that match this event type.

   Arguments:
     event - Event map with :event/type

   Returns:
     Seq of matching triggers (empty seq if no matches)"
  [event]
  (->> @registry
       vals
       (filter #(= (:event/type event) (:trigger/event-type %)))))


(defn get-all-triggers
  "Return all registered triggers. For debugging/testing.

   Returns:
     Seq of all trigger maps"
  []
  (vals @registry))


(defn clear-registry!
  "Remove all triggers. Use in tests and game reset.

   Returns:
     nil"
  []
  (reset! registry {})
  nil)
