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
