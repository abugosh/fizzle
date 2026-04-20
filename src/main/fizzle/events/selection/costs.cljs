(ns fizzle.events.selection.costs
  "Cost-related selection types: exile-cards-cost and x-mana-cost.

  Includes detection helpers, builders, execute-confirmed-selection
  defmethods, and re-frame event handlers for X cost interactions."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.selection.core :as core]
    [re-frame.core :as rf]))


;; =====================================================
;; X Cost Detection Helpers
;; =====================================================

(defn has-exile-cards-x-cost?
  "Check if mode has an :exile-cards additional cost with :cost/count :x"
  [mode]
  (some (fn [cost]
          (and (= :exile-cards (:cost/type cost))
               (= :x (:cost/count cost))))
        (:mode/additional-costs mode)))


(defn get-exile-cards-x-cost
  "Get the :exile-cards additional cost with :cost/count :x from a mode"
  [mode]
  (first (filter (fn [cost]
                   (and (= :exile-cards (:cost/type cost))
                        (= :x (:cost/count cost))))
                 (:mode/additional-costs mode))))


(defn has-mana-x-cost?
  "Check if mode has :x true in its mana cost"
  [mode]
  (true? (get-in mode [:mode/mana-cost :x])))


(defn has-return-land-cost?
  "Check if mode has a :return-land additional cost"
  [mode]
  (some (fn [cost] (= :return-land (:cost/type cost)))
        (:mode/additional-costs mode)))


(defn get-return-land-cost
  "Get the :return-land additional cost from a mode"
  [mode]
  (first (filter (fn [cost] (= :return-land (:cost/type cost)))
                 (:mode/additional-costs mode))))


(defn has-discard-specific-cost?
  "Check if mode has a :discard-specific additional cost"
  [mode]
  (some (fn [cost] (= :discard-specific (:cost/type cost)))
        (:mode/additional-costs mode)))


(defn get-discard-specific-cost
  "Get the :discard-specific additional cost from a mode"
  [mode]
  (first (filter (fn [cost] (= :discard-specific (:cost/type cost)))
                 (:mode/additional-costs mode))))


(defn has-pay-x-life-cost?
  "Check if mode has a :pay-x-life additional cost"
  [mode]
  (some (fn [cost] (= :pay-x-life (:cost/type cost)))
        (:mode/additional-costs mode)))


(defn has-sacrifice-permanent-cost?
  "Check if mode has a :sacrifice-permanent additional cost"
  [mode]
  (some (fn [cost] (= :sacrifice-permanent (:cost/type cost)))
        (:mode/additional-costs mode)))


(defn get-sacrifice-permanent-cost
  "Get the :sacrifice-permanent additional cost from a mode"
  [mode]
  (first (filter (fn [cost] (= :sacrifice-permanent (:cost/type cost)))
                 (:mode/additional-costs mode))))


(defn has-generic-mana-cost?
  "Check if a resolved cost has a generic mana portion (:colorless > 0).
   Cost must NOT contain :x (caller resolves X before calling)."
  [cost]
  (pos? (get cost :colorless 0)))


;; Forward declarations for builder-declared chains
(declare build-mana-allocation-selection)


;; =====================================================
;; Selection Builders
;; =====================================================

