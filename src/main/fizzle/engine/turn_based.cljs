(ns fizzle.engine.turn-based
  "Turn-based game actions implemented as game-rule triggers.

   Turn-based actions execute immediately (no stack) when their
   phase is entered. They fire before card triggers."
  (:require
    [fizzle.engine.trigger-registry :as registry]))


(defn register-turn-based-actions!
  "Register all turn-based action triggers.
   Call once at game initialization.

   Registers:
   - :game-rule-draw - Draw a card on draw phase (skip turn 1)
   - :game-rule-untap - Untap all permanents on untap phase
   - :cleanup-expire-grants - Expire grants at cleanup phase

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

  ;; Cleanup step - expire grants (for "until end of turn" effects)
  (registry/register-trigger!
    {:trigger/id :cleanup-expire-grants
     :trigger/source :game-rule
     :trigger/type :expire-grants
     :trigger/event-type :phase-entered
     :trigger/filter {:event/phase :cleanup}
     :trigger/uses-stack? false
     :trigger/controller :player-1})

  nil)
