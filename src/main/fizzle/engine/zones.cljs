(ns fizzle.engine.zones
  "Zone transition operations for Fizzle.

   All functions are pure: (db, args) -> db

   Valid zones: :hand, :library, :graveyard, :stack, :battlefield, :exile"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


(defn shuffle-library
  "Randomize the order of all cards in a player's library.
   Updates :object/position for all library cards with new random positions.
   Pure function: (db, player-id) -> db

   Handles edge cases:
     - Empty library: returns db unchanged (no-op)
     - Single card: remains at position 0"
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (if player-eid
      (let [library-cards (d/q '[:find ?e
                                 :in $ ?owner
                                 :where [?e :object/owner ?owner]
                                 [?e :object/zone :library]]
                               db player-eid)]
        (if (seq library-cards)
          (let [shuffled-eids (vec (shuffle library-cards))
                position-txs (map-indexed (fn [pos [eid]]
                                            [:db/add eid :object/position pos])
                                          shuffled-eids)]
            (d/db-with db position-txs))
          db))
      db)))


(defn move-to-zone
  "Move a game object to a new zone. Pure function: (db, args) -> db

   Arguments:
     db - Datascript database value
     object-id - The :object/id of the object to move
     new-zone - The target zone keyword

   Returns:
     New db with object in new zone, or same db if already in that zone.

   Note: Caller must ensure object-id exists. If object doesn't exist,
   behavior is undefined (query fails). This is a programming error."
  [db object-id new-zone]
  (let [current-zone (:object/zone (q/get-object db object-id))]
    (if (= current-zone new-zone)
      db
      (let [obj-eid (q/get-object-eid db object-id)
            ;; Retract Datascript trigger entities when leaving battlefield
            trigger-retract-txs
            (when (= current-zone :battlefield)
              (let [trigger-eids (d/q '[:find [?t ...]
                                        :in $ ?obj
                                        :where [?obj :object/triggers ?t]]
                                      db obj-eid)]
                (mapv (fn [teid] [:db.fn/retractEntity teid]) trigger-eids)))
            ;; Reset tapped state when entering or leaving battlefield
            ;; Leaving: card loses memory of being tapped
            ;; Entering: permanents enter untapped per MTG rule 110.6
            base-txs (if (or (= current-zone :battlefield)
                             (= new-zone :battlefield))
                       [[:db/add obj-eid :object/zone new-zone]
                        [:db/add obj-eid :object/tapped false]]
                       [[:db/add obj-eid :object/zone new-zone]])
            txs (into base-txs trigger-retract-txs)]
        (d/db-with db txs)))))


(defn remove-object
  "Remove a game object entirely from the database.
   Used for spell copies that cease to exist when leaving the stack (per MTG rules).
   Pure function: (db, object-id) -> db

   Returns db unchanged if object doesn't exist."
  [db object-id]
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (d/db-with db [[:db.fn/retractEntity obj-eid]])
    db))
