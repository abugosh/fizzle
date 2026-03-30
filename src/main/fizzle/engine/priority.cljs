(ns fizzle.engine.priority
  "Pure functions for MTG priority state management.

   Priority determines which player can act. Both players must pass
   priority (on an empty stack) for phases to advance. These functions
   operate on Datascript db values."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]))


(def priority-phases
  "Phases where players receive priority per MTG rules.
   Untap and cleanup do not grant priority."
  #{:upkeep :draw :main1 :combat :main2 :end})


(defn in-priority-phase?
  "Check if the given phase grants priority to players.
   Returns false for :untap, :cleanup, nil, and unknown phases."
  [phase]
  (boolean (contains? priority-phases phase)))


(defn get-priority-holder-eid
  "Get the entity ID of the player who currently holds priority."
  [db]
  (queries/q-safe '[:find ?p .
                    :where [?g :game/id _]
                    [?g :game/priority ?p]]
                  db))


(defn get-passed-eids
  "Get the set of entity IDs of players who have passed priority."
  [db]
  (let [game (queries/pull-safe db [:game/passed] [:game/id :game-1])
        passed (:game/passed game)]
    (if passed
      (set (map :db/id passed))
      #{})))


(defn yield-priority
  "Add a player to the passed set.
   Pure function: (db, player-eid) -> db"
  [db player-eid]
  (let [game-eid (queries/q-safe '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/passed player-eid]])))


(defn both-passed?
  "Check if both players have passed priority."
  [db]
  (>= (count (get-passed-eids db)) 2))


(defn transfer-priority
  "Transfer priority to the other player.
   Pure function: (db, current-eid) -> db"
  [db current-eid]
  (let [game-eid (queries/q-safe '[:find ?e . :where [?e :game/id _]] db)
        other-eid (queries/q-safe '[:find ?e .
                                    :in $ ?current
                                    :where [?e :player/id _]
                                    [(not= ?e ?current)]]
                                  db current-eid)]
    (d/db-with db [[:db/add game-eid :game/priority other-eid]])))


(defn reset-passes
  "Clear all passed flags.
   Pure function: (db) -> db"
  [db]
  (let [game-eid (queries/q-safe '[:find ?e . :where [?e :game/id _]] db)
        passed (get-passed-eids db)]
    (if (seq passed)
      (d/db-with db (mapv (fn [p-eid] [:db/retract game-eid :game/passed p-eid])
                          passed))
      db)))


(defn set-priority-holder
  "Set the priority holder to a specific player.
   Pure function: (db, player-eid) -> db"
  [db player-eid]
  (let [game-eid (queries/q-safe '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/priority player-eid]])))
