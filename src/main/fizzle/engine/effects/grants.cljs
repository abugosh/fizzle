(ns fizzle.engine.effects.grants
  "Grant and counter effects: grant-flashback, grant-delayed-draw,
   add-restriction, grant-mana-ability, add-counters."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]))


(defmethod effects/execute-effect-impl :add-counters
  [db player-id effect object-id]
  (let [target-id (:effect/target effect)
        raw-counters (:effect/counters effect)
        ;; Resolve dynamic counter values (e.g., {:+1/+1 {:dynamic/type :cost-exiled-card-mana-value}})
        counters-to-add (into {}
                              (map (fn [[counter-type count-val]]
                                     [counter-type (effects/resolve-dynamic-value
                                                     db player-id count-val object-id)]))
                              raw-counters)]
    (if-let [obj-eid (q/get-object-eid db target-id)]
      (let [existing (or (q/q-safe '[:find ?c .
                                     :in $ ?e
                                     :where [?e :object/counters ?c]]
                                   db obj-eid)
                         {})
            merged (merge-with + existing counters-to-add)]
        (d/db-with db [[:db/add obj-eid :object/counters merged]]))
      db)))


(defmethod effects/execute-effect-impl :grant-flashback
  [db _player-id effect object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-let [target-obj (q/get-object db target-id)]
        (let [target-card (:object/card target-obj)
              target-mana-cost (:card/mana-cost target-card)
              game-state (q/get-game-state db)
              current-turn (or (:game/turn game-state) 1)
              grant {:grant/id (random-uuid)
                     :grant/type :alternate-cost
                     :grant/source object-id
                     :grant/expires {:expires/turn current-turn
                                     :expires/phase :cleanup}
                     :grant/data {:alternate/id :granted-flashback
                                  :alternate/zone :graveyard
                                  :alternate/mana-cost target-mana-cost
                                  :alternate/on-resolve :exile}}]
          (grants/add-grant db target-id grant))
        db))))


(defmethod effects/execute-effect-impl :grant-delayed-draw
  [db player-id effect object-id]
  (let [target (:effect/target effect)
        target-player (case target
                        :controller player-id
                        :self player-id
                        target)
        game-state (q/get-game-state db)
        current-turn (or (:game/turn game-state) 1)
        next-turn (inc current-turn)
        source-name (when object-id
                      (:card/name (:object/card (q/get-object db object-id))))
        grant {:grant/id (random-uuid)
               :grant/type :delayed-effect
               :grant/source object-id
               :grant/expires {:expires/turn next-turn
                               :expires/phase :upkeep}
               :grant/data {:delayed/phase :upkeep
                            :delayed/description (str (or source-name "Delayed trigger")
                                                      " — Draw a card")
                            :delayed/effect {:effect/type :draw
                                             :effect/amount 1}}}]
    (grants/add-player-grant db target-player grant)))


(defmethod effects/execute-effect-impl :add-restriction
  [db player-id effect source-object-id]
  (let [target-raw (:effect/target effect)
        target-player (case target-raw
                        :self player-id
                        :opponent (q/get-other-player-id db player-id)
                        (or target-raw player-id))
        game (q/get-game-state db)
        current-turn (or (:game/turn game) 1)]
    (if (q/get-player-eid db target-player)
      (let [grant {:grant/id (random-uuid)
                   :grant/type :restriction
                   :grant/source source-object-id
                   :grant/expires {:expires/turn current-turn :expires/phase :cleanup}
                   :grant/data {:restriction/type (:restriction/type effect)}}]
        (grants/add-player-grant db target-player grant))
      db)))


(defmethod effects/execute-effect-impl :grant-mana-ability
  [db player-id effect object-id]
  (let [target-filter (:effect/target effect)
        ability-data (:effect/ability effect)
        game-state (q/get-game-state db)
        current-turn (or (:game/turn game-state) 1)]
    (if (= target-filter :controlled-lands)
      (let [lands (q/query-zone-by-criteria db player-id :battlefield
                                            {:match/types #{:land}})
            land-ids (map :object/id lands)]
        (reduce (fn [d land-id]
                  (grants/add-grant d land-id
                                    {:grant/id (random-uuid)
                                     :grant/type :ability
                                     :grant/source object-id
                                     :grant/expires {:expires/turn current-turn
                                                     :expires/phase :cleanup}
                                     :grant/data ability-data}))
                db land-ids))
      db)))
