(ns fizzle.engine.events
  "Event creation helpers for the event-driven trigger system.

   Events are immutable maps describing game state changes.
   All functions are pure: (args) -> event-map")


(defn phase-entered-event
  "Create a phase-entered event.

   Arguments:
     phase     - Keyword identifying the phase (e.g. :draw, :upkeep)
     turn      - Turn number (integer)
     player-id - ID of the active player

   Returns:
     Event map with :event/type :phase-entered"
  [phase turn player-id]
  {:event/type :phase-entered
   :event/phase phase
   :event/turn turn
   :event/player player-id})


(defn permanent-tapped-event
  "Create a permanent-tapped event.

   Arguments:
     object-id     - ID of the tapped permanent
     controller-id - ID of the permanent's controller

   Returns:
     Event map with :event/type :permanent-tapped"
  [object-id controller-id]
  {:event/type :permanent-tapped
   :event/object-id object-id
   :event/controller controller-id})


(defn zone-change-event
  "Create a zone-change event.

   Arguments:
     object-id - ID of the object changing zones
     from-zone - Keyword for source zone (e.g. :library, :hand)
     to-zone   - Keyword for destination zone

   Returns:
     Event map with :event/type :zone-change"
  [object-id from-zone to-zone]
  {:event/type :zone-change
   :event/object-id object-id
   :event/from-zone from-zone
   :event/to-zone to-zone})


(defn land-entered-event
  "Create a land-entered event.

   Arguments:
     object-id     - ID of the land that entered the battlefield
     controller-id - Player ID of the land's controller

   Returns:
     Event map with :event/type :land-entered"
  [object-id controller-id]
  {:event/type :land-entered
   :event/object-id object-id
   :event/controller controller-id})


(defn creature-attacked-event
  "Create a creature-attacked event.

   Arguments:
     object-id     - ID of the attacking creature
     controller-id - ID of the creature's controller

   Returns:
     Event map with :event/type :creature-attacked"
  [object-id controller-id]
  {:event/type :creature-attacked
   :event/object-id object-id
   :event/controller controller-id})
