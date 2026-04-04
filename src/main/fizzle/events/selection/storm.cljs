(ns fizzle.events.selection.storm
  "Storm split selection type: distributing storm copies across targets.

   When a storm trigger resolves for a targeted spell, this module builds
   a :storm-split selection allowing the player to distribute copies across
   valid targets using stepper controls.

   Follows the mana-allocation pattern: non-card-picking selection with
   accumulating state in the selection map."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.selection.core :as core]
    [re-frame.core :as rf]))


;; =====================================================
;; Builder
;; =====================================================

(defn build-storm-split-selection
  "Build a storm-split selection for distributing storm copies across targets.

   valid-targets order: [opponent-id player-id] when opponent exists,
   [player-id] in goldfish mode. Opponent is first because it's the most
   common target for targeted storm spells.

   Default allocation puts all copies on the first valid target.

   Returns nil if copy-count is 0, source doesn't exist, or no valid targets."
  [game-db player-id storm-stack-item]
  (let [source-id (:stack-item/source storm-stack-item)
        source-obj (queries/get-object game-db source-id)]
    (when source-obj
      (let [effects (:stack-item/effects storm-stack-item)
            copy-count (or (:effect/count
                             (first (filter #(= :storm-copies (:effect/type %)) effects)))
                           0)
            opponent-id (queries/get-opponent-id game-db player-id)
            ;; Build valid-targets using player-ids (not entity IDs)
            ;; Order: opponent first (most common target), then self
            valid-targets (filterv some? [opponent-id player-id])]
        (when (and (pos? copy-count) (seq valid-targets))
          {:selection/type :storm-split
           :selection/pattern :accumulator
           :selection/lifecycle :finalized
           :selection/copy-count copy-count
           :selection/valid-targets valid-targets
           :selection/allocation (assoc (zipmap valid-targets (repeat 0))
                                        (first valid-targets) copy-count)
           :selection/source-object-id source-id
           :selection/player-id player-id
           :selection/stack-item-eid (:db/id storm-stack-item)
           :selection/selected #{}
           :selection/validation :always
           :selection/auto-confirm? false})))))


;; =====================================================
;; Confirm Handler
;; =====================================================

(defmethod core/execute-confirmed-selection :storm-split
  [game-db selection]
  (let [allocation (:selection/allocation selection)
        source-id (:selection/source-object-id selection)
        controller (:selection/player-id selection)
        si-eid (:selection/stack-item-eid selection)
        copy-count (:selection/copy-count selection)
        total (apply + (vals allocation))]
    (if (not= total copy-count)
      ;; Reject partial allocation (no-op)
      {:db game-db}
      ;; Create copies with individually assigned targets
      (let [db-with-copies (reduce-kv
                             (fn [d target-player-id cnt]
                               (if (pos? cnt)
                                 (loop [d' d remaining cnt]
                                   (if (pos? remaining)
                                     (recur (triggers/create-spell-copy
                                              d' source-id controller
                                              {:player target-player-id})
                                            (dec remaining))
                                     d'))
                                 d))
                             game-db allocation)
            db-final (stack/remove-stack-item db-with-copies si-eid)]
        {:db db-final}))))


;; =====================================================
;; Stepper Event Handlers
;; =====================================================

(defn adjust-storm-split-impl
  "Handle adjusting a storm-split allocation by delta (+1 or -1).
   Validates bounds: no negative values, total cannot exceed copy-count.
   Returns updated app-db."
  [app-db target-id delta]
  (let [selection (:game/pending-selection app-db)
        allocation (:selection/allocation selection)
        current (get allocation target-id 0)
        new-val (+ current delta)
        copy-count (:selection/copy-count selection)
        total-others (- (apply + (vals allocation)) current)]
    (if (and (>= new-val 0) (<= (+ new-val total-others) copy-count))
      (assoc-in app-db [:game/pending-selection :selection/allocation target-id] new-val)
      app-db)))


(rf/reg-event-db
  ::adjust-storm-split
  (fn [db [_ target-id delta]]
    (adjust-storm-split-impl db target-id delta)))


(rf/reg-event-db
  ::reset-storm-split
  (fn [db _]
    (let [selection (:game/pending-selection db)
          targets (:selection/valid-targets selection)
          copy-count (:selection/copy-count selection)]
      (assoc-in db [:game/pending-selection :selection/allocation]
                (assoc (zipmap targets (repeat 0))
                       (first targets) copy-count)))))
