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
   Dispatches on explicit :selection/source-type with 3 branches:

   - :ability  → remove the stack-item entity (no spell zone transition)
   - :spell    → remove stack-item + move spell via move-resolved-spell
                  (idempotent: zone guard in move-resolved-spell handles
                   already-moved spells such as those moved by :exile-self)
   - default   → nil/unknown source-type: no-op (legacy path, non-spell
                  selections, or selections that don't own a source)"
  [game-db selection]
  (case (:selection/source-type selection)
    ;; :stack-item is the legacy non-spell source type (abilities/triggers via non-object-ref).
    ;; Both :ability and :stack-item just remove the stack-item — no spell move needed.
    (:ability :stack-item)
    (let [si-eid (:selection/stack-item-eid selection)]
      (stack/remove-stack-item game-db si-eid))

    :spell
    (let [spell-id (:selection/spell-id selection)]
      (if (nil? spell-id)
        ;; Guard: routing bug produced :spell source-type with no spell-id
        game-db
        (let [db-after-cleanup (remove-spell-stack-item game-db spell-id)
              ;; move-resolved-spell returns {:db db'} or {:db db :needs-selection {...}}
              ;; cleanup-selection-source callers expect a plain db, so extract :db.
              ;; If a replacement fires during post-selection cleanup, the signal is
              ;; discarded here (replacement already handled during initial resolution).
              move-result (resolution/move-resolved-spell db-after-cleanup spell-id
                                                          (queries/get-object game-db spell-id))]
          (:db move-result))))

    ;; default: nil/unknown source-type — no-op
    game-db))
