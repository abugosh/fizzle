(ns fizzle.engine.objects
  "Game object construction chokepoint.

   build-object-tx is the sole function for building Datascript tx maps
   for game objects. All consumers (init, restorer, tokens, test helpers)
   must call this function — no manual object map construction elsewhere.

   Battlefield-specific fields (summoning-sick, damage-marked) are NOT set here.
   Those are zone-transition concerns owned by engine/zones.cljs:move-to-zone."
  (:require
    [fizzle.engine.trigger-db :as trigger-db]))


(defn- creature?
  [card-data]
  (contains? (set (:card/types card-data)) :creature))


(defonce ^:private tempid-counter (atom 0))


(defn- next-tempid
  "Generate a unique negative integer tempid for use in Datascript transactions.
   Negative integers are resolved to real EIDs by Datascript within a transaction."
  []
  (swap! tempid-counter dec))


(defn build-object-tx
  "Build a Datascript tx map for one game object from card data.

   Parameters:
     db        - Datascript db value (needed for trigger filter resolution)
     card-eid  - Datascript entity id of the card (used for :object/card reference)
     card-data - pulled card entity with at minimum :card/types, :card/power,
                 :card/toughness, :card/triggers
     zone      - keyword (:hand, :library, :graveyard, :exile, :sideboard, :battlefield, :stack)
     owner-eid - Datascript entity id of owning player
     position  - integer position (0 for non-library zones, index for library)

   Options (keyword args):
     :id         - UUID to use as :object/id (default: generate random-uuid)
     :controller - entity id for controller (default: same as owner-eid)

   Returns a map suitable for d/transact!/d/db-with.
   Includes :object/triggers (nested entities) when card has :card/triggers.
   Does NOT set battlefield-specific fields (summoning-sick, damage-marked).
   Those belong to zone transitions (engine/zones.cljs:move-to-zone)."
  [db card-eid card-data zone owner-eid position & {:keys [id controller]}]
  (let [tempid (next-tempid)
        controller-eid (or controller owner-eid)
        card-triggers (:card/triggers card-data)]
    (cond-> {:db/id             tempid
             :object/id         (or id (random-uuid))
             :object/card       card-eid
             :object/zone       zone
             :object/owner      owner-eid
             :object/controller controller-eid
             :object/tapped     false
             :object/position   position}
      (creature? card-data)
      (assoc :object/power     (:card/power card-data)
             :object/toughness (:card/toughness card-data))

      (seq card-triggers)
      (assoc :object/triggers
             ;; Extract trigger entities from create-triggers-for-card-tx.
             ;; Uses tempid as :trigger/source so it resolves in the same transaction.
             (:object/triggers
               (first (trigger-db/create-triggers-for-card-tx
                        db tempid controller-eid card-triggers)))))))
