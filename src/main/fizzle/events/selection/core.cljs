(ns fizzle.events.selection.core
  "Selection mechanism: multimethod definitions, data-driven validation,
   confirm/toggle implementation, and cleanup helpers.

   This namespace contains ONLY mechanism — zero knowledge of specific
   selection types. Domain modules register methods on the multimethods
   defined here.

   Selection hierarchy: effect types derive from interaction patterns
   (:zone-pick, :accumulator, :reorder) via Clojure's multimethod
   hierarchy. Generic builders/executors handle common patterns;
   custom defmethods on specific keywords take precedence automatically."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.validation :as validation]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.events.selection.spell-cleanup :as spell-cleanup]
    [fizzle.history.descriptions :as descriptions]))


;; =====================================================
;; Selection Hierarchy
;; =====================================================
;; Effect types derive from interaction patterns. Generic builders and
;; executors register on parent keywords (:zone-pick, :accumulator,
;; :reorder). Custom defmethods on specific child keywords take
;; precedence automatically via multimethod dispatch.

(def selection-hierarchy
  "Hierarchy mapping effect types to interaction patterns.
   Used by build-selection-for-effect, execute-confirmed-selection,
   and build-chain-selection for dispatch.

   Note: build-selection-for-effect dispatches on :effect/type, while
   execute-confirmed-selection dispatches on :selection/type. These may
   differ (e.g., :return-from-graveyard effect -> :graveyard-return selection).
   Both sides must derive from :zone-pick for the hierarchy to work."
  (-> (make-hierarchy)
      ;; Zone-pick: select N cards from a zone
      (derive :discard :zone-pick)
      (derive :return-from-graveyard :zone-pick)
      (derive :graveyard-return :zone-pick)
      (derive :shuffle-from-graveyard-to-library :zone-pick)
      ;; Accumulator: distribute/increment a value via stepper controls
      (derive :storm-split :accumulator)
      (derive :x-mana-cost :accumulator)
      (derive :mana-allocation :accumulator)
      ;; Reorder: sort/assign cards into piles or positions
      (derive :scry :reorder)
      (derive :peek-and-reorder :reorder)
      (derive :order-bottom :reorder)
      (derive :order-top :reorder)
      ;; Pre-cast cost chains: cost selections that chain to targeting
      (derive :discard-specific-cost :pre-cast-cost-to-targeting)
      (derive :return-land-cost :pre-cast-cost-to-targeting)
      (derive :sacrifice-permanent-cost :pre-cast-cost-to-targeting)
      ;; Targeting chains: targeting selections that chain to mana-allocation
      (derive :cast-time-targeting :targeting-to-mana-allocation)
      (derive :ability-cast-targeting :targeting-to-mana-allocation)
      ;; Builder-declared chains: selection stores chain-builder fn
      (derive :tutor :builder-declared-chain)
      (derive :peek-and-select :builder-declared-chain)
      (derive :x-mana-cost :builder-declared-chain)))


;; =====================================================
;; Multimethod Definitions
;; =====================================================

