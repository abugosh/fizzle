(ns fizzle.db.game-state
  "Single source of truth for game state entity construction.

   Exports identity constants (human-player-id, opponent-player-id) and
   flat factory functions (create-player-tx, create-game-entity-tx) with
   universal defaults. No role dispatch.

   All consumers (events/init.cljs, test_helpers.cljs, sharing/restorer.cljs)
   should import from here instead of constructing entities inline.

   ADR-016: :opponent as a player-id is forbidden — it collides with
   effect target directives (:effect/target :opponent). Use :player-2."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.engine.turn-based :as turn-based]))


(def human-player-id
  "The canonical player-id for the human player."
  :player-1)


(def opponent-player-id
  "The canonical player-id for the opponent player.
   Must be :player-2 (not :opponent) — ADR-016: :opponent is an effect target directive."
  :player-2)


(def empty-mana-pool
  "A mana pool with zero of each color."
  {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0})


(defn create-player-tx
  "Return transaction data (vector of one tx-map) for a player entity.

   player-id: keyword identifying the player (e.g. human-player-id, opponent-player-id)
   overrides: map of player attributes to merge over the defaults

   Default values:
     :player/life           20
     :player/mana-pool      empty-mana-pool
     :player/storm-count    0
     :player/land-plays-left 1
     :player/max-hand-size  7

   Note: :player/mana-pool is replaced wholesale by override (not deep-merged).
   If overriding mana-pool, provide all color keys."
  [player-id overrides]
  (let [mana-pool (get overrides :player/mana-pool empty-mana-pool)]
    (spec-util/validate-at-chokepoint! :game/mana-pool mana-pool "create-player-tx"))
  [(merge {:player/id             player-id
           :player/life           20
           :player/mana-pool      empty-mana-pool
           :player/storm-count    0
           :player/land-plays-left 1
           :player/max-hand-size  7}
          overrides)])


(defn create-game-entity-tx
  "Return transaction data (vector of one tx-map) for the game entity.

   active-player-eid: integer entity ID of the active player (must exist in db first)
   overrides: map of game attributes to merge over the defaults

   Default values:
     :game/id            :game-1
     :game/turn          1
     :game/phase         :main1
     :game/human-player-id human-player-id

   :game/active-player and :game/priority are both set to active-player-eid."
  [active-player-eid overrides]
  [(merge {:game/id            :game-1
           :game/turn          1
           :game/phase         :main1
           :game/active-player active-player-eid
           :game/priority      active-player-eid
           :game/human-player-id human-player-id}
          overrides)])


(defn create-complete-player
  "Create a complete player: entity + turn-based triggers.
   Transacts both into conn. Returns player-eid.

   player-id: keyword identifying the player (human-player-id or opponent-player-id)
   overrides: map merged into player defaults (life, mana, name, etc.)

   This is the canonical way to add a player — callers must not call
   create-player-tx + create-turn-based-triggers-tx independently."
  [conn player-id overrides]
  (d/transact! conn (create-player-tx player-id overrides))
  (let [player-eid (q/get-player-eid @conn player-id)]
    (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid player-id))
    player-eid))
