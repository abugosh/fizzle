(ns fizzle.db.queries
  "Common Datalog queries for game state.

   All queries are pure functions: (db, args) -> result
   They do not modify state."
  (:require [datascript.core :as d]))

(defn get-player-eid
  "Get the entity ID for a player by their :player/id.
   Returns nil if player doesn't exist."
  [db player-id]
  (d/q '[:find ?e .
         :in $ ?pid
         :where [?e :player/id ?pid]]
       db player-id))

(defn get-mana-pool
  "Get the mana pool for a player.
   Returns nil if player doesn't exist."
  [db player-id]
  (d/q '[:find ?pool .
         :in $ ?pid
         :where [?e :player/id ?pid]
                [?e :player/mana-pool ?pool]]
       db player-id))

(defn get-storm-count
  "Get the storm count (spells cast this turn) for a player.
   Returns nil if player doesn't exist."
  [db player-id]
  (d/q '[:find ?count .
         :in $ ?pid
         :where [?e :player/id ?pid]
                [?e :player/storm-count ?count]]
       db player-id))

(defn get-hand
  "Get all game objects in a player's hand.
   Returns vector of objects with their card data pulled in.
   Returns empty vector if player has no cards in hand.
   Returns nil if player doesn't exist."
  [db player-id]
  (let [player-eid (get-player-eid db player-id)]
    (when player-eid
      (->> (d/q '[:find [(pull ?obj [* {:object/card [*]}]) ...]
                  :in $ ?owner
                  :where [?obj :object/owner ?owner]
                         [?obj :object/zone :hand]]
                db player-eid)
           (vec)))))

(defn get-card
  "Get the card definition for a game object by its :object/id.
   Returns nil if object doesn't exist or has no card reference."
  [db object-id]
  (d/q '[:find (pull ?card [*]) .
         :in $ ?oid
         :where [?obj :object/id ?oid]
                [?obj :object/card ?card]]
       db object-id))

(defn get-object
  "Get a game object by its :object/id with card data pulled in.
   Returns nil if object doesn't exist."
  [db object-id]
  (d/q '[:find (pull ?obj [* {:object/card [*]}]) .
         :in $ ?oid
         :where [?obj :object/id ?oid]]
       db object-id))

(defn get-objects-in-zone
  "Get all game objects in a specific zone for a player.
   Returns vector of objects with card data."
  [db player-id zone]
  (let [player-eid (get-player-eid db player-id)]
    (when player-eid
      (->> (d/q '[:find [(pull ?obj [* {:object/card [*]}]) ...]
                  :in $ ?owner ?zone
                  :where [?obj :object/owner ?owner]
                         [?obj :object/zone ?zone]]
                db player-eid zone)
           (vec)))))

(defn get-game-state
  "Get the game state entity."
  [db]
  (d/q '[:find (pull ?g [* {:game/active-player [:player/id :player/name]}]) .
         :where [?g :game/id _]]
       db))
