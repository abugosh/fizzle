(ns fizzle.bots.protocol
  "Bot protocol functions.

   Bots are identified by their :player/bot-archetype value.
   Three functions dispatch on archetype keyword via spec lookup:
   - bot-priority-decision: what the bot does when it receives priority
   - bot-phase-action: what the bot does at a given phase during its turn
   - bot-deck: the deck list for a bot archetype"
  (:require
    [datascript.core :as d]
    [fizzle.bots.definitions :as definitions]
    [fizzle.bots.rules :as rules]))


(defn get-bot-archetype
  "Get the bot archetype for a player, or nil if the player is human.
   Pure function: (db, player-id) -> keyword | nil"
  [db player-id]
  (d/q '[:find ?arch .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/bot-archetype ?arch]]
       db player-id))


(defn bot-priority-decision
  "Decide what a bot does when it receives priority.
   Returns :pass or an action map like {:action :cast-spell :object-id oid :target pid}.
   context is a map with {:db game-db :player-id player-id}."
  [archetype context]
  (let [spec (definitions/get-spec archetype)]
    (if (and spec (:db context) (:player-id context))
      (rules/match-priority-rule (:bot/priority-rules spec) context)
      :pass)))


(defn bot-phase-action
  "Decide what a bot does at a given phase during its turn.
   Returns an action map: {:action :play-land} or {:action :pass}.
   Pure function: (archetype, phase, db, player-id) -> action-map"
  [archetype phase _db _player-id]
  (let [spec (definitions/get-spec archetype)]
    (if spec
      (rules/get-phase-action spec phase)
      {:action :pass})))


(defn bot-choose-attackers
  "Choose which eligible creatures a bot attacks with.
   Returns a vector of object-ids. Empty = don't attack.
   Pure function: (archetype, eligible-attacker-ids) -> [object-ids]"
  [archetype eligible-attackers]
  (let [spec (definitions/get-spec archetype)]
    (if spec
      (rules/choose-attackers spec eligible-attackers)
      [])))


(defn bot-deck
  "Return the deck list for a bot archetype.
   Returns a vector of {:card/id keyword :count int} maps.
   Pure function: (archetype) -> deck-list"
  [archetype]
  (or (definitions/get-deck archetype)
      (definitions/get-deck :goldfish)))
