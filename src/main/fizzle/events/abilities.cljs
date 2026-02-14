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
    (cond->
      {:selection/type :ability-targeting
       :selection/player-id player-id
       :selection/object-id object-id
       :selection/ability-index ability-index
       :selection/target-requirement target-req
       :selection/valid-targets valid-targets
       :selection/selected #{}
       :selection/select-count 1
       :selection/validation :exact
       :selection/auto-confirm? true}
      (= :object (:target/type target-req))
      (assoc :selection/card-source :valid-targets))))


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


(defn- pay-costs-and-create-stack-item
  "Pay all ability costs and create a stack-item.
   Returns {:db :pending-selection nil} or nil if costs can't be paid."
  [db player-id object-id ability]
  (when-let [db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
    {:db (stack/create-stack-item db-after-costs
                                  {:stack-item/type :activated-ability
                                   :stack-item/controller player-id
                                   :stack-item/source object-id
                                   :stack-item/effects (:ability/effects ability [])
                                   :stack-item/description (:ability/description ability)})
     :pending-selection nil}))


(defn- activate-ability-with-generic-mana
  "Handle ability activation that requires generic mana allocation.
   Pays non-mana costs first, then enters mana allocation selection.
   Falls back to direct cost payment if allocation builder returns nil."
  [db player-id object-id ability]
  (let [ability-cost (:ability/cost ability)
        mana-cost (:mana ability-cost)
        non-mana-costs (dissoc ability-cost :mana)
        db-after-non-mana (if (seq non-mana-costs)
                            (abilities/pay-all-costs db object-id non-mana-costs)
                            db)]
    (if-not db-after-non-mana
      {:db db :pending-selection nil}
      (let [sel (sel-costs/build-mana-allocation-selection
                  db-after-non-mana player-id object-id
                  {:mode/mana-cost mana-cost} mana-cost)]
        (if sel
          {:db db-after-non-mana
           :pending-selection (assoc sel
                                     :selection/source-type :ability
                                     :selection/ability ability)}
          (or (pay-costs-and-create-stack-item db player-id object-id ability)
              {:db db :pending-selection nil}))))))


(defn- activate-validated-ability
  "Activate an ability that has passed validation checks.
   Handles three paths: targeting, generic mana allocation, and direct cost payment."
  [db player-id object-id ability-index ability]
  (let [targeting-reqs (targeting/get-targeting-requirements ability)]
    (cond
      ;; Has targeting - pause for target selection (don't pay costs yet)
      (seq targeting-reqs)
      {:db db
       :pending-selection (build-ability-target-selection
                            db player-id object-id ability-index (first targeting-reqs))}

      ;; Has generic mana cost - enter mana allocation
      (and (:mana (:ability/cost ability))
           (sel-costs/has-generic-mana-cost? (:mana (:ability/cost ability))))
      (activate-ability-with-generic-mana db player-id object-id ability)

      ;; No special handling - pay all costs directly and add to stack
      :else
      (or (pay-costs-and-create-stack-item db player-id object-id ability)
          {:db db :pending-selection nil}))))


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
   game/resolve-one-item.

   Returns {:db db :pending-selection selection-state-or-nil}"
  [db player-id object-id ability-index]
  (let [obj (queries/get-object db object-id)
        player-eid (queries/get-player-eid db player-id)]
    (if-not (and obj
                 player-eid
                 (= (:object/zone obj) :battlefield)
                 (= (:db/id (:object/controller obj)) player-eid))
      {:db db :pending-selection nil}
      (let [ability (nth (:card/abilities (:object/card obj)) ability-index nil)]
        (if-not (and ability
                     (= :activated (:ability/type ability))
                     (abilities/can-activate? db object-id ability))
          {:db db :pending-selection nil}
          (activate-validated-ability db player-id object-id ability-index ability))))))


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
