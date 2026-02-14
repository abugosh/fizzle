(ns fizzle.events.selection.resolution
  "Resolution bridge: uses tagged return values from execute-effect-impl
   to detect selection effects. Orchestrates spell/ability resolution
   with selection pauses.

   Interactive effects self-declare via {:db db :needs-selection effect}
   return values. The reduce-effects helper in engine/effects.cljs
   automatically pauses when an interactive effect is encountered."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Helpers
;; =====================================================

(defn split-effects-around-index
  "Split effects list around the effect at idx.
   Returns [effects-before target-effect effects-after]."
  [effects idx]
  (let [v (vec effects)]
    [(subvec v 0 idx)
     (nth v idx)
     (subvec v (inc idx))]))


(defn- find-any-player-effect-index
  "Find the index of the first effect targeting :any-player.
   Used by stored-targets resolution to identify effects needing
   pre-resolution with cast-time target data."
  [effects]
  (first (keep-indexed (fn [i effect]
                         (when (= :any-player (:effect/target effect)) i))
                       effects)))


(defn- handle-reduce-result
  "Handle the result of reduce-effects for spell resolution.
   If paused at interactive effect, builds selection.
   If complete, moves spell to its destination zone."
  [result player-id object-id obj]
  (if (:needs-selection result)
    {:db (:db result)
     :pending-selection (core/build-selection-for-effect
                          (:db result) player-id object-id
                          (:needs-selection result)
                          (vec (:remaining-effects result)))}
    (let [is-copy (:object/is-copy obj)
          card (:object/card obj)
          card-types (:card/types card)
          cast-mode (:object/cast-mode obj)
          mode-destination (:mode/on-resolve cast-mode)
          destination (cond
                        mode-destination mode-destination
                        (rules/permanent-type? card-types) :battlefield
                        :else :graveyard)]
      {:db (if is-copy
             (zones/remove-object (:db result) object-id)
             (zones/move-to-zone (:db result) object-id destination))
       :pending-selection nil})))


;; =====================================================
;; Spell Resolution with Selection
;; =====================================================

(defn resolve-spell-with-selection
  "Resolve a spell, pausing if a selection effect is encountered.

   Returns a map with:
     :db - The updated game-db
     :pending-selection - Selection state if paused for selection, nil otherwise

   Uses reduce-effects from engine/effects.cljs which automatically detects
   interactive effects via tagged return values from execute-effect-impl.

   If stack-item has :stack-item/targets from cast-time targeting:
   - Uses stored targets instead of prompting
   - For effects with :any-player: pre-resolves to concrete player, executes directly
   - For other effects (:targeted-player, :effect/target-ref): copies targets to object
     as bridge for effects/execute-effect, then delegates to rules/resolve-spell

   Arguments:
     game-db - Datascript database
     player-id - Player resolving the spell
     object-id - Object ID of the spell
     stack-item - (optional) Stack-item entity with :stack-item/targets"
  ([game-db player-id object-id]
   (resolve-spell-with-selection game-db player-id object-id nil))

  ([game-db player-id object-id stack-item]
   (let [obj (queries/get-object game-db object-id)]
     (if (not= :stack (:object/zone obj))
       {:db game-db :pending-selection nil}
       (let [card (:object/card obj)
             stored-targets (or (:stack-item/targets stack-item)
                                (:object/targets obj))
             effects-list (or (rules/get-active-effects game-db player-id card object-id) [])
             ;; Check if stored targets can satisfy an :any-player effect
             has-stored-player-target (and (seq stored-targets)
                                           (:player stored-targets)
                                           (some #(= :any-player (:effect/target %)) effects-list))]
         (cond
           ;; Stored player target + :any-player effect: pre-resolve and execute
           ;; (e.g., Deep Analysis — effect uses :any-player which execute-effect can't resolve)
           has-stored-player-target
           (let [cast-mode (:object/cast-mode obj)
                 destination (or (:mode/on-resolve cast-mode) :graveyard)
                 requirements (targeting/get-targeting-requirements card)]
             (if (targeting/all-targets-legal? game-db stored-targets requirements)
               (let [resolved-effects (mapv #(stack/resolve-effect-target % object-id player-id stored-targets) effects-list)
                     db-after-effects (reduce (fn [d effect]
                                                (effects/execute-effect d player-id effect object-id))
                                              game-db
                                              resolved-effects)
                     db-final (if (:object/is-copy obj)
                                (zones/remove-object db-after-effects object-id)
                                (zones/move-to-zone db-after-effects object-id destination))]
                 {:db db-final
                  :pending-selection nil})
               {:db (if (:object/is-copy obj)
                      (zones/remove-object game-db object-id)
                      (zones/move-to-zone game-db object-id destination))
                :pending-selection nil}))

           ;; Other stored targets: bridge to object + rules/resolve-spell
           ;; (e.g., Orim's Chant :targeted-player, Recoup :effect/target-ref)
           ;; effects/execute-effect reads :object/targets for these target types
           (seq stored-targets)
           (let [cast-mode (:object/cast-mode obj)
                 destination (or (:mode/on-resolve cast-mode) :graveyard)
                 requirements (targeting/get-targeting-requirements card)
                 obj-eid (d/q '[:find ?e . :in $ ?oid
                                :where [?e :object/id ?oid]]
                              game-db object-id)]
             (if (targeting/all-targets-legal? game-db stored-targets requirements)
               (let [;; Bridge: copy stack-item targets to object so execute-effect can find them
                     db-with-targets (d/db-with game-db
                                                [[:db/add obj-eid :object/targets stored-targets]])]
                 {:db (rules/resolve-spell db-with-targets player-id object-id)
                  :pending-selection nil})
               {:db (zones/move-to-zone game-db object-id destination)
                :pending-selection nil}))

           ;; No stored targets: execute effects via reduce-effects.
           ;; Tagged return values from execute-effect-impl automatically
           ;; signal when player selection is needed — no pre-scanning required.
           :else
           (let [result (effects/reduce-effects game-db player-id effects-list object-id)]
             (handle-reduce-result result player-id object-id obj))))))))


;; =====================================================
;; Stack-Item Ability Resolution
;; =====================================================

(defn resolve-stack-item-ability-with-selection
  "Resolve a stack-item for an activated ability, pausing if a selection effect is encountered.
   Uses resolve-effect-target for all target resolution, then reduce-effects
   for sequential execution with automatic interactive effect detection.

   Arguments:
     game-db - Datascript database
     stack-item - Stack-item entity map with :stack-item/* attributes

   Returns {:db db :pending-selection selection-or-nil}"
  [game-db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        stack-item-eid (:db/id stack-item)
        effects-list (or (:stack-item/effects stack-item) [])
        stored-targets (:stack-item/targets stack-item)
        stored-player (:player stored-targets)
        ;; Check for :any-player effect that can be satisfied by stored player target
        any-player-idx (when stored-player
                         (find-any-player-effect-index effects-list))
        has-stored-player-target (boolean any-player-idx)]
    (cond
      ;; Stored targets for player targeting (e.g., Cephalid Coliseum)
      ;; Pre-resolve :any-player, execute, then handle remaining interactive effects
      has-stored-player-target
      (let [[effects-before player-target-effect effects-after]
            (split-effects-around-index effects-list any-player-idx)
            resolved-player-effect (stack/resolve-effect-target player-target-effect source-id controller stored-targets)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d controller
                                                              (stack/resolve-effect-target effect source-id controller nil)))
                                    game-db effects-before)
            db-after-player-effect (effects/execute-effect db-after-before controller resolved-player-effect)
            ;; Use reduce-effects for remaining effects — automatically detects
            ;; interactive effects via tagged return values
            result (effects/reduce-effects db-after-player-effect controller effects-after)]
        (if (:needs-selection result)
          (let [sel-effect (:needs-selection result)]
            {:db (:db result)
             :pending-selection (when (= :player (:effect/selection sel-effect))
                                  {:selection/type :discard
                                   :selection/card-source :hand
                                   :selection/player-id stored-player
                                   :selection/select-count (:effect/count sel-effect)
                                   :selection/selected #{}
                                   :selection/stack-item-eid stack-item-eid
                                   :selection/source-type :stack-item
                                   :selection/remaining-effects (vec (:remaining-effects result))
                                   :selection/validation :exact
                                   :selection/auto-confirm? false})})
          {:db (:db result) :pending-selection nil}))

      ;; No stored player target: pre-resolve all targets, execute via reduce-effects
      :else
      (let [resolved-effects (mapv #(stack/resolve-effect-target % source-id controller stored-targets) effects-list)
            result (effects/reduce-effects game-db controller resolved-effects source-id)]
        (if (:needs-selection result)
          (let [sel (core/build-selection-for-effect
                      (:db result) controller stack-item-eid
                      (:needs-selection result) (vec (:remaining-effects result)))]
            {:db (:db result)
             :pending-selection (when sel
                                  (-> sel
                                      (dissoc :selection/spell-id)
                                      (assoc :selection/stack-item-eid stack-item-eid
                                             :selection/source-type :stack-item)))})
          {:db (:db result) :pending-selection nil})))))