(defn build-exile-cards-selection
  "Build pending selection for exile-cards additional cost with :x count.
   Player selects which cards to exile (1 or more), and the count becomes X.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with :exile-cards cost
     exile-cost - The :exile-cards cost map

   Returns selection state for choosing cards to exile."
  [game-db player-id object-id mode exile-cost]
  (let [zone (:cost/zone exile-cost)
        criteria (:cost/criteria exile-cost)
        ;; Get available cards matching criteria
        available (queries/query-zone-by-criteria game-db player-id zone criteria)
        ;; Exclude the spell being cast (can't exile itself)
        candidates (filterv #(not= object-id (:object/id %)) available)
        candidate-ids (set (map :object/id candidates))]
    (when (seq candidate-ids)
      {:selection/zone zone
       :selection/type :exile-cards-cost
       :selection/lifecycle :finalized
       :selection/clear-selected-card? true
       :selection/card-source :candidates
       :selection/candidates candidate-ids
       :selection/select-count (count candidate-ids)  ; Can select up to all candidates
       :selection/exact? false    ; Can select any number >= 1
       :selection/allow-fail-to-find? false  ; Must select at least 1
       :selection/selected #{}
       :selection/player-id player-id
       :selection/spell-id object-id
       :selection/mode mode       ; Store mode for casting after selection
       :selection/exile-cost exile-cost
       :selection/validation :at-least-one
       :selection/auto-confirm? false})))


(defn build-return-land-selection
  "Build pending selection for return-land additional cost.
   Player selects which land matching criteria to return to hand.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with :return-land cost
     return-cost - The :return-land cost map (has :cost/criteria)

   Returns selection state for choosing a land to return."
  [game-db player-id object-id mode return-cost]
  (let [criteria (or (:cost/criteria return-cost) {})
        matching (queries/query-zone-by-criteria game-db player-id :battlefield criteria)
        candidate-ids (set (map :object/id matching))
        ;; Check if spell has targeting (determines lifecycle)
        obj (queries/get-object game-db object-id)
        has-targeting? (seq (:card/targeting (:object/card obj)))]
    (when (seq candidate-ids)
      (cond-> {:selection/zone :battlefield
               :selection/type :return-land-cost
               :selection/card-source :valid-targets
               :selection/select-count 1
               :selection/valid-targets (vec candidate-ids)
               :selection/selected #{}
               :selection/player-id player-id
               :selection/spell-id object-id
               :selection/mode mode
               :selection/return-cost return-cost
               :selection/validation :exact
               :selection/auto-confirm? true}
        has-targeting? (assoc :selection/lifecycle :chaining)
        (not has-targeting?) (assoc :selection/lifecycle :finalized
                                    :selection/clear-selected-card? true)))))


(defn build-discard-specific-selection
  "Build pending selection for discard-specific additional cost.
   Player selects cards to discard that satisfy all group requirements.

   For Foil: select 2 cards from hand, one must have Island subtype.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with :discard-specific cost
     discard-cost - The :discard-specific cost map

   Returns selection state for choosing cards to discard."
  [game-db player-id object-id mode discard-cost]
  (let [groups (:cost/groups discard-cost)
        total (:cost/total discard-cost)
        hand (or (queries/get-objects-in-zone game-db player-id :hand) [])
        ;; Exclude the spell being cast
        candidates (filterv #(not= object-id (:object/id %)) hand)
        candidate-ids (set (map :object/id candidates))
        ;; Check if spell has targeting (determines lifecycle)
        obj (queries/get-object game-db object-id)
        has-targeting? (seq (:card/targeting (:object/card obj)))]
    (when (seq candidate-ids)
      (cond-> {:selection/zone :hand
               :selection/type :discard-specific-cost
               :selection/card-source :hand
               :selection/select-count total
               :selection/selected #{}
               :selection/player-id player-id
               :selection/spell-id object-id
               :selection/mode mode
               :selection/discard-cost discard-cost
               :selection/discard-groups groups
               :selection/valid-targets (vec candidate-ids)
               :selection/candidate-card-map (into {} (map (fn [obj']
                                                             [(:object/id obj') (:object/card obj')]))
                                                   candidates)
               :selection/validation :exact
               :selection/auto-confirm? false}
        has-targeting? (assoc :selection/lifecycle :chaining)
        (not has-targeting?) (assoc :selection/lifecycle :finalized
                                    :selection/clear-selected-card? true)))))


(defn build-sacrifice-permanent-selection
  "Build pending selection for sacrifice-permanent additional cost.
   Player selects which battlefield permanent matching criteria to sacrifice.
   Player always picks — auto-confirm is disabled by design.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell or activating ability
     object-id - Spell being cast or source permanent for ability
     mode - Casting mode (nil for ability activations)
     sac-cost - The :sacrifice-permanent cost map (has :cost/criteria)
     opts - Optional map with :source-type :ability and :ability for ability path

   Returns selection state for choosing a permanent to sacrifice."
  ([game-db player-id object-id mode sac-cost]
   (build-sacrifice-permanent-selection game-db player-id object-id mode sac-cost nil))
  ([game-db player-id object-id mode sac-cost opts]
   (let [criteria (:cost/criteria sac-cost)
         matching (queries/query-zone-by-criteria game-db player-id :battlefield criteria)
         candidate-ids (set (map :object/id matching))
         ;; Check if spell/ability has targeting (determines lifecycle for spell path)
         source-type (:source-type opts)
         ability (:ability opts)
         ability-index (:ability-index opts)
         has-targeting? (if (= source-type :ability)
                          ;; Ability targeting is handled separately after sacrifice confirmation
                          false
                          (let [obj (queries/get-object game-db object-id)]
                            (seq (:card/targeting (:object/card obj)))))]
     (when (seq candidate-ids)
       (cond-> {:selection/zone :battlefield
                :selection/type :sacrifice-permanent-cost
                :selection/card-source :valid-targets
                :selection/select-count 1
                :selection/valid-targets (vec candidate-ids)
                :selection/selected #{}
                :selection/player-id player-id
                :selection/spell-id object-id
                :selection/mode mode
                :selection/sac-cost sac-cost
                :selection/validation :exact
                :selection/auto-confirm? false}
         (= source-type :ability) (assoc :selection/source-type :ability
                                         :selection/ability ability
                                         :selection/ability-index ability-index)
         (and (not= source-type :ability) has-targeting?) (assoc :selection/lifecycle :chaining)
         (and (not= source-type :ability) (not has-targeting?)) (assoc :selection/lifecycle :finalized
                                                                       :selection/clear-selected-card? true)
         (and (= source-type :ability) (seq (:ability/targeting ability))) (assoc :selection/lifecycle :chaining)
         (and (= source-type :ability) (not (seq (:ability/targeting ability)))) (assoc :selection/lifecycle :finalized
                                                                                        :selection/clear-selected-card? true))))))


(defn build-x-mana-selection
  "Build pending selection for X mana cost.
   Player selects how much to pay for X.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with X in mana cost

   Returns selection state for choosing X value."
  [game-db player-id object-id mode]
  (let [pool (queries/get-mana-pool game-db player-id)
        mana-cost (:mode/mana-cost mode)
        ;; Fixed costs (non-X portion)
        fixed-colorless (get mana-cost :colorless 0)
        fixed-colored (dissoc mana-cost :colorless :x)
        ;; Calculate remaining mana after paying fixed costs
        pool-after-colored (merge-with - pool fixed-colored)
        total-remaining (max 0 (- (reduce + 0 (vals pool-after-colored)) fixed-colorless))
        ;; Max X is what's left after paying fixed costs
        max-x total-remaining]
    {:selection/zone :mana-pool
     :selection/type :x-mana-cost
     :selection/pattern :accumulator
     :selection/lifecycle :chaining
     :selection/player-id player-id
     :selection/spell-id object-id
     :selection/mode mode
     :selection/max-x max-x
     :selection/selected-x 0  ; Default to 0, player increments
     :selection/validation :always
     :selection/auto-confirm? false
     :selection/chain-builder
     (fn [db sel]
       (let [x-value (:selection/selected-x sel)
             sel-mode (:selection/mode sel)
             sel-player (:selection/player-id sel)
             sel-object (:selection/spell-id sel)
             resolved-mana-cost (mana/resolve-x-cost (:mode/mana-cost sel-mode) x-value)
             mode-with-resolved-cost (assoc sel-mode :mode/mana-cost resolved-mana-cost)]
         ;; Always chain to mana-allocation (even with 0 generic)
         (or (build-mana-allocation-selection db sel-player sel-object
                                              mode-with-resolved-cost resolved-mana-cost)
             ;; No generic mana: build minimal mana-allocation that auto-casts
             (let [colored-cost (dissoc resolved-mana-cost :colorless)
                   pool' (queries/get-mana-pool db sel-player)
                   remaining-pool (merge-with - pool' colored-cost)]
               {:selection/zone :mana-pool
                :selection/type :mana-allocation
                :selection/pattern :accumulator
                :selection/lifecycle :finalized
                :selection/clear-selected-card? true
                :selection/player-id sel-player
                :selection/spell-id sel-object
                :selection/mode mode-with-resolved-cost
                :selection/generic-remaining 0
                :selection/generic-total 0
                :selection/allocation {}
                :selection/remaining-pool remaining-pool
                :selection/original-remaining-pool remaining-pool
                :selection/colored-cost colored-cost
                :selection/original-cost resolved-mana-cost
                :selection/validation :always
                :selection/auto-confirm? true}))))}))


(defn build-pay-x-life-selection
  "Build pending selection for pay-x-life additional cost.
   Player selects how much life to pay for X via accumulator stepper.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with :pay-x-life cost

   Returns selection state for choosing X life payment."
  [game-db player-id object-id mode]
  (let [current-life (queries/get-life-total game-db player-id)
        ;; Max X is current life (can pay down to 0)
        max-x (max 0 current-life)]
    {:selection/zone :life
     :selection/type :pay-x-life
     :selection/pattern :accumulator
     :selection/lifecycle :finalized
     :selection/clear-selected-card? true
     :selection/player-id player-id
     :selection/spell-id object-id
     :selection/mode mode
     :selection/max-x max-x
     :selection/selected-x 0
     :selection/validation :always
     :selection/auto-confirm? false}))


(defn build-mana-allocation-selection
  "Build pending selection for manual generic mana allocation.
   Player clicks mana symbols to allocate toward generic cost.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode
     resolved-cost - Mana cost with X already resolved. MUST NOT contain :x.

   Returns selection state, or nil if generic cost is 0."
  [game-db player-id object-id mode resolved-cost]
  (let [generic (get resolved-cost :colorless 0)
        colored-cost (dissoc resolved-cost :colorless)]
    (when (pos? generic)
      (let [pool (queries/get-mana-pool game-db player-id)
            remaining-pool (merge-with - pool colored-cost)]
        {:selection/zone :mana-pool
         :selection/type :mana-allocation
         :selection/pattern :accumulator
         :selection/lifecycle :finalized
         :selection/clear-selected-card? true
         :selection/player-id player-id
         :selection/spell-id object-id
         :selection/mode mode
         :selection/generic-remaining generic
         :selection/generic-total generic
         :selection/allocation {}
         :selection/remaining-pool remaining-pool
         :selection/original-remaining-pool remaining-pool
         :selection/colored-cost colored-cost
         :selection/original-cost resolved-cost
         :selection/validation :always
         :selection/auto-confirm? true}))))


;; =====================================================
;; Confirm Selection Defmethods
;; =====================================================

;; Generic chain: pre-cast cost selections that may chain to targeting.
;; Dispatches for :discard-specific-cost and :return-land-cost via hierarchy.
(defmethod core/build-chain-selection :pre-cast-cost-to-targeting
  [db selection]
  (let [object-id (:selection/spell-id selection)
        player-id (:selection/player-id selection)
        mode (:selection/mode selection)
        obj (queries/get-object db object-id)
        targeting-reqs (:card/targeting (:object/card obj))]
    (when (seq targeting-reqs)
      (let [first-req (first targeting-reqs)]
        {:selection/type :cast-time-targeting
         :selection/lifecycle :finalized
         :selection/player-id player-id
         :selection/object-id object-id
         :selection/mode mode
         :selection/target-requirement first-req
         :selection/valid-targets (targeting/find-valid-targets db player-id first-req)
         :selection/selected #{}
         :selection/select-count 1
         :selection/validation :exact
         :selection/auto-confirm? true
         :selection/card-source :valid-targets}))))


;; Override chain selection for :sacrifice-permanent-cost to handle both spell and ability paths.
;; For the spell path, delegate to parent :pre-cast-cost-to-targeting behavior (cast-time targeting).
;; For the ability path, build an ability-targeting selection instead.
(defmethod core/build-chain-selection :sacrifice-permanent-cost
  [db selection]
  (let [source-type (:selection/source-type selection)]
    (if (= source-type :ability)
      ;; Ability path: build ability-targeting selection
      (let [ability (:selection/ability selection)
            player-id (:selection/player-id selection)
            object-id (:selection/spell-id selection)
            ability-index (:selection/ability-index selection)
            targeting-reqs (targeting/get-targeting-requirements ability)]
        (when (seq targeting-reqs)
          (let [first-req (first targeting-reqs)
                remaining-reqs (vec (rest targeting-reqs))]
            {:selection/type :ability-targeting
             :selection/lifecycle (if (seq remaining-reqs) :chaining :finalized)
             :selection/player-id player-id
             :selection/object-id object-id
             :selection/ability-index ability-index
             :selection/target-requirement first-req
             :selection/chosen-targets {}
             :selection/remaining-target-reqs remaining-reqs
             :selection/valid-targets (targeting/find-valid-targets db player-id first-req)
             :selection/selected #{}
             :selection/select-count 1
             :selection/validation :exact
             :selection/auto-confirm? true
             :selection/card-source :valid-targets})))
      ;; Spell path: delegate to pre-cast-cost-to-targeting chain behavior
      (let [object-id (:selection/spell-id selection)
            player-id (:selection/player-id selection)
            mode (:selection/mode selection)
            obj (queries/get-object db object-id)
            targeting-reqs (:card/targeting (:object/card obj))]
        (when (seq targeting-reqs)
          (let [first-req (first targeting-reqs)]
            {:selection/type :cast-time-targeting
             :selection/lifecycle :finalized
             :selection/player-id player-id
             :selection/object-id object-id
             :selection/mode mode
             :selection/target-requirement first-req
             :selection/valid-targets (targeting/find-valid-targets db player-id first-req)
             :selection/selected #{}
             :selection/select-count 1
             :selection/validation :exact
             :selection/auto-confirm? true
             :selection/card-source :valid-targets}))))))


(defmethod core/execute-confirmed-selection :discard-specific-cost
  [game-db selection]
  (let [selected (:selection/selected selection)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        ;; Discard selected cards to graveyard
        db-after-discard (reduce (fn [d card-id]
                                   (zone-change-dispatch/move-to-zone-db d card-id :graveyard))
                                 game-db
                                 selected)]
    (if (= :chaining (:selection/lifecycle selection))
      ;; Chaining: just discard, chain builder handles targeting
      {:db db-after-discard}
      ;; Finalized: no targeting, cast directly
      {:db (rules/cast-spell-mode db-after-discard player-id object-id mode)})))


(defmethod core/execute-confirmed-selection :return-land-cost
  [game-db selection]
  (let [selected-land-id (first (:selection/selected selection))
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        ;; Return the land to hand (bounce)
        db-after-return (zone-change-dispatch/move-to-zone-db game-db selected-land-id :hand)]
    (if (= :chaining (:selection/lifecycle selection))
      ;; Chaining: just bounce, chain builder handles targeting
      {:db db-after-return}
      ;; Finalized: no targeting, cast directly
      {:db (rules/cast-spell-mode db-after-return player-id object-id mode)})))


(defn- find-stack-item-eid-for-object
  "Find the stack-item EID that references the given object UUID.
   Returns nil if no stack item references this object."
  [db object-id]
  (let [obj-eid (queries/get-object-eid db object-id)]
    (when obj-eid
      (d/q '[:find ?e .
             :in $ ?obj-eid
             :where [?e :stack-item/object-ref ?obj-eid]]
           db obj-eid))))


(defmethod core/execute-confirmed-selection :sacrifice-permanent-cost
  [game-db selection]
  (let [selected-permanent-id (first (:selection/selected selection))
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        source-type (:selection/source-type selection)
        ability (:selection/ability selection)
        ;; Capture effective power BEFORE graveyard move (zone-based statics may change)
        effective-power (creatures/effective-power game-db selected-permanent-id)
        sacrifice-info {:power effective-power}
        ;; Sacrifice the permanent (move to graveyard)
        db-after-sacrifice (zone-change-dispatch/move-to-zone-db game-db selected-permanent-id :graveyard)]
    (cond
      ;; Chaining lifecycle (spell with targeting): sacrifice permanent, store sacrifice-info
      ;; temporarily on spell object so the targeting executor can transfer it to the stack item.
      (= :chaining (:selection/lifecycle selection))
      (let [obj-eid (queries/get-object-eid db-after-sacrifice object-id)
            db-with-pending (if obj-eid
                              (d/db-with db-after-sacrifice
                                         [[:db/add obj-eid :object/pending-sacrifice-info sacrifice-info]])
                              db-after-sacrifice)]
        {:db db-with-pending})

      ;; Finalized lifecycle, ability path: create stack item directly with sacrifice-info.
      (= source-type :ability)
      (let [effects-list (:ability/effects ability [])
            db-with-item (stack/create-stack-item db-after-sacrifice
                                                  {:stack-item/type :activated-ability
                                                   :stack-item/controller player-id
                                                   :stack-item/source object-id
                                                   :stack-item/effects effects-list
                                                   :stack-item/sacrifice-info sacrifice-info
                                                   :stack-item/description (:ability/description ability)})]
        {:db db-with-item})

      ;; Finalized lifecycle, spell path: cast spell then store sacrifice-info on stack item.
      :else
      (let [db-after-cast (rules/cast-spell-mode db-after-sacrifice player-id object-id mode)
            stack-item-eid (find-stack-item-eid-for-object db-after-cast object-id)
            db-final (if stack-item-eid
                       (d/db-with db-after-cast
                                  [[:db/add stack-item-eid :stack-item/sacrifice-info sacrifice-info]])
                       db-after-cast)]
        {:db db-final}))))


(defmethod core/execute-confirmed-selection :exile-cards-cost
  [game-db selection]
  (let [selected (:selection/selected selection)
        selected-count (count selected)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        db-after-exile (reduce (fn [d card-id]
                                 (zone-change-dispatch/move-to-zone-db d card-id :exile))
                               game-db
                               selected)
        obj-eid (queries/get-object-eid db-after-exile object-id)
        db-with-x (d/db-with db-after-exile
                             [[:db/add obj-eid :object/x-value selected-count]])
        db-after-cast (rules/cast-spell-mode db-with-x player-id object-id mode)]
    {:db db-after-cast}))


(defmethod core/execute-confirmed-selection :pay-x-life
  [game-db selection]
  (let [x-value (:selection/selected-x selection)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        ;; Deduct life from controller
        player-eid (queries/get-player-eid game-db player-id)
        current-life (queries/get-life-total game-db player-id)
        new-life (- current-life x-value)
        db-after-life (d/db-with game-db [[:db/add player-eid :player/life new-life]])
        ;; Cast the spell
        db-after-cast (rules/cast-spell-mode db-after-life player-id object-id mode)
        ;; Store chosen-x on the stack item
        obj-eid (queries/get-object-eid db-after-cast object-id)
        stack-item-eid (when obj-eid
                         (d/q '[:find ?e .
                                :in $ ?obj-eid
                                :where [?e :stack-item/object-ref ?obj-eid]]
                              db-after-cast obj-eid))
        db-final (if stack-item-eid
                   (d/db-with db-after-cast
                              [[:db/add stack-item-eid :stack-item/chosen-x x-value]])
                   db-after-cast)]
    {:db db-final}))


(defmethod core/execute-confirmed-selection :x-mana-cost
  [game-db selection]
  (let [x-value (:selection/selected-x selection)
        object-id (:selection/spell-id selection)
        obj-eid (queries/get-object-eid game-db object-id)
        db-with-x (d/db-with game-db
                             [[:db/add obj-eid :object/x-value x-value]])]
    ;; Store X value. Chain builder provides mana-allocation for casting.
    {:db db-with-x}))


(defn- confirm-spell-mana-allocation
  "Complete spell casting after mana allocation.
   Stores pending targets on stack-item if present."
  [game-db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        allocation (:selection/allocation selection)
        pending-targets (:selection/pending-targets selection)
        db-after-cast (rules/cast-spell-mode-with-allocation
                        game-db player-id object-id mode allocation)
        db-final (if pending-targets
                   (let [obj-eid (queries/get-object-eid db-after-cast object-id)
                         stack-item-eid (when obj-eid
                                          (d/q '[:find ?e .
                                                 :in $ ?obj-eid
                                                 :where [?e :stack-item/object-ref ?obj-eid]]
                                               db-after-cast obj-eid))]
                     (if stack-item-eid
                       (d/db-with db-after-cast
                                  [[:db/add stack-item-eid :stack-item/targets pending-targets]])
                       db-after-cast))
                   db-after-cast)]
    {:db db-final}))


(defn- confirm-ability-mana-allocation
  "Complete ability activation after mana allocation.
   Pays mana with explicit allocation and creates stack-item."
  [game-db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        ability (:selection/ability selection)
        allocation (:selection/allocation selection)
        mana-cost (:selection/original-cost selection)
        db-after-mana (mana/pay-mana-with-allocation game-db player-id mana-cost allocation)
        effects-list (:ability/effects ability [])
        db-with-item (stack/create-stack-item db-after-mana
                                              {:stack-item/type :activated-ability
                                               :stack-item/controller player-id
                                               :stack-item/source object-id
                                               :stack-item/effects effects-list
                                               :stack-item/description (:ability/description ability)})]
    {:db db-with-item}))


(defmethod core/execute-confirmed-selection :mana-allocation
  [game-db selection]
  (let [source-type (:selection/source-type selection)]
    (if (= source-type :ability)
      (confirm-ability-mana-allocation game-db selection)
      (confirm-spell-mana-allocation game-db selection))))


;; =====================================================
;; Re-frame Event Handlers
;; =====================================================

(rf/reg-event-db
  ::cancel-exile-cards-selection
  (fn [db _]
    (dissoc db :game/pending-selection)))


(rf/reg-event-db
  ::increment-x-value
  (fn [db _]
    (let [selection (:game/pending-selection db)
          current-x (or (:selection/selected-x selection) 0)
          max-x (or (:selection/max-x selection) 0)]
      (if (< current-x max-x)
        (assoc-in db [:game/pending-selection :selection/selected-x] (inc current-x))
        db))))


(rf/reg-event-db
  ::decrement-x-value
  (fn [db _]
    (let [selection (:game/pending-selection db)
          current-x (or (:selection/selected-x selection) 0)]
      (if (pos? current-x)
        (assoc-in db [:game/pending-selection :selection/selected-x] (dec current-x))
        db))))


(rf/reg-event-db
  ::cancel-x-mana-selection
  (fn [db _]
    (dissoc db :game/pending-selection)))


;; =====================================================
;; Mana Allocation Event Handlers
;; =====================================================

(defn allocate-mana-color-impl
  "Handle allocating one mana of a color toward the generic cost.
   Increments allocation, decrements remaining-pool and generic-remaining.
   Auto-confirms when generic-remaining hits 0.

   Arguments:
     app-db - Full re-frame app-db with :game/pending-selection
     color - Keyword like :black, :blue, :colorless

   Returns updated app-db.

   Contract: :selection/generic-remaining and :selection/remaining-pool must be
   present (non-nil) in pending-selection. Missing or nil means malformed selection.
   Note: generic-remaining=0 is LEGITIMATE (at-limit). remaining-pool={} is LEGITIMATE.
   The violation is specifically key-absence or nil — use contains? not pos-int?."
  [app-db color]
  (let [selection (:game/pending-selection app-db)]
    (when-not (and (contains? selection :selection/generic-remaining)
                   (some? (:selection/generic-remaining selection)))
      (throw (ex-info "allocate-mana-color-impl: :selection/generic-remaining missing from pending-selection"
                      {:effect-type :mana-allocation
                       :color color
                       :selection selection})))
    (when-not (and (contains? selection :selection/remaining-pool)
                   (some? (:selection/remaining-pool selection)))
      (throw (ex-info "allocate-mana-color-impl: :selection/remaining-pool missing from pending-selection"
                      {:effect-type :mana-allocation
                       :color color
                       :selection selection})))
    (let [remaining (:selection/generic-remaining selection)
          pool (:selection/remaining-pool selection)
          available (get pool color 0)]
      (if (or (zero? remaining) (zero? available))
        app-db
        (let [new-remaining (dec remaining)
              new-allocation (update (:selection/allocation selection) color (fnil inc 0))
              new-pool (update pool color dec)
              updated-db (-> app-db
                             (assoc-in [:game/pending-selection :selection/generic-remaining] new-remaining)
                             (assoc-in [:game/pending-selection :selection/allocation] new-allocation)
                             (assoc-in [:game/pending-selection :selection/remaining-pool] new-pool))]
          (if (zero? new-remaining)
            (core/confirm-selection-impl updated-db)
            updated-db))))))


(defn reset-mana-allocation-impl
  "Reset mana allocation to initial state (clear allocation, restore pool).

   Arguments:
     app-db - Full re-frame app-db with :game/pending-selection

   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        generic-total (:selection/generic-total selection)
        original-pool (:selection/original-remaining-pool selection)]
    (-> app-db
        (assoc-in [:game/pending-selection :selection/allocation] {})
        (assoc-in [:game/pending-selection :selection/generic-remaining] generic-total)
        (assoc-in [:game/pending-selection :selection/remaining-pool] original-pool))))


(rf/reg-event-db
  ::allocate-mana-color
  (fn [db [_ color]]
    (allocate-mana-color-impl db color)))


(rf/reg-event-db
  ::reset-mana-allocation
  (fn [db _]
    (reset-mana-allocation-impl db)))


(rf/reg-event-db
  ::cancel-mana-allocation
  (fn [db _]
    (dissoc db :game/pending-selection)))