(defmulti execute-confirmed-selection
  "Execute the type-specific logic for a confirmed selection.
   Dispatches on :selection/type using the selection hierarchy.

   Arguments:
     game-db - Datascript database
     selection - Selection state map

   Returns {:db game-db}. Lifecycle behavior (standard, finalized, chaining)
   is declared by builders via :selection/lifecycle on the selection map, not
   signaled by executor return shape."
  (fn [_game-db selection] (:selection/type selection))
  :hierarchy #'selection-hierarchy)


(defmulti build-selection-for-effect
  "Build selection state for an effect requiring player interaction.
   Dispatches on effect type or :player-target for :any-player targeting.
   Uses selection hierarchy for pattern-based routing."
  (fn [_db _player-id _object-id effect _remaining-effects]
    (if (= :any-player (:effect/target effect))
      :player-target
      (:effect/type effect)))
  :hierarchy #'selection-hierarchy)


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
;; Generic Zone-Pick Builder
;; =====================================================

(def ^:private zone-pick-defaults
  "Default configuration for zone-pick selections."
  {:source-zone :hand
   :target-zone :graveyard
   :card-source :hand
   :validation :exact})


(def ^:private zone-pick-config
  "Per-effect-type overrides for zone-pick builder.
   Keys not present fall through to zone-pick-defaults.
   :selection-type overrides :selection/type when effect type differs
   from the historical selection type (e.g., :return-from-graveyard
   effect produces :graveyard-return selection type)."
  {:return-from-graveyard {:selection-type :graveyard-return
                           :source-zone :graveyard
                           :target-zone :hand
                           :card-source :zone
                           :validation :at-most
                           :min-count 0}
   :shuffle-from-graveyard-to-library
   {:source-zone :graveyard
    :target-zone :library
    :card-source :zone
    :validation :at-most
    :min-count 0}})


(defn- resolve-target-player
  "Resolve symbolic :effect/target to a concrete player-id.
   :self -> player-id, :opponent -> looked up, nil -> player-id."
  [db player-id effect]
  (let [explicit-target (:effect/target effect)]
    (cond
      (nil? explicit-target) player-id
      (= explicit-target :opponent) (queries/get-opponent-id db player-id)
      (= explicit-target :self) player-id
      :else explicit-target)))


(defmethod build-selection-for-effect :zone-pick
  [db player-id object-id effect remaining-effects]
  (let [effect-type (:effect/type effect)
        config (merge zone-pick-defaults (get zone-pick-config effect-type))
        sel-type (or (:selection-type config) effect-type)
        target-player (resolve-target-player db player-id effect)
        source-zone (:source-zone config)
        candidates (when (= :zone (:card-source config))
                     (let [zone-cards (or (queries/get-objects-in-zone
                                            db target-player source-zone)
                                          [])]
                       (set (map :object/id zone-cards))))]
    (cond-> {:selection/type sel-type
             :selection/lifecycle :standard
             :selection/pattern :zone-pick
             :selection/zone source-zone
             :selection/card-source (:card-source config)
             :selection/target-zone (:target-zone config)
             :selection/select-count (or (:effect/count effect)
                                         (:effect/select-count effect))
             :selection/player-id target-player
             :selection/caster-id player-id
             :selection/selected #{}
             :selection/spell-id object-id
             :selection/remaining-effects remaining-effects
             :selection/validation (:validation config)
             :selection/auto-confirm? false}
      candidates (assoc :selection/candidate-ids candidates)
      (:min-count config) (assoc :selection/min-count (:min-count config)))))


;; =====================================================
;; Generic Zone-Pick Executor
;; =====================================================

(defmethod execute-confirmed-selection :zone-pick
  [game-db selection]
  (let [selected (:selection/selected selection)
        target-zone (:selection/target-zone selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zone-change-dispatch/move-to-zone gdb obj-id target-zone))
                 game-db
                 selected)}))


;; =====================================================
;; Continuation Protocol
;; =====================================================

