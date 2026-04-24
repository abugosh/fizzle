(ns fizzle.events.abilities
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.selection.core :as selection-core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.events.selection.mana-ability :as mana-ability]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


;; === Activate Mana Ability ===

(def activate-mana-ability
  "Delegates to engine.mana/activate-mana-ability.
   Kept here so existing callers can import from this namespace."
  engine-mana/activate-mana-ability)


(rf/reg-event-db
  ::activate-mana-ability
  (fn [db [_ object-id mana-color player-id ability-index]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))
          result (mana-ability/activate-mana-ability-with-generic-mana
                   game-db pid object-id mana-color ability-index)
          game-db-after (:db result)
          pending-sel (:pending-selection result)
          description (descriptions/describe-activate-mana object-id mana-color game-db)
          base (-> db
                   (assoc :game/db game-db-after)
                   (assoc :history/pending-entry
                          (descriptions/build-pending-entry game-db-after ::activate-mana-ability description pid)))]
      ;; Critical: use set-pending-selection (synchronous) — do NOT rf/dispatch inside handler.
      ;; Async dispatch races with :history/pending-entry assoc (ADR-020 prohibition).
      (if pending-sel
        (sel-spec/set-pending-selection base pending-sel)
        base))))


;; === Activate Non-Mana Ability ===

(defn build-ability-target-selection
  "Build pending selection state for ability targeting.
   Used when an ability has :ability/targeting requirements.

   Arguments:
     db - Datascript database
     player-id - Activating player
     object-id - Permanent being activated
     ability-index - Index of ability in :card/abilities
     target-req - Current targeting requirement
     chosen-targets - Map of already-chosen {target-ref -> target-id} (for multi-target chains)
     remaining-reqs - Vector of remaining target requirements after this one

   Returns selection state map."
  ([db player-id object-id ability-index target-req]
   (build-ability-target-selection db player-id object-id ability-index target-req {} []))
  ([db player-id object-id ability-index target-req chosen-targets remaining-reqs]
   (let [valid-targets (targeting/find-valid-targets db player-id target-req chosen-targets)
         ;; If more requirements remain, lifecycle is :chaining to collect all targets before paying costs
         lifecycle (if (seq remaining-reqs) :chaining :finalized)]
     (cond->
       {:selection/type :ability-targeting
        :selection/lifecycle lifecycle
        :selection/player-id player-id
        :selection/object-id object-id
        :selection/ability-index ability-index
        :selection/target-requirement target-req
        :selection/chosen-targets chosen-targets
        :selection/remaining-target-reqs remaining-reqs
        :selection/valid-targets valid-targets
        :selection/selected #{}
        :selection/select-count 1
        :selection/validation :exact
        :selection/auto-confirm? true}
       (= :object (:target/type target-req))
       (assoc :selection/card-source :valid-targets)))))


