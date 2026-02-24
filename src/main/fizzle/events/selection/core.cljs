(ns fizzle.events.selection.core
  "Selection mechanism: multimethod definitions, data-driven validation,
   confirm/toggle implementation, and cleanup helpers.

   This namespace contains ONLY mechanism — zero knowledge of specific
   selection types. Domain modules register methods on the multimethods
   defined here."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.resolution :as resolution]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.validation :as validation]))


;; =====================================================
;; Multimethod Definitions
;; =====================================================

(defmulti execute-confirmed-selection
  "Execute the type-specific logic for a confirmed selection.
   Dispatches on :selection/type.

   Arguments:
     game-db - Datascript database
     selection - Selection state map

   Returns one of:
     {:db game-db} — standard, wrapper handles remaining-effects + cleanup
     {:db game-db :pending-selection next-sel} — chain to next selection
     {:db game-db :finalized? true} — fully handled (pre-cast, ability)"
  (fn [_game-db selection] (:selection/type selection)))


(defmulti build-selection-for-effect
  "Build selection state for an effect requiring player interaction.
   Dispatches on effect type or :player-target for :any-player targeting."
  (fn [_db _player-id _object-id effect _remaining-effects]
    (if (= :any-player (:effect/target effect))
      :player-target
      (:effect/type effect))))


(defmethod build-selection-for-effect :default
  [_db player-id object-id effect remaining-effects]
  {:selection/zone :hand
   :selection/select-count (:effect/count effect)
   :selection/player-id player-id
   :selection/selected #{}
   :selection/spell-id object-id
   :selection/remaining-effects remaining-effects
   :selection/type (:effect/type effect)
   :selection/validation :exact
   :selection/auto-confirm? false})


;; =====================================================
;; Continuation Protocol
;; =====================================================

(defmulti apply-continuation
  "Apply a continuation after selection completes.
   Dispatches on (:continuation/type continuation).

   Arguments:
     continuation - Map with :continuation/type and continuation-specific data
     app-db - The current app-db (selection already dissoc'd)

   Returns updated app-db.

   Defmethods are registered by domain modules (e.g., events/game.cljs
   registers :resolve-one-and-stop)."
  (fn [continuation _app-db] (:continuation/type continuation)))


(defmethod apply-continuation :default
  [_ app-db]
  app-db)


;; =====================================================
;; Stack-Item Cleanup Helper
;; =====================================================

(defn remove-spell-stack-item
  "Remove the stack-item for a spell after resolution.
   Looks up the stack-item by the spell's object EID and removes it.
   Returns updated db. Safe to call when no stack-item exists."
  [game-db spell-id]
  (let [obj-eid (queries/get-object-eid game-db spell-id)]
    (if-let [si (when obj-eid (stack/get-stack-item-by-object-ref game-db obj-eid))]
      (stack/remove-stack-item game-db (:db/id si))
      game-db)))


;; =====================================================
;; Source Cleanup Helper
;; =====================================================

(defn cleanup-selection-source
  "Clean up the source of a resolved selection.
   Handles 2 source types:
   - :stack-item → remove the stack-item entity
   - nil/spell → remove stack-item first (needs object EID), then move spell
                  off stack via resolution/move-resolved-spell (single source
                  of truth for copy vs non-copy zone transitions)"
  [game-db selection]
  (let [source-type (:selection/source-type selection)]
    (if (= source-type :stack-item)
      (let [si-eid (:selection/stack-item-eid selection)]
        (stack/remove-stack-item game-db si-eid))
      (let [spell-id (:selection/spell-id selection)
            spell-obj (queries/get-object game-db spell-id)
            current-zone (:object/zone spell-obj)]
        (if (= current-zone :stack)
          ;; Remove stack-item first (needs object EID for lookup), then
          ;; move spell off stack. Order matters: move-resolved-spell may
          ;; retract the object entirely (copies), making EID lookup fail.
          (-> game-db
              (remove-spell-stack-item spell-id)
              (resolution/move-resolved-spell spell-id spell-obj))
          game-db)))))


;; =====================================================
;; Confirm Selection Wrapper
;; =====================================================

(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations.
   1. Gets game-db and selection from app-db
   2. Reads :selection/on-complete continuation BEFORE dissoc
   3. Calls execute-confirmed-selection multimethod
   4. Handles remaining-effects, cleanup, and chaining based on result
   5. Applies continuation (if present) after selection is cleared

   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        on-complete (:selection/on-complete selection)
        game-db (:game/db app-db)
        result (execute-confirmed-selection game-db selection)]
    (cond
      ;; Chain to next selection — propagate on-complete to chained selection
      (:pending-selection result)
      (let [chained-sel (cond-> (:pending-selection result)
                          on-complete (assoc :selection/on-complete on-complete))]
        (-> app-db
            (assoc :game/db (:db result))
            (assoc :game/pending-selection chained-sel)))

      ;; Fully handled (pre-cast, ability, pile-choice, scry, discard with cleanup?)
      (:finalized? result)
      (let [updated (cond-> app-db
                      true (assoc :game/db (:db result))
                      true (dissoc :game/pending-selection)
                      (:clear-selected-card? result) (dissoc :game/selected-card))]
        (if on-complete
          (apply-continuation on-complete updated)
          updated))

      ;; Standard: execute remaining-effects and cleanup
      :else
      (let [remaining-effects (:selection/remaining-effects selection)
            player-id (:selection/player-id selection)
            db-after-remaining (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       (:db result)
                                       (or remaining-effects []))
            db-final (cleanup-selection-source db-after-remaining selection)
            updated (-> app-db
                        (assoc :game/db db-final)
                        (dissoc :game/pending-selection))]
        (if on-complete
          (apply-continuation on-complete updated)
          updated)))))