(defmulti apply-continuation
  "Apply a continuation after selection completes.
   Dispatches on (:continuation/type continuation).

   Arguments:
     continuation - Map with :continuation/type and continuation-specific data
     app-db - The current app-db (selection already dissoc'd)

   Returns {:app-db updated-app-db} or {:app-db updated-app-db :then next-continuation}.
   When :then is present, confirm-selection-impl will apply the next continuation
   immediately, chaining until :then is absent or nil.

   Defmethods are registered by domain modules (e.g., priority_flow.cljs
   registers :resolve-one-and-stop)."
  (fn [continuation _app-db] (:continuation/type continuation)))


(defmethod apply-continuation :default
  [_ app-db]
  {:app-db app-db})


;; =====================================================
;; Chain Selection Protocol
;; =====================================================

(defmulti build-chain-selection
  "Build the next selection for a chaining lifecycle.
   Dispatches on :selection/type of the completed selection.
   Uses selection hierarchy for pattern-based routing.
   Returns next selection map, or nil for conditional chaining
   (no chain needed, fall through to standard behavior)."
  (fn [_db selection] (:selection/type selection))
  :hierarchy #'selection-hierarchy)


(defmethod build-chain-selection :default [_db _selection] nil)


;; Generic chain: builder-declared chain via :selection/chain-builder fn.
;; Selection types that derive from :builder-declared-chain store a
;; function on :selection/chain-builder that receives (db, selection)
;; and returns the next selection map (or nil).
(defmethod build-chain-selection :builder-declared-chain
  [db selection]
  (when-let [chain-fn (:selection/chain-builder selection)]
    (chain-fn db selection)))


;; =====================================================
;; Deferred Entry Processing
;; =====================================================

(defn process-deferred-entry
  "Process a :history/deferred-entry on app-db if present.
   Returns updated app-db with :history/pending-entry set and
   :history/deferred-entry cleared, or app-db unchanged if no deferred entry."
  [app-db]
  (if-let [deferred (:history/deferred-entry app-db)]
    (let [game-db (:game/db app-db)
          desc (case (:type deferred)
                 :cast-spell (descriptions/describe-cast-spell game-db (:object-id deferred))
                 :cast-and-yield (descriptions/describe-cast-and-yield
                                   (:pre-game-db deferred) game-db (:object-id deferred))
                 :activate-ability (descriptions/describe-activate-from-stack game-db))]
      (-> app-db
          (dissoc :history/deferred-entry)
          (assoc :history/pending-entry
                 (descriptions/build-pending-entry
                   game-db
                   (:event-type deferred)
                   desc
                   (:principal deferred)))))
    app-db))


;; =====================================================
;; Confirm Selection Wrapper
;; =====================================================

(defn- drain-continuation-chain
  "Apply a continuation and drain the :then chain until exhausted.
   Returns the final app-db after all continuations in the chain have run."
  [on-complete app-db]
  (loop [result (apply-continuation on-complete app-db)]
    (if-let [next-cont (:then result)]
      (recur (apply-continuation next-cont (:app-db result)))
      (:app-db result))))


(defn- standard-path
  "Standard lifecycle: execute remaining-effects and cleanup source.
   If remaining-effects contain an interactive effect, pauses and builds
   a new pending selection (chained interactive effects support)."
  [app-db result selection on-complete]
  (let [remaining-effects (:selection/remaining-effects selection)
        player-id (or (:selection/caster-id selection) (:selection/player-id selection))
        object-id (:selection/spell-id selection)
        remaining-result (effects/reduce-effects (:db result) player-id
                                                 (or remaining-effects []))]
    (if (:needs-selection remaining-result)
      ;; Interactive effect in remaining-effects: build next selection, defer cleanup.
      ;; The spell stays on stack; cleanup happens after the final selection resolves.
      ;; Propagate source-type from parent selection so cleanup uses the correct branch.
      (let [next-effect (:needs-selection remaining-result)
            further-remaining (vec (:remaining-effects remaining-result))
            next-sel (build-selection-for-effect (:db remaining-result) player-id object-id
                                                 next-effect further-remaining)
            next-sel (cond-> next-sel
                       (not (:selection/source-type next-sel))
                       (assoc :selection/source-type (:selection/source-type selection))
                       on-complete (assoc :selection/on-complete on-complete))]
        (-> app-db
            (assoc :game/db (:db remaining-result))
            (sel-spec/set-pending-selection next-sel)))
      ;; No more interactive effects: cleanup source and finish.
      (let [db-final (spell-cleanup/cleanup-selection-source (:db remaining-result) selection)
            updated (-> app-db
                        (assoc :game/db db-final)
                        (dissoc :game/pending-selection))]
        (if on-complete
          (drain-continuation-chain on-complete updated)
          updated)))))


(defn- finalized-path
  "Finalized lifecycle: clear selection, optionally clear selected-card.
   When the selection carries :selection/remaining-effects it originated from
   spell resolution (e.g. :order-bottom chained from :peek-and-select), so the
   spell must be cleaned up off the stack. Pre-cast finalized selections
   (targeting, mana-allocation, spell-mode) never carry remaining-effects."
  [app-db result selection on-complete clear-selected-card?]
  (let [resolution-phase? (contains? selection :selection/remaining-effects)
        db-final (if resolution-phase?
                   (spell-cleanup/cleanup-selection-source (:db result) selection)
                   (:db result))
        updated (cond-> app-db
                  true (assoc :game/db db-final)
                  true (dissoc :game/pending-selection)
                  clear-selected-card? (dissoc :game/selected-card))]
    (if on-complete
      (drain-continuation-chain on-complete updated)
      updated)))


(defn- chaining-path
  "Chaining lifecycle: call build-chain-selection. If it returns a selection,
   chain to it. If nil, fall through to standard path.
   Propagates :selection/source-type from parent so cleanup uses the correct branch."
  [app-db result selection on-complete]
  (let [next-sel (build-chain-selection (:db result) selection)]
    (if next-sel
      (let [chained-sel (cond-> next-sel
                          (not (:selection/source-type next-sel))
                          (assoc :selection/source-type (:selection/source-type selection))
                          on-complete (assoc :selection/on-complete on-complete))]
        (-> app-db
            (assoc :game/db (:db result))
            (sel-spec/set-pending-selection chained-sel)))
      ;; nil = conditional chaining, fall through to standard
      (standard-path app-db result selection on-complete))))


(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations.
   1. Validates selection — returns app-db unchanged on failure (no exceptions)
   2. Gets game-db and selection from app-db
   3. Reads :selection/on-complete continuation BEFORE dissoc
   4. Calls execute-confirmed-selection multimethod
   5. Routes based on :selection/lifecycle
   6. Applies continuation (if present) after selection is cleared
   7. Processes deferred history entry when selection chain is complete

   Lifecycle values (:selection/lifecycle on selection map):
     :standard  — remaining-effects applied, source cleaned up (default)
     :finalized — no remaining-effects, selection cleared
     :chaining  — calls build-chain-selection for next selection

   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)]
    (if-not (validation/validate-selection selection)
      app-db
      (let [on-complete (:selection/on-complete selection)
            game-db (:game/db app-db)
            result (execute-confirmed-selection game-db selection)
            lifecycle (or (:selection/lifecycle selection) :standard)
            routed (case lifecycle
                     :chaining (chaining-path app-db result selection on-complete)
                     :finalized (finalized-path app-db result selection on-complete
                                                (:selection/clear-selected-card? selection))
                     :standard (standard-path app-db result selection on-complete))]
        ;; Process deferred entry as the terminal step when the selection chain
        ;; is complete (no pending-selection). process-deferred-entry is a no-op
        ;; when no deferred entry is present, so this is unconditional.
        (if (nil? (:game/pending-selection routed))
          (process-deferred-entry routed)
          routed)))))


;; =====================================================
;; Toggle Selection Implementation
;; =====================================================

(defn toggle-selection-impl
  "Handle toggling a selection item. Data-driven: uses :selection/select-count
   universally (no type-specific max-count logic) and :selection/auto-confirm?
   flag instead of static type set.

   Returns {:app-db updated-app-db :auto-confirm? bool}.
   The caller (re-frame handler) reads :auto-confirm? and dispatches
   ::confirm-selection as an fx effect when true. This ensures the confirmation
   goes through the event system and hits the :db effect handler chokepoint
   (for SBA + bot checks)."
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
          :else [app-db false])
        auto-confirm? (and selected?
                           (= select-count 1)
                           (:selection/auto-confirm? selection))]
    {:app-db new-db
     :auto-confirm? (boolean auto-confirm?)}))


;; =====================================================
;; Confirm Selection Handler
;; =====================================================

(defn confirm-selection-handler
  "Handle confirm-selection event. Thin pass-through to confirm-selection-impl,
   which handles validation, lifecycle routing, and deferred entry processing.

   Returns updated app-db."
  [app-db]
  (confirm-selection-impl app-db))
