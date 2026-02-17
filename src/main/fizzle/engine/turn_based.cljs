(ns fizzle.engine.turn-based
  "Turn-based game actions implemented as game-rule triggers.

   Turn-based actions execute immediately (no stack) when their
   phase is entered. They fire before card triggers."
  (:require
    [fizzle.engine.trigger-db :as trigger-db]))


(defn create-turn-based-triggers-tx
  "Return tx-data for game-rule triggers as Datascript entities.
   Pure function — caller must transact the returned data.

   Creates draw-step and untap-step triggers with :trigger/always-active? true.
   Each trigger filters on both phase and player-id so it only fires on its
   controller's turn (not the opponent's turn).

   Arguments:
     player-eid - Entity ID of the controlling player
     player-id  - Player keyword (e.g. :player-1, :player-2) for event filtering

   Returns:
     Vector of tx-data maps"
  [player-eid player-id]
  (vec (concat
         (trigger-db/create-game-rule-trigger-tx
           {:trigger/type :draw-step
            :trigger/event-type :phase-entered
            :trigger/filter {:event/phase :draw :event/player player-id}
            :trigger/controller player-eid})
         (trigger-db/create-game-rule-trigger-tx
           {:trigger/type :untap-step
            :trigger/event-type :phase-entered
            :trigger/filter {:event/phase :untap :event/player player-id}
            :trigger/controller player-eid}))))
