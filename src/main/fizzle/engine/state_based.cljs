(ns fizzle.engine.state-based
  "State-based actions for Fizzle.

   SBAs are checked after every game action. Uses multimethod pattern
   for extensibility - add new SBA types by adding defmethods.

   SBA events use either:
     {:sba/type keyword :sba/target object-id} for object-level SBAs
     {:sba/type keyword :sba/player-id player-id} for player-level SBAs

   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.zones :as zones]))


(defmulti check-sba
  "Check for a specific type of state-based action.

   Arguments:
     db - Datascript database value
     sba-type - Keyword identifying the SBA type

   Returns:
     Seq of SBA events: {:sba/type keyword :sba/target object-id}
     or {:sba/type keyword :sba/player-id player-id} for player-level SBAs.
     Empty seq if no SBAs of this type apply."
  (fn [_db sba-type] sba-type))


(defmethod check-sba :default [_db _type] [])


(defn check-all-sbas
  "Run all registered SBA checks.

   Arguments:
     db - Datascript database value

   Returns:
     Seq of SBA events."
  [db]
  (let [sba-types (keys (dissoc (methods check-sba) :default))]
    (mapcat #(check-sba db %) sba-types)))


(defmulti execute-sba
  "Execute a single state-based action.

   Arguments:
     db - Datascript database value
     sba - Map with :sba/type and either :sba/target or :sba/player-id

   Returns:
     New db after SBA executed."
  (fn [_db sba] (:sba/type sba)))


(defmethod execute-sba :default [db _sba] db)


(defn check-and-execute-sbas
  "Check all SBAs and execute any that apply.

   Runs checks repeatedly until no more SBAs fire (loop handles cascading SBAs).

   Arguments:
     db - Datascript database value

   Returns:
     New db after all SBAs executed."
  [db]
  (loop [db' db]
    (let [sbas (check-all-sbas db')]
      (if (empty? sbas)
        db'
        (recur (reduce execute-sba db' sbas))))))


;; === Loss Condition ===

(defn set-loss-condition
  "Set the game loss condition and winner. Returns db with :game/loss-condition
   and :game/winner set atomically. The winner is the other player (not the loser).
   If no other player exists, only sets :game/loss-condition."
  [db condition losing-player-id]
  (let [game-eid (d/q '[:find ?e .
                        :where [?e :game/id _]]
                      db)
        winner-eid (d/q '[:find ?e .
                          :in $ ?loser-pid
                          :where [?e :player/id ?pid]
                          [(not= ?pid ?loser-pid)]]
                        db losing-player-id)
        txs (cond-> [[:db/add game-eid :game/loss-condition condition]]
              winner-eid (conj [:db/add game-eid :game/winner winner-eid]))]
    (d/db-with db txs)))


;; === :life-zero SBA ===

(defmethod check-sba :life-zero
  [db _type]
  (let [game (q/get-game-state db)]
    ;; Don't fire if game already has a loss condition (prevents infinite loop)
    (if (:game/loss-condition game)
      []
      (let [players (d/q '[:find ?pid ?life
                           :where [?e :player/id ?pid]
                           [?e :player/life ?life]]
                         db)]
        (into []
              (comp
                (filter (fn [[_pid life]] (<= life 0)))
                (map (fn [[pid _life]] {:sba/type :life-zero :sba/player-id pid})))
              players)))))


(defmethod execute-sba :life-zero
  [db sba]
  (set-loss-condition db :life-zero (:sba/player-id sba)))


;; === :empty-library SBA ===

(defmethod check-sba :empty-library
  [db _type]
  (let [game (q/get-game-state db)]
    ;; Don't fire if game already has a loss condition
    (if (:game/loss-condition game)
      []
      (let [flagged (d/q '[:find ?pid
                           :where [?e :player/id ?pid]
                           [?e :player/drew-from-empty true]]
                         db)]
        (mapv (fn [[pid]] {:sba/type :empty-library :sba/player-id pid})
              flagged)))))


(defmethod execute-sba :empty-library
  [db sba]
  (let [player-id (:sba/player-id sba)
        player-eid (q/get-player-eid db player-id)
        db' (set-loss-condition db :empty-library player-id)]
    ;; Clear the flag
    (d/db-with db' [[:db/retract player-eid :player/drew-from-empty true]])))


;; === :token-cleanup SBA ===
;; Tokens in non-battlefield zones cease to exist (entity retracted).

(defmethod check-sba :token-cleanup
  [db _type]
  (let [tokens-outside-bf (d/q '[:find ?oid ?zone
                                 :where [?e :object/is-token true]
                                 [?e :object/id ?oid]
                                 [?e :object/zone ?zone]
                                 [(not= ?zone :battlefield)]]
                               db)]
    (mapv (fn [[oid _zone]] {:sba/type :token-cleanup :sba/target oid})
          tokens-outside-bf)))


(defmethod execute-sba :token-cleanup
  [db sba]
  (let [token-id (:sba/target sba)]
    (if-let [token-eid (q/get-object-eid db token-id)]
      ;; Retract the token entity and its synthetic card entity
      (let [obj (d/pull db [{:object/card [:db/id]}] token-eid)
            card-eid (get-in obj [:object/card :db/id])
            txs (cond-> [[:db.fn/retractEntity token-eid]]
                  card-eid (conj [:db.fn/retractEntity card-eid]))]
        (d/db-with db txs))
      db)))


;; === :lethal-damage SBA ===
;; Creatures with damage-marked >= effective toughness move to graveyard.

(defmethod check-sba :lethal-damage
  [db _type]
  (let [damaged (d/q '[:find ?oid ?dmg
                       :where [?e :object/zone :battlefield]
                       [?e :object/id ?oid]
                       [?e :object/damage-marked ?dmg]
                       [(> ?dmg 0)]]
                     db)]
    (into []
          (comp
            (filter (fn [[oid dmg]]
                      (when-let [toughness (creatures/effective-toughness db oid)]
                        (>= dmg toughness))))
            (map (fn [[oid _]] {:sba/type :lethal-damage :sba/target oid})))
          damaged)))


(defmethod execute-sba :lethal-damage
  [db sba]
  (let [object-id (:sba/target sba)]
    (if (q/get-object-eid db object-id)
      (zones/move-to-zone db object-id :graveyard)
      db)))


;; === :zero-toughness SBA ===
;; Creatures with effective toughness <= 0 move to graveyard.

(defmethod check-sba :zero-toughness
  [db _type]
  (let [bf-creatures (d/q '[:find [?oid ...]
                            :where [?e :object/zone :battlefield]
                            [?e :object/id ?oid]]
                          db)]
    (into []
          (comp
            (filter (fn [oid]
                      (when-let [toughness (creatures/effective-toughness db oid)]
                        (<= toughness 0))))
            (map (fn [oid] {:sba/type :zero-toughness :sba/target oid})))
          (or bf-creatures []))))


(defmethod execute-sba :zero-toughness
  [db sba]
  (let [object-id (:sba/target sba)]
    (if (q/get-object-eid db object-id)
      (zones/move-to-zone db object-id :graveyard)
      db)))
