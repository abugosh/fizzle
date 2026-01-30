(ns fizzle.engine.abilities
  "Generic ability activation for Fizzle.

   Provides can-activate?, pay-all-costs, and activate-ability functions
   that work with any ability defined as data."
  (:require
    [fizzle.engine.costs :as costs]
    [fizzle.engine.effects :as effects]))


(defn can-activate?
  "Check if an ability can be activated.

   Arguments:
     db - Datascript database value
     object-id - UUID of the permanent
     ability - Map with :ability/cost and optionally :ability/effects

   Returns:
     Boolean - true if all costs can be paid (or no costs)."
  [db object-id ability]
  (let [cost (:ability/cost ability)]
    (if (or (nil? cost) (empty? cost))
      true ; Free activation
      (every? (fn [[cost-type cost-value]]
                (costs/can-pay? db object-id {cost-type cost-value}))
              cost))))


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
