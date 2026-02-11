(ns fizzle.engine.trigger-db
  "Trigger entities in Datascript. Pure query and transaction functions.
   No mutable state -- all triggers are immutable DB values."
  (:require
    [datascript.core :as d]))


(def trigger-type->event-type
  "Map card trigger type to event type.
   E.g., :becomes-tapped in card data becomes :permanent-tapped event."
  {:becomes-tapped :permanent-tapped
   :land-entered :land-entered})


(defn- ref->eid
  "Unwrap a Datascript ref value to its entity ID.
   Pulled refs come back as {:db/id N} — this returns N.
   If already a raw value, returns it unchanged."
  [v]
  (if (map? v) (:db/id v) v))


(defn- matches-filter?
  "Check if event matches trigger's filter criteria.

   :self in filter value matches trigger's source entity ID.
   :exclude-self true means trigger source must NOT match event object.
   Returns true if no filter (matches all events of type).

   Arguments:
     trigger - Trigger entity map with optional :trigger/filter and :trigger/source
     event   - Event map to match against

   Returns:
     Boolean - true if event matches filter"
  [trigger event]
  (let [f (:trigger/filter trigger)
        source (ref->eid (:trigger/source trigger))]
    (if (and f (seq f))
      (every? (fn [[k v]]
                (cond
                  (= v :self) (= (get event k) source)
                  (= k :exclude-self) (not= (:event/object-id event) source)
                  :else (= (get event k) v)))
              f)
      true)))


(defn create-trigger-tx
  "Create tx-data for a trigger entity. Returns vector of tx-data maps.

   Filters out nil values (Datascript rejects nil).
   Defaults :trigger/uses-stack? to true if not specified.

   Arguments:
     trigger-map - Map with :trigger/* keys

   Returns:
     Vector of one tx-data map"
  [trigger-map]
  (let [with-default (if (contains? trigger-map :trigger/uses-stack?)
                       trigger-map
                       (assoc trigger-map :trigger/uses-stack? true))
        cleaned (into {} (remove (fn [[_ v]] (nil? v))) with-default)]
    [cleaned]))


(defn create-triggers-for-card-tx
  "Create tx-data for card triggers, linking to source object as components.

   Maps :trigger/type to :trigger/event-type.
   Applies default filter {:event/object-id :self} when no filter specified.
   Uses nested tx-data so Datascript creates child entities and links automatically.

   Arguments:
     db             - Datascript db value (unused, reserved for future)
     object-eid     - Entity ID of the source object
     player-eid     - Entity ID of the controlling player
     card-triggers  - Vector of card trigger definitions from :card/triggers

   Returns:
     Vector of tx-data (object update with nested trigger entities)"
  [_db object-eid player-eid card-triggers]
  (let [trigger-entities
        (mapv (fn [ct]
                (let [trigger-type (:trigger/type ct)
                      event-type (get trigger-type->event-type trigger-type trigger-type)
                      trigger-filter (or (:trigger/filter ct)
                                         {:event/object-id :self})
                      base {:trigger/type trigger-type
                            :trigger/event-type event-type
                            :trigger/source object-eid
                            :trigger/controller player-eid
                            :trigger/filter trigger-filter
                            :trigger/uses-stack? true}]
                  (cond-> base
                    (:trigger/effects ct)
                    (assoc :trigger/effects (:trigger/effects ct))

                    (:trigger/description ct)
                    (assoc :trigger/description (:trigger/description ct)))))
              card-triggers)]
    [{:db/id object-eid
      :object/triggers trigger-entities}]))


(defn create-game-rule-trigger-tx
  "Create tx-data for a game-rule trigger (always-active, no source, no stack).

   Sets :trigger/always-active? true and :trigger/uses-stack? false.
   Omits :trigger/source (game rules have no source permanent).

   Arguments:
     trigger-map - Map with :trigger/* keys (no :trigger/source)

   Returns:
     Vector of one tx-data map"
  [trigger-map]
  (let [base (-> trigger-map
                 (assoc :trigger/always-active? true)
                 (assoc :trigger/uses-stack? false)
                 (dissoc :trigger/source))
        cleaned (into {} (remove (fn [[_ v]] (nil? v))) base)]
    [cleaned]))


(defn get-all-triggers
  "Return all trigger entities in the database.

   Arguments:
     db - Datascript db value

   Returns:
     Seq of trigger entity maps (empty seq if none)"
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :trigger/event-type _]]
       db))


(defn get-triggers-for-event
  "Find triggers matching an event, applying filter logic.

   First queries by :trigger/event-type, then applies filter matching in code.
   Filter matching handles :self resolution, :exclude-self, exact match,
   and multiple conditions (all must match).

   Arguments:
     db    - Datascript db value
     event - Event map with :event/type and other event data

   Returns:
     Seq of trigger entity maps matching the event (empty seq if none)"
  [db event]
  (let [event-type (:event/type event)
        candidates (d/q '[:find [(pull ?e [*]) ...]
                          :in $ ?et
                          :where [?e :trigger/event-type ?et]]
                        db event-type)]
    (filter #(matches-filter? % event) candidates)))
