(ns fizzle.engine.zone-change-dispatch
  "Public move-to-zone entry point that dispatches :zone-change trigger events
   and :land-entered trigger events when a land enters the battlefield.

   Lives outside the zones→trigger-dispatch→triggers→effects→zones cycle.
   All callers should use this namespace's move-to-zone instead of zones/move-to-zone*
   to ensure zone-change triggered abilities fire.

   Exception: engine/effects.cljs cannot use this namespace (would create a cycle
   through triggers→effects) and therefore uses zones/move-to-zone* directly.
   That path (counter-spell zone changes) is acceptable — countered spells moving
   to graveyard from stack do not trigger zone-change abilities in Premodern combo.

   NOTE: Cannot require engine/rules here — rules requires zone-change-dispatch
   (circular). Land type check is inlined."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as events]
    [fizzle.engine.trigger-dispatch :as trigger-dispatch]
    [fizzle.engine.zones :as zones]))


(defn move-to-zone
  "Move a game object to a new zone and dispatch a :zone-change event to all
   matching triggers. Pure function: (db, args) -> db

   Captures from-zone before delegating to zones/move-to-zone*, then fires
   trigger-dispatch/dispatch-event with the zone-change event so that
   zone-change triggered abilities land on the stack.

   Arguments:
     db       - Datascript database value
     object-id - The :object/id of the object to move
     new-zone  - The target zone keyword

   Returns:
     New db with object in new zone and matching triggers added to stack.

   NOTE: This fires during init/mulligan too (~67 events per mulligan).
   Safe today because no card's trigger match-map accepts init-time moves.
   A future card with {:trigger/match {:event/to-zone :library}}-style
   triggers would need game-state gating — see Epic fizzle-vvrn design for
   Path Y rationale."
  [db object-id new-zone]
  (let [from-zone (:object/zone (q/get-object db object-id))
        db' (zones/move-to-zone* db object-id new-zone)]
    ;; Only dispatch if the zone actually changed (move-to-zone* short-circuits no-ops)
    (if (= from-zone new-zone)
      db'
      (let [db-after-zone-change (trigger-dispatch/dispatch-event
                                   db'
                                   (events/zone-change-event object-id from-zone new-zone))]
        ;; Additionally dispatch :land-entered when a land enters the battlefield.
        ;; Cannot use rules/land-card? here (circular dep: rules → zone-change-dispatch),
        ;; so we inline the land type check.
        (if (= new-zone :battlefield)
          (let [obj (q/get-object db' object-id)
                card-types (:card/types (:object/card obj))]
            (if (contains? (set card-types) :land)
              (let [controller-eid (:db/id (:object/controller obj))
                    controller-id (q/get-player-id db' controller-eid)]
                (trigger-dispatch/dispatch-event
                  db-after-zone-change
                  (events/land-entered-event object-id controller-id)))
              db-after-zone-change))
          db-after-zone-change)))))
