(ns fizzle.engine.abilities
  "Generic ability activation for Fizzle.

   Provides can-activate?, pay-all-costs, and activate-ability functions
   that work with any ability defined as data."
  (:require
    [fizzle.engine.conditions :as conditions]
    [fizzle.engine.costs :as costs]
    [fizzle.engine.effects :as effects]))


(defn- check-condition
  "Check if an ability's condition is met.
   Dispatches on :condition/type within :ability/condition.
   Returns true if no condition or condition is met."
  [db player-id condition]
  (if (nil? condition)
    true ; No condition = always met
    (case (:condition/type condition)
      :threshold (conditions/threshold? db player-id)
      ;; Unknown condition types fail closed (cannot activate)
      false)))


(defn can-activate?
  "Check if an ability can be activated.

   Arguments:
     db - Datascript database value
     object-id - UUID of the permanent
     ability - Map with :ability/cost and optionally :ability/effects
     player-id - (optional) The player trying to activate. Defaults to controller.

   Checks both:
     1. All costs can be paid
     2. Any :ability/condition is met

   Returns:
     Boolean - true if all costs can be paid AND condition is met."
  ([db object-id ability]
   ;; Default to checking controller's conditions
   (let [obj (costs/get-object-eid db object-id)]
     (if obj
       (let [controller-eid (costs/get-controller-eid db object-id)
             player-id (when controller-eid
                         (costs/get-player-id-from-eid db controller-eid))]
         (can-activate? db object-id ability player-id))
       false)))
  ([db object-id ability player-id]
   (let [cost (:ability/cost ability)
         condition (:ability/condition ability)]
     (and
       ;; Check condition first (threshold, etc.)
       (check-condition db player-id condition)
       ;; Then check all costs can be paid
       (if (or (nil? cost) (empty? cost))
         true ; Free activation
         (every? (fn [[cost-type cost-value]]
                   (costs/can-pay? db object-id {cost-type cost-value}))
                 cost))))))


(defn pay-all-costs
  "Pay all costs for an ability.

   Arguments:
     db - Datascript database value
     object-id - UUID of the permanent
     costs - Map of costs {:tap true :remove-counter {:mining 1}}

   Returns:
     New db with all costs paid, or nil if any cost cannot be paid.
     Empty/nil costs return db unchanged."
  [db object-id costs]
  (if (or (nil? costs) (empty? costs))
    db ; No costs to pay
    (reduce (fn [db' [cost-type cost-value]]
              (if-let [new-db (costs/pay-cost db' object-id {cost-type cost-value})]
                new-db
                (reduced nil)))
            db
            costs)))


(defn activate-ability
  "Activate an ability on a permanent.

   Arguments:
     db - Datascript database value
     object-id - UUID of the permanent
     ability - Map with :ability/cost and :ability/effects
     player-id - The player activating

   Returns:
     New db with costs paid and effects applied, or nil if cannot activate."
  [db object-id ability player-id]
  (when (can-activate? db object-id ability)
    (let [cost (:ability/cost ability)
          db-after-costs (pay-all-costs db object-id cost)]
      (when db-after-costs
        ;; Apply effects (empty list is fine)
        (reduce (fn [db' effect]
                  (effects/execute-effect db' player-id effect))
                db-after-costs
                (:ability/effects ability []))))))
