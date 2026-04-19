(ns fizzle.engine.zone-change-dispatch
  "Public move-to-zone entry point that dispatches :zone-change trigger events
   and :land-entered trigger events when a land enters the battlefield.
   Also intercepts zone-change events pre-commit for replacement effects.

   Lives outside the zones→trigger-dispatch→triggers→effects→zones cycle.
   All callers must use this namespace's move-to-zone (or move-to-zone-db shim)
   instead of zones/move-to-zone* to ensure zone-change triggered abilities fire
   and replacement effects are checked.

   API contract:
     move-to-zone     — returns {:db db'} or {:db db :needs-selection {...}}
     move-to-zone-db  — returns plain db (shim for callers that never need :needs-selection)

   Counter-spell zone changes route through this namespace via effects/stack.cljs,
   which can safely require zone-change-dispatch (no cycle: effects/stack →
   zone-change-dispatch → trigger-dispatch → triggers → effects, and effects does
   not require effects/stack).

   NOTE: Cannot require engine/rules here — rules requires zone-change-dispatch
   (circular). Land type check is inlined."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as events]
    [fizzle.engine.replacement-db :as replacement-db]
    [fizzle.engine.trigger-dispatch :as trigger-dispatch]
    [fizzle.engine.zones :as zones]))


(defn- dispatch-post-move-triggers
  "Fire :zone-change and optionally :land-entered triggers after a zone move.
   Returns updated db."
  [_db db-after-move object-id from-zone new-zone]
  (let [db-after-zone-change (trigger-dispatch/dispatch-event
                               db-after-move
                               (events/zone-change-event object-id from-zone new-zone))]
    ;; Additionally dispatch :land-entered when a land enters the battlefield.
    ;; Cannot use rules/land-card? here (circular dep: rules → zone-change-dispatch),
    ;; so we inline the land type check.
    (if (= new-zone :battlefield)
      (let [obj (q/get-object db-after-move object-id)
            card-types (:card/types (:object/card obj))]
        (if (contains? (set card-types) :land)
          (let [controller-eid (:db/id (:object/controller obj))
                controller-id (q/get-player-id db-after-move controller-eid)]
            (trigger-dispatch/dispatch-event
              db-after-zone-change
              (events/land-entered-event object-id controller-id)))
          db-after-zone-change))
      db-after-zone-change)))


(defn move-to-zone
  "Move a game object to a new zone, checking for applicable replacement effects first.

   Pre-event phase: queries replacement entities on the moving object.
   If a matching replacement is found, returns :needs-selection signal for the events
   layer to handle (task 2 will consume this signal).
   If no replacement applies, commits the zone change and dispatches triggers.

   Arguments:
     db        - Datascript database value
     object-id - The :object/id of the object to move
     new-zone  - The target zone keyword

   Returns:
     {:db db'}                             — no replacement applies, zone change committed
     {:db db :needs-selection {:input/type :replacement ...}} — replacement pauses the event

   NOTE: This fires during init/mulligan too (~67 events per mulligan).
   Safe today because no card's trigger match-map accepts init-time moves.
   A future card with {:trigger/match {:event/to-zone :library}}-style
   triggers would need game-state gating — see Epic fizzle-vvrn design for
   Path Y rationale."
  [db object-id new-zone]
  (let [from-zone (:object/zone (q/get-object db object-id))]
    ;; No-op if zone unchanged
    (if (= from-zone new-zone)
      {:db (zones/move-to-zone* db object-id new-zone)}
      ;; Pre-event: check for applicable replacement effects
      (let [event {:event/type      :zone-change
                   :event/object-id object-id
                   :event/from-zone from-zone
                   :event/to-zone   new-zone}
            matches (replacement-db/get-replacements-for-event db object-id event)]
        (if-let [replacement (first matches)]
          ;; Replacement matches — pause and return :needs-selection signal
          ;; The zone change does NOT commit yet; task 2 resumes after player choice
          {:db db
           :needs-selection {:input/type          :replacement
                             :replacement/entity  replacement
                             :replacement/event   {:event/type  :zone-change
                                                   :event/object object-id
                                                   :event/from  from-zone
                                                   :event/to    new-zone}
                             :replacement/object-id object-id}}
          ;; No replacement — commit zone change and dispatch triggers
          (let [db-after-move (zones/move-to-zone* db object-id new-zone)
                db-final (dispatch-post-move-triggers db db-after-move object-id from-zone new-zone)]
            {:db db-final}))))))


(defn move-to-zone-db
  "Shim for callers that never need to handle :needs-selection (plain-db paths).

   Calls move-to-zone and extracts :db from the tagged return.
   Use this for: effects/zones, events/*, engine/state_based, engine/rules,
   engine/resolution (plain-db case), etc.

   Arguments:
     db        - Datascript database value
     object-id - The :object/id of the object to move
     new-zone  - The target zone keyword

   Returns:
     Plain Datascript db (not a tagged map)"
  [db object-id new-zone]
  (:db (move-to-zone db object-id new-zone)))
