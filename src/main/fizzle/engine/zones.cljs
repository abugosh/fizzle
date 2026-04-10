(ns fizzle.engine.zones
  "Zone transition operations for Fizzle.

   All functions are pure: (db, args) -> db

   Valid zones: :hand, :library, :graveyard, :stack, :battlefield, :exile, :sideboard, :phased-out

   Stack-item safety: When an object leaves the :stack zone (via move-to-zone
   or remove-object), any associated stack-item is automatically cleaned up.
   This prevents orphaned stack items that would stall the priority system."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as events]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-db :as trigger-db]))


(defn- dispatch-zone-change-event
  "Dispatch a zone-change event to all matching stacked triggers.
   Creates stack items for triggers whose :trigger/filter and :trigger/match
   both satisfy the event.

   Uses trigger-db and stack directly to avoid the circular dependency:
   trigger-dispatch -> triggers -> effects -> zones.

   Arguments:
     db    - Datascript db value (post-move commit)
     event - Zone-change event map (:event/object-id is UUID, from/to are zone keywords)

   Returns:
     New db with matching trigger stack items added."
  [db event]
  (let [;; Enrich event: convert UUID :event/object-id to EID for DS filter matching
        ;; (trigger-db/get-triggers-for-event uses the enriched event for :trigger/filter)
        enriched-event (cond-> event
                         (:event/object-id event)
                         (assoc :event/object-id
                                (q/get-object-eid db (:event/object-id event))))
        ;; get-triggers-for-event filters by :trigger/event-type + :trigger/filter
        ds-triggers (trigger-db/get-triggers-for-event db enriched-event)]
    (reduce (fn [db-acc t]
              (let [src-eid     (let [s (:trigger/source t)]
                                  (if (map? s) (:db/id s) s))
                    src-uuid    (when src-eid
                                  (d/q '[:find ?oid .
                                         :in $ ?e
                                         :where [?e :object/id ?oid]]
                                       db-acc src-eid))
                    ctrl-eid    (let [c (:trigger/controller t)]
                                  (if (map? c) (:db/id c) c))
                    ctrl-pid    (when ctrl-eid
                                  (d/q '[:find ?pid .
                                         :in $ ?e
                                         :where [?e :player/id ?pid]]
                                       db-acc ctrl-eid))
                    uses-stack? (get t :trigger/uses-stack? true)]
                ;; Apply :trigger/match second-stage filter using original (UUID-form) event
                (if (and uses-stack?
                         (trigger-db/matches? event src-uuid t))
                  (stack/create-stack-item
                    db-acc
                    (cond-> {:stack-item/type    (:trigger/event-type t)
                             :stack-item/controller ctrl-pid
                             :stack-item/effects (or (:trigger/effects t) [])}
                      src-uuid                 (assoc :stack-item/source src-uuid)
                      (:trigger/description t) (assoc :stack-item/description (:trigger/description t))))
                  db-acc)))
            db
            ds-triggers)))


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
            ;; Clean up stack-item when leaving the stack zone
            db (if (= current-zone :stack)
                 (if-let [si (stack/get-stack-item-by-object-ref db obj-eid)]
                   (stack/remove-stack-item db (:db/id si))
                   db)
                 db)
            ;; Retract Datascript trigger entities when leaving battlefield
            trigger-retract-txs
            (when (= current-zone :battlefield)
              (let [trigger-eids (d/q '[:find [?t ...]
                                        :in $ ?obj
                                        :where [?obj :object/triggers ?t]]
                                      db obj-eid)]
                (mapv (fn [teid] [:db.fn/retractEntity teid]) trigger-eids)))
            ;; Reset creature fields when leaving battlefield
            ;; P/T reset to card base values (not retracted — creatures have P/T in all zones)
            ;; Combat-only attrs (summoning-sick, damage-marked, attacking, blocking) are retracted
            creature-leave-txs
            (when (= current-zone :battlefield)
              (let [obj (d/pull db [{:object/card [:card/types :card/power :card/toughness]}
                                    :object/summoning-sick :object/damage-marked
                                    :object/attacking :object/blocking] obj-eid)
                    card-data (:object/card obj)
                    card-types (set (:card/types card-data))]
                (cond-> []
                  (contains? card-types :creature)
                  (into [[:db/add obj-eid :object/power (:card/power card-data)]
                         [:db/add obj-eid :object/toughness (:card/toughness card-data)]])
                  (some? (:object/summoning-sick obj))
                  (conj [:db/retract obj-eid :object/summoning-sick (:object/summoning-sick obj)])
                  (some? (:object/damage-marked obj))
                  (conj [:db/retract obj-eid :object/damage-marked (:object/damage-marked obj)])
                  (some? (:object/attacking obj))
                  (conj [:db/retract obj-eid :object/attacking (:object/attacking obj)])
                  (some? (:object/blocking obj))
                  (conj [:db/retract obj-eid :object/blocking (:object/blocking obj)]))))
            ;; Add creature fields when entering battlefield
            ;; P/T set from card definition (idempotent — safe if already present)
            ;; Summoning-sick and damage-marked are battlefield-only combat attrs
            creature-enter-txs
            (when (= new-zone :battlefield)
              (let [card (d/pull db [{:object/card [:card/types :card/power :card/toughness]}] obj-eid)
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
                    (into creature-enter-txs))
            ;; Commit zone move
            db' (d/db-with db txs)]
        ;; Dispatch zone-change event for triggered abilities.
        ;; NOTE: This fires during mulligan (~67 calls per attempt) when the
        ;; deck contains zone-change-triggered cards. Those triggers land on
        ;; the stack but do not stall init — no player has priority yet.
        ;; This is acceptable under Path Y (no director loop changes in this epic).
        (dispatch-zone-change-event
          db'
          (events/zone-change-event object-id current-zone new-zone))))))


(defn phase-out
  "Phase out a permanent. Direct zone change to :phased-out — bypasses
   move-to-zone intentionally to preserve all object state (tapped,
   counters, creature fields, triggers). Does NOT retract triggers or
   reset tapped state."
  [db object-id]
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (d/db-with db [[:db/add obj-eid :object/zone :phased-out]])
    db))


(defn phase-in
  "Phase in a permanent. Direct zone change back to :battlefield —
   bypasses move-to-zone intentionally. Does NOT trigger ETB, does NOT
   reset tapped state, does NOT add summoning sickness. Preserves all
   object state exactly as it was when phased out."
  [db object-id]
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (d/db-with db [[:db/add obj-eid :object/zone :battlefield]])
    db))


(defn move-to-bottom-of-library
  "Move a game object to the bottom of a player's library.
   Sets :object/position to one past the current max position.
   Pure function: (db, object-id, player-id) -> db"
  [db object-id player-id]
  (let [player-eid (q/get-player-eid db player-id)
        max-pos (or (d/q '[:find (max ?pos) .
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
