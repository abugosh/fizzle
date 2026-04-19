(ns fizzle.engine.effects.stack
  "Stack-interaction effects: counter-spell, counter-ability, chain-bounce.

   Defines counter-target-spell here (not in engine/effects.cljs) because this
   namespace can safely require zone-change-dispatch without creating a cycle:
     effects/stack → zone-change-dispatch → trigger-dispatch → triggers → effects
   engine/effects.cljs cannot require zone-change-dispatch (would create a cycle
   through the same chain back to effects)."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.engine.zones :as zones]))


(defn counter-target-spell
  "Counter a spell on the stack — move it to the appropriate zone.

   Zone transitions:
   - Copies cease to exist (removed from db)
   - Flashback spells go to exile (MTG rule)
   - All other spells go to graveyard (including permanents)

   Routes through zone-change-dispatch/move-to-zone so that :zone-change
   triggers fire on the countered spell's move (stack → destination).
   Stack-item cleanup is handled automatically by zones/move-to-zone
   and zones/remove-object when the object leaves the :stack zone.

   Returns db. No-op if target is nil, doesn't exist, or not on stack."
  [db target-id]
  (if-not target-id
    db
    (if-let [obj (q/get-object db target-id)]
      (if (not= :stack (:object/zone obj))
        db
        (if (:object/is-copy obj)
          (zones/remove-object db target-id)
          (let [cast-mode (:object/cast-mode obj)
                mode-destination (:mode/on-resolve cast-mode)
                destination (if (= :exile mode-destination) :exile :graveyard)]
            (zone-change-dispatch/move-to-zone-db db target-id destination))))
      db)))


(defmethod effects/execute-effect-impl :counter-spell
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)
        unless-pay (:effect/unless-pay effect)]
    (if-not target-id
      db
      (if-let [target-obj (q/get-object db target-id)]
        (if (not= :stack (:object/zone target-obj))
          db
          (if unless-pay
            (let [controller-eid (:db/id (:object/controller target-obj))
                  controller-id (when controller-eid
                                  (:player/id (d/pull db [:player/id] controller-eid)))]
              {:db db :needs-selection (assoc effect :unless-pay/controller controller-id)})
            (counter-target-spell db target-id)))
        db))))


(defmethod effects/execute-effect-impl :counter-ability
  [db _player-id effect _object-id]
  (let [target-eid (:effect/target effect)]
    (if-not target-eid
      db
      (if-let [stack-item (d/pull db '[*] target-eid)]
        (let [item-type (:stack-item/type stack-item)
              ability-type (:stack-item/ability-type stack-item)]
          (cond
            (not (or (= :activated-ability item-type)
                     (= :triggered-ability item-type)))
            db

            (= :mana ability-type)
            db

            :else
            (stack/remove-stack-item db target-eid)))
        db))))


(defmethod effects/execute-effect-impl :chain-bounce
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (let [target-obj (q/get-object db target-id)
            controller-eid (when target-obj
                             (:db/id (:object/controller target-obj)))
            controller-id (when controller-eid
                            (:player/id (d/pull db [:player/id] controller-eid)))]
        {:db db :needs-selection (cond-> effect
                                   controller-id (assoc :chain/controller controller-id)
                                   target-id (assoc :chain/target-id target-id))}))))
