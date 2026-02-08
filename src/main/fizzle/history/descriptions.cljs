(ns fizzle.history.descriptions)


(defn describe-event
  "Generate a human-readable description for a re-frame event.
   Takes event vector [event-id & args]. Returns string or nil.
   Only priority-action events need descriptions (the interceptor filters others)."
  [[event-id & _args]]
  (case event-id
    :fizzle.events.game/init-game                         "Game started"
    :fizzle.events.game/cast-spell                        "Cast spell"
    :fizzle.events.game/resolve-top                       "Resolve top of stack"
    :fizzle.events.game/advance-phase                     "Advance phase"
    :fizzle.events.game/start-turn                        "Start new turn"
    :fizzle.events.game/play-land                         "Play land"
    :fizzle.events.abilities/activate-mana-ability        "Activate mana ability"
    :fizzle.events.abilities/activate-ability              "Activate ability"
    nil))
