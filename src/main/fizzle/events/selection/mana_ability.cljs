(ns fizzle.events.selection.mana-ability
  "Selection pipeline for :mana abilities with generic mana costs.
   Separated from events/selection/costs.cljs per ADR-022 — domain
   module for mana-ability-specific selection logic.

   ## :selection/context schema

   When a :mana-allocation selection is opened for a mana ability, the
   :selection/context map MUST carry all four namespaced keys:

     :mana-ability/object-id     <UUID>    — source permanent (before sacrifice)
     :mana-ability/ability-index <integer> — index into :card/abilities
     :mana-ability/chosen-color  <keyword> — the color to ADD (:white :blue :black :red :green)
     :mana-ability/generic-count <integer> — N from :colorless N (for UI display and validation)

   Reason for namespaced keys: prevents collision with context from other source-types
   (:ability, :spell, :replacement-choice).

   ## Dependency direction

   This module depends on:
   - engine/mana-activation (execute-mana-ability-production-and-effects)
   - engine/mana (pay-mana-with-allocation)
   - engine/abilities (can-activate?, pay-all-costs)
   - db/queries (get-object, get-mana-pool)

   It does NOT require events/selection/costs.cljs — that ns requires this one
   (for the dispatch extension). Building the mana-allocation selection inline
   avoids the circular dependency."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.mana-activation :as engine-mana]))


;; =====================================================
;; Internal helpers
;; =====================================================

(defn- build-mana-allocation-selection-for-ability
  "Build a :mana-allocation selection for a mana ability's generic cost portion.
   Mirrors costs/build-mana-allocation-selection but is local to this module
   to avoid circular dependency (costs.cljs requires this ns for dispatch).

   Returns the selection map, or nil if generic cost is 0."
  [db player-id object-id mana-cost]
  (let [generic (get mana-cost :colorless 0)
        colored-cost (dissoc mana-cost :colorless)]
    (when (pos? generic)
      (let [pool (q/get-mana-pool db player-id)
            remaining-pool (merge-with - pool colored-cost)]
        {:selection/zone      :mana-pool
         :selection/type      :mana-allocation
         :selection/mechanism :allocate-resource
         :selection/domain    :mana-allocation
         :selection/lifecycle :finalized
         :selection/clear-selected-card? true
         :selection/player-id player-id
         :selection/spell-id object-id
         :selection/mode {:mode/mana-cost mana-cost}
         :selection/generic-remaining generic
         :selection/generic-total generic
         :selection/allocation {}
         :selection/remaining-pool remaining-pool
         :selection/original-remaining-pool remaining-pool
         :selection/colored-cost colored-cost
         :selection/original-cost mana-cost
         :selection/validation :always
         :selection/auto-confirm? true}))))


;; =====================================================
;; Public API
;; =====================================================

(defn open-mana-allocation-for-mana-ability
  "Pay non-mana costs, then build a :mana-allocation selection tagged for
   mana-ability source-type. The selection stashes everything the confirm
   executor needs to produce mana + run effects.

   Pure: (db, player-id, object-id, mana-color, ability-index) -> {:db :pending-selection}

   Returns:
     :db               — db with non-mana costs paid (tap, sacrifice-self, remove-counter, etc.)
     :pending-selection — :mana-allocation selection with :selection/source-type :mana-ability
                          and all four required :mana-ability/* context keys set.
                          nil if the ability has no generic cost (caller should delegate
                          to engine-mana directly in that case)."
  [db player-id object-id mana-color ability-index]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)
        ability (nth (:card/abilities card) ability-index nil)]
    ;; Guard: ability must exist and be a :mana type
    (if-not (and ability (= :mana (:ability/type ability)))
      {:db db :pending-selection nil}
      ;; Guard: can-activate? before paying any costs
      (if-not (abilities/can-activate? db object-id ability player-id)
        {:db db :pending-selection nil}
        (let [ability-cost (:ability/cost ability)
              mana-cost (:mana ability-cost)
              generic (get mana-cost :colorless 0)]
          ;; Guard: mana-color must be non-nil when ability has {:any N} produces
          (if (and (nil? mana-color) (contains? (:ability/produces ability) :any))
            {:db db :pending-selection nil}
            ;; Short-circuit: no generic cost — caller should use simple engine path
            (if-not (pos? generic)
              {:db db :pending-selection nil}
              ;; Pay non-mana costs (tap, sacrifice-self, remove-counter)
              (let [non-mana-costs (dissoc ability-cost :mana)
                    db-after-non-mana (if (seq non-mana-costs)
                                        (abilities/pay-all-costs db object-id non-mana-costs)
                                        db)]
                (if-not db-after-non-mana
                  ;; Guard: non-mana cost payment failed
                  {:db db :pending-selection nil}
                  ;; Build the mana-allocation selection for the generic portion
                  (let [sel (build-mana-allocation-selection-for-ability
                              db-after-non-mana player-id object-id mana-cost)]
                    (if-not sel
                      ;; Guard: no generic cost (already checked above, shouldn't happen)
                      {:db db-after-non-mana :pending-selection nil}
                      {:db db-after-non-mana
                       :pending-selection (assoc sel
                                                 :selection/source-type :mana-ability
                                                 :selection/context
                                                 {:mana-ability/object-id     object-id
                                                  :mana-ability/ability-index  ability-index
                                                  :mana-ability/chosen-color   mana-color
                                                  :mana-ability/generic-count  generic})})))))))))))


(defn confirm-mana-ability-mana-allocation
  "Selection confirm executor for :selection/source-type :mana-ability.
   Called by execute-confirmed-selection :mana-allocation dispatch when
   source-type is :mana-ability.

   Reads :selection/context to extract: object-id, ability-index, chosen-color.
   Deducts allocated mana from pool via pay-mana-with-allocation, then calls
   execute-mana-ability-production-and-effects to add produced mana + run effects.

   Pure: (db, selection) -> {:db db'}

   Returns :db only — mana abilities don't use the stack (MTG CR 605.1).
   No stack-item creation. Fails closed if any required context key is absent."
  [db selection]
  (let [ctx (:selection/context selection)
        object-id (:mana-ability/object-id ctx)
        ability-index (:mana-ability/ability-index ctx)
        chosen-color (:mana-ability/chosen-color ctx)
        generic-count (:mana-ability/generic-count ctx)]
    ;; Guard: all four context keys must be present — fail closed if any missing
    (if-not (and (some? object-id)
                 (some? ability-index)
                 (some? chosen-color)
                 (some? generic-count))
      {:db db}
      (let [player-id (:selection/player-id selection)
            allocation (:selection/allocation selection)
            mana-cost (:selection/original-cost selection)
            ;; Deduct the generic portion: pay-mana-with-allocation handles both
            ;; colored and colorless deductions (allocation covers the :colorless cost)
            db-after-mana (mana/pay-mana-with-allocation db player-id mana-cost allocation)
            ;; Look up the ability from the object's card data.
            ;; Object may now be in graveyard after sacrifice — q/get-object works by UUID
            ;; regardless of zone, so this is safe.
            obj (q/get-object db-after-mana object-id)
            card (:object/card obj)
            ability (nth (:card/abilities card) ability-index nil)]
        ;; Guard: ability must still be resolvable (card data is stable)
        (if-not ability
          {:db db}
          ;; Run production (add produced mana to pool) + ability effects (draw, etc.)
          {:db (engine-mana/execute-mana-ability-production-and-effects
                 db-after-mana player-id object-id ability chosen-color)})))))


(defn activate-mana-ability-with-generic-mana
  "Event-handler helper: detect generic cost and route.

   If the ability at ability-index has a generic (:colorless) mana cost component:
     → call open-mana-allocation-for-mana-ability to collect allocation.
   If not (simple ability like Mountain, Lotus Petal, basic lands):
     → delegate directly to engine-mana/activate-mana-ability 5-arity (simple path).

   Pure: (db, player-id, object-id, mana-color, ability-index) -> {:db :pending-selection-or-nil}"
  [db player-id object-id mana-color ability-index]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)
        ability (when card
                  (if ability-index
                    (nth (:card/abilities card) ability-index nil)
                    (first (filter #(= :mana (:ability/type %)) (:card/abilities card)))))
        mana-cost (:mana (:ability/cost ability))
        generic (get mana-cost :colorless 0)]
    (if (pos? generic)
      ;; Generic cost path: open mana-allocation selection
      (open-mana-allocation-for-mana-ability db player-id object-id mana-color ability-index)
      ;; Simple path: delegate to engine fn directly (no selection)
      {:db (engine-mana/activate-mana-ability db player-id object-id mana-color ability-index)
       :pending-selection nil})))
