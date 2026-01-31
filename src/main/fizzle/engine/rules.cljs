(ns fizzle.engine.rules
  "Core game rules for Fizzle.

   Orchestrates mana, zones, and effects into casting operations.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.conditions :as conditions]
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


(defn- set-stack-order
  "Set stack position on an object so stack resolves LIFO."
  [db object-id]
  (let [stack-order (q/get-next-stack-order db)
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)]
    (d/db-with db [[:db/add obj-eid :object/position stack-order]])))


(defn cast-spell
  "Cast a spell from hand.

   - Pays mana cost
   - Moves card to stack (with stack order for LIFO resolution)
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
        (set-stack-order object-id)
        (increment-storm player-id)
        (maybe-create-storm-trigger player-id object-id))))


(defn get-active-effects
  "Select which effects to use based on card conditions.

   Cards may have:
   - :card/effects - Default effects (always used if no condition matches)
   - :card/conditional-effects - Map of condition keyword to effects list
     e.g., {:threshold [{:effect/type :add-mana :effect/mana {:black 5}}]}

   Condition checking is done at resolution time (not cast time)."
  [db player-id card]
  (let [default-effects (:card/effects card)
        conditional-effects (:card/conditional-effects card)]
    (cond
      ;; Check threshold condition if card has it
      (and (:threshold conditional-effects)
           (conditions/threshold? db player-id))
      (:threshold conditional-effects)

      ;; Fall back to default effects
      :else
      default-effects)))


(defn resolve-spell
  "Resolve a spell on the stack.

   - Verifies spell is on stack (no-op if not)
   - Checks conditions and selects appropriate effects
   - Executes all selected effects
   - Moves card to graveyard"
  [db player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      db  ; No-op if spell not on stack
      (let [card (:object/card obj)
            effects-list (get-active-effects db player-id card)]
        (as-> db db'
              (reduce (fn [d effect] (effects/execute-effect d player-id effect))
                      db'
                      (or effects-list []))
              (zones/move-to-zone db' object-id :graveyard))))))