;; =====================================================
;; Toggle Selection Implementation
;; =====================================================

(defn toggle-selection-impl
  "Handle toggling a selection item. Data-driven: uses :selection/select-count
   universally (no type-specific max-count logic) and :selection/auto-confirm?
   flag instead of static type set.

   Returns updated app-db."
  [app-db id]
  (let [selection (get app-db :game/pending-selection)
        selected (get selection :selection/selected #{})
        valid-targets (:selection/valid-targets selection)
        select-count (get selection :selection/select-count 0)
        currently-selected? (contains? selected id)
        [new-db selected?]
        (cond
          ;; Reject invalid targets when valid-targets set exists
          (and valid-targets (not (contains? (set valid-targets) id)))
          [app-db false]

          ;; Deselect: remove from set
          currently-selected?
          [(assoc-in app-db [:game/pending-selection :selection/selected]
                     (disj selected id))
           false]

          ;; Single-select (select-count=1): replace current selection
          (= select-count 1)
          [(assoc-in app-db [:game/pending-selection :selection/selected]
                     #{id})
           true]

          ;; Unlimited select (exact?=false, e.g. exile-cards): always add
          (false? (:selection/exact? selection))
          [(assoc-in app-db [:game/pending-selection :selection/selected]
                     (conj selected id))
           true]

          ;; Multi-select under limit: add
          (< (count selected) select-count)
          [(assoc-in app-db [:game/pending-selection :selection/selected]
                     (conj selected id))
           true]

          ;; At limit: ignore
          :else [app-db false])]
    ;; Auto-confirm: data-driven via :selection/auto-confirm? flag
    (if (and selected?
             (= select-count 1)
             (:selection/auto-confirm? selection))
      (confirm-selection-impl new-db)
      new-db)))


;; =====================================================
;; Confirm Selection Handler
;; =====================================================

(defn confirm-selection-handler
  "Handle confirm-selection event. Uses validate-selection for data-driven
   validation, then delegates to confirm-selection-impl.

   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)]
    (if (validation/validate-selection selection)
      (confirm-selection-impl app-db)
      app-db)))
