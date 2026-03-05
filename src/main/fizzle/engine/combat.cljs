(ns fizzle.engine.combat
  "Combat system for Fizzle.

   Pure functions for combat phase initiation, attacker selection,
   and combat state management. Combat uses the existing stack and
   priority system — no separate state machine."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.stack :as stack]))


(defn has-creatures-on-battlefield?
  "Check if any creatures exist on the battlefield (either player).
   Used to determine whether to enter combat or skip to Main 2."
  [db]
  (let [bf-objects (q/get-all-objects-in-zone db :battlefield)]
    (boolean (some (fn [obj]
                     (let [card-types (set (or (:card/types (:object/card obj)) #{}))]
                       (contains? card-types :creature)))
                   bf-objects))))


(defn get-eligible-attackers
  "Get object-ids of creatures that can legally attack for a player.
   Checks: creature, not summoning sick, not tapped, not defender."
  [db player-id]
  (let [bf (q/get-objects-in-zone db player-id :battlefield)]
    (->> bf
         (filter (fn [obj]
                   (creatures/can-attack? db (:object/id obj))))
         (mapv :object/id))))


(defn tap-and-mark-attackers
  "Tap selected attackers and mark them as attacking.
   Pure function: (db, attacker-ids) -> db"
  [db attacker-ids]
  (if (empty? attacker-ids)
    db
    (let [txs (mapcat (fn [obj-id]
                        (let [obj-eid (q/get-object-eid db obj-id)]
                          [[:db/add obj-eid :object/tapped true]
                           [:db/add obj-eid :object/attacking true]]))
                      attacker-ids)]
      (d/db-with db (vec txs)))))



(defn get-attacking-creatures
  "Get object-ids of all attacking creatures on the battlefield."
  [db]
  (let [eids (d/q '[:find [?e ...]
                     :where [?e :object/zone :battlefield]
                     [?e :object/attacking true]]
                   db)]
    (mapv (fn [eid] (:object/id (d/pull db [:object/id] eid))) eids)))


(defn get-eligible-blockers
  "Get object-ids of creatures that can legally block a specific attacker.
   Checks: creature, not tapped, not already blocking, flying/reach rules."
  [db defender-player-id attacker-id]
  (let [bf (q/get-objects-in-zone db defender-player-id :battlefield)]
    (->> bf
         (filter (fn [obj]
                   (let [obj-id (:object/id obj)]
                     (and (creatures/can-block? db obj-id attacker-id)
                          (nil? (:object/blocking obj))))))
         (mapv :object/id))))


(defn mark-blockers
  "Mark selected blockers as blocking a specific attacker.
   Pure function: (db, blocker-ids, attacker-id) -> db"
  [db blocker-ids attacker-id]
  (if (empty? blocker-ids)
    db
    (let [atk-eid (q/get-object-eid db attacker-id)
          txs (mapv (fn [obj-id]
                      (let [obj-eid (q/get-object-eid db obj-id)]
                        [:db/add obj-eid :object/blocking atk-eid]))
                    blocker-ids)]
      (d/db-with db txs))))


(defn clear-combat-state
  "Clear all attacking/blocking flags from battlefield creatures.
   Called at end of combat or cleanup."
  [db]
  (let [attacking (d/q '[:find ?e
                         :where [?e :object/zone :battlefield]
                         [?e :object/attacking true]]
                       db)
        blocking (d/q '[:find ?e ?v
                        :where [?e :object/zone :battlefield]
                        [?e :object/blocking ?v]]
                      db)
        txs (concat
              (mapv (fn [[eid]] [:db/retract eid :object/attacking true]) attacking)
              (mapv (fn [[eid v]] [:db/retract eid :object/blocking v]) blocking))]
    (if (seq txs)
      (d/db-with db (vec txs))
      db)))


(defn begin-combat
  "Begin the combat phase by pushing :declare-attackers on the stack.
   Returns db unchanged if no creatures exist on the battlefield.
   Pure function: (db, active-player-id) -> db"
  [db active-player-id]
  (if (has-creatures-on-battlefield? db)
    (stack/create-stack-item db
                             {:stack-item/type :declare-attackers
                              :stack-item/controller active-player-id
                              :stack-item/description "Declare Attackers"})
    db))
