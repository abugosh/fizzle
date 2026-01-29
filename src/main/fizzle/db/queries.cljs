(ns fizzle.db.queries
  "Common Datalog queries for game state.

   All queries are pure functions: (db, args) -> result
   They do not modify state."
  (:require
    [datascript.core :as d]))


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


;; === Trigger Queries ===

(defn get-trigger
  "Get a trigger by its :trigger/id.
   Returns trigger map or nil if not found."
  [db trigger-id]
  (d/q '[:find (pull ?t [*]) .
         :in $ ?tid
         :where [?t :trigger/id ?tid]]
       db trigger-id))


(defn get-next-stack-order
  "Get the next stack order value.
   Checks both trigger stack-orders and object positions on the stack
   so they share a unified counter space.
   Returns current max + 1, or 0 if stack is empty."
  [db]
  (let [trigger-max (d/q '[:find (max ?order) .
                           :where [?t :trigger/stack-order ?order]]
                         db)
        object-max (d/q '[:find (max ?pos) .
                          :where [?o :object/zone :stack]
                          [?o :object/position ?pos]]
                        db)
        current-max (max (or trigger-max -1) (or object-max -1))]
    (inc current-max)))


(defn get-stack-items
  "Get all items on the stack in LIFO order (most recent first).
   Returns vector of trigger maps ordered by stack position.
   Returns empty vector if stack is empty."
  [db]
  (->> (d/q '[:find [(pull ?t [*]) ...]
              :where [?t :trigger/stack-order _]]
            db)
       (sort-by :trigger/stack-order >)
       (vec)))


;; === Library Queries ===

(defn get-top-n-library
  "Get the top N cards from a player's library by position.
   Returns vector of object-ids (lowest position = top).
   Returns [] if library has fewer than N cards (returns all available).
   Returns nil if player doesn't exist."
  [db player-id n]
  (let [player-eid (get-player-eid db player-id)]
    (when player-eid
      (->> (d/q '[:find ?oid ?pos
                  :in $ ?owner
                  :where [?obj :object/owner ?owner]
                  [?obj :object/zone :library]
                  [?obj :object/id ?oid]
                  [?obj :object/position ?pos]]
                db player-eid)
           (sort-by second)  ; sort by position ascending (0 = top)
           (take n)
           (mapv first)))))  ; extract just object-ids


(defn get-opponent-id
  "Get the opponent player's ID for a given player.
   Returns the player-id of the player marked as opponent.
   Returns nil if no opponent exists."
  [db player-id]
  (d/q '[:find ?pid .
         :in $ ?my-pid
         :where [?p :player/id ?pid]
         [?p :player/is-opponent true]
         [(not= ?pid ?my-pid)]]
       db player-id))


(defn get-life-total
  "Get the life total for a player.
   Returns nil if player doesn't exist."
  [db player-id]
  (d/q '[:find ?life .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/life ?life]]
       db player-id))
