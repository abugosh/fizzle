(ns fizzle.events.abilities
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.state-based :as state-based]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.events.selection.core :as selection-core]
    [fizzle.events.selection.costs :as sel-costs]
    [re-frame.core :as rf]))


;; === Activate Mana Ability ===

(defn activate-mana-ability
  "Activate a mana ability on a land.
   Pure function: (db, player-id, object-id, mana-color) -> db

   Arguments:
     db - Datascript database value
     player-id - The player activating the ability
     object-id - The land to tap
     mana-color - The color of mana to produce (:white :blue :black :red :green)

   Flow:
     1. Find mana ability from card data
     2. Check can-activate? via abilities module
     3. Pay costs via abilities module (includes tap, remove counters)
     4. Add chosen mana to pool
     5. Fire matching triggers (e.g., City of Brass :becomes-tapped)
     6. Check state-based actions (e.g., Gemstone Mine sacrifice)

   Returns unchanged db if any step fails."
  [db player-id object-id mana-color]
  (let [obj (queries/get-object db object-id)
        player-eid (queries/get-player-eid db player-id)]
    ;; Basic validation
    (if (and obj
             player-eid
             (= (:object/zone obj) :battlefield)
             (= (:db/id (:object/controller obj)) player-eid))
      ;; Find mana ability from card data
      (let [card (:object/card obj)
            card-abilities (:card/abilities card)
            all-mana-abilities (filter #(= :mana (:ability/type %)) card-abilities)
            ;; Find mana ability that produces the requested color
            ;; Handles multiple patterns:
            ;; 1. :ability/produces {:blue 1} - direct match
            ;; 2. :ability/produces {:any 1} - any color (Gemstone Mine, Lotus Petal)
            ;; 3. :ability/effects with :add-mana {:any N} - City of Brass, LED
            ;; If mana-color is nil or no match found, fall back to first mana ability
            matching-ability (when mana-color
                               (first (filter
                                        (fn [ability]
                                          (let [produces (:ability/produces ability)
                                                effects (:ability/effects ability)
                                                ;; Check if any effect adds {:any N} mana
                                                has-any-mana-effect? (some #(and (= :add-mana (:effect/type %))
                                                                                 (contains? (:effect/mana %) :any))
                                                                           effects)]
                                            (or
                                              ;; Direct produces match
                                              (and produces (contains? produces mana-color))
                                              ;; Produces any color
                                              (and produces (contains? produces :any))
                                              ;; Effect adds any color
                                              has-any-mana-effect?)))
                                        all-mana-abilities)))
            ;; Fall back to first mana ability if no match or nil color
            mana-ability (or matching-ability (first all-mana-abilities))]
        (if (and mana-ability
                 (abilities/can-activate? db object-id mana-ability))
          ;; Execute ability
          (let [;; Step 1: Pay costs (tap, remove counters, etc.)
                db-after-costs (abilities/pay-all-costs db object-id (:ability/cost mana-ability))]
            (if db-after-costs
              (let [;; Step 2a: Handle :ability/produces (direct mana production)
                    ;; Resolve {:any N} to chosen color
                    produces (:ability/produces mana-ability)
                    db-after-produces (if produces
                                        (let [resolved-mana (if-let [any-count (:any produces)]
                                                              {mana-color any-count}
                                                              produces)]
                                          (effects/execute-effect db-after-costs player-id
                                                                  {:effect/type :add-mana
                                                                   :effect/mana resolved-mana}))
                                        db-after-costs)
                    ;; Step 2b: Execute ability effects (mana and other effects)
                    ;; Resolve :self targets to object-id before execution
                    ;; Resolve :controller targets to player-id before execution
                    ;; Resolve {:any N} mana effects to chosen color
                    db-after-effects (reduce (fn [db' effect]
                                               (let [;; Resolve symbolic targets
                                                     resolved-effect (stack/resolve-effect-target effect object-id player-id nil)
                                                     ;; Resolve {:any N} mana to chosen color
                                                     resolved-effect (if (= :add-mana (:effect/type resolved-effect))
                                                                       (let [mana (:effect/mana resolved-effect)]
                                                                         (if-let [any-count (:any mana)]
                                                                           (assoc resolved-effect :effect/mana {mana-color any-count})
                                                                           resolved-effect))
                                                                       resolved-effect)]
                                                 (effects/execute-effect db' player-id resolved-effect)))
                                             db-after-produces
                                             (:ability/effects mana-ability []))
                    ;; Step 3: Dispatch permanent-tapped event to trigger registered triggers
                    ;; (replaces old fire-matching-triggers scanning approach)
                    db-after-triggers (dispatch/dispatch-event db-after-effects
                                                               (game-events/permanent-tapped-event object-id player-id))
                    ;; Step 4: Check state-based actions
                    db-after-sbas (state-based/check-and-execute-sbas db-after-triggers)]
                db-after-sbas)
              db))
          db))
      db)))


(rf/reg-event-db
  ::activate-mana-ability
  (fn [db [_ object-id mana-color]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (activate-mana-ability game-db :player-1 object-id mana-color)))))


;; === Activate Non-Mana Ability ===

(defn- build-ability-target-selection
  "Build pending selection state for ability targeting.
   Used when an ability has :ability/targeting requirements.

   Arguments:
     db - Datascript database
     player-id - Activating player
     object-id - Permanent being activated
     ability-index - Index of ability in :card/abilities
     target-req - First targeting requirement (player target for now)

   Returns selection state map."
  [db player-id object-id ability-index target-req]
  (let [valid-targets (targeting/find-valid-targets db player-id target-req)]
    {:selection/type :ability-targeting
     :selection/player-id player-id
     :selection/object-id object-id
     :selection/ability-index ability-index
     :selection/target-requirement target-req
     :selection/valid-targets valid-targets
     :selection/selected #{}
     :selection/select-count 1
     :selection/validation :exact
     :selection/auto-confirm? true}))


(defn confirm-ability-target
  "Complete ability activation after target selection.

   1. Pays costs (tap, sacrifice, pay-life)
   2. Creates ability trigger with stored targets
   3. Adds trigger to stack

   Arguments:
     db - Datascript database
     selection - Ability target selection state with :selection/selected set

   Returns {:db db :pending-selection nil}"
  [db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        ability-index (:selection/ability-index selection)
        target-req (:selection/target-requirement selection)
        target-id (first (:selection/selected selection))
        target-ref (:target/id target-req)]
    (if target-id
      (let [obj (queries/get-object db object-id)
            card (:object/card obj)
            ability (nth (:card/abilities card) ability-index)
            ;; Pay costs now
            db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
        (if db-after-costs
          ;; Create stack-item with stored targets
          (let [effects-list (:ability/effects ability [])
                db-with-item (stack/create-stack-item db-after-costs
                                                      {:stack-item/type :activated-ability
                                                       :stack-item/controller player-id
                                                       :stack-item/source object-id
                                                       :stack-item/effects effects-list
                                                       :stack-item/targets {target-ref target-id}
                                                       :stack-item/description (:ability/description ability)})]
            {:db db-with-item
             :pending-selection nil})
          {:db db :pending-selection nil}))
      ;; No target selected - return unchanged (activation cancelled)
      {:db db :pending-selection nil})))


(defn activate-ability
  "Activate a non-mana ability on a permanent (e.g., fetchlands).
   Pure function: (db, player-id, object-id, ability-index) -> {:db db :pending-selection nil}

   Arguments:
     db - Datascript database value
     player-id - The player activating the ability
     object-id - The permanent to activate
     ability-index - Index of the ability in :card/abilities

   Flow:
     1. Find ability from card data
     2. Check can-activate? via abilities module
     3. If ability has targeting: pause for target selection (costs not paid yet)
     4. If no targeting: Pay costs and add to stack immediately

   The ability goes on the stack and effects execute on resolution.
   This allows opponents to respond (e.g., counter with Stifle).
   Costs are paid on activation (or after target selection), not resolution.

   Selection effects (like tutor) are handled on resolution via
   resolve-stack-item-ability-with-selection.

   Returns {:db db :pending-selection selection-state-or-nil}"
  [db player-id object-id ability-index]
  (let [obj (queries/get-object db object-id)
        player-eid (queries/get-player-eid db player-id)]
    (if (and obj
             player-eid
             (= (:object/zone obj) :battlefield)
             (= (:db/id (:object/controller obj)) player-eid))
      (let [card (:object/card obj)
            card-abilities (:card/abilities card)
            ability (nth card-abilities ability-index nil)]
        (if (and ability
                 (= :activated (:ability/type ability))
                 (abilities/can-activate? db object-id ability))
          ;; Check if ability has targeting requirements
          (let [targeting-reqs (targeting/get-targeting-requirements ability)]
            (if (seq targeting-reqs)
              ;; Has targeting - pause for target selection (don't pay costs yet)
              (let [first-req (first targeting-reqs)
                    selection (build-ability-target-selection db player-id object-id ability-index first-req)]
                {:db db
                 :pending-selection selection})
              ;; No targeting - check for generic mana allocation
              (let [ability-cost (:ability/cost ability)
                    mana-cost (:mana ability-cost)]
                (if (and mana-cost (sel-costs/has-generic-mana-cost? mana-cost))
                  ;; Has generic mana: pay non-mana costs first, then enter allocation
                  (let [non-mana-costs (dissoc ability-cost :mana)
                        db-after-non-mana (if (seq non-mana-costs)
                                            (abilities/pay-all-costs db object-id non-mana-costs)
                                            db)]
                    (if db-after-non-mana
                      (let [mode {:mode/mana-cost mana-cost}
                            sel (sel-costs/build-mana-allocation-selection
                                  db-after-non-mana player-id object-id mode mana-cost)]
                        (if sel
                          {:db db-after-non-mana
                           :pending-selection (assoc sel
                                                     :selection/source-type :ability
                                                     :selection/ability ability)}
                          ;; Builder returned nil: pay all costs normally, add to stack
                          (let [db-all (abilities/pay-all-costs db object-id ability-cost)]
                            (if db-all
                              {:db (stack/create-stack-item db-all
                                                            {:stack-item/type :activated-ability
                                                             :stack-item/controller player-id
                                                             :stack-item/source object-id
                                                             :stack-item/effects (:ability/effects ability [])
                                                             :stack-item/description (:ability/description ability)})
                               :pending-selection nil}
                              {:db db :pending-selection nil}))))
                      ;; Non-mana costs failed to pay
                      {:db db :pending-selection nil}))
                  ;; No generic mana: pay all costs directly (existing code unchanged)
                  (let [db-after-costs (abilities/pay-all-costs db object-id ability-cost)]
                    (if db-after-costs
                      (let [effects-list (:ability/effects ability [])
                            db-with-item (stack/create-stack-item db-after-costs
                                                                  {:stack-item/type :activated-ability
                                                                   :stack-item/controller player-id
                                                                   :stack-item/source object-id
                                                                   :stack-item/effects effects-list
                                                                   :stack-item/description (:ability/description ability)})]
                        {:db db-with-item
                         :pending-selection nil})
                      {:db db :pending-selection nil}))))))
          {:db db :pending-selection nil}))
      {:db db :pending-selection nil})))


(rf/reg-event-db
  ::activate-ability
  (fn [db [_ object-id ability-index]]
    (let [game-db (:game/db db)
          result (activate-ability game-db :player-1 object-id ability-index)]
      (cond-> (assoc db :game/db (:db result))
        ;; Clear selected card after activation (sacrifice may move it to graveyard,
        ;; and stale selection causes it to appear highlighted there)
        true (dissoc :game/selected-card)
        (:pending-selection result) (assoc :game/pending-selection (:pending-selection result))))))


(defmethod selection-core/execute-confirmed-selection :ability-targeting
  [game-db selection]
  (let [result (confirm-ability-target game-db selection)]
    {:db (:db result) :finalized? true}))
