(ns fizzle.engine.object-spec
  "Object creation validation specs for sharing/restorer.cljs.

   Validates game object transaction maps at the point they are built by
   restorer/object-tx-for-zone — before being transacted into Datascript.

   Uses goog.DEBUG guard via spec-util/validate-at-chokepoint! — dev-only,
   dead-code eliminated in production by Closure compiler.

   These specs are DESCRIPTIVE: they describe what object-tx-for-zone actually
   produces. Never change production code to satisfy a spec.

   Object transaction map fields:
   Required (all objects):
   - :object/id         — UUID (fresh random-uuid per restore)
   - :object/card       — int (Datascript EID of the card entity)
   - :object/zone       — keyword (hand/library/graveyard/exile/battlefield)
   - :object/owner      — int (Datascript EID of owning player)
   - :object/controller — int (Datascript EID of controlling player)
   - :object/tapped     — boolean
   - :object/position   — int (0 for non-library, index for library)

   Optional:
   - :object/counters       — map (present when (seq counters))
   - :object/grants         — collection (present when (seq grants))
   - :object/power          — int (battlefield creature only)
   - :object/toughness      — int (battlefield creature only)
   - :object/summoning-sick — boolean (battlefield creature only, always false on restore)
   - :object/damage-marked  — int (battlefield creature only, always 0 on restore)"
  (:require
    [cljs.spec.alpha :as s]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]))


;; Valid zones for objects during restore
(def valid-object-zones
  #{:hand :library :graveyard :exile :battlefield :stack :sideboard :phased-out})


(s/def :object/id uuid?)
(s/def :object/card int?)
(s/def :object/zone valid-object-zones)
(s/def :object/owner int?)
(s/def :object/controller int?)
(s/def :object/tapped boolean?)
(s/def :object/position int?)
(s/def :object/counters map?)
(s/def :object/grants :game/collection-flexible)
(s/def :object/power int?)
(s/def :object/toughness int?)
(s/def :object/summoning-sick boolean?)
(s/def :object/damage-marked int?)


(s/def ::object-tx
  (s/keys :req [:object/id
                :object/card
                :object/zone
                :object/owner
                :object/controller
                :object/tapped
                :object/position]
          :opt [:object/counters
                :object/grants
                :object/power
                :object/toughness
                :object/summoning-sick
                :object/damage-marked]))


(defn validate-object-tx!
  "Validate an object transaction map at restorer creation boundary.
   In dev (goog.DEBUG): logs console.error if invalid.
   In prod: no-op.
   Returns nil always."
  [obj-tx]
  (spec-util/validate-at-chokepoint! ::object-tx obj-tx "object-tx-for-zone"))
