(ns fizzle.engine.resolution
  "Unified stack-item resolution via multimethod dispatch.

   Single entry point: resolve-stack-item dispatches on :stack-item/type.
   Each defmethod handles its type: gets effects, reads targets from
   :stack-item/targets, pre-resolves target references, executes via
   effects/reduce-effects, and handles zone transition for spells.

   Returns {:db db'} or {:db db' :needs-selection effect :remaining-effects [...]}.

   Target unification: :stack-item/targets is the single source of truth.
   Effects receive pre-resolved :effect/target values. No code reads
   :object/targets in this namespace."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.combat :as combat]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.engine.zones :as zones]))


(defmulti resolve-stack-item
  "Resolve a stack-item by type.

   Controller identity comes from :stack-item/controller on the stack-item
   itself — never from a parameter. This prevents the footgun where the
   active player is passed instead of the caster.

   Arguments:
     db         - Datascript database value
     stack-item - Stack-item entity map (must have :stack-item/controller)

   Returns:
     {:db db'} - Resolution complete
     {:db db' :needs-selection effect :remaining-effects [...]} - Paused for selection"
  (fn [_db stack-item] (:stack-item/type stack-item)))


(defmethod resolve-stack-item :default
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        effects-list (or (:stack-item/effects stack-item) [])
        stored-targets (:stack-item/targets stack-item)]
    (if (empty? effects-list)
      {:db db}
      {:db (reduce (fn [d effect]
                     (if (= :storm-copies (:effect/type effect))
                       (let [copy-count (:effect/count effect 0)]
                         (if (and (pos? copy-count) (queries/get-object d source-id))
                           (loop [d' d
                                  remaining copy-count]
                             (if (pos? remaining)
                               (recur (triggers/create-spell-copy d' source-id controller)
                                      (dec remaining))
                               d'))
                           d))
                       (let [resolved (stack/resolve-effect-target
                                        effect source-id controller stored-targets)]
                         (effects/execute-effect d controller resolved source-id))))
                   db
                   effects-list)})))


;; =====================================================
;; Shared Helpers
;; =====================================================

(defn- resolve-targeted-player
  "Resolve :targeted-player symbolic target to concrete player-id.
   :targeted-player means 'the player chosen at cast time', stored under
   :player key in stored-targets."
  [effect stored-targets]
  (if (= :targeted-player (:effect/target effect))
    (if-let [player-target (:player stored-targets)]
      (assoc effect :effect/target player-target)
      effect)
    effect))


(defn- pre-resolve-targets
  "Pre-resolve all target references in an effects list.
   Uses stack/resolve-effect-target for standard resolution (:self, :controller,
   :any-player, :effect/target-ref), plus resolves :targeted-player via stored-targets.

   Arguments:
     effects-list   - Vector of effect maps
     source-id      - Object ID of the source spell/ability
     controller     - Player ID of the controller
     stored-targets - Map from :stack-item/targets (cast-time targeting data)"
  [effects-list source-id controller stored-targets]
  (mapv (fn [effect]
          (-> effect
              (resolve-targeted-player stored-targets)
              (stack/resolve-effect-target source-id controller stored-targets)))
        effects-list))


(defn move-resolved-spell
  "Move a resolved spell to its destination zone.

   Copies cease to exist when leaving the stack (per MTG rules 707.2).
   Non-copies go to: cast-mode destination > permanent-type battlefield > graveyard.

   When a permanent enters the battlefield and has card triggers, registers
   those triggers in Datascript so they fire on future events.

   This is the SINGLE SOURCE OF TRUTH for spell-off-stack zone transitions.
   Both direct resolution (resolve-spell-type) and selection cleanup
   (cleanup-selection-source) must use this function.

   Idempotent: if the spell's CURRENT zone (re-fetched from db) is not :stack,
   returns db unchanged. This handles :exile-self and double-move cases.
   The obj parameter zone field is NOT trusted — it may be stale.

   Arguments:
     db        - Datascript database value
     object-id - Object ID of the spell
     obj       - Object entity map (pre-fetched, zone field may be stale)"
  [db object-id obj]
  ;; Re-fetch the current zone from db — the obj parameter may be stale (e.g.
  ;; resolve-spell-type pre-fetches obj BEFORE running effects, so if :exile-self
  ;; fires during effects, obj still claims :stack but db reflects :exile).
  (let [current-obj (queries/get-object db object-id)
        current-zone (:object/zone current-obj)]
    (if (not= current-zone :stack)
      ;; Not on stack — idempotent no-op (handles exile-self, double-move, ceased-to-exist copies)
      (do
        (when (and (some? current-zone)
                   (not (contains? #{:exile :graveyard} current-zone)))
          (js/console.warn
            (str "move-resolved-spell: spell " object-id
                 " is in zone " current-zone " (not :stack). "
                 "If this is not expected, check spell disposition routing.")))
        db)
      ;; Spell is on stack — proceed with normal zone transition
      (if (:object/is-copy obj)
        (zones/remove-object db object-id)
        (let [cast-mode (:object/cast-mode obj)
              mode-destination (:mode/on-resolve cast-mode)
              card-types (:card/types (:object/card obj))
              destination (cond
                            mode-destination mode-destination
                            (rules/permanent-type? card-types) :battlefield
                            :else :graveyard)
              db-after-move (zone-change-dispatch/move-to-zone db object-id destination)]
          (if (= destination :battlefield)
            (let [controller-ref (:object/controller obj)
                  controller-eid (if (map? controller-ref) (:db/id controller-ref) controller-ref)
                  controller-id (d/q '[:find ?pid .
                                       :in $ ?e
                                       :where [?e :player/id ?pid]]
                                     db-after-move controller-eid)]
              ;; Triggers are embedded in the object at creation (build-object-tx) — no ETB registration needed.
              ;; Dispatch permanent-entered event for ETB triggers.
              (dispatch/dispatch-event db-after-move
                                       (game-events/permanent-entered-event object-id controller-id)))
            db-after-move))))))


(defn- resolve-spell-effects
  "Resolve effects for a spell/storm-copy stack-item.

   Controller identity comes from :stack-item/controller on the stack-item.

   1. Gets active effects from card definition
   2. Reads targets from :stack-item/targets (single source of truth)
   3. Checks target legality if targets exist
   4. Pre-resolves ALL target refs to concrete IDs
   5. Executes via effects/reduce-effects

   Returns {:db db'} or {:db db' :needs-selection effect :remaining-effects [...]}.
   If targets are illegal, skips effects and returns {:db db :fizzled? true}."
  [db stack-item object-id obj]
  (let [controller (:stack-item/controller stack-item)
        card (:object/card obj)
        chosen-mode (:object/chosen-mode obj)
        effects-list (or (rules/get-active-effects db controller card object-id) [])
        stored-targets (:stack-item/targets stack-item)
        ;; Check target legality — for modal spells, use the chosen mode's targeting
        requirements (if chosen-mode
                       (or (:mode/targeting chosen-mode) [])
                       (targeting/get-targeting-requirements card))
        targets-legal? (targeting/all-targets-legal? db stored-targets requirements)]
    (if (and (seq stored-targets) (not targets-legal?))
      ;; Targets illegal — spell fizzles (no effects, still moves off stack)
      {:db db :fizzled? true}
      ;; Pre-resolve targets only when stored-targets exist (cast-time targeting).
      ;; Effects that handle :self/:opponent internally (e.g., discard-hand) break
      ;; if we pre-resolve :self to an object-id.
      (let [resolved-effects (if (seq stored-targets)
                               (pre-resolve-targets effects-list object-id controller stored-targets)
                               effects-list)]
        (effects/reduce-effects db controller resolved-effects object-id)))))


;; =====================================================
;; Spell / Storm-Copy Resolution
;; =====================================================

(defn- resolve-spell-type
  "Shared implementation for :spell and :storm-copy resolution.

   1. Gets object from stack-item/object-ref
   2. Verifies spell is on stack (no-op if not)
   3. Resolves effects via resolve-spell-effects
   4. If needs-selection, returns paused result (spell stays on stack)
   5. Otherwise, moves spell to destination zone (battlefield for permanents)
   6. If permanent enters battlefield, dispatches :permanent-entered event
      which puts any ETB triggers on the stack as separate stack-items."
  [db stack-item]
  (let [obj-ref-raw (:stack-item/object-ref stack-item)
        obj-ref (if (map? obj-ref-raw) (:db/id obj-ref-raw) obj-ref-raw)
        obj (when obj-ref
              (let [pulled (d/pull db [:object/id] obj-ref)]
                (when (:object/id pulled)
                  (queries/get-object db (:object/id pulled)))))]
    (if-not obj
      {:db db}
      (let [object-id (:object/id obj)]
        (if (not= :stack (:object/zone obj))
          {:db db}
          (let [result (resolve-spell-effects db stack-item object-id obj)]
            (if (:needs-selection result)
              result
              {:db (move-resolved-spell (:db result) object-id obj)})))))))


(defmethod resolve-stack-item :spell
  [db stack-item]
  (resolve-spell-type db stack-item))


(defmethod resolve-stack-item :storm-copy
  [db stack-item]
  (resolve-spell-type db stack-item))


;; =====================================================
;; ETB Trigger Resolution
;; =====================================================

(defmethod resolve-stack-item :permanent-entered
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        effects-list (or (:stack-item/effects stack-item) [])]
    (if (empty? effects-list)
      {:db db}
      (effects/reduce-effects db controller effects-list source-id))))


;; =====================================================
;; Activated Ability Resolution
;; =====================================================

(defn- find-any-player-effect-index
  "Find the index of the first effect targeting :any-player."
  [effects]
  (first (keep-indexed (fn [i effect]
                         (when (= :any-player (:effect/target effect)) i))
                       effects)))


(defn- split-effects-around-index
  "Split effects list around the effect at idx.
   Returns [effects-before target-effect effects-after]."
  [effects idx]
  (let [v (vec effects)]
    [(subvec v 0 idx)
     (nth v idx)
     (subvec v (inc idx))]))


(defmethod resolve-stack-item :activated-ability
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        effects-list (or (:stack-item/effects stack-item) [])
        stored-targets (:stack-item/targets stack-item)
        stored-player (:player stored-targets)
        any-player-idx (when stored-player
                         (find-any-player-effect-index effects-list))
        has-stored-player-target (boolean any-player-idx)]
    (if has-stored-player-target
      ;; Stored player targeting (e.g., Cephalid Coliseum)
      ;; Pre-resolve :any-player, execute effects before it, then reduce-effects for after
      (let [[effects-before player-target-effect effects-after]
            (split-effects-around-index effects-list any-player-idx)
            resolved-player-effect (stack/resolve-effect-target
                                     player-target-effect source-id controller stored-targets)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect
                                        d controller
                                        (stack/resolve-effect-target effect source-id controller nil)))
                                    db effects-before)
            db-after-player-effect (effects/execute-effect
                                     db-after-before controller resolved-player-effect)
            result (effects/reduce-effects db-after-player-effect controller effects-after)]
        (if (:needs-selection result)
          (assoc result :stored-player stored-player)
          {:db (:db result)}))
      ;; Standard path: pre-resolve all targets, execute via reduce-effects
      (let [resolved-effects (pre-resolve-targets effects-list source-id controller stored-targets)]
        (effects/reduce-effects db controller resolved-effects source-id)))))


