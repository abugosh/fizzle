(ns fizzle.engine.mana
  "Mana pool operations for Fizzle.

   All functions are pure: (db, args) -> db
   Caller must check can-pay? before calling pay-mana."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]))


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
   Empty cost {} or zero amounts {:black 0} always return true."
  [db player-id cost]
  (if (empty? cost)
    true
    (let [pool (q/get-mana-pool db player-id)]
      (every? (fn [[color amount]]
                (>= (get pool color 0) amount))
              cost))))


(defn pay-mana
  "Remove mana from player's pool to pay a cost.

   IMPORTANT: Caller MUST verify can-pay? first.
   Calling pay-mana without sufficient mana will result in negative pool values.
   Empty cost {} is a no-op."
  [db player-id cost]
  (if (empty? cost)
    db
    (let [player-eid (q/get-player-eid db player-id)
          current-pool (q/get-mana-pool db player-id)
          new-pool (merge-with - current-pool cost)]
      (d/db-with db [[:db/add player-eid :player/mana-pool new-pool]]))))
