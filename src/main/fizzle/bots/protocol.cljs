(ns fizzle.bots.protocol
  "Bot priority decision protocol.

   Bots are identified by their :player/bot-archetype value.
   The bot-priority-decision multimethod dispatches on archetype
   to determine what the bot does when it receives priority."
  (:require
    [datascript.core :as d]))


(defn get-bot-archetype
  "Get the bot archetype for a player, or nil if the player is human.
   Pure function: (db, player-id) -> keyword | nil"
  [db player-id]
  (d/q '[:find ?arch .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/bot-archetype ?arch]]
       db player-id))


(defmulti bot-priority-decision
  "Decide what a bot does when it receives priority.
   Dispatches on archetype keyword.
   Returns :pass (always, for now — future archetypes may return actions).
   context is a map with game state info (unused for now)."
  (fn [archetype _context] archetype))


(defmethod bot-priority-decision :goldfish
  [_ _]
  :pass)


(defmethod bot-priority-decision :default
  [_ _]
  :pass)
