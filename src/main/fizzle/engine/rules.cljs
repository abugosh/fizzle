(ns fizzle.engine.rules
  "Core game rules for Fizzle.

   Orchestrates mana, zones, and effects into casting operations.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.zones :as zones]))


(defn can-cast?
  "Check if a player can cast a card.

   Requires:
   - Card is in player's hand
   - Player has sufficient mana

   Returns false if object doesn't exist."
  [db player-id object-id]
  (when-let [obj (q/get-object db object-id)]
    (let [card (:object/card obj)
          cost (:card/mana-cost card)]
      (and (= :hand (:object/zone obj))
           (mana/can-pay? db player-id cost)))))


(defn- increment-storm
  "Increment a player's storm count."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        current (q/get-storm-count db player-id)]
    (d/db-with db [[:db/add player-eid :player/storm-count (inc current)]])))


(defn cast-spell
  "Cast a spell from hand.

   - Pays mana cost
   - Moves card to stack
   - Increments storm count

   Caller should verify can-cast? first."
  [db player-id object-id]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)
        cost (:card/mana-cost card)]
    (-> db
        (mana/pay-mana player-id cost)
        (zones/move-to-zone object-id :stack)
        (increment-storm player-id))))


(defn resolve-spell
  "Resolve a spell on the stack.

   - Executes all effects
   - Moves card to graveyard"
  [db player-id object-id]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)
        effects-list (:card/effects card)]
    (as-> db db'
          (reduce (fn [d effect] (effects/execute-effect d player-id effect))
                  db'
                  (or effects-list []))
          (zones/move-to-zone db' object-id :graveyard))))
