(ns fizzle.engine.replacement-db
  "Replacement-effect entities in Datascript. Pure query and transaction functions.
   No mutable state -- all replacement effects are immutable DB values.

   Replacement effects intercept zone-change events BEFORE they execute, allowing
   player-choice-driven modification or cancellation (ADR-026 uniform with triggers).

   Registration:
     create-replacements-for-card-tx -- parallel to trigger-db/create-triggers-for-card-tx

   Matching:
     matches-replacement? -- checks a single replacement against an event
     get-replacements-for-event -- returns all matching replacements for an event"
  (:require
    [datascript.core :as d]))


(defn matches-replacement?
  "Returns true if the event satisfies this replacement's :replacement/match map.
   :self in match value compares the event field against object-uuid.
   Empty/nil match = matches any event of the right type.

   Arguments:
     event       - Event map with UUID-form :event/object-id
     object-uuid - UUID of the source object (for :self sigil resolution)
     replacement - Replacement entity map with optional :replacement/match

   Returns:
     Boolean - true if event satisfies all match-map conditions"
  [event object-uuid replacement]
  (let [match-map (:replacement/match replacement)]
    (if (or (nil? match-map) (empty? match-map))
      true
      (every? (fn [[match-key expected]]
                (let [actual (cond
                               (= match-key :match/object) (:event/object-id event)
                               (= match-key :match/to)     (:event/to-zone event)
                               (= match-key :match/from)   (:event/from-zone event)
                               :else                       (get event match-key ::missing))]
                  (if (= actual ::missing)
                    false
                    (if (= expected :self)
                      (= actual object-uuid)
                      (= actual expected)))))
              match-map))))


(defn create-replacements-for-card-tx
  "Create tx-data for card replacement effects, linking to source object as components.
   Parallel to trigger-db/create-triggers-for-card-tx.

   Uses nested tx-data so Datascript creates child entities and links automatically.
   All match and choices values are stored as plain EDN (not Datascript refs).

   Arguments:
     db                 - Datascript db value (for future filter resolution; not yet used)
     object-eid         - Entity ID (or tempid) of the source object
     _player-eid        - Entity ID of the controlling player (reserved for future use)
     card-replacements  - Vector of replacement definitions from :card/replacement-effects

   Returns:
     Vector of tx-data (object update with nested replacement entities)"
  [_db object-eid _player-eid card-replacements]
  (let [replacement-entities
        (mapv (fn [r]
                (cond-> {:replacement/event         (:replacement/event r)
                         :replacement/source-object object-eid}
                  (:replacement/match r)
                  (assoc :replacement/match (:replacement/match r))

                  (:replacement/choices r)
                  (assoc :replacement/choices (:replacement/choices r))))
              card-replacements)]
    [{:db/id                      object-eid
      :object/replacement-effects replacement-entities}]))


(defn get-replacements-for-event
  "Find replacement effects matching an event for a specific object.

   Queries replacement entities linked to the object, then filters by:
   1. Event type match (:replacement/event = :event/type)
   2. Match-map criteria via matches-replacement? (:self sigil, destination, etc.)

   Arguments:
     db        - Datascript db value
     object-id - UUID of the moving object
     event     - Event map with :event/type, :event/to-zone, :event/object-id, etc.

   Returns:
     Seq of matching replacement entity maps (empty seq if none)"
  [db object-id event]
  (let [event-type (:event/type event)
        ;; Find the object EID by UUID, then pull its replacement effects
        candidates (d/q '[:find [(pull ?r [*]) ...]
                          :in $ ?oid
                          :where
                          [?o :object/id ?oid]
                          [?o :object/replacement-effects ?r]]
                        db object-id)]
    (->> (or candidates [])
         (filter #(= event-type (:replacement/event %)))
         (filter #(matches-replacement? event object-id %)))))
