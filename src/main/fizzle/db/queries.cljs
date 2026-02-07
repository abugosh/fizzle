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


(defn stack-empty?
  "Check if the stack has no items (no spells and no stack-items).
   The stack is shared between all players (MTG Rules 117, 405)."
  [db]
  (and (nil? (d/q '[:find ?e .
                    :where [?e :object/zone :stack]]
                  db))
       (nil? (d/q '[:find ?e .
                    :where [?e :stack-item/position _]]
                  db))))


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


(defn get-max-hand-size
  "Get the maximum hand size for a player.
   Returns 7 (MTG default) if :player/max-hand-size is not set."
  [db player-id]
  (or (d/q '[:find ?max .
             :in $ ?pid
             :where [?e :player/id ?pid]
             [?e :player/max-hand-size ?max]]
           db player-id)
      7))


(defn get-life-total
  "Get the life total for a player.
   Returns nil if player doesn't exist."
  [db player-id]
  (d/q '[:find ?life .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/life ?life]]
       db player-id))


(defn- matches-criteria?
  "Check if an object's card matches the given criteria.
   Criteria map supports:
     :card/types    - Set of types, object must have ALL (AND)
     :card/colors   - Set of colors, object must have ANY (OR - blue OR white)
     :card/subtypes - Set of subtypes, object must have ANY (OR)"
  [obj criteria]
  (let [card (:object/card obj)
        card-types (set (or (:card/types card) #{}))
        card-colors (set (or (:card/colors card) #{}))
        card-subtypes (set (or (:card/subtypes card) #{}))]
    (and
      ;; All required types must be present (AND logic)
      (every? card-types (get criteria :card/types #{}))
      ;; At least one required color (OR logic) - empty criteria = matches all
      (or (empty? (get criteria :card/colors #{}))
          (some card-colors (get criteria :card/colors #{})))
      ;; At least one required subtype (OR logic)
      (or (empty? (get criteria :card/subtypes #{}))
          (some card-subtypes (get criteria :card/subtypes #{}))))))


(defn query-zone-by-criteria
  "Return objects in a zone matching criteria.
   Pure function: (db, player-id, zone, criteria) -> vector

   Criteria map supports:
     :card/types    - Set of types, object must have ALL (AND)
     :card/colors   - Set of colors, object must have ANY (OR - blue OR white)
     :card/subtypes - Set of subtypes, object must have ANY (OR)

   Returns vector of objects with card data, or [] if no matches.
   Returns nil if player doesn't exist."
  [db player-id zone criteria]
  (let [player-eid (get-player-eid db player-id)]
    (when player-eid
      (let [zone-objs (get-objects-in-zone db player-id zone)]
        (filterv #(matches-criteria? % criteria) zone-objs)))))


(defn query-library-by-criteria
  "Return objects in library matching criteria.
   Pure function: (db, player-id, criteria) -> vector

   Criteria map supports:
     :card/types    - Set of types, object must have ALL (AND)
     :card/colors   - Set of colors, object must have ANY (OR - blue OR white)
     :card/subtypes - Set of subtypes, object must have ANY (OR)

   Returns vector of objects with card data, or [] if no matches.
   Returns nil if player doesn't exist."
  [db player-id criteria]
  (query-zone-by-criteria db player-id :library criteria))
