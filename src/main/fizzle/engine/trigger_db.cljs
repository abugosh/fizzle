(ns fizzle.engine.trigger-db
  "Trigger entities in Datascript. Pure query and transaction functions.
   No mutable state -- all triggers are immutable DB values."
  (:require
    [datascript.core :as d]))


(defmulti trigger-type->event-type
  "Map card trigger type to event type. Extensible via defmethod.
   E.g., :becomes-tapped in card data becomes :permanent-tapped event.
   Default returns the trigger-type unchanged (identity mapping)."
  identity)


(defmethod trigger-type->event-type :becomes-tapped [_] :permanent-tapped)
(defmethod trigger-type->event-type :land-entered [_] :land-entered)
(defmethod trigger-type->event-type :creature-attacks [_] :creature-attacked)
(defmethod trigger-type->event-type :enters-battlefield [_] :permanent-entered)
(defmethod trigger-type->event-type :zone-change [_] :zone-change)
(defmethod trigger-type->event-type :default [type] type)


(defn matches?
  "Returns true if the event satisfies the trigger's :trigger/match map.
   Empty/nil match = wildcard (fires on event-type match alone, preserving
   backwards compatibility for existing triggers like City of Brass).
   :self sigil compares the event field against the trigger-source UUID.
   Uses ::missing sentinel so absent fields (vs nil-valued) correctly return false.

   Arguments:
     event          - Event map with UUID-form :event/object-id (not EID-enriched)
     trigger-source - UUID of the source object (for :self sigil resolution)
     trigger        - Trigger map with optional :trigger/match

   Returns:
     Boolean - true if event satisfies all match-map conditions"
  [event trigger-source trigger]
  (let [match-map (:trigger/match trigger)]
    (or (nil? match-map)
        (empty? match-map)
        (every? (fn [[event-key expected]]
                  (let [actual (get event event-key ::missing)]
                    (cond
                      (= actual ::missing) false
                      (= expected :self)   (= actual trigger-source)
                      :else                (= actual expected))))
                match-map))))


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


(defn- resolve-filter-values
  "Resolve symbolic filter values at trigger registration time.
   :self-controller is replaced with the controller's player-id keyword."
  [db player-eid filter-map]
  (into {}
        (map (fn [[k v]]
               (if (= v :self-controller)
                 (let [player-id (d/q '[:find ?pid .
                                        :in $ ?e
                                        :where [?e :player/id ?pid]]
                                      db player-eid)]
                   [k player-id])
                 [k v]))
             filter-map)))


(defn create-triggers-for-card-tx
  "Create tx-data for card triggers, linking to source object as components.

   Maps :trigger/type to :trigger/event-type.
   Applies default filter {:event/object-id :self} when no filter specified.
   Resolves :self-controller in filter values to the controller's player-id.
   Uses nested tx-data so Datascript creates child entities and links automatically.

   Arguments:
     db             - Datascript db value
     object-eid     - Entity ID of the source object
     player-eid     - Entity ID of the controlling player
     card-triggers  - Vector of card trigger definitions from :card/triggers

   Returns:
     Vector of tx-data (object update with nested trigger entities)"
  [db object-eid player-eid card-triggers]
  (let [trigger-entities
        (mapv (fn [ct]
                (let [trigger-type (:trigger/type ct)
                      event-type (trigger-type->event-type trigger-type)
                      raw-filter (or (:trigger/filter ct)
                                     {:event/object-id :self})
                      trigger-filter (resolve-filter-values db player-eid raw-filter)
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
                    (assoc :trigger/description (:trigger/description ct))

                    (:trigger/match ct)
                    (assoc :trigger/match (:trigger/match ct))

                    (:trigger/active-zone ct)
                    (assoc :trigger/active-zone (:trigger/active-zone ct)))))
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


(defn- self-scoped?
  "Returns true if the trigger is self-scoped.
   Self-scoped triggers have {:event/object-id :self} in either
   :trigger/filter or :trigger/match. Zone check is skipped for these —
   match-map is the sole authority for zone relevance.

   Arguments:
     trigger - Trigger entity map

   Returns:
     Boolean - true if trigger is self-scoped"
  [trigger]
  (or (= :self (get (:trigger/filter trigger) :event/object-id))
      (= :self (get (:trigger/match trigger) :event/object-id))))


(defn- in-active-zone?
  "Returns true if the trigger's source is in the trigger's :trigger/active-zone.
   Defaults to :battlefield when :trigger/active-zone is absent.
   Skips zone check for always-active triggers (:trigger/always-active? true).

   Uses d/q to look up source EID directly — more reliable than reading
   :trigger/source from the pull result when dealing with ref attributes.
   If no :trigger/source datom exists, trigger is treated as sourceless and fires.

   Arguments:
     db      - Datascript db value
     trigger - Trigger entity map

   Returns:
     Boolean - true if trigger is eligible to fire"
  [db trigger]
  (if (:trigger/always-active? trigger)
    true
    (let [trigger-eid (:db/id trigger)
          source-eid (d/q '[:find ?s .
                            :in $ ?t
                            :where [?t :trigger/source ?s]]
                          db trigger-eid)]
      (if (nil? source-eid)
        true
        (let [source-zone (:object/zone (d/entity db source-eid))
              active-zone (or (:trigger/active-zone trigger) :battlefield)]
          (= source-zone active-zone))))))


(defn get-triggers-for-event
  "Find triggers matching an event, applying filter and zone check logic.

   First queries by :trigger/event-type, then applies filter matching and
   zone relevance in code. Zone check: observer triggers only fire when
   their source is in :trigger/active-zone (default :battlefield).
   Self-scoped triggers (match or filter has {:event/object-id :self})
   skip the zone check — match-map is the sole zone authority for them.

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
    (->> candidates
         (filter #(matches-filter? % event))
         (filter #(or (self-scoped? %) (in-active-zone? db %))))))
