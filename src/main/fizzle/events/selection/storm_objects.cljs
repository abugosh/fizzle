(ns fizzle.events.selection.storm-objects
  "Storm object-sequence selection: distributing storm copies across object targets.

   When a storm trigger resolves for an object-targeting spell, this module builds
   a :storm-object-sequence selection allowing the player to pick targets in
   resolution order using a sequence picker."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Builder
;; =====================================================

(defn build-storm-object-sequence-selection
  "Build a storm-object-sequence selection for picking object targets for storm copies.

   For each storm copy, the player picks an object target (e.g., creature in graveyard).
   The sequence order determines resolution order: first-picked resolves first.

   Returns nil if:
   - source object not found
   - no valid targets exist
   - max-picks <= 0 (copy-count is 0 or no targets to pick from)"
  [game-db player-id storm-stack-item]
  (let [source-id (:stack-item/source storm-stack-item)
        source-obj (queries/get-object game-db source-id)]
    (when source-obj
      (let [card (:object/card source-obj)
            targeting-req (first (:card/targeting card))
            target-ref-key (:target/id targeting-req)
            valid-targets (targeting/find-valid-targets game-db player-id targeting-req)
            effects (:stack-item/effects storm-stack-item)
            copy-count (or (:effect/count
                             (first (filter #(= :storm-copies (:effect/type %)) effects)))
                           0)
            max-picks (min copy-count (count valid-targets))]
        (when (and (pos? max-picks) (seq valid-targets))
          {:selection/mechanism :sequence-pick
           :selection/domain :storm-object-sequence
           :selection/lifecycle :finalized
           :selection/sequence []
           :selection/valid-targets valid-targets
           :selection/max-picks max-picks
           :selection/target-ref-key target-ref-key
           :selection/source-object-id source-id
           :selection/source-name (get-in source-obj [:object/card :card/name])
           :selection/player-id player-id
           :selection/stack-item-eid (:db/id storm-stack-item)
           :selection/selected #{}
           :selection/validation :always
           :selection/auto-confirm? false})))))


;; =====================================================
;; Confirm Handler
;; =====================================================

(defmethod core/apply-domain-policy :storm-object-sequence
  [game-db selection]
  (let [sequence (:selection/sequence selection)
        target-ref-key (:selection/target-ref-key selection)
        source-id (:selection/source-object-id selection)
        controller (:selection/player-id selection)
        si-eid (:selection/stack-item-eid selection)
        ;; Create copies in REVERSE order so first-in-sequence ends up on top of
        ;; stack and resolves first (stack is LIFO).
        db-with-copies (reduce (fn [db target-uuid]
                                 (triggers/create-spell-copy db source-id controller
                                                             {target-ref-key target-uuid}))
                               game-db
                               (reverse sequence))
        db-final (stack/remove-stack-item db-with-copies si-eid)]
    {:db db-final}))
