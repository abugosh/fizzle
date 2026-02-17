(ns fizzle.bots.protocol
  "Bot protocol multimethods.

   Bots are identified by their :player/bot-archetype value.
   Three multimethods dispatch on archetype keyword:
   - bot-priority-decision: what the bot does when it receives priority
   - bot-phase-action: what the bot does at a given phase during its turn
   - bot-deck: the deck list for a bot archetype"
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


(defmulti bot-phase-action
  "Decide what a bot does at a given phase during its turn.
   Dispatches on archetype keyword.
   Returns an action map:
     {:action :play-land} — play a land from hand
     {:action :pass}      — do nothing this phase
   Pure function: (archetype, phase, db, player-id) -> action-map"
  (fn [archetype _phase _db _player-id] archetype))


(defmethod bot-phase-action :goldfish
  [_ phase _db _player-id]
  (if (= :main1 phase)
    {:action :play-land}
    {:action :pass}))


(defmethod bot-phase-action :default
  [_ _phase _db _player-id]
  {:action :pass})


(defmulti bot-deck
  "Return the deck list for a bot archetype.
   Dispatches on archetype keyword.
   Returns a vector of {:card/id keyword :count int} maps.
   Pure function: (archetype) -> deck-list"
  (fn [archetype] archetype))


(defmethod bot-deck :goldfish
  [_]
  [{:card/id :plains :count 12}
   {:card/id :island :count 12}
   {:card/id :swamp :count 12}
   {:card/id :mountain :count 12}
   {:card/id :forest :count 12}])


(defmethod bot-deck :default
  [_]
  [{:card/id :plains :count 12}
   {:card/id :island :count 12}
   {:card/id :swamp :count 12}
   {:card/id :mountain :count 12}
   {:card/id :forest :count 12}])
