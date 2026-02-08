(ns fizzle.history.descriptions)


(defn describe-event
  "Generate a human-readable description for a re-frame event.
   Takes event vector [event-id & args]. Returns string or nil.
   Returns nil for events that should not generate descriptions (UI-only events)."
  [[event-id & _args]]
  (case event-id
    ;; Game events
    :fizzle.events.game/cast-spell          "Cast spell"
    :fizzle.events.game/resolve-top         "Resolve top of stack"
    :fizzle.events.game/advance-phase       "Advance phase"
    :fizzle.events.game/start-turn          "Start new turn"
    :fizzle.events.game/play-land           "Play land"
    :fizzle.events.game/select-casting-mode "Select casting mode"

    ;; Ability events
    :fizzle.events.abilities/activate-mana-ability  "Activate mana ability"
    :fizzle.events.abilities/activate-ability        "Activate ability"
    :fizzle.events.abilities/confirm-ability-target  "Confirm ability target"

    ;; Selection confirmations (these change game state)
    :fizzle.events.selection/confirm-selection              "Confirm selection"
    :fizzle.events.selection/confirm-peek-selection         "Confirm peek selection"
    :fizzle.events.selection/confirm-graveyard-selection    "Confirm graveyard selection"
    :fizzle.events.selection/confirm-tutor-selection        "Confirm tutor selection"
    :fizzle.events.selection/confirm-pile-choice-selection  "Confirm pile choice"
    :fizzle.events.selection/confirm-scry-selection         "Confirm scry"
    :fizzle.events.selection/confirm-player-target-selection "Confirm player target"
    :fizzle.events.selection/confirm-exile-cards-selection  "Confirm exile cards"
    :fizzle.events.selection/confirm-x-mana-selection      "Confirm X mana"
    :fizzle.events.selection/confirm-cast-time-target      "Confirm cast-time target"

    ;; Default: nil for unknown/UI-only events
    nil))
