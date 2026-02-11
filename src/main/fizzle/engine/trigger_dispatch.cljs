(ns fizzle.engine.trigger-dispatch
  "Event dispatch and trigger matching.

   Handles both immediate (turn-based) and stacked (card) triggers.
   Order: immediate triggers first, then stacked triggers.

   Reads triggers from Datascript first (primary). Falls back to atom
   registry when Datascript returns empty (backward compat for tests)."
  (:require
    [datascript.core :as d]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-db :as trigger-db]
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


(defn- enrich-event-ids
  "Convert event UUIDs to entity IDs for Datascript filter matching.
   Events use UUIDs (:event/object-id is a UUID), but Datascript triggers
   use entity IDs for :trigger/source. This bridges the impedance mismatch."
  [db event]
  (cond-> event
    (:event/object-id event)
    (assoc :event/object-id
           (d/q '[:find ?e .
                  :in $ ?oid
                  :where [?e :object/id ?oid]]
                db (:event/object-id event)))))


(defn- datascript-trigger->dispatch-format
  "Convert a pulled Datascript trigger entity to dispatch-compatible format.
   Resolves ref values ({:db/id N}) to the values the rest of the system expects:
   - :trigger/controller -> player keyword (:player-1) via :player/id lookup
   - :trigger/source -> object UUID via :object/id lookup"
  [db trigger]
  (let [controller-ref (:trigger/controller trigger)
        source-ref (:trigger/source trigger)
        controller-eid (if (map? controller-ref) (:db/id controller-ref) controller-ref)
        source-eid (if (map? source-ref) (:db/id source-ref) source-ref)]
    (cond-> trigger
      controller-eid
      (assoc :trigger/controller
             (d/q '[:find ?pid .
                    :in $ ?e
                    :where [?e :player/id ?pid]]
                  db controller-eid))
      source-eid
      (assoc :trigger/source
             (d/q '[:find ?oid .
                    :in $ ?e
                    :where [?e :object/id ?oid]]
                  db source-eid)))))


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

   Queries Datascript first (primary path). Falls back to atom registry
   when Datascript returns empty results (backward compat for tests).

   Turn-based actions (uses-stack? false) execute immediately.
   Card triggers (uses-stack? true) go on stack.
   Order: immediate triggers first, then stacked triggers.

   Arguments:
     db    - Datascript database value
     event - Event map to dispatch

   Returns:
     New db after all triggers processed"
  [db event]
  (let [;; Try Datascript first (enriched event for entity ID matching)
        enriched-event (enrich-event-ids db event)
        ds-triggers (trigger-db/get-triggers-for-event db enriched-event)
        ;; If Datascript has triggers, use them (convert format).
        ;; Otherwise, fall back to atom (backward compat for tests).
        matching (if (seq ds-triggers)
                   (mapv #(datascript-trigger->dispatch-format db %) ds-triggers)
                   (filter #(matches-filter? % event)
                           (registry/get-triggers-for-event event)))
        ;; Group by uses-stack? - note: false = immediate, true = stacked
        ;; Default to true (card triggers use stack) if not specified
        {stacked true, immediate false} (group-by #(get % :trigger/uses-stack? true) matching)]
    (as-> db db'
          ;; Execute immediate triggers first (turn-based actions)
          ;; Immediate triggers use registry format (already have :trigger/type from turn-based)
          (reduce (fn [d t] (triggers/resolve-trigger d t)) db' (or immediate []))
          ;; Add stacked triggers to stack as stack-items (card triggers)
          (reduce (fn [d t]
                    (let [st (registry-trigger->stack-trigger t)]
                      (stack/create-stack-item d
                                               (cond-> {:stack-item/type (:trigger/type st)
                                                        :stack-item/controller (:trigger/controller st)
                                                        :stack-item/source (:trigger/source st)
                                                        :stack-item/effects (get-in st [:trigger/data :effects] [])}
                                                 (:trigger/description st)
                                                 (assoc :stack-item/description (:trigger/description st))))))
                  db'
                  (or stacked [])))))
