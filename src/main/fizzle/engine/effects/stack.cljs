(ns fizzle.engine.effects.stack
  "Stack-interaction effects: counter-spell, counter-ability, chain-bounce."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.stack :as stack]))


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
                                  (:player/id (q/pull-safe db [:player/id] controller-eid)))]
              {:db db :needs-selection (assoc effect :unless-pay/controller controller-id)})
            (effects/counter-target-spell db target-id)))
        db))))


(defmethod effects/execute-effect-impl :counter-ability
  [db _player-id effect _object-id]
  (let [target-eid (:effect/target effect)]
    (if-not target-eid
      db
      (if-let [stack-item (q/pull-safe db '[*] target-eid)]
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
                            (:player/id (q/pull-safe db [:player/id] controller-eid)))]
        {:db db :needs-selection (cond-> effect
                                   controller-id (assoc :chain/controller controller-id)
                                   target-id (assoc :chain/target-id target-id))}))))