;; =====================================================
;; Storm Resolution
;; =====================================================

(defmethod resolve-stack-item :storm
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        source-obj (queries/get-object db source-id)
        has-targeting (when source-obj
                        (:card/targeting (:object/card source-obj)))
        effects-list (or (:stack-item/effects stack-item) [])
        stored-targets (:stack-item/targets stack-item)]
    ;; For targeted storm, signal caller to build storm-split selection BEFORE
    ;; creating copies. The storm-split selection handles copy creation.
    (if has-targeting
      {:db db :needs-storm-split true}
      ;; Non-targeted storm: execute effects directly (creates copies)
      {:db (reduce (fn [d effect]
                     (if (= :storm-copies (:effect/type effect))
                       (let [copy-count (:effect/count effect 0)]
                         (if (and (pos? copy-count) (queries/get-object d source-id))
                           (loop [d' d
                                  remaining copy-count]
                             (if (pos? remaining)
                               (recur (triggers/create-spell-copy d' source-id controller)
                                      (dec remaining))
                               d'))
                           d))
                       (let [resolved (stack/resolve-effect-target
                                        effect source-id controller stored-targets)]
                         (effects/execute-effect d controller resolved source-id))))
                   db
                   effects-list)})))


;; =====================================================
;; Combat Resolution
;; =====================================================

(defmethod resolve-stack-item :declare-attackers
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        eligible (combat/get-eligible-attackers db controller)]
    (if (empty? eligible)
      ;; No eligible attackers — skip to post-combat
      {:db db}
      ;; Signal that attacker selection is needed
      {:db db
       :needs-attackers true
       :eligible-attackers eligible})))


(defmethod resolve-stack-item :declare-blockers
  [db stack-item]
  (let [attackers (combat/get-attacking-creatures db)]
    (if (empty? attackers)
      {:db db}
      (let [controller (:stack-item/controller stack-item)
            defender-id (queries/get-other-player-id db controller)]
        {:db db
         :needs-blockers true
         :attackers attackers
         :defender-id defender-id}))))


(defmethod resolve-stack-item :combat-damage
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)]
    {:db (combat/deal-combat-damage db controller)}))
