(ns fizzle.engine.effects.simple
  "Simple non-interactive effects: add-mana, peek-random-hand."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]))


(defmethod effects/execute-effect-impl :add-mana
  [db player-id effect _object-id]
  (mana/add-mana db player-id (:effect/mana effect)))


(defmethod effects/execute-effect-impl :peek-random-hand
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)]
    (if target-player
      (let [hand (q/get-hand db target-player)]
        (if (seq hand)
          (let [card (rand-nth hand)
                card-name (get-in card [:object/card :card/name])
                game-eid (q/q-safe '[:find ?g . :where [?g :game/id _]] db)]
            (d/db-with db [[:db/add game-eid :game/peek-result card-name]]))
          db))
      db)))
