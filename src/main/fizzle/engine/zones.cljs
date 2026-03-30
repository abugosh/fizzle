(ns fizzle.engine.zones
  "Zone transition operations for Fizzle.

   All functions are pure: (db, args) -> db

   Valid zones: :hand, :library, :graveyard, :stack, :battlefield, :exile, :sideboard

   Stack-item safety: When an object leaves the :stack zone (via move-to-zone
   or remove-object), any associated stack-item is automatically cleaned up.
   This prevents orphaned stack items that would stall the priority system."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]))


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
      (let [library-cards (q/q-safe '[:find ?e
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
            ;; Clean up stack-item when leaving the stack zone
            db (if (= current-zone :stack)
                 (if-let [si (stack/get-stack-item-by-object-ref db obj-eid)]
                   (stack/remove-stack-item db (:db/id si))
                   db)
                 db)
            ;; Retract Datascript trigger entities when leaving battlefield
            trigger-retract-txs
            (when (= current-zone :battlefield)
              (let [trigger-eids (q/q-safe '[:find [?t ...]
                                             :in $ ?obj
                                             :where [?obj :object/triggers ?t]]
                                           db obj-eid)]
                (mapv (fn [teid] [:db.fn/retractEntity teid]) trigger-eids)))
            ;; Retract creature fields when leaving battlefield
            creature-leave-txs
            (when (= current-zone :battlefield)
              (let [obj (q/pull-safe db [:object/power :object/toughness
                                         :object/summoning-sick :object/damage-marked
                                         :object/attacking :object/blocking] obj-eid)]
                (cond-> []
                  (some? (:object/power obj))
                  (conj [:db/retract obj-eid :object/power (:object/power obj)])
                  (some? (:object/toughness obj))
                  (conj [:db/retract obj-eid :object/toughness (:object/toughness obj)])
                  (some? (:object/summoning-sick obj))
                  (conj [:db/retract obj-eid :object/summoning-sick (:object/summoning-sick obj)])
                  (some? (:object/damage-marked obj))
                  (conj [:db/retract obj-eid :object/damage-marked (:object/damage-marked obj)])
                  (some? (:object/attacking obj))
                  (conj [:db/retract obj-eid :object/attacking (:object/attacking obj)])
                  (some? (:object/blocking obj))
                  (conj [:db/retract obj-eid :object/blocking (:object/blocking obj)]))))
            ;; Add creature fields when entering battlefield
            creature-enter-txs
            (when (= new-zone :battlefield)
              (let [card (q/pull-safe db [{:object/card [:card/types :card/power :card/toughness]}] obj-eid)
                    card-data (:object/card card)
                    card-types (set (:card/types card-data))]
                (when (contains? card-types :creature)
                  [[:db/add obj-eid :object/power (:card/power card-data)]
                   [:db/add obj-eid :object/toughness (:card/toughness card-data)]
                   [:db/add obj-eid :object/summoning-sick true]
                   [:db/add obj-eid :object/damage-marked 0]])))
            ;; Reset tapped state when entering or leaving battlefield
            ;; Leaving: card loses memory of being tapped
            ;; Entering: permanents enter untapped per MTG rule 110.6
            base-txs (if (or (= current-zone :battlefield)
                             (= new-zone :battlefield))
                       [[:db/add obj-eid :object/zone new-zone]
                        [:db/add obj-eid :object/tapped false]]
                       [[:db/add obj-eid :object/zone new-zone]])
            txs (-> base-txs
                    (into trigger-retract-txs)
                    (into creature-leave-txs)
                    (into creature-enter-txs))]
        (d/db-with db txs)))))


(defn move-to-bottom-of-library
  "Move a game object to the bottom of a player's library.
   Sets :object/position to one past the current max position.
   Pure function: (db, object-id, player-id) -> db"
  [db object-id player-id]
  (let [player-eid (q/get-player-eid db player-id)
        max-pos (or (q/q-safe '[:find (max ?pos) .
                                :in $ ?owner
                                :where [?e :object/owner ?owner]
                                [?e :object/zone :library]
                                [?e :object/position ?pos]]
                              db player-eid)
                    -1)
        db (move-to-zone db object-id :library)
        obj-eid (q/get-object-eid db object-id)]
    (d/db-with db [[:db/add obj-eid :object/position (inc max-pos)]])))


(defn remove-object
  "Remove a game object entirely from the database.
   Used for spell copies that cease to exist when leaving the stack (per MTG rules).
   Pure function: (db, object-id) -> db

   Automatically cleans up any associated stack-item.
   Returns db unchanged if object doesn't exist."
  [db object-id]
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (let [;; Clean up stack-item if one exists for this object
          db (if-let [si (stack/get-stack-item-by-object-ref db obj-eid)]
               (stack/remove-stack-item db (:db/id si))
               db)]
      (d/db-with db [[:db.fn/retractEntity obj-eid]]))
    db))
