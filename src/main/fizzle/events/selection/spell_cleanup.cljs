(ns fizzle.events.selection.spell-cleanup
  "Spell cleanup helpers: remove resolved spells from the stack and clean up
   the selection source after a spell resolves.

   Extracted from events/selection/core per ADR-022 — the selection mechanism
   should not import engine/resolution or engine/stack directly."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.resolution :as resolution]
    [fizzle.engine.stack :as stack]))


(defn remove-spell-stack-item
  "Remove the stack-item for a spell after resolution.
   Looks up the stack-item by the spell's object EID and removes it.
   Returns updated db. Safe to call when no stack-item exists."
  [game-db spell-id]
  (let [obj-eid (queries/get-object-eid game-db spell-id)]
    (if-let [si (when obj-eid (stack/get-stack-item-by-object-ref game-db obj-eid))]
      (stack/remove-stack-item game-db (:db/id si))
      game-db)))


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
            spell-obj (when spell-id (queries/get-object game-db spell-id))
            current-zone (:object/zone spell-obj)]
        (cond
          ;; No spell-id: nothing to clean up (e.g., finalized selection without spell context)
          (nil? spell-id)
          game-db

          ;; Spell object already removed (e.g., copy that ceased to exist)
          (nil? spell-obj)
          game-db

          ;; Spell on stack: normal cleanup path
          (= current-zone :stack)
          (-> game-db
              (remove-spell-stack-item spell-id)
              (resolution/move-resolved-spell spell-id spell-obj))

          ;; Spell already moved off stack by its own effects (exile-self, graveyard).
          ;; This is expected when effects like :exile-self run before cleanup.
          ;; Warn on non-exile/non-graveyard zones since those likely indicate a
          ;; routing bug (e.g., ETB trigger mis-routed through spell cleanup path).
          :else
          (do
            (when-not (contains? #{:exile :graveyard} current-zone)
              (js/console.warn
                (str "cleanup-selection-source: spell " spell-id
                     " is in zone " current-zone " (not :stack). "
                     "If this is not expected, check build-selection-from-result routing.")))
            game-db))))))
