(ns fizzle.events.selection.replacement
  "Replacement-choice selection domain: builder and executor for :replacement-choice.

   Called from events-layer handlers when move-to-zone returns
   {:needs-selection {:input/type :replacement ...}}.

   build-selection-for-replacement:
     Converts a :needs-selection signal into a :replacement-choice selection map.
     Sets :object/replacement-pending true on the moving object.
     When any :proceed choice has an interactive cost (e.g. :discard-specific), sets
     :selection/on-complete to :build-discard-for-replacement-proceed so the continuation
     chain builds the discard selection after the player confirms :proceed.
     Returns {:db updated-db :selection sel-map}.

   execute-confirmed-selection :replacement-choice:
     1. Reads the chosen :selection/selected choice
     2. RETRACTS replacement entity from db BEFORE zone change (infinite loop prevention)
     3. If :proceed and cost is non-interactive → executes cost + commits original zone change
     4. If :proceed and cost is interactive → retracts entity + clears marker, returns {:db db}
        (the :selection/on-complete continuation chain builds the discard selection and
         later :resume-replacement-zone-change commits the zone change)
     5. If :redirect → calls move-to-zone-db for :choice/redirect-to zone (no cost)
     6. Clears :object/replacement-pending on the object (non-interactive path only)
     7. Returns {:db updated-db} (ADR-020 continuation chain shape)

   apply-continuation :build-discard-for-replacement-proceed:
     Builds a zone-pick discard selection (hand lands matching criteria) with
     :selection/on-complete = :resume-replacement-zone-change, places it as pending.
     Returns {:app-db updated-app-db}.

   apply-continuation :resume-replacement-zone-change:
     Called after player confirms land discard.
     1. Clears :object/replacement-pending on the moving object
     2. Calls move-to-zone-db with original destination
     3. Returns {:app-db updated-app-db}

   Anti-patterns:
     - NO calling move-to-zone (tagged return) in continuations — use move-to-zone-db (shim)
     - NO new pending-state mechanism — reuses :game/pending-selection
     - NO card-name-specific logic — cost type drives dispatch"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.spec :as sel-spec]))


;; =====================================================
;; Interactive Cost Detection
;; =====================================================

(defn- interactive-cost?
  "Returns true when a :choice/cost map would require player interaction
   (i.e. :discard-specific — player must choose which card to discard).
   Non-interactive costs (e.g. :lose-life, :add-mana) can be executed inline."
  [cost]
  (when cost
    (= :discard-specific (:effect/type cost))))


;; =====================================================
;; Builder
;; =====================================================

(defn- has-valid-targets-for-discard-cost?
  "Returns true if the player has valid discard targets in hand for a :discard-specific cost.
   The moving object (on :stack) is excluded since it cannot be discarded from hand.

   Arguments:
     db        - Datascript database value
     player-id - The player whose hand to search
     object-id - UUID of the moving object (excluded from candidates)
     cost      - :discard-specific effect map with :effect/criteria

   Returns:
     Boolean - true if at least one valid discard target exists in hand"
  [db player-id object-id cost]
  (let [criteria   (or (:effect/criteria cost) {})
        candidates (q/query-zone-by-criteria db player-id :hand criteria)
        ;; Exclude the moving object itself (it is on :stack, not :hand, but be safe)
        filtered   (filterv #(not= object-id (:object/id %)) candidates)]
    (seq filtered)))


(defn build-selection-for-replacement
  "Convert a :needs-selection signal into a :replacement-choice selection.

   Sets :object/replacement-pending true on the moving object.

   When any :proceed choice has an interactive cost (e.g. :discard-specific),
   sets :selection/on-complete to :build-discard-for-replacement-proceed so the
   continuation chain handles the interactive cost after the player chooses :proceed.

   No-valid-targets filtering:
   If a :proceed choice has an interactive :discard-specific cost but no valid
   discard targets exist in the player's hand, the :proceed choice is suppressed.
   If only one choice remains after suppression (e.g. only :redirect), returns
   {:db updated-db :selection nil :auto-redirect redirect-choice} so the events
   layer can auto-confirm without presenting a player prompt.

   Arguments:
     db         - Datascript database value
     needs-sel  - {:input/type :replacement
                   :replacement/entity  <replacement-entity-map>
                   :replacement/event   {:event/type :zone-change :event/object <uuid>
                                         :event/from <zone> :event/to <zone>}
                   :replacement/object-id <UUID>}

   Returns:
     {:db updated-db :selection replacement-choice-sel-map}
     or {:db updated-db :selection nil :auto-redirect redirect-choice} when only
     one choice remains after filtering invalid proceed choices (auto-confirm path)"
  [db needs-sel]
  (let [replacement-entity (:replacement/entity needs-sel)
        in-flight-event    (:replacement/event needs-sel)
        object-id          (:replacement/object-id needs-sel)
        replacement-eid    (:db/id replacement-entity)
        choices            (:replacement/choices replacement-entity)
        ;; Determine the controlling player (owner of the moving object)
        obj        (q/get-object db object-id)
        controller-ref (:object/controller obj)
        controller-eid (if (map? controller-ref) (:db/id controller-ref) controller-ref)
        player-id  (q/get-player-id db controller-eid)
        ;; Set :object/replacement-pending on the object
        obj-eid    (q/get-object-eid db object-id)
        updated-db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
        ;; Filter out :proceed choices with interactive costs when no valid targets exist.
        ;; A :proceed choice with :discard-specific cost is only valid if at least one
        ;; matching card exists in the player's hand.
        valid-choices
        (filterv (fn [choice]
                   (if (and (= :proceed (:choice/action choice))
                            (interactive-cost? (:choice/cost choice)))
                     (has-valid-targets-for-discard-cost? updated-db player-id object-id
                                                          (:choice/cost choice))
                     ;; Non-interactive proceed costs and all redirect choices are always valid
                     true))
                 choices)
        ;; Check if we can auto-confirm (only one choice available after filtering)
        proceed-choice (first (filter #(= :proceed (:choice/action %)) valid-choices))
        proceed-cost   (when proceed-choice (:choice/cost proceed-choice))
        destination    (:event/to in-flight-event)
        ;; When interactive proceed cost exists, set on-complete continuation
        on-complete    (when (interactive-cost? proceed-cost)
                         {:continuation/type        :build-discard-for-replacement-proceed
                          :continuation/object-id   object-id
                          :continuation/destination destination
                          :continuation/cost        proceed-cost
                          :continuation/player-id   player-id})]
    (if (and (= 1 (count valid-choices))
             (= :redirect (:choice/action (first valid-choices))))
      ;; Single :redirect choice — auto-confirm path; no player prompt needed.
      ;; Return :auto-redirect so the events layer can commit the redirect immediately.
      {:db updated-db :selection nil :auto-redirect (first valid-choices)}
      ;; Multiple valid choices — build the selection map for player input
      (let [sel (cond-> {:selection/type                 :replacement-choice
                         :selection/player-id            player-id
                         :selection/object-id            object-id
                         :selection/replacement-entity-id replacement-eid
                         :selection/replacement-event    in-flight-event
                         :selection/choices              valid-choices
                         :selection/selected             nil
                         :selection/validation           :always
                         :selection/auto-confirm?        false
                         :selection/lifecycle            :finalized}
                  on-complete (assoc :selection/on-complete on-complete))]
        {:db updated-db :selection sel}))))


;; =====================================================
;; Executor
;; =====================================================

(defmethod core/execute-confirmed-selection :replacement-choice
  [game-db selection]
  (let [choice          (:selection/selected selection)
        object-id       (:selection/object-id selection)
        replacement-eid (:selection/replacement-entity-id selection)
        in-flight-event (:selection/replacement-event selection)
        player-id       (:selection/player-id selection)
        action          (:choice/action choice)

        ;; STEP 1: Retract replacement entity BEFORE zone change to prevent infinite loop.
        ;; If the entity still existed when move-to-zone-db fires, get-replacements-for-event
        ;; would match again → infinite recursion.
        db-no-replacement (d/db-with game-db [[:db/retractEntity replacement-eid]])

        ;; STEP 2: Dispatch based on chosen action
        db-final
        (case action
          :proceed
          (let [cost-effect (:choice/cost choice)]
            (if (interactive-cost? cost-effect)
              ;; Interactive cost: retract entity + marker, do NOT commit zone change.
              ;; The :selection/on-complete continuation (set by build-selection-for-replacement)
              ;; builds the discard selection; :resume-replacement-zone-change commits the zone change
              ;; after the player confirms.
              (let [obj-eid (q/get-object-eid db-no-replacement object-id)
                    db-marker-cleared (if obj-eid
                                        (d/db-with db-no-replacement
                                                   [[:db/retract obj-eid :object/replacement-pending true]])
                                        db-no-replacement)]
                db-marker-cleared)
              ;; Non-interactive cost: execute inline, then commit original zone change.
              (let [db-after-cost (if cost-effect
                                    (let [cost-result (effects/execute-effect-checked
                                                        db-no-replacement player-id
                                                        cost-effect object-id)]
                                      (:db cost-result))
                                    db-no-replacement)
                    ;; Clear :object/replacement-pending after non-interactive cost
                    obj-eid (q/get-object-eid db-after-cost object-id)
                    db-marker-cleared (if obj-eid
                                        (d/db-with db-after-cost
                                                   [[:db/retract obj-eid :object/replacement-pending true]])
                                        db-after-cost)
                    destination (get in-flight-event :event/to)]
                ;; Guard: only move if the object still exists (not retracted by cost execution)
                (if (q/get-object-eid db-marker-cleared object-id)
                  (zone-change-dispatch/move-to-zone-db db-marker-cleared object-id destination)
                  db-marker-cleared))))

          :redirect
          ;; Move to redirect zone — no cost execution.
          ;; Clear :object/replacement-pending before zone change.
          (let [redirect-to (:choice/redirect-to choice)
                obj-eid (q/get-object-eid db-no-replacement object-id)
                db-marker-cleared (if obj-eid
                                    (d/db-with db-no-replacement
                                               [[:db/retract obj-eid :object/replacement-pending true]])
                                    db-no-replacement)]
            (zone-change-dispatch/move-to-zone-db db-marker-cleared object-id redirect-to))

          ;; Unknown action — no-op
          (do
            (js/console.warn
              (str "execute-confirmed-selection :replacement-choice: unknown action " action))
            (let [obj-eid (q/get-object-eid db-no-replacement object-id)]
              (if obj-eid
                (d/db-with db-no-replacement
                           [[:db/retract obj-eid :object/replacement-pending true]])
                db-no-replacement))))]
    {:db db-final}))


;; =====================================================
;; Continuations
;; =====================================================

(defmethod core/apply-continuation :build-discard-for-replacement-proceed
  [continuation app-db]
  (let [object-id   (:continuation/object-id continuation)
        destination (:continuation/destination continuation)
        cost        (:continuation/cost continuation)
        player-id   (:continuation/player-id continuation)
        game-db     (:game/db app-db)
        ;; Guard: only build discard selection if the object has NOT already been
        ;; redirected to graveyard. If the player chose :redirect,
        ;; execute-confirmed-selection already moved the object to :graveyard.
        ;; This continuation fires unconditionally via finalized-path, so we
        ;; must skip the discard step when redirect already resolved.
        ;; The object is on :stack during normal proceed path (not :hand — it was
        ;; cast before the replacement fires).
        current-obj (q/get-object game-db object-id)
        current-zone (:object/zone current-obj)]
    (if (= :graveyard current-zone)
      ;; Object already redirected to graveyard — no-op, don't build discard selection
      {:app-db app-db}
      ;; Object still on stack (:proceed path) — build land-discard selection
      (let [criteria    (or (:effect/criteria cost) {})
            matching    (q/query-zone-by-criteria game-db player-id :hand criteria)
            ;; Exclude the object itself (it's on stack, not in hand)
            candidates  (filterv #(not= object-id (:object/id %)) matching)
            cand-ids    (set (map :object/id candidates))
            ;; Build discard selection with on-complete = :resume-replacement-zone-change
            discard-sel {:selection/type          :discard
                         :selection/lifecycle     :finalized
                         :selection/zone          :hand
                         :selection/card-source   :valid-targets
                         :selection/target-zone   :graveyard
                         :selection/select-count  (or (:effect/count cost) 1)
                         :selection/valid-targets (vec cand-ids)
                         :selection/candidate-ids cand-ids
                         :selection/selected      #{}
                         :selection/player-id     player-id
                         :selection/validation    :exact
                         :selection/auto-confirm? false
                         :selection/on-complete   {:continuation/type        :resume-replacement-zone-change
                                                   :continuation/object-id   object-id
                                                   :continuation/destination destination}}]
        {:app-db (sel-spec/set-pending-selection app-db discard-sel)}))))


(defmethod core/apply-continuation :resume-replacement-zone-change
  [continuation app-db]
  (let [object-id   (:continuation/object-id continuation)
        destination (:continuation/destination continuation)
        game-db     (:game/db app-db)
        ;; Clear :object/replacement-pending (may already be cleared by execute-confirmed-selection
        ;; for non-interactive path, but safe to retract again — idempotent)
        obj-eid     (q/get-object-eid game-db object-id)
        db-cleared  (if obj-eid
                      (d/db-with game-db [[:db/retract obj-eid :object/replacement-pending true]])
                      game-db)
        ;; Commit the original zone change (replacement entity already retracted earlier)
        db-final    (zone-change-dispatch/move-to-zone-db db-cleared object-id destination)]
    {:app-db (assoc app-db :game/db db-final)}))
