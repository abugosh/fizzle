(ns fizzle.events.casting
  "Spell casting pipeline: pre-cast checks, mode selection, and cast dispatch.
   Pure functions and re-frame event handlers."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


;; =====================================================
;; Data-Driven Pre-Cast Pipeline (ADR-015)
;; =====================================================
;; Each pre-cast step is a defmethod on evaluate-pre-cast-step.
;; Steps return nil (skip), {:selection sel} (pause for input),
;; or {:db db} (terminal — casting complete).

(defmulti evaluate-pre-cast-step
  "Evaluate a single pre-cast pipeline step.
   Dispatches on step keyword.

   Arguments:
     step - Keyword identifying the pre-cast step
     ctx  - Map with :game-db, :player-id, :object-id, :mode, :target

   Returns:
     nil           — skip this step (condition not met)
     {:selection s} — pause pipeline for player input
     {:db db}       — terminal, casting complete"
  (fn [step _ctx] step))


(def pre-cast-pipeline
  "Ordered vector of pre-cast step keywords. Evaluated left-to-right.
   Costs before targeting, targeting before mana allocation."
  [:exile-cards-cost :return-land-cost :discard-specific-cost :sacrifice-permanent-cost
   :pay-x-life :x-mana-cost :targeting :mana-allocation])


(defmethod evaluate-pre-cast-step :exile-cards-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-exile-cards-x-cost? mode)
    (let [exile-cost (sel-costs/get-exile-cards-x-cost mode)
          sel (sel-costs/build-exile-cards-selection game-db player-id object-id mode exile-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :return-land-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-return-land-cost? mode)
    (let [return-cost (sel-costs/get-return-land-cost mode)
          sel (sel-costs/build-return-land-selection game-db player-id object-id mode return-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :discard-specific-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-discard-specific-cost? mode)
    (let [discard-cost (sel-costs/get-discard-specific-cost mode)
          sel (sel-costs/build-discard-specific-selection game-db player-id object-id mode discard-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :sacrifice-permanent-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-sacrifice-permanent-cost? mode)
    (let [sac-cost (sel-costs/get-sacrifice-permanent-cost mode)
          sel (sel-costs/build-sacrifice-permanent-selection game-db player-id object-id mode sac-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :pay-x-life
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-pay-x-life-cost? mode)
    {:selection (sel-costs/build-pay-x-life-selection game-db player-id object-id mode)}))


(defmethod evaluate-pre-cast-step :x-mana-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-mana-x-cost? mode)
    {:selection (sel-costs/build-x-mana-selection game-db player-id object-id mode)}))


(defmethod evaluate-pre-cast-step :targeting
  [_ {:keys [game-db player-id object-id mode target]}]
  (let [obj (queries/get-object game-db object-id)
        card (:object/card obj)
        chosen-mode (:object/chosen-mode obj)
        targeting-reqs (if chosen-mode
                         (or (:mode/targeting chosen-mode) [])
                         (targeting/get-targeting-requirements card))]
    (when (seq targeting-reqs)
      (let [first-req (first targeting-reqs)]
        (if target
          ;; Pre-determined target: cast directly
          (let [sel {:selection/player-id player-id
                     :selection/object-id object-id
                     :selection/mode mode
                     :selection/target-requirement first-req
                     :selection/selected #{target}}]
            {:db (sel-targeting/confirm-cast-time-target game-db sel)})
          ;; Interactive targeting
          (let [valid-targets (targeting/find-valid-targets game-db player-id first-req)]
            (if (and (= :player (:target/type first-req))
                     (= 1 (count valid-targets)))
              ;; Single player target — auto-cast without dialog
              (let [auto-target (first valid-targets)
                    sel {:selection/player-id player-id
                         :selection/object-id object-id
                         :selection/mode mode
                         :selection/target-requirement first-req
                         :selection/selected #{auto-target}}]
                {:db (sel-targeting/confirm-cast-time-target game-db sel)})
              ;; Multiple targets or object targeting — show selection
              {:selection (sel-targeting/build-cast-time-target-selection
                            game-db player-id object-id mode first-req)})))))))


(defmethod evaluate-pre-cast-step :mana-allocation
  [_ {:keys [game-db player-id object-id mode]}]
  (let [effective-cost (static-abilities/get-effective-mana-cost
                         game-db player-id object-id mode)]
    (when (sel-costs/has-generic-mana-cost? effective-cost)
      (if-let [sel (sel-costs/build-mana-allocation-selection
                     game-db player-id object-id mode effective-cost)]
        {:selection sel}
        ;; nil from builder means 0 generic (defensive fallback) — cast directly
        {:db (rules/cast-spell-mode game-db player-id object-id mode)}))))


(defn- initiate-cast-with-mode
  "Start casting a spell with a specific mode.
   Evaluates the pre-cast pipeline steps in order. Each step checks for a
   pre-cast requirement (additional cost, targeting, mana allocation) and
   returns nil (skip), {:selection sel} (pause for input), or {:db db} (done).
   If all steps return nil, casts immediately.
   Returns updated app-db."
  [app-db player-id object-id mode target]
  (let [game-db (:game/db app-db)
        ctx {:game-db game-db :player-id player-id
             :object-id object-id :mode mode :target target}]
    (loop [steps pre-cast-pipeline]
      (if (empty? steps)
        ;; All steps skipped — cast immediately
        (-> app-db
            (assoc :game/db (rules/cast-spell-mode game-db player-id object-id mode))
            (dissoc :game/selected-card))
        (let [result (evaluate-pre-cast-step (first steps) ctx)]
          (cond
            (nil? result) (recur (rest steps))
            (:selection result) (-> app-db
                                    (sel-spec/set-pending-selection (:selection result))
                                    (dissoc :game/selected-card))
            (:db result) (-> app-db
                             (assoc :game/db (:db result))
                             (dissoc :game/selected-card))))))))


(defn- get-valid-spell-modes
  "For a modal card (:card/modes), return only modes that have valid targets.
   Returns nil for non-modal cards."
  [game-db player-id card]
  (when-let [card-modes (:card/modes card)]
    (filterv (fn [spell-mode]
               (let [targeting (or (:mode/targeting spell-mode) [])]
                 (every? (fn [req]
                           (if (:target/required req)
                             (seq (targeting/find-valid-targets game-db player-id req))
                             true))
                         targeting)))
             card-modes)))


(defn build-spell-mode-selection
  "Build a spell-mode selection for a modal card.
   Mode maps are used as candidate identifiers — select-count 1, auto-confirm true.
   Per-mode targeting evaluation happens here (valid modes only).
   Returns selection map for :game/pending-selection."
  [player-id object-id valid-modes]
  {:selection/mechanism :pick-mode
   :selection/domain    :spell-mode
   :selection/lifecycle :finalized
   :selection/player-id player-id
   :selection/object-id object-id
   :selection/candidates valid-modes
   :selection/selected #{}
   :selection/select-count 1
   :selection/auto-confirm? true
   :selection/validation :exact
   :selection/on-complete {:continuation/type :cast-after-spell-mode
                           :continuation/object-id object-id}})


(defmethod sel-core/apply-domain-policy :spell-mode
  [game-db selection]
  (let [chosen-mode (first (:selection/selected selection))
        object-id (:selection/object-id selection)
        obj-eid (queries/get-object-eid game-db object-id)]
    {:db (if (and chosen-mode obj-eid)
           (d/db-with game-db [[:db/add obj-eid :object/chosen-mode chosen-mode]])
           game-db)}))


(defmethod sel-core/execute-confirmed-selection :spell-mode
  [game-db selection]
  (let [chosen-mode (first (:selection/selected selection))
        object-id (:selection/object-id selection)
        obj-eid (queries/get-object-eid game-db object-id)]
    {:db (if (and chosen-mode obj-eid)
           (d/db-with game-db [[:db/add obj-eid :object/chosen-mode chosen-mode]])
           game-db)}))


(defmethod sel-core/apply-continuation :cast-after-spell-mode
  [continuation app-db]
  (let [object-id (:continuation/object-id continuation)
        game-db (:game/db app-db)
        player-id (queries/get-human-player-id game-db)
        ;; Determine casting mode for this spell.
        ;; For alternate-cost spells (primary + hand-zone alternate), :object/chosen-mode is a
        ;; casting mode map (has :mode/mana-cost) — use it to honour the player's mode choice.
        ;; For modal cards (:card/modes), :object/chosen-mode is an effects mode map (no
        ;; :mode/mana-cost) — ignore it and fall back to primary casting mode as before.
        obj (queries/get-object game-db object-id)
        chosen-mode (:object/chosen-mode obj)
        modes (rules/get-casting-modes game-db player-id object-id)
        casting-mode (if (and chosen-mode (:mode/mana-cost chosen-mode))
                       ;; Alternate-cost scenario: chosen-mode IS the casting mode.
                       chosen-mode
                       ;; Modal card scenario: chosen-mode is an effects mode, not a casting mode.
                       ;; Pick :primary or first from get-casting-modes.
                       (or (first (filter #(= :primary (:mode/id %)) modes))
                           (first modes)))
        result (initiate-cast-with-mode app-db player-id object-id casting-mode nil)
        cast-and-yield? (= :cast-and-yield (:type (:history/deferred-entry result)))]
    (cond
      ;; Targeted mode in cast-and-yield: propagate resolve-one-and-stop
      ;; to the new selection so auto-resolve fires after targeting.
      (and cast-and-yield?
           (:game/pending-selection result)
           (not (:selection/on-complete (:game/pending-selection result))))
      {:app-db (assoc-in result [:game/pending-selection :selection/on-complete]
                         {:continuation/type :resolve-one-and-stop})}

      ;; Non-targeted mode in cast-and-yield: spell cast directly,
      ;; chain resolve-one-and-stop via continuation (no circular dep).
      (and cast-and-yield?
           (nil? (:game/pending-selection result)))
      {:app-db result :then {:continuation/type :resolve-one-and-stop}}

      :else {:app-db result})))


(defn cast-spell-handler
  "Handle cast-spell event: check casting modes and either auto-cast,
   show mode selector, or initiate casting with X cost/targeting checks.
   Accepts optional opts map with :player-id, :object-id, :target.
   When opts provided, uses explicit values instead of human-pid/selected-card.
   Pure function: (app-db, opts?) -> app-db"
  ([app-db] (cast-spell-handler app-db nil))
  ([app-db opts]
   (let [game-db-before (:game/db app-db)
         player-id (or (:player-id opts)
                       (queries/get-human-player-id game-db-before))
         object-id (or (:object-id opts)
                       (:game/selected-card app-db))
         target (:target opts)]
     (if (and object-id (rules/can-cast? game-db-before player-id object-id))
       (let [obj (queries/get-object game-db-before object-id)
             card (:object/card obj)
             valid-spell-modes (get-valid-spell-modes game-db-before player-id card)]
         (if (seq valid-spell-modes)
           ;; Modal card: spell mode selection through standard selection system.
           ;; Set deferred-entry — entry will be created when selection chain completes.
           (-> app-db
               (sel-spec/set-pending-selection
                 (build-spell-mode-selection player-id object-id valid-spell-modes))
               (assoc :history/deferred-entry
                      {:type :cast-spell
                       :object-id object-id
                       :pre-game-db game-db-before
                       :principal player-id
                       :event-type :fizzle.events.casting/cast-spell}))
           ;; Non-modal card: proceed with casting mode selection
           (let [modes (rules/get-casting-modes game-db-before player-id object-id)
                 castable-modes (filterv #(rules/can-cast-mode? game-db-before player-id object-id %) modes)]
             (cond
               ;; No castable modes - shouldn't happen if can-cast? passed
               (empty? castable-modes)
               app-db

               ;; Multiple modes: show selector via standard selection pipeline (ADR-023).
               ;; build-spell-mode-selection creates a :spell-mode selection whose
               ;; :cast-after-spell-mode continuation reads :object/chosen-mode.
               (> (count castable-modes) 1)
               (-> app-db
                   (sel-spec/set-pending-selection
                     (build-spell-mode-selection player-id object-id castable-modes))
                   (assoc :history/deferred-entry
                          {:type :cast-spell
                           :object-id object-id
                           :pre-game-db game-db-before
                           :principal player-id
                           :event-type :fizzle.events.casting/cast-spell}))

               ;; Single mode: check for X costs, targeting, then cast
               :else
               (let [result (initiate-cast-with-mode app-db player-id object-id (first castable-modes) target)]
                 (if (:game/pending-selection result)
                   ;; Pre-cast selection needed — defer history entry
                   (assoc result :history/deferred-entry
                          {:type :cast-spell
                           :object-id object-id
                           :pre-game-db game-db-before
                           :principal player-id
                           :event-type :fizzle.events.casting/cast-spell})
                   ;; Immediate cast — set pending-entry now
                   (let [game-db-after (:game/db result)
                         desc (descriptions/describe-cast-spell game-db-after object-id)]
                     (assoc result :history/pending-entry
                            (descriptions/build-pending-entry
                              game-db-after
                              :fizzle.events.casting/cast-spell
                              desc
                              player-id)))))))))
       app-db))))


(rf/reg-event-db
  ::cast-spell
  (fn [db [_ opts]]
    (cast-spell-handler db opts)))
