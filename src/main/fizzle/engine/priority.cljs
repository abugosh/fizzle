(ns fizzle.engine.priority
  "Pure functions for MTG priority state management.

   Priority determines which player can act. Both players must pass
   priority (on an empty stack) for phases to advance. These functions
   operate on Datascript db values."
  (:require
    [datascript.core :as d]))


(def priority-phases
  "Phases where players receive priority per MTG rules.
   Untap and cleanup do not grant priority."
  #{:upkeep :draw :main1 :combat :main2 :end})


(defn get-priority-holder-eid
  "Get the entity ID of the player who currently holds priority."
  [db]
  (d/q '[:find ?p .
         :where [?g :game/id _]
         [?g :game/priority ?p]]
       db))


(defn get-passed-eids
  "Get the set of entity IDs of players who have passed priority."
  [db]
  (let [game (d/pull db [:game/passed] [:game/id :game-1])
        passed (:game/passed game)]
    (if passed
      (set (map :db/id passed))
      #{})))


(defn yield-priority
  "Add a player to the passed set.
   Pure function: (db, player-eid) -> db"
  [db player-eid]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/passed player-eid]])))


(defn both-passed?
  "Check if both players have passed priority."
  [db]
  (>= (count (get-passed-eids db)) 2))


(defn transfer-priority
  "Transfer priority to the other player.
   Pure function: (db, current-eid) -> db"
  [db current-eid]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        other-eid (d/q '[:find ?e .
                         :in $ ?current
                         :where [?e :player/id _]
                         [(not= ?e ?current)]]
                       db current-eid)]
    (d/db-with db [[:db/add game-eid :game/priority other-eid]])))


(defn reset-passes
  "Clear all passed flags.
   Pure function: (db) -> db"
  [db]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        passed (get-passed-eids db)]
    (if (seq passed)
      (d/db-with db (mapv (fn [p-eid] [:db/retract game-eid :game/passed p-eid])
                          passed))
      db)))


(defn set-player-stops
  "Set the phase stops for a player.
   Pure function: (db, player-eid, stops-set) -> db"
  [db player-eid stops]
  (d/db-with db [[:db/add player-eid :player/stops stops]]))


(defn check-stop
  "Check if a player has a stop set for a given phase.
   Returns true if the player's :player/stops contains the phase."
  [db player-eid phase]
  (let [stops (:player/stops (d/pull db [:player/stops] player-eid))]
    (boolean (and stops (contains? stops phase)))))


(defn get-auto-mode
  "Get the current auto-mode (:resolving, :f6, or nil)."
  [db]
  (:game/auto-mode (d/pull db [:game/auto-mode] [:game/id :game-1])))


(defn set-auto-mode
  "Set the auto-mode for priority passing.
   Pure function: (db, mode) -> db"
  [db mode]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/auto-mode mode]])))


(defn clear-auto-mode
  "Clear the auto-mode (set to nil).
   Pure function: (db) -> db"
  [db]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        current (get-auto-mode db)]
    (if current
      (d/db-with db [[:db/retract game-eid :game/auto-mode current]])
      db)))
