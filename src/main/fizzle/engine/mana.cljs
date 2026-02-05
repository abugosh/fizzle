(ns fizzle.engine.mana
  "Mana pool operations for Fizzle.

   All functions are pure: (db, args) -> db
   Caller must check can-pay? before calling pay-mana."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


(defn resolve-x-cost
  "Convert X in mana cost to colorless. x-value defaults to 0 if nil.

   {:x 1 :colorless 1 :blue 1} with x-value=3 -> {:colorless 4 :blue 1}
   {:x 2} with x-value=3 -> {:colorless 6} (for XX costs like Blaze)

   If cost has no :x key, returns cost unchanged (backwards compatible)."
  [mana-cost x-value]
  (let [x-count (get mana-cost :x 0)
        x-val (or x-value 0)
        x-as-colorless (* x-count x-val)
        existing-colorless (get mana-cost :colorless 0)]
    (-> mana-cost
        (dissoc :x)
        (assoc :colorless (+ existing-colorless x-as-colorless)))))


(defn add-mana
  "Add mana to a player's pool. Pure function: (db, args) -> db

   mana-to-add is a map like {:black 3} or {:black 2 :blue 1}.
   Empty map {} is a no-op."
  [db player-id mana-to-add]
  (if (empty? mana-to-add)
    db
    (let [player-eid (q/get-player-eid db player-id)
          current-pool (q/get-mana-pool db player-id)
          new-pool (merge-with + current-pool mana-to-add)]
      (d/db-with db [[:db/add player-eid :player/mana-pool new-pool]]))))


(defn can-pay?
  "Check if player can pay a mana cost.

   Returns true if player has sufficient mana of each color.
   The :colorless key in cost is treated as generic mana (payable by any color).
   The :x key is ignored (X can always be 0, so X costs are always payable).
   Empty cost {} or zero amounts {:black 0} always return true."
  [db player-id cost]
  (if (empty? cost)
    true
    (let [;; Strip :x from cost - X portion is always payable (can be 0)
          cost-without-x (dissoc cost :x)
          pool (q/get-mana-pool db player-id)
          generic (get cost-without-x :colorless 0)
          colored-cost (dissoc cost-without-x :colorless)]
      (and (every? (fn [[color amount]]
                     (>= (get pool color 0) amount))
                   colored-cost)
           (let [total-pool (reduce + (vals pool))
                 total-colored (reduce + 0 (vals colored-cost))]
             (>= (- total-pool total-colored) generic))))))


(defn empty-pool
  "Clear a player's mana pool to all zeros. Pure function: (db, player-id) -> db"
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (if player-eid
      (d/db-with db [[:db/add player-eid :player/mana-pool
                      {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}]])
      db)))


(defn pay-mana
  "Remove mana from player's pool to pay a cost.

   The :colorless key in cost is treated as generic mana, paid automatically
   from the largest available pools.

   The :x key in cost is resolved using x-value (defaults to 0 if omitted).
   {:x 1 :colorless 1 :blue 1} with x-value=3 becomes {:colorless 4 :blue 1}.

   IMPORTANT: Caller MUST verify can-pay? first.
   Calling pay-mana without sufficient mana will result in negative pool values.
   Empty cost {} is a no-op."
  ([db player-id cost]
   (pay-mana db player-id cost nil))
  ([db player-id cost x-value]
   (let [resolved-cost (resolve-x-cost cost x-value)]
     (if (empty? resolved-cost)
       db
       (let [player-eid (q/get-player-eid db player-id)
             current-pool (q/get-mana-pool db player-id)
             generic (get resolved-cost :colorless 0)
             colored-cost (dissoc resolved-cost :colorless)
             ;; Pay colored costs first
             pool-after-colored (merge-with - current-pool colored-cost)
             ;; Pay generic from largest available pools
             new-pool (if (zero? generic)
                        pool-after-colored
                        (loop [pool pool-after-colored
                               remaining generic
                               colors (->> (keys pool-after-colored)
                                           (sort-by #(get pool-after-colored % 0) >))]
                          (if (or (zero? remaining) (empty? colors))
                            pool
                            (let [color (first colors)
                                  available (get pool color 0)
                                  to-pay (min available remaining)]
                              (recur (update pool color - to-pay)
                                     (- remaining to-pay)
                                     (rest colors))))))]
         (d/db-with db [[:db/add player-eid :player/mana-pool new-pool]]))))))
