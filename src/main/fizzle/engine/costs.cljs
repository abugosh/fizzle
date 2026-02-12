(ns fizzle.engine.costs
  "Cost payment system for abilities.

   Dispatches on cost type to pay costs.
   All functions are pure: (db, object-id, cost) -> db or nil."
  (:require
    [datascript.core :as d]))


(defn get-object-eid
  "Get the entity ID for an object by its UUID.
   Returns nil if object doesn't exist."
  [db object-id]
  (d/q '[:find ?e .
         :in $ ?oid
         :where [?e :object/id ?oid]]
       db object-id))


(defn get-controller-eid
  "Get the controller entity ID for an object.
   Returns nil if object doesn't exist."
  [db object-id]
  (when-let [obj-eid (get-object-eid db object-id)]
    (d/q '[:find ?c .
           :in $ ?e
           :where [?e :object/controller ?c]]
         db obj-eid)))


(defn get-player-id-from-eid
  "Get the :player/id for a player entity.
   Returns nil if entity is not a player."
  [db player-eid]
  (d/q '[:find ?pid .
         :in $ ?e
         :where [?e :player/id ?pid]]
       db player-eid))


(defmulti pay-cost
  "Pay a single cost on an object.

   Arguments:
     db - Datascript database value
     object-id - UUID of the permanent paying the cost
     cost - Map with single cost type key, e.g. {:tap true} or {:remove-counter {:mining 1}}

   Returns:
     New db with cost paid, or nil if cost cannot be paid."
  (fn [_db _object-id cost] (first (keys cost))))


(defmulti can-pay?
  "Check if a cost can be paid.

   Arguments:
     db - Datascript database value
     object-id - UUID of the permanent
     cost - Map with single cost type key

   Returns:
     Boolean - true if cost can be paid, false otherwise."
  (fn [_db _object-id cost] (first (keys cost))))


;; Default: unknown cost types cannot be paid
(defmethod pay-cost :default [_db _object-id _cost] nil)
(defmethod can-pay? :default [_db _object-id _cost] false)


;; === :tap cost ===

(defmethod can-pay? :tap [db object-id _cost]
  ;; Can pay tap cost if:
  ;; 1. Object exists
  ;; 2. Object is not already tapped
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [tapped (d/q '[:find ?tapped .
                        :in $ ?e
                        :where [?e :object/tapped ?tapped]]
                      db obj-eid)]
      (not tapped))
    false))


(defmethod pay-cost :tap [db object-id cost]
  ;; Guard: check can pay first
  (when (can-pay? db object-id cost)
    (let [obj-eid (get-object-eid db object-id)]
      (d/db-with db [[:db/add obj-eid :object/tapped true]]))))


;; === :remove-counter cost ===

(defmethod can-pay? :remove-counter [db object-id cost]
  ;; Can pay remove-counter if:
  ;; 1. Object exists
  ;; 2. Object has sufficient counters of required type(s)
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [required (:remove-counter cost)
          current (or (d/q '[:find ?c .
                             :in $ ?e
                             :where [?e :object/counters ?c]]
                           db obj-eid)
                      {})]
      (every? (fn [[counter-type amount]]
                (>= (get current counter-type 0) amount))
              required))
    false))


(defmethod pay-cost :remove-counter [db object-id cost]
  ;; Guard: check can pay first
  (when (can-pay? db object-id cost)
    (let [obj-eid (get-object-eid db object-id)
          required (:remove-counter cost)
          current (or (d/q '[:find ?c .
                             :in $ ?e
                             :where [?e :object/counters ?c]]
                           db obj-eid)
                      {})
          new-counters (merge-with - current required)]
      (d/db-with db [[:db/add obj-eid :object/counters new-counters]]))))


;; === :sacrifice-self cost ===

(defmethod can-pay? :sacrifice-self [db object-id _cost]
  ;; Can pay sacrifice-self if:
  ;; 1. Object exists
  ;; 2. Object is on the battlefield
  (if-let [obj-eid (get-object-eid db object-id)]
    (= :battlefield (d/q '[:find ?z .
                           :in $ ?e
                           :where [?e :object/zone ?z]]
                         db obj-eid))
    false))


(defmethod pay-cost :sacrifice-self [db object-id cost]
  ;; Guard: check can pay first
  (when (can-pay? db object-id cost)
    (let [obj-eid (get-object-eid db object-id)]
      (d/db-with db [[:db/add obj-eid :object/zone :graveyard]]))))


