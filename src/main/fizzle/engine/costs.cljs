(ns fizzle.engine.costs
  "Cost payment system for abilities and spells.

   Dispatches on cost type to pay costs.
   All functions are pure: (db, object-id, cost) -> db or nil."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


(defn get-controller-eid
  "Get the controller entity ID for an object.
   Returns nil if object doesn't exist."
  [db object-id]
  (when-let [obj-eid (q/get-object-eid db object-id)]
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (let [tapped (d/q '[:find ?tapped .
                        :in $ ?e
                        :where [?e :object/tapped ?tapped]]
                      db obj-eid)]
      (not tapped))
    false))


(defmethod pay-cost :tap [db object-id cost]
  ;; Guard: check can pay first
  (when (can-pay? db object-id cost)
    (let [obj-eid (q/get-object-eid db object-id)]
      (d/db-with db [[:db/add obj-eid :object/tapped true]]))))


;; === :remove-counter cost ===

(defmethod can-pay? :remove-counter [db object-id cost]
  ;; Can pay remove-counter if:
  ;; 1. Object exists
  ;; 2. Object has sufficient counters of required type(s)
  (if-let [obj-eid (q/get-object-eid db object-id)]
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
    (let [obj-eid (q/get-object-eid db object-id)
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
    (= :battlefield (d/q '[:find ?z .
                           :in $ ?e
                           :where [?e :object/zone ?z]]
                         db obj-eid))
    false))


(defmethod pay-cost :sacrifice-self [db object-id cost]
  ;; Guard: check can pay first
  (when (can-pay? db object-id cost)
    (let [obj-eid (q/get-object-eid db object-id)]
      (d/db-with db [[:db/add obj-eid :object/zone :graveyard]]))))


;; === :discard-hand cost ===

(defmethod can-pay? :discard-hand [db object-id _cost]
  ;; Can pay discard-hand if:
  ;; 1. Object exists
  ;; 2. Object is on the battlefield
  ;; Note: Can always discard hand (even if empty)
  (if-let [obj-eid (q/get-object-eid db object-id)]
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
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
  (if-let [obj-eid (q/get-object-eid db object-id)]
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


;; === :return-land cost ===
;; Used for alternate costs like "return an Island you control to its owner's hand"
;; Cost format: {:return-land {:criteria {:match/subtypes #{:island}}}}
;; Note: pay-cost returns db unchanged because actual return is deferred
;; to the event layer's selection system (player chooses which land).

(defmethod can-pay? :return-land [db object-id cost]
  ;; Can pay return-land if:
  ;; 1. Object exists (to find controller)
  ;; 2. Controller has at least one matching land on the battlefield
  (if-let [controller-eid (get-controller-eid db object-id)]
    (let [config (:return-land cost)
          criteria (:criteria config)
          controller-id (get-player-id-from-eid db controller-eid)
          matching (q/query-zone-by-criteria db controller-id :battlefield criteria)]
      (pos? (count (or matching []))))
    false))


(defmethod pay-cost :return-land [db _object-id _cost]
  ;; Actual return is deferred to the event layer's selection system.
  ;; Player must choose which land to return, so we return db unchanged.
  db)


;; === :discard-specific cost ===
;; Used for costs like "discard an Island card and another card"
;; Cost format: {:discard-specific {:groups [{:criteria {...} :count 1} {:count 1}] :total 2}}
;; Note: pay-cost returns db unchanged because actual discard is deferred
;; to the event layer's selection system (player chooses which cards).

(defmethod can-pay? :discard-specific [db object-id cost]
  ;; Can pay discard-specific if:
  ;; 1. Object exists (to find controller)
  ;; 2. Controller's hand has enough cards total AND enough matching each group
  ;; The spell being cast (in hand) is excluded from candidates.
  (if-let [controller-eid (get-controller-eid db object-id)]
    (let [config (:discard-specific cost)
          groups (:groups config)
          total-required (:total config)
          controller-id (get-player-id-from-eid db controller-eid)
          hand (or (q/get-objects-in-zone db controller-id :hand) [])
          ;; Exclude the spell being cast
          candidates (filterv #(not= object-id (:object/id %)) hand)
          candidate-count (count candidates)]
      (and (>= candidate-count total-required)
           ;; Check each group with criteria can be satisfied
           (every? (fn [group]
                     (if-let [criteria (:criteria group)]
                       (let [matching (filterv #(q/matches-criteria? % criteria) candidates)]
                         (>= (count matching) (:count group)))
                       true))
                   groups)))
    false))


(defmethod pay-cost :discard-specific [db _object-id _cost]
  ;; Actual discard is deferred to the event layer's selection system.
  ;; Player must choose which cards to discard, so we return db unchanged.
  db)


;; === :exile-cards cost ===
;; Used for costs like "exile X blue cards from your graveyard"
;; Cost format: {:exile-cards {:zone :graveyard :criteria {...} :count N-or-:x}}
;; Note: pay-cost returns db unchanged because actual exile is deferred
;; to the event layer's selection system (player chooses which cards).

(defmethod can-pay? :exile-cards [db object-id cost]
  ;; Can pay exile-cards if:
  ;; 1. Object exists (to find controller)
  ;; 2. Controller has enough matching cards in the specified zone
  ;; 3. The object itself is excluded (can't exile the spell you're casting)
  (if-let [controller-eid (get-controller-eid db object-id)]
    (let [config (:exile-cards cost)
          zone (:zone config)
          criteria (:criteria config)
          required (:count config)
          controller-id (get-player-id-from-eid db controller-eid)
          all-available (q/query-zone-by-criteria db controller-id zone criteria)
          available (filterv #(not= object-id (:object/id %)) all-available)
          available-count (count available)]
      (cond
        (= required :x) (pos? available-count)
        (zero? required) true
        :else (>= available-count required)))
    false))


(defmethod pay-cost :exile-cards [db _object-id _cost]
  ;; Actual exile is deferred to the event layer's selection system.
  ;; Player must choose which cards to exile, so we return db unchanged.
  db)


;; === :pay-x-life cost ===
;; Used for costs like "pay X life" where X is chosen by the player.
;; Cost format: {:pay-x-life true}
;; Always payable (player can choose X=0).
;; Actual payment deferred to selection system (accumulator UI).

(defmethod can-pay? :pay-x-life [_db _object-id _cost]
  true)


(defmethod pay-cost :pay-x-life [db _object-id _cost]
  ;; Actual payment deferred to the event layer's selection system.
  ;; Player chooses X via accumulator UI, then life is deducted.
  db)
