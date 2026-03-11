(ns fizzle.events.lands
  "Land play and permanent tapping.
   Pure functions and re-frame event handlers."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.zones :as zones]
    [re-frame.core :as rf]))


(def land-card?
  "Check if an object's card has :land in its types. Delegates to engine/rules."
  rules/land-card?)


(def can-play-land?
  "Check if a player can play a land from their hand. Delegates to engine/rules."
  rules/can-play-land?)


(defn play-land
  "Play a land from hand to battlefield.
   Pure function: (db, player-id, object-id) -> db

   Validates via rules/can-play-land?, then:
   1. Moves land to battlefield and decrements land plays
   2. Registers card triggers
   3. Fires ETB effects from :card/etb-effects
   4. Dispatches :land-entered event for triggers like City of Traitors

   Returns unchanged db if validation fails."
  [db player-id object-id]
  (if-not (rules/can-play-land? db player-id object-id)
    db
    (let [player-eid (queries/get-player-eid db player-id)
          land-plays (d/q '[:find ?plays .
                            :in $ ?pid
                            :where [?e :player/id ?pid]
                            [?e :player/land-plays-left ?plays]]
                          db player-id)
          db-after-move (-> db
                            (zones/move-to-zone object-id :battlefield)
                            (d/db-with [[:db/add player-eid :player/land-plays-left (dec land-plays)]]))
          obj-after (queries/get-object db-after-move object-id)
          card (:object/card obj-after)
          etb-effects (:card/etb-effects card)
          db-after-triggers (if (seq (:card/triggers card))
                              (let [obj-eid (queries/get-object-eid db-after-move object-id)
                                    tx (trigger-db/create-triggers-for-card-tx
                                         db-after-move obj-eid player-eid (:card/triggers card))]
                                (d/db-with db-after-move tx))
                              db-after-move)
          db-after-etb (if (seq etb-effects)
                         (reduce (fn [db' effect]
                                   (let [resolved-effect (if (= :self (:effect/target effect))
                                                           (assoc effect :effect/target object-id)
                                                           effect)]
                                     (effects/execute-effect db' player-id resolved-effect)))
                                 db-after-triggers
                                 etb-effects)
                         db-after-triggers)]
      (dispatch/dispatch-event db-after-etb (game-events/land-entered-event object-id player-id)))))


(rf/reg-event-db
  :fizzle.events.game/play-land
  (fn [db [_ object-id player-id]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))]
      (assoc db :game/db (play-land game-db pid object-id)))))


(defn tap-permanent
  "Tap a permanent on the battlefield.
   Pure function: (db, object-id) -> db

   Sets :object/tapped to true for the given object.
   Returns unchanged db if object doesn't exist."
  [db object-id]
  (let [obj (queries/get-object db object-id)]
    (if obj
      (let [obj-eid (queries/get-object-eid db object-id)]
        (d/db-with db [[:db/add obj-eid :object/tapped true]]))
      db)))


(rf/reg-event-db
  :fizzle.events.game/tap-permanent
  (fn [db [_ object-id]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (tap-permanent game-db object-id)))))


(defn untap-permanent
  "Untap a permanent on the battlefield.
   Pure function: (db, object-id) -> db

   Sets :object/tapped to false for the given object.
   Returns unchanged db if object doesn't exist."
  [db object-id]
  (let [obj (queries/get-object db object-id)]
    (if obj
      (let [obj-eid (queries/get-object-eid db object-id)]
        (d/db-with db [[:db/add obj-eid :object/tapped false]]))
      db)))
