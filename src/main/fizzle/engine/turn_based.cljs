(ns fizzle.engine.turn-based
  "Turn-based game actions implemented as game-rule triggers.

   Turn-based actions execute immediately (no stack) when their
   phase is entered. They fire before card triggers."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-db :as trigger-db]))


(defn create-turn-based-triggers-tx
  "Return tx-data for game-rule triggers as Datascript entities.
   Pure function — caller must transact the returned data.

   Creates draw-step and untap-step triggers with :trigger/always-active? true.
   Each trigger filters on both phase and player-id so it only fires on its
   controller's turn (not the opponent's turn).

   Arguments:
     player-eid - Entity ID of the controlling player
     player-id  - Player keyword (e.g. :player-1, :player-2) for event filtering

   Returns:
     Vector of tx-data maps"
  [player-eid player-id]
  (vec (concat
         (trigger-db/create-game-rule-trigger-tx
           {:trigger/type :draw-step
            :trigger/event-type :phase-entered
            :trigger/filter {:event/phase :draw :event/player player-id}
            :trigger/controller player-eid})
         (trigger-db/create-game-rule-trigger-tx
           {:trigger/type :untap-step
            :trigger/event-type :phase-entered
            :trigger/filter {:event/phase :untap :event/player player-id}
            :trigger/controller player-eid}))))


(defn fire-delayed-effects
  "Put delayed-effect grants on the stack as triggered abilities.
   Pure function: (db, player-id) -> db

   Delayed-effect grants are player grants with:
   - :grant/type :delayed-effect
   - :grant/data {:delayed/phase :upkeep
                  :delayed/effect {...}}
   - :grant/expires {:expires/turn N :expires/phase P}

   When entering a phase, checks all delayed-effect grants:
   - If grant's :delayed/phase matches current phase AND
   - If current turn >= grant's :expires/turn
   - Creates a :delayed-trigger stack item with the grant's effect
   - Removes the grant (consumed regardless of whether the trigger resolves)

   The stack item resolves via the :default handler in resolution.cljs,
   which executes the effects. Players can respond (e.g., Stifle can
   counter the delayed trigger).

   Returns updated db with stack items created and grants removed."
  [db player-id]
  (let [game-state (q/get-game-state db)
        current-turn (:game/turn game-state)
        current-phase (:game/phase game-state)
        delayed-grants (grants/get-player-grants-by-type db player-id :delayed-effect)
        ;; Filter grants that should fire this phase
        matching-grants (filter (fn [grant]
                                  (let [data (:grant/data grant)
                                        expires (:grant/expires grant)
                                        target-phase (:delayed/phase data)
                                        target-turn (:expires/turn expires)]
                                    (and (= target-phase current-phase)
                                         (>= current-turn target-turn))))
                                delayed-grants)]
    (if (seq matching-grants)
      ;; Create stack items for each grant's effect and remove the grant
      (reduce (fn [d grant]
                (let [effect (get-in grant [:grant/data :delayed/effect])
                      grant-id (:grant/id grant)
                      description (or (:delayed/description (:grant/data grant))
                                      "Delayed trigger")
                      db-with-stack-item (stack/create-stack-item d
                                                                  {:stack-item/type :delayed-trigger
                                                                   :stack-item/controller player-id
                                                                   :stack-item/effects [effect]
                                                                   :stack-item/description description})]
                  (grants/remove-player-grant db-with-stack-item player-id grant-id)))
              db
              matching-grants)
      ;; No matching grants
      db)))
