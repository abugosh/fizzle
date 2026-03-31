(ns fizzle.engine.triggers
  "Trigger system for Fizzle.

   Provides resolve-trigger multimethod for immediate (non-stack) triggers
   like draw-step and untap-step. Also provides create-spell-copy for storm.

   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.zones :as zones]))


(defmulti resolve-trigger
  "Resolve a trigger on the stack.

   Dispatches on (:trigger/type trigger).
   Each trigger type should implement its own resolution logic.

   Arguments:
     db      - Datascript database value
     trigger - Trigger map

   Returns:
     New db after trigger resolution."
  (fn [_db trigger] (:trigger/type trigger)))


(defmethod resolve-trigger :default
  [db _trigger]
  db)


(defn create-spell-copy
  "Create a copy of a spell on the stack.

   The copy inherits the card reference from the source object but has
   :object/is-copy set to true. Copies are not cast (don't trigger storm).
   Creates a :storm-copy stack-item alongside the copy object.

   Arguments:
     db               - Datascript database value
     source-object-id - ID of the object to copy
     controller-id    - Player ID who controls this copy
     target-override  - (optional) Target map to use instead of inheriting
                        from original. Format: {:player player-id}

   Returns:
     New db with copy on stack, or unchanged db if source not found."
  ([db source-object-id controller-id]
   (create-spell-copy db source-object-id controller-id nil))
  ([db source-object-id controller-id target-override]
   (if-let [source-obj (q/get-object db source-object-id)]
     (let [;; Get owner/controller entity IDs
           owner-eid (:db/id (:object/owner source-obj))
           controller-eid (q/get-player-eid db controller-id)
           card-eid (:db/id (:object/card source-obj))
           copy-id (random-uuid)
           ;; Create the copy object (no position yet — stack-item handles ordering)
           db-with-copy (d/db-with db [{:object/id copy-id
                                        :object/card card-eid
                                        :object/zone :stack
                                        :object/owner owner-eid
                                        :object/controller controller-eid
                                        :object/tapped false
                                        :object/is-copy true}])
           ;; Get the copy's EID for stack-item ref
           copy-eid (q/get-object-eid db-with-copy copy-id)
           ;; Resolve targets: use override if provided, else inherit from original
           targets (or target-override
                       (let [original-si (d/q '[:find (pull ?e [:stack-item/targets]) .
                                                :in $ ?src
                                                :where [?e :stack-item/source ?src]]
                                              db-with-copy source-object-id)]
                         (:stack-item/targets original-si)))
           ;; Create stack-item for the copy
           db-with-item (stack/create-stack-item db-with-copy
                                                 (cond-> {:stack-item/type :storm-copy
                                                          :stack-item/controller controller-id
                                                          :stack-item/source source-object-id
                                                          :stack-item/object-ref copy-eid
                                                          :stack-item/is-copy true}
                                                   targets
                                                   (assoc :stack-item/targets targets)))
           ;; Set object/position to match stack-item (backward compat)
           stack-item (stack/get-stack-item-by-object-ref db-with-item copy-eid)
           position (:stack-item/position stack-item)]
       (d/db-with db-with-item [[:db/add copy-eid :object/position position]]))
     db)))


;; === Turn-Based Action Triggers ===

(defn- untap-all-permanents
  "Untap all permanents controlled by a player on the battlefield.
   Pure function: (db, player-id) -> db"
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        ;; Find all tapped permanents controlled by player on battlefield
        tapped-permanents (d/q '[:find ?e
                                 :in $ ?controller
                                 :where [?e :object/controller ?controller]
                                 [?e :object/zone :battlefield]
                                 [?e :object/tapped true]]
                               db player-eid)]
    (if (seq tapped-permanents)
      (d/db-with db (mapv (fn [[eid]] [:db/add eid :object/tapped false])
                          tapped-permanents))
      db)))


(defmethod resolve-trigger :draw-step
  [db trigger]
  (let [game-state (q/get-game-state db)
        turn (:game/turn game-state)
        active-player (:trigger/controller trigger)]
    ;; Skip draw on turn 1 (MTG rules: play/draw rule)
    (if (and turn (> turn 1))
      (effects/execute-effect db active-player {:effect/type :draw :effect/amount 1})
      db)))


(defn- phase-in-permanents
  "Phase in all phased-out permanents owned by the active player.
   Called at beginning of untap step, before untapping.
   Ownership (not controller) determines who phases in the permanent.
   Direct zone change — does NOT trigger ETB."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        phased-out (when player-eid
                     (d/q '[:find [(pull ?obj [:object/id]) ...]
                            :in $ ?owner
                            :where [?obj :object/owner ?owner]
                            [?obj :object/zone :phased-out]]
                          db player-eid))]
    (reduce (fn [db' obj]
              (zones/phase-in db' (:object/id obj)))
            db
            (or phased-out []))))


(defn- clear-summoning-sickness
  "Clear summoning sickness for all creatures controlled by a player on battlefield.
   Only clears for the active player's creatures at their untap step."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        sick-creatures (d/q '[:find ?e
                              :in $ ?controller
                              :where [?e :object/controller ?controller]
                              [?e :object/zone :battlefield]
                              [?e :object/summoning-sick true]]
                            db player-eid)]
    (if (seq sick-creatures)
      (d/db-with db (mapv (fn [[eid]] [:db/retract eid :object/summoning-sick true])
                          sick-creatures))
      db)))


(defmethod resolve-trigger :untap-step
  [db trigger]
  (let [active-player (:trigger/controller trigger)]
    (-> db
        (phase-in-permanents active-player)
        (untap-all-permanents active-player)
        (clear-summoning-sickness active-player))))
