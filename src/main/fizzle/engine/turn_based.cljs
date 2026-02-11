(ns fizzle.engine.turn-based
  "Turn-based game actions implemented as game-rule triggers.

   Turn-based actions execute immediately (no stack) when their
   phase is entered. They fire before card triggers."
  (:require
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-registry :as registry]))


(defn register-turn-based-actions!
  "Register all turn-based action triggers.
   Call once at game initialization.

   Registers:
   - :game-rule-draw - Draw a card on draw phase (skip turn 1)
   - :game-rule-untap - Untap all permanents on untap phase

   Note: Grant expiration at cleanup is handled explicitly by the
   ::advance-phase handler after hand-size discard (MTG Rule 514).

   Returns:
     nil"
  []
  ;; Draw step - draw a card (skip turn 1)
  (registry/register-trigger!
    {:trigger/id :game-rule-draw
     :trigger/source :game-rule
     :trigger/type :draw-step
     :trigger/event-type :phase-entered
     :trigger/filter {:event/phase :draw}
     :trigger/uses-stack? false
     :trigger/controller :player-1})

  ;; Untap step - untap all permanents
  (registry/register-trigger!
    {:trigger/id :game-rule-untap
     :trigger/source :game-rule
     :trigger/type :untap-step
     :trigger/event-type :phase-entered
     :trigger/filter {:event/phase :untap}
     :trigger/uses-stack? false
     :trigger/controller :player-1})

  nil)


(defn create-turn-based-triggers-tx
  "Return tx-data for game-rule triggers as Datascript entities.
   Pure function — caller must transact the returned data.

   Creates draw-step and untap-step triggers with :trigger/always-active? true.

   Arguments:
     player-eid - Entity ID of the controlling player

   Returns:
     Vector of tx-data maps"
  [player-eid]
  (vec (concat
         (trigger-db/create-game-rule-trigger-tx
           {:trigger/type :draw-step
            :trigger/event-type :phase-entered
            :trigger/filter {:event/phase :draw}
            :trigger/controller player-eid})
         (trigger-db/create-game-rule-trigger-tx
           {:trigger/type :untap-step
            :trigger/event-type :phase-entered
            :trigger/filter {:event/phase :untap}
            :trigger/controller player-eid}))))
