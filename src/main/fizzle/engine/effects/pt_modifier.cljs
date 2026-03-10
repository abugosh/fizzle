(ns fizzle.engine.effects.pt-modifier
  "PT modification effect handler.

   :apply-pt-modifier creates a :pt-modifier grant on the target creature
   with until-EOT expiration via the existing grants system.
   Reads :effect/power and :effect/toughness from the effect data — not
   hardcoded values."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]))


(defmethod effects/execute-effect-impl :apply-pt-modifier
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-not (q/get-object-eid db target-id)
        db
        (let [game-state (q/get-game-state db)
              current-turn (or (:game/turn game-state) 1)
              grant {:grant/id (random-uuid)
                     :grant/type :pt-modifier
                     :grant/source nil
                     :grant/expires {:expires/turn current-turn
                                     :expires/phase :cleanup}
                     :grant/data {:grant/power (:effect/power effect)
                                  :grant/toughness (:effect/toughness effect)}}]
          (grants/add-grant db target-id grant))))))