;; === :discard-hand cost ===

(defmethod can-pay? :discard-hand [db object-id _cost]
  ;; Can pay discard-hand if:
  ;; 1. Object exists
  ;; 2. Object is on the battlefield
  ;; Note: Can always discard hand (even if empty)
  (if-let [obj-eid (get-object-eid db object-id)]
    (= :battlefield (d/q '[:find ?z .
                           :in $ ?e
                           :where [?e :object/zone ?z]]
                         db obj-eid))
    false))


(defmethod pay-cost :discard-hand [db object-id _cost]
  ;; Move all cards in controller's hand to graveyard
  ;; NOTE: No can-pay? guard here - by the time pay-cost is called,
  ;; can-activate? has already verified all costs are payable.
  ;; The object may have been sacrificed by a prior cost (:sacrifice-self),
  ;; so we can't re-check zone. We just need the controller reference.
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [controller-eid (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/controller ?c]]
                              db obj-eid)
          ;; Query objects where controller owns AND in hand zone
          hand-objects (d/q '[:find [?e ...]
                              :in $ ?owner
                              :where [?e :object/owner ?owner]
                              [?e :object/zone ?zone]
                              [(= ?zone :hand)]]
                            db controller-eid)]
      (if (seq hand-objects)
        (d/db-with db (mapv (fn [eid] [:db/add eid :object/zone :graveyard])
                            hand-objects))
        db))
    db))


;; === :pay-life cost ===

(defmethod can-pay? :pay-life [db object-id cost]
  ;; Can pay life if:
  ;; 1. Object exists
  ;; 2. Controller has life >= required amount
  ;; Note: Can pay life even if it would reduce to 0 (MTG rules)
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [controller-eid (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/controller ?c]]
                              db obj-eid)
          current-life (d/q '[:find ?life .
                              :in $ ?p
                              :where [?p :player/life ?life]]
                            db controller-eid)
          required (:pay-life cost)]
      (>= (or current-life 0) required))
    false))


(defmethod pay-cost :pay-life [db object-id cost]
  ;; Deduct life from controller.
  ;; NOTE: No can-pay? guard here - by the time pay-cost is called,
  ;; can-activate? has already verified all costs are payable.
  ;; The object may have been sacrificed by a prior cost (:sacrifice-self),
  ;; so we can't re-check zone. We just need the controller reference.
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [controller-eid (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/controller ?c]]
                              db obj-eid)
          current-life (d/q '[:find ?life .
                              :in $ ?p
                              :where [?p :player/life ?life]]
                            db controller-eid)
          required (:pay-life cost)
          new-life (- current-life required)]
      (d/db-with db [[:db/add controller-eid :player/life new-life]]))
    db))


;; === :mana cost ===

(defmethod can-pay? :mana [db object-id cost]
  ;; Can pay mana if:
  ;; 1. Object exists
  ;; 2. Controller has sufficient mana for each colored cost
  ;; 3. Remaining mana covers generic (:colorless) portion
  ;; The :colorless key is treated as generic mana (payable by any color).
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [controller-eid (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/controller ?c]]
                              db obj-eid)
          current-pool (or (d/q '[:find ?pool .
                                  :in $ ?p
                                  :where [?p :player/mana-pool ?pool]]
                                db controller-eid)
                           {})
          required (:mana cost)
          generic (get required :colorless 0)
          colored (dissoc required :colorless)]
      (and (every? (fn [[color amount]]
                     (>= (get current-pool color 0) amount))
                   colored)
           (let [total-pool (reduce + (vals current-pool))
                 total-colored (reduce + 0 (vals colored))]
             (>= (- total-pool total-colored) generic))))
    false))


(defmethod pay-cost :mana [db object-id cost]
  ;; Deduct mana from controller's pool.
  ;; NOTE: No can-pay? guard here - by the time pay-cost is called,
  ;; can-activate? has already verified all costs are payable.
  (if-let [obj-eid (get-object-eid db object-id)]
    (let [controller-eid (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/controller ?c]]
                              db obj-eid)
          current-pool (or (d/q '[:find ?pool .
                                  :in $ ?p
                                  :where [?p :player/mana-pool ?pool]]
                                db controller-eid)
                           {})
          required (:mana cost)
          new-pool (merge-with - current-pool required)]
      (d/db-with db [[:db/add controller-eid :player/mana-pool new-pool]]))
    db))
