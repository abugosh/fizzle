(ns fizzle.engine.objects
  "Game object construction chokepoint.

   build-object-tx is the sole function for building Datascript tx maps
   for game objects. All consumers (init, restorer, tokens, test helpers)
   must call this function — no manual object map construction elsewhere.

   Battlefield-specific fields (summoning-sick, damage-marked) are NOT set here.
   Those are zone-transition concerns owned by engine/zones.cljs:move-to-zone.")


(defn- creature?
  [card-data]
  (contains? (set (:card/types card-data)) :creature))


(defn build-object-tx
  "Build a Datascript tx map for one game object from card data.

   Parameters:
     card-eid  - Datascript entity id of the card (used for :object/card reference)
     card-data - pulled card entity with at minimum :card/types, :card/power, :card/toughness
     zone      - keyword (:hand, :library, :graveyard, :exile, :sideboard, :battlefield, :stack)
     owner-eid - Datascript entity id of owning player
     position  - integer position (0 for non-library zones, index for library)

   Options (keyword args):
     :id         - UUID to use as :object/id (default: generate random-uuid)
     :controller - entity id for controller (default: same as owner-eid)

   Returns a map suitable for d/transact!/d/db-with.
   Does NOT set battlefield-specific fields (summoning-sick, damage-marked).
   Those belong to zone transitions (engine/zones.cljs:move-to-zone)."
  [card-eid card-data zone owner-eid position & {:keys [id controller]}]
  (cond-> {:object/id         (or id (random-uuid))
           :object/card       card-eid
           :object/zone       zone
           :object/owner      owner-eid
           :object/controller (or controller owner-eid)
           :object/tapped     false
           :object/position   position}
    (creature? card-data)
    (assoc :object/power     (:card/power card-data)
           :object/toughness (:card/toughness card-data))))
