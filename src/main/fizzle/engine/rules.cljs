(ns fizzle.engine.rules
  "Core game rules for Fizzle.

   Orchestrates mana, zones, and effects into casting operations.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.triggers :as triggers]
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


(defn- maybe-create-storm-trigger
  "Create storm trigger if spell has :storm keyword and is not a copy.

   Storm count for copies = current storm count - 1 (spells BEFORE this one).
   Copies (:object/is-copy true) do not trigger storm."
  [db player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if-let [card (:object/card obj)]
      (let [keywords (:card/keywords card)
            is-copy (:object/is-copy obj)
            ;; Check if :storm is in keywords (handle both sets and vectors)
            has-storm (cond
                        (set? keywords) (contains? keywords :storm)
                        (sequential? keywords) (some #{:storm} keywords)
                        :else false)]
        (if (and has-storm (not is-copy))
          ;; Create storm trigger - count = spells cast before this one
          (let [storm-count (q/get-storm-count db player-id)
                copy-count (dec storm-count)  ; Already incremented, so dec for "before"
                trigger (triggers/create-trigger :storm object-id player-id {:count copy-count})]
            (triggers/add-trigger-to-stack db trigger))
          db))
      db)))


(defn cast-spell
  "Cast a spell from hand.

   - Pays mana cost
   - Moves card to stack
   - Increments storm count
   - Creates storm trigger if spell has :storm keyword

   Caller should verify can-cast? first."
  [db player-id object-id]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)
        cost (:card/mana-cost card)]
    (-> db
        (mana/pay-mana player-id cost)
        (zones/move-to-zone object-id :stack)
        (increment-storm player-id)
        (maybe-create-storm-trigger player-id object-id))))


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
