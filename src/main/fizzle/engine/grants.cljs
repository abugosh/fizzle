(ns fizzle.engine.grants
  "Grant system for temporarily modifying card instances and players.

   Grants allow adding temporary abilities, alternate costs, keywords,
   restrictions, or static effects. They support:
   - Grants on objects (cards/permanents)
   - Grants on players (restrictions like 'can't cast spells')
   - Expiration at specific turn/phase
   - Source tracking for removal effects
   - Multiple grants per entity

   Grant structure:
   {:grant/id        uuid      ; Unique identifier
    :grant/type      keyword   ; :alternate-cost, :ability, :keyword, :static-effect, :restriction
    :grant/source    uuid      ; Object ID that created this grant
    :grant/expires   map       ; {:expires/turn N :expires/phase :cleanup}
    :grant/data      map}      ; The granted thing"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


(defn get-grants-by-type
  "Get grants of a specific type on an object.
   Returns empty vector if no matching grants."
  [db object-id grant-type]
  (let [grants (q/get-grants db object-id)]
    (filterv #(= grant-type (:grant/type %)) grants)))


(defn add-grant
  "Add a grant to an object. Returns updated db.
   Does not check for duplicates - caller should ensure grant IDs are unique."
  [db object-id grant]
  (let [obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)
        current-grants (q/get-grants db object-id)
        new-grants (conj current-grants grant)]
    (d/db-with db [[:db/add obj-eid :object/grants new-grants]])))


(defn remove-grant
  "Remove a specific grant by ID from an object. Returns updated db.
   No-op if grant doesn't exist."
  [db object-id grant-id]
  (let [obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)
        current-grants (q/get-grants db object-id)
        new-grants (filterv #(not= grant-id (:grant/id %)) current-grants)]
    (if (= (count current-grants) (count new-grants))
      db  ; Grant not found, no change
      (d/db-with db [[:db/add obj-eid :object/grants new-grants]]))))


(defn- get-all-objects-with-grants
  "Find all objects that have grants.
   Returns sequence of [object-eid object-id grants] tuples."
  [db]
  (->> (d/q '[:find ?e ?oid ?grants
              :where
              [?e :object/id ?oid]
              [?e :object/grants ?grants]]
            db)
       (filter (fn [[_ _ grants]] (seq grants)))))


(defn remove-grants-by-source
  "Remove all grants from a specific source object across all objects.
   Returns updated db."
  [db source-id]
  (let [objects-with-grants (get-all-objects-with-grants db)]
    (reduce (fn [db' [obj-eid _obj-id grants]]
              (let [filtered (filterv #(not= source-id (:grant/source %)) grants)]
                (if (= (count grants) (count filtered))
                  db'  ; No change needed
                  (d/db-with db' [[:db/add obj-eid :object/grants filtered]]))))
            db
            objects-with-grants)))


;; =====================================================
;; Player Grants
;; =====================================================
;; Player grants extend the grant system to player entities.
;; This enables restrictions like "can't cast spells" (Orim's Chant).

(defn get-player-grants
  "Get all grants on a player.
   Returns empty vector if player has no grants or doesn't exist."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (if player-eid
      (or (d/q '[:find ?grants .
                 :in $ ?e
                 :where [?e :player/grants ?grants]]
               db player-eid)
          [])
      [])))


(defn get-player-grants-by-type
  "Get grants of a specific type on a player.
   Returns empty vector if no matching grants."
  [db player-id grant-type]
  (let [grants (get-player-grants db player-id)]
    (filterv #(= grant-type (:grant/type %)) grants)))


(defn add-player-grant
  "Add a grant to a player. Returns updated db.
   Does not check for duplicates - caller should ensure grant IDs are unique.
   No-op if player doesn't exist."
  [db player-id grant]
  (let [player-eid (q/get-player-eid db player-id)]
    (if player-eid
      (let [current-grants (get-player-grants db player-id)
            new-grants (conj current-grants grant)]
        (d/db-with db [[:db/add player-eid :player/grants new-grants]]))
      db)))


(defn remove-player-grant
  "Remove a specific grant by ID from a player. Returns updated db.
   No-op if grant doesn't exist or player doesn't exist."
  [db player-id grant-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (if player-eid
      (let [current-grants (get-player-grants db player-id)
            new-grants (filterv #(not= grant-id (:grant/id %)) current-grants)]
        (if (= (count current-grants) (count new-grants))
          db  ; Grant not found, no change
          (d/db-with db [[:db/add player-eid :player/grants new-grants]])))
      db)))


(defn- get-all-players-with-grants
  "Find all players that have grants.
   Returns sequence of [player-eid player-id grants] tuples."
  [db]
  (->> (d/q '[:find ?e ?pid ?grants
              :where
              [?e :player/id ?pid]
              [?e :player/grants ?grants]]
            db)
       (filter (fn [[_ _ grants]] (seq grants)))))


;; =====================================================
;; Grant Expiration
;; =====================================================

(def phase-order
  "MTG turn phases in order for comparison."
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])


(defn- phase-index
  "Get numeric index of a phase for comparison."
  [phase]
  (.indexOf phase-order phase))


(defn- phase-reached?
  "Check if current-phase has reached or passed target-phase."
  [current-phase target-phase]
  (>= (phase-index current-phase) (phase-index target-phase)))


(defn grant-expired?
  "Check if a grant has expired given current turn/phase.

   Expiration format:
   - {:expires/turn N :expires/phase :cleanup} - expires at turn N, phase
   - {:expires/permanent true} - never expires
   - nil / missing - never expires"
  [grant current-turn current-phase]
  (let [expires (:grant/expires grant)]
    (cond
      ;; No expiration data = never expires
      (nil? expires)
      false

      ;; Permanent grants never expire
      (:expires/permanent expires)
      false

      ;; Past the expiration turn
      (> current-turn (:expires/turn expires))
      true

      ;; Same turn, check phase
      (= current-turn (:expires/turn expires))
      (phase-reached? current-phase (:expires/phase expires))

      ;; Before expiration turn
      :else
      false)))


(defn- expire-object-grants
  "Remove expired grants from all objects. Helper for expire-grants."
  [db current-turn current-phase]
  (let [objects-with-grants (get-all-objects-with-grants db)]
    (reduce (fn [db' [obj-eid _obj-id grants]]
              (let [active (filterv #(not (grant-expired? % current-turn current-phase))
                                    grants)]
                (if (= (count grants) (count active))
                  db'  ; No change needed
                  (d/db-with db' [[:db/add obj-eid :object/grants active]]))))
            db
            objects-with-grants)))


(defn- expire-player-grants
  "Remove expired grants from all players. Helper for expire-grants."
  [db current-turn current-phase]
  (let [players-with-grants (get-all-players-with-grants db)]
    (reduce (fn [db' [player-eid _player-id grants]]
              (let [active (filterv #(not (grant-expired? % current-turn current-phase))
                                    grants)]
                (if (= (count grants) (count active))
                  db'  ; No change needed
                  (d/db-with db' [[:db/add player-eid :player/grants active]]))))
            db
            players-with-grants)))


(defn expire-grants
  "Remove all expired grants from all objects AND players given current turn/phase.
   Returns updated db."
  [db current-turn current-phase]
  (-> db
      (expire-object-grants current-turn current-phase)
      (expire-player-grants current-turn current-phase)))
