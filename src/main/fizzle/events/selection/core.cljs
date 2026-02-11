(ns fizzle.events.selection.core
  "Selection mechanism: multimethod definitions, data-driven validation,
   confirm/toggle implementation, and cleanup helpers.

   This namespace contains ONLY mechanism — zero knowledge of specific
   selection types. Domain modules register methods on the multimethods
   defined here."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.zones :as zones]))


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


;; =====================================================
;; Data-Driven Validation
;; =====================================================

(defn validate-selection
  "Validate a selection using data-driven rules.
   Checks :selection/validation type and candidate membership.
   Returns true if valid, false otherwise.

   Validation types:
     :exact        — count(selected) == select-count
     :at-most      — count(selected) <= select-count
     :at-least-one — count(selected) >= 1
     :always       — always valid
     :exact-or-zero — 0 or exact (tutor fail-to-find)
     nil           — reject (safe default for unset builders)"
  [selection]
  (let [selected (:selection/selected selection)
        count-selected (count selected)
        select-count (:selection/select-count selection 0)
        candidates (or (:selection/candidates selection)
                       (:selection/candidate-ids selection))
        ;; Universal candidate membership check
        candidates-valid? (if candidates
                            (every? #(contains? candidates %) selected)
                            true)
        ;; Count validation based on :selection/validation
        count-valid? (case (:selection/validation selection)
                       :exact (= count-selected select-count)
                       :at-most (<= count-selected select-count)
                       :at-least-one (pos? count-selected)
                       :always true
                       :exact-or-zero (or (zero? count-selected)
                                          (= count-selected select-count))
                       ;; nil or unknown: reject (safe default)
                       false)]
    (and candidates-valid? count-valid?)))


;; =====================================================
;; Stack-Item Cleanup Helper
;; =====================================================

(defn remove-spell-stack-item
  "Remove the stack-item for a spell after resolution.
   Looks up the stack-item by the spell's object EID and removes it.
   Returns updated db. Safe to call when no stack-item exists."
  [game-db spell-id]
  (let [obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     game-db spell-id)]
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
   - nil/spell → move spell to destination zone + remove stack-item"
  [game-db selection]
  (let [source-type (:selection/source-type selection)]
    (if (= source-type :stack-item)
      (let [si-eid (:selection/stack-item-eid selection)]
        (stack/remove-stack-item game-db si-eid))
      (let [spell-id (:selection/spell-id selection)
            spell-obj (queries/get-object game-db spell-id)
            current-zone (:object/zone spell-obj)
            db-after-move (if (= current-zone :stack)
                            (let [cast-mode (:object/cast-mode spell-obj)
                                  destination (or (:mode/on-resolve cast-mode) :graveyard)]
                              (zones/move-to-zone game-db spell-id destination))
                            game-db)]
        (remove-spell-stack-item db-after-move spell-id)))))


;; =====================================================
;; Confirm Selection Wrapper
;; =====================================================

(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations.
   1. Gets game-db and selection from app-db
   2. Calls execute-confirmed-selection multimethod
   3. Handles remaining-effects, cleanup, and chaining based on result

   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        game-db (:game/db app-db)
        result (execute-confirmed-selection game-db selection)]
    (cond
      ;; Chain to next selection
      (:pending-selection result)
      (-> app-db
          (assoc :game/db (:db result))
          (assoc :game/pending-selection (:pending-selection result)))

      ;; Fully handled (pre-cast, ability, pile-choice, scry, cleanup-discard)
      (:finalized? result)
      (cond-> app-db
        true (assoc :game/db (:db result))
        true (dissoc :game/pending-selection)
        (:clear-selected-card? result) (dissoc :game/selected-card))

      ;; Standard: execute remaining-effects and cleanup
      :else
      (let [remaining-effects (:selection/remaining-effects selection)
            player-id (:selection/player-id selection)
            db-after-remaining (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       (:db result)
                                       (or remaining-effects []))
            db-final (cleanup-selection-source db-after-remaining selection)]
        (-> app-db
            (assoc :game/db db-final)
            (dissoc :game/pending-selection))))))


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
    (if (validate-selection selection)
      (confirm-selection-impl app-db)
      app-db)))
