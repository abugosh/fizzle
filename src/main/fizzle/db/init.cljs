(ns fizzle.db.init
  "Initialize game state for Fizzle.

   Creates a Datascript database with:
   - Card definitions loaded
   - Player(s) initialized
   - Game objects (cards in zones) created
   - Game state set up"
  (:require
    [datascript.core :as d]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]))


(defn init-game-state
  "Create a fresh game state with Dark Ritual in hand.

   Returns an immutable Datascript db value (not a conn).
   This is the starting point for goldfishing.

   The returned db contains:
   - Dark Ritual card definition
   - Player 1 with 20 life, empty mana pool, 0 storm count
   - One Dark Ritual object in player's hand
   - Game state at turn 1, main phase 1"
  []
  (let [conn (d/create-conn schema)]
    ;; 1. Transact card definitions
    (d/transact! conn [(cards/card-by-id :dark-ritual)])

    ;; 2. Transact player
    (d/transact! conn (game-state/create-player-tx game-state/human-player-id
                                                   {:player/name "Player"}))

    ;; 3. Get entity IDs for references
    (let [player-eid (d/q '[:find ?e .
                            :where [?e :player/id :player-1]]
                          @conn)
          card-eid (d/q '[:find ?e .
                          :where [?e :card/id :dark-ritual]]
                        @conn)]

      ;; 4. Transact game object (Dark Ritual in hand)
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])

      ;; 5. Transact game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid
                          :game/human-player-id :player-1}]))

    ;; Return immutable db value
    @conn))
