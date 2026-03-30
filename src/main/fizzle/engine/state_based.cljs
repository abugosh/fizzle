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
    [fizzle.engine.conditions :as conditions]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.stack :as stack]
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


;; === :state-check-trigger SBA ===
;; Finds battlefield permanents with :card/state-triggers whose conditions are met.
;; Unlike immediate SBAs (life-zero, lethal-damage), state-check triggers go on
;; the stack so players can respond.
;;
;; State trigger format in card data:
;;   {:state/condition {:condition/type :power-gte
;;                      :condition/object-id <resolved-at-runtime>
;;                      :condition/threshold 7}
;;    :state/effects [{:effect/type :sacrifice :effect/target :self}]
;;    :state/description "Sacrifice when power >= 7"}
;;
;; Guard: only fires if the trigger is NOT already on the stack for this source.
;; This prevents re-triggering every time SBAs are checked.

(defn- state-trigger-already-on-stack?
  "Check if a state-check trigger for a given object is already on the stack.
   Prevents duplicate triggers from firing every SBA cycle."
  [db object-id _description]
  (boolean
    (d/q '[:find ?e .
           :in $ ?src
           :where [?e :stack-item/type :state-check-trigger]
           [?e :stack-item/source ?src]]
         db object-id)))


(defn- check-state-condition
  "Check a state trigger condition. Handles condition types that need
   creature stats (power-gte) directly to avoid circular deps with conditions.cljs."
  [db object-id condition]
  (case (:condition/type condition)
    :power-gte
    (let [threshold (:condition/threshold condition)
          power (creatures/effective-power db object-id)]
      (and power threshold (>= power threshold)))
    ;; Fallback: delegate to conditions module for non-creature conditions
    (conditions/check-condition db nil condition)))


(defn- check-state-triggers-for-object
  "Check all state triggers for a battlefield permanent.
   Returns seq of SBA events for triggers whose conditions are met and aren't
   already on the stack."
  [db object-id card]
  (let [state-triggers (:card/state-triggers card)]
    (when (seq state-triggers)
      (into []
            (comp
              (filter
                (fn [st]
                  (let [cond (:state/condition st)
                        description (:state/description st)]
                    (and (check-state-condition db object-id cond)
                         (not (state-trigger-already-on-stack? db object-id description))))))
              (map (fn [st]
                     {:sba/type :state-check-trigger
                      :sba/target object-id
                      :sba/state-trigger st})))
            state-triggers))))


(defmethod check-sba :state-check-trigger
  [db _type]
  ;; Find all battlefield objects and check their state triggers
  (let [bf-objects (d/q '[:find [?oid ...]
                          :where [?e :object/zone :battlefield]
                          [?e :object/id ?oid]]
                        db)]
    (into []
          (mapcat (fn [oid]
                    (let [obj (q/get-object db oid)
                          card (:object/card obj)]
                      (check-state-triggers-for-object db oid card))))
          (or bf-objects []))))


(defmethod execute-sba :state-check-trigger
  [db sba]
  (let [object-id (:sba/target sba)
        st (:sba/state-trigger sba)
        effects-list (:state/effects st)
        description (:state/description st)
        obj-eid (q/get-object-eid db object-id)]
    (if obj-eid
      (let [obj (q/get-object db object-id)
            controller-ref (:object/controller obj)
            raw-controller-eid (if (map? controller-ref) (:db/id controller-ref) controller-ref)
            controller-id (q/get-player-id db raw-controller-eid)]
        (stack/create-stack-item db
                                 (cond-> {:stack-item/type :state-check-trigger
                                          :stack-item/controller controller-id
                                          :stack-item/source object-id
                                          :stack-item/effects effects-list}
                                   description (assoc :stack-item/description description))))
      db)))
