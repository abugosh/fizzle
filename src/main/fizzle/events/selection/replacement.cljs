(ns fizzle.events.selection.replacement
  "Replacement-choice selection domain: builder and executor for :replacement-choice.

   Called from events-layer handlers when move-to-zone returns
   {:needs-selection {:input/type :replacement ...}}.

   build-selection-for-replacement:
     Converts a :needs-selection signal into a :replacement-choice selection map.
     Sets :object/replacement-pending true on the moving object.
     Returns {:db updated-db :selection sel-map}.

   execute-confirmed-selection :replacement-choice:
     1. Reads the chosen :selection/selected choice
     2. RETRACTS replacement entity from db BEFORE zone change (infinite loop prevention)
     3. If :proceed → executes :choice/cost effects (must be non-interactive), then
                        calls move-to-zone-db to commit original zone change
     4. If :redirect → calls move-to-zone-db for :choice/redirect-to zone (no cost)
     5. Clears :object/replacement-pending on the object
     6. Returns {:db updated-db} (ADR-020 continuation chain shape)

   Anti-patterns:
     - NO interactive :choice/cost (would require nested selection) — assert non-interactive
     - NO calling move-to-zone (tagged return) — use move-to-zone-db (shim)
     - NO pending-state mechanism other than :game/pending-selection (reuses existing system)"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Builder
;; =====================================================

(defn build-selection-for-replacement
  "Convert a :needs-selection signal into a :replacement-choice selection.

   Sets :object/replacement-pending true on the moving object.

   Arguments:
     db         - Datascript database value
     needs-sel  - {:input/type :replacement
                   :replacement/entity  <replacement-entity-map>
                   :replacement/event   {:event/type :zone-change :event/object <uuid>
                                         :event/from <zone> :event/to <zone>}
                   :replacement/object-id <UUID>}

   Returns:
     {:db updated-db :selection replacement-choice-sel-map}"
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
        ;; Build the selection map
        sel {:selection/type                 :replacement-choice
             :selection/player-id            player-id
             :selection/object-id            object-id
             :selection/replacement-entity-id replacement-eid
             :selection/replacement-event    in-flight-event
             :selection/choices              choices
             :selection/selected             nil
             :selection/validation           :always
             :selection/auto-confirm?        false
             :selection/lifecycle            :finalized}]
    {:db updated-db :selection sel}))


;; =====================================================
;; Executor
;; =====================================================

(defmethod core/execute-confirmed-selection :replacement-choice
  [game-db selection]
  (let [choice         (:selection/selected selection)
        object-id      (:selection/object-id selection)
        replacement-eid (:selection/replacement-entity-id selection)
        in-flight-event (:selection/replacement-event selection)
        player-id      (:selection/player-id selection)
        action         (:choice/action choice)

        ;; STEP 1: Retract replacement entity BEFORE zone change to prevent infinite loop.
        ;; If the entity still existed when move-to-zone-db fires, get-replacements-for-event
        ;; would match again → infinite recursion.
        db-no-replacement (d/db-with game-db [[:db/retractEntity replacement-eid]])

        ;; STEP 2: Clear :object/replacement-pending marker
        obj-eid (q/get-object-eid db-no-replacement object-id)
        db-marker-cleared (if obj-eid
                            (d/db-with db-no-replacement
                                       [[:db/retract obj-eid :object/replacement-pending true]])
                            db-no-replacement)

        ;; STEP 3: Dispatch based on chosen action
        db-final
        (case action
          :proceed
          ;; Execute :choice/cost effects (must be non-interactive)
          (let [cost-effect (:choice/cost choice)
                db-after-cost (if cost-effect
                                (let [cost-result (effects/execute-effect-checked
                                                    db-marker-cleared player-id
                                                    cost-effect object-id)]
                                  (when (:needs-selection cost-result)
                                    (throw (ex-info
                                             "replacement :choice/cost returned :needs-selection — costs must be non-interactive in this task"
                                             {:cost cost-effect
                                              :result cost-result})))
                                  (:db cost-result))
                                db-marker-cleared)
                ;; Commit original zone change
                destination (get in-flight-event :event/to)]
            (zone-change-dispatch/move-to-zone-db db-after-cost object-id destination))

          :redirect
          ;; Move to redirect zone — no cost execution
          (let [redirect-to (:choice/redirect-to choice)]
            (zone-change-dispatch/move-to-zone-db db-marker-cleared object-id redirect-to))

          ;; Unknown action — no-op
          (do
            (js/console.warn
              (str "execute-confirmed-selection :replacement-choice: unknown action " action))
            db-marker-cleared))]
    {:db db-final}))
