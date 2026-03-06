(ns fizzle.engine.combat
  "Combat system for Fizzle.

   Pure functions for combat phase initiation, attacker selection,
   combat damage assignment, and combat state management. Combat uses
   the existing stack and priority system — no separate state machine."
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


(defn get-blockers-for-attacker
  "Get object-ids of creatures blocking a specific attacker.
   Pure function: (db, attacker-id) -> [blocker-ids]"
  [db attacker-id]
  (let [atk-eid (q/get-object-eid db attacker-id)
        blocker-eids (d/q '[:find [?e ...]
                            :in $ ?atk-eid
                            :where [?e :object/zone :battlefield]
                            [?e :object/blocking ?atk-eid]]
                          db atk-eid)]
    (mapv (fn [eid] (:object/id (d/pull db [:object/id] eid)))
          (or blocker-eids []))))


(defn mark-damage
  "Add damage to a creature's damage-marked counter.
   Pure function: (db, object-id, amount) -> db"
  [db object-id amount]
  (if (<= amount 0)
    db
    (let [obj-eid (q/get-object-eid db object-id)
          current (or (:object/damage-marked (q/get-object db object-id)) 0)]
      (d/db-with db [[:db/add obj-eid :object/damage-marked (+ current amount)]]))))


(defn deal-damage-to-player
  "Deal damage to a player by reducing life total.
   Pure function: (db, player-id, amount) -> db"
  [db player-id amount]
  (if (<= amount 0)
    db
    (let [player-eid (q/get-player-eid db player-id)
          current-life (q/get-life-total db player-id)]
      (d/db-with db [[:db/add player-eid :player/life (- current-life amount)]]))))


(defn assign-combat-damage-for-attacker
  "Assign combat damage for a single attacker.
   - If unblocked, deals effective power to defending player.
   - If blocked, auto-assigns lethal damage to each blocker in order.
     With trample, excess damage goes to defending player.
   Returns updated db."
  [db attacker-id defender-id]
  (let [power (creatures/effective-power db attacker-id)
        blockers (get-blockers-for-attacker db attacker-id)]
    (if (empty? blockers)
      ;; Unblocked — deal damage to defending player
      (deal-damage-to-player db defender-id (or power 0))
      ;; Blocked — auto-assign lethal to blockers in order
      (let [has-trample (creatures/has-keyword? db attacker-id :trample)
            result (reduce (fn [{:keys [db remaining-damage]} blocker-id]
                             (let [toughness (or (creatures/effective-toughness db blocker-id) 0)
                                   existing-dmg (or (:object/damage-marked (q/get-object db blocker-id)) 0)
                                   lethal (max 0 (- toughness existing-dmg))
                                   assigned (min remaining-damage lethal)]
                               {:db (mark-damage db blocker-id assigned)
                                :remaining-damage (- remaining-damage assigned)}))
                           {:db db :remaining-damage (or power 0)}
                           blockers)]
        ;; Blockers also deal damage back to attacker
        ;; Trample: excess goes to defending player
        (cond-> (:db result)
          (and has-trample (pos? (:remaining-damage result)))
          (deal-damage-to-player defender-id (:remaining-damage result)))))))


(defn assign-blocker-damage-to-attacker
  "Each blocker deals its effective power as damage to the attacker it blocks.
   Pure function: (db, blocker-id) -> db"
  [db blocker-id]
  (let [obj (q/get-object db blocker-id)
        atk-eid (:object/blocking obj)]
    (if-not atk-eid
      db
      (let [atk-id (:object/id (d/pull db [:object/id] atk-eid))
            power (or (creatures/effective-power db blocker-id) 0)]
        (mark-damage db atk-id power)))))


(defn deal-combat-damage
  "Deal all combat damage for the current combat.
   1. Each attacker deals damage (to player or blockers).
   2. Each blocker deals damage to its attacker.
   3. Clear combat state (attacking/blocking flags).
   Pure function: (db, controller) -> db"
  [db controller]
  (let [attackers (get-attacking-creatures db)
        defender-id (q/get-other-player-id db controller)
        ;; All blockers on the battlefield
        all-blockers (d/q '[:find [?e ...]
                            :where [?e :object/zone :battlefield]
                            [?e :object/blocking _]]
                          db)
        blocker-ids (mapv (fn [eid] (:object/id (d/pull db [:object/id] eid)))
                          (or all-blockers []))
        ;; Step 1: Attackers deal damage
        db (reduce (fn [d atk-id]
                     (assign-combat-damage-for-attacker d atk-id defender-id))
                   db attackers)
        ;; Step 2: Blockers deal damage to attackers
        db (reduce assign-blocker-damage-to-attacker db blocker-ids)]
    ;; Step 3: Clear combat state
    (clear-combat-state db)))


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