(defn confirm-ability-target
  "Complete ability activation after target selection.

   For single-target abilities (no remaining-target-reqs):
   1. Pays costs (tap, sacrifice, pay-life)
   2. Creates ability trigger with stored targets
   3. Adds trigger to stack

   For multi-target abilities (remaining-target-reqs is non-empty):
   - Stores current target in chosen-targets
   - Returns next pending selection for the next target requirement

   Arguments:
     db - Datascript database
     selection - Ability target selection state with :selection/selected set

   Returns {:db db :pending-selection nil-or-next-selection}"
  [db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        ability-index (:selection/ability-index selection)
        target-req (:selection/target-requirement selection)
        target-id (first (:selection/selected selection))
        target-ref (:target/id target-req)
        chosen-targets (or (:selection/chosen-targets selection) {})
        remaining-reqs (or (:selection/remaining-target-reqs selection) [])]
    (if target-id
      (let [updated-chosen (assoc chosen-targets target-ref target-id)]
        (if (seq remaining-reqs)
          ;; More targets to collect — build next selection
          (let [next-req (first remaining-reqs)
                next-remaining (vec (rest remaining-reqs))]
            {:db db
             :pending-selection (build-ability-target-selection
                                  db player-id object-id ability-index
                                  next-req updated-chosen next-remaining)})
          ;; All targets collected — pay costs and create stack-item
          (let [obj (queries/get-object db object-id)
                card (:object/card obj)
                ability (nth (:card/abilities card) ability-index)
                ;; Read pending-sacrifice-info from source object if present
                ;; (set by sacrifice executor when chaining from sacrifice to targeting)
                obj-eid (queries/get-object-eid db object-id)
                pending-sacrifice-info (when obj-eid
                                         (d/q '[:find ?info .
                                                :in $ ?e
                                                :where [?e :object/pending-sacrifice-info ?info]]
                                              db obj-eid))
                db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
            (if db-after-costs
              (let [effects-list (:ability/effects ability [])
                    stack-item-attrs (cond-> {:stack-item/type :activated-ability
                                              :stack-item/controller player-id
                                              :stack-item/source object-id
                                              :stack-item/effects effects-list
                                              :stack-item/targets updated-chosen
                                              :stack-item/description (:ability/description ability)}
                                       pending-sacrifice-info (assoc :stack-item/sacrifice-info pending-sacrifice-info))
                    db-with-item (stack/create-stack-item db-after-costs stack-item-attrs)]
                {:db db-with-item
                 :pending-selection nil})
              {:db db :pending-selection nil}))))
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


(defn- has-sacrifice-permanent-cost?
  "Check if an ability cost map contains a :sacrifice-permanent entry."
  [ability-cost]
  (contains? ability-cost :sacrifice-permanent))


(defn- activate-ability-with-sacrifice
  "Handle ability activation that requires a sacrifice-permanent cost.
   Pays non-sacrifice costs first, then enters sacrifice selection.
   After sacrifice confirmation, executor creates the stack item."
  [db player-id object-id ability-index ability]
  (let [ability-cost (:ability/cost ability)
        sac-criteria (:sacrifice-permanent ability-cost)
        non-sac-costs (dissoc ability-cost :sacrifice-permanent)
        db-after-non-sac (if (seq non-sac-costs)
                           (abilities/pay-all-costs db object-id non-sac-costs)
                           db)]
    (if-not db-after-non-sac
      {:db db :pending-selection nil}
      (let [sac-cost {:cost/criteria sac-criteria}
            sel (sel-costs/build-sacrifice-permanent-selection
                  db-after-non-sac player-id object-id nil sac-cost
                  {:source-type :ability
                   :ability ability
                   :ability-index ability-index})]
        (if sel
          {:db db-after-non-sac :pending-selection sel}
          {:db db :pending-selection nil})))))


(defn- activate-validated-ability
  "Activate an ability that has passed validation checks.
   Handles four paths: sacrifice cost, targeting, generic mana allocation, direct.
   Sacrifice is checked FIRST because it is the interactive cost that chains to targeting."
  [db player-id object-id ability-index ability]
  (cond
    ;; Has sacrifice-permanent cost - pause for sacrifice selection.
    ;; Sacrifice chains to targeting (if any) after confirmation.
    (has-sacrifice-permanent-cost? (:ability/cost ability))
    (activate-ability-with-sacrifice db player-id object-id ability-index ability)

    ;; Has targeting (no sacrifice) - pause for target selection (don't pay costs yet)
    (seq (targeting/get-targeting-requirements ability))
    {:db db
     :pending-selection (assoc (build-ability-target-selection
                                 db player-id object-id ability-index
                                 (first (targeting/get-targeting-requirements ability)) {}
                                 (vec (rest (targeting/get-targeting-requirements ability))))
                               :selection/source-type :ability)}

    ;; Has generic mana cost - enter mana allocation
    (and (:mana (:ability/cost ability))
         (sel-costs/has-generic-mana-cost? (:mana (:ability/cost ability))))
    (activate-ability-with-generic-mana db player-id object-id ability)

    ;; No special handling - pay all costs directly and add to stack
    :else
    (or (pay-costs-and-create-stack-item db player-id object-id ability)
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
   game/resolve-one-item.

   Returns {:db db :pending-selection selection-state-or-nil}"
  [db player-id object-id ability-index]
  (if-not (priority/in-priority-phase? (:game/phase (queries/get-game-state db)))
    {:db db :pending-selection nil}
    ;; Check restriction: cannot-activate-non-mana-abilities blocks non-mana activated abilities
    (if (rules/has-restriction? db player-id :cannot-activate-non-mana-abilities)
      {:db db :pending-selection nil}
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
              (activate-validated-ability db player-id object-id ability-index ability))))))))


(rf/reg-event-db
  ::activate-ability
  (fn [db [_ object-id ability-index]]
    (let [game-db (:game/db db)
          player-id (queries/get-human-player-id game-db)
          result (activate-ability game-db player-id object-id ability-index)
          base-db (cond-> (assoc db :game/db (:db result))
                    ;; Clear selected card after activation (sacrifice may move it to graveyard,
                    ;; and stale selection causes it to appear highlighted there)
                    true (dissoc :game/selected-card)
                    (:pending-selection result) (sel-spec/set-pending-selection (:pending-selection result)))]
      (if (:pending-selection result)
        ;; Targeting needed — defer history entry until selection chain completes
        (assoc base-db :history/deferred-entry
               {:type :activate-ability
                :object-id object-id
                :pre-game-db game-db
                :principal player-id
                :event-type :fizzle.events.abilities/activate-ability})
        ;; No targeting — create history entry now
        (let [desc (descriptions/describe-activate-ability object-id ability-index game-db)]
          (assoc base-db :history/pending-entry
                 (descriptions/build-pending-entry
                   (:db result)
                   :fizzle.events.abilities/activate-ability
                   desc
                   player-id)))))))


(defmethod selection-core/execute-confirmed-selection :ability-targeting
  [game-db selection]
  (let [remaining-reqs (:selection/remaining-target-reqs selection)]
    (if (seq remaining-reqs)
      ;; Chaining: db unchanged, build-chain-selection handles next target
      {:db game-db}
      ;; Final target: pay costs and create stack item
      (let [result (confirm-ability-target game-db selection)]
        {:db (:db result)}))))


(defmethod selection-core/apply-domain-policy :ability-targeting
  [game-db selection]
  (let [remaining-reqs (:selection/remaining-target-reqs selection)]
    (if (seq remaining-reqs)
      ;; Chaining: db unchanged, build-chain-selection handles next target
      {:db game-db}
      ;; Final target: pay costs and create stack item
      (let [result (confirm-ability-target game-db selection)]
        {:db (:db result)}))))


(defmethod selection-core/build-chain-selection :ability-targeting
  [db selection]
  (let [target-id (first (:selection/selected selection))
        target-req (:selection/target-requirement selection)
        target-ref (:target/id target-req)
        chosen-targets (assoc (or (:selection/chosen-targets selection) {})
                              target-ref target-id)
        remaining-reqs (or (:selection/remaining-target-reqs selection) [])
        next-req (first remaining-reqs)
        next-remaining (vec (rest remaining-reqs))]
    (when (and target-id next-req)
      (build-ability-target-selection
        db
        (:selection/player-id selection)
        (:selection/object-id selection)
        (:selection/ability-index selection)
        next-req
        chosen-targets
        next-remaining))))


;; === Activate Granted Mana Ability ===

(defn activate-granted-mana-ability
  "Activate a granted mana ability on a permanent.
   Granted abilities are stored in :object/grants with :grant/type :ability.
   Pure function: (db, player-id, object-id, grant-id) -> db

   Unlike native mana abilities, granted abilities ignore tapped state
   (no :tap cost). The grant's :ability/cost (e.g., :sacrifice-self)
   determines what must be paid."
  [db player-id object-id grant-id]
  (if-not (priority/in-priority-phase? (:game/phase (queries/get-game-state db)))
    db
    (let [obj (queries/get-object db object-id)
          player-eid (queries/get-player-eid db player-id)]
      (if-not (and obj
                   player-eid
                   (= (:object/zone obj) :battlefield)
                   (= (:db/id (:object/controller obj)) player-eid))
        db
        (let [grants (or (:object/grants obj) [])
              grant (first (filter #(= grant-id (:grant/id %)) grants))
              ability (:grant/data grant)]
          (if-not (and grant ability (= :mana (:ability/type ability)))
            db
            ;; Pay costs (sacrifice-self, etc.) — skip can-activate? tap check
            (if-let [db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
              ;; Execute effects (add-mana)
              (reduce (fn [db' effect]
                        (effects/execute-effect db' player-id effect))
                      db-after-costs
                      (:ability/effects ability []))
              db)))))))


(rf/reg-event-db
  ::activate-granted-mana-ability
  (fn [db [_ object-id grant-id]]
    (let [game-db (:game/db db)
          pid (queries/get-human-player-id game-db)]
      (assoc db :game/db (activate-granted-mana-ability game-db pid object-id grant-id)))))
