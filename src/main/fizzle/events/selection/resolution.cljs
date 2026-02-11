(ns fizzle.events.selection.resolution
  "Resolution bridge: detects selection effects, splits effect lists,
   and orchestrates spell/ability resolution with selection pauses.

   This namespace requires only core.cljs and engine/* — zero domain
   file imports. Builder dispatch goes through the
   core/build-selection-for-effect multimethod."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Selection Detection Helpers
;; =====================================================

(defn has-selection-effect?
  "Check if any effect in the list requires player selection."
  [effects]
  (some #(or (= :player (:effect/selection %))
             (= :tutor (:effect/type %))
             (and (= :scry (:effect/type %))
                  (pos? (or (:effect/amount %) 0))))
        effects))


(defn find-selection-effect-index
  "Find the index of the first effect that requires player selection.
   This includes:
   - :discard with :selection :player - player chooses cards to discard
   - :tutor - player searches library for matching card
   - :scry with amount > 0 - player arranges top N cards
   - :effect/target :any-player - player chooses a target player
   - :peek-and-select - player chooses from top N cards (Flash of Insight)
   - :return-from-graveyard with :selection :player - player chooses cards to return
   Returns nil if no selection effect found."
  [effects]
  (first (keep-indexed (fn [i effect]
                         (when (or (= :player (:effect/selection effect))
                                   (= :tutor (:effect/type effect))
                                   (and (= :scry (:effect/type effect))
                                        (pos? (or (:effect/amount effect) 0)))
                                   (= :any-player (:effect/target effect))
                                   (= :peek-and-select (:effect/type effect))
                                   (= :return-from-graveyard (:effect/type effect)))
                           i))
                       effects)))


(defn split-effects-around-index
  "Split effects list around the effect at idx.
   Returns [effects-before target-effect effects-after]."
  [effects idx]
  (let [v (vec effects)]
    [(subvec v 0 idx)
     (nth v idx)
     (subvec v (inc idx))]))


;; =====================================================
;; Stored Target Resolution
;; =====================================================

(defn- resolve-effect-with-stored-target
  "Resolve an effect using stored targets from :object/targets.
   Returns the effect with :any-player replaced by the actual stored target."
  [effect stored-targets]
  (if (= :any-player (:effect/target effect))
    ;; Look up the stored player target and resolve
    (if-let [stored-player (:player stored-targets)]
      (assoc effect :effect/target stored-player)
      effect)
    effect))


;; =====================================================
;; Spell Resolution with Selection
;; =====================================================

(defn resolve-spell-with-selection
  "Resolve a spell, pausing if a selection effect is encountered.

   Returns a map with:
     :db - The updated game-db
     :pending-selection - Selection state if paused for selection, nil otherwise

   When a selection effect is encountered:
   - Effects before the selection are executed
   - The selection effect creates pending selection state via build-selection-for-effect multimethod
   - Effects after the selection are stored for later execution

   If :object/targets contains stored targets from cast-time targeting:
   - Uses stored targets instead of prompting
   - Resolves effects normally using the pre-selected targets"
  [game-db player-id object-id]
  (let [obj (queries/get-object game-db object-id)]
    (if (not= :stack (:object/zone obj))
      {:db game-db :pending-selection nil}  ; No-op if spell not on stack
      (let [card (:object/card obj)
            stored-targets (:object/targets obj)
            effects-list (or (rules/get-active-effects game-db player-id card object-id) [])
            selection-idx (find-selection-effect-index effects-list)
            ;; Check if stored targets can satisfy the selection effect
            has-stored-player-target (and stored-targets
                                          (:player stored-targets)
                                          selection-idx
                                          (= :any-player (:effect/target (nth effects-list selection-idx))))
            ;; Check if spell has object targets (for fizzle checking)
            has-stored-object-targets (and stored-targets
                                           (seq stored-targets)
                                           (not (:player stored-targets)))]
        (cond
          ;; Cast-time targeting with player target: check fizzle, use stored targets
          has-stored-player-target
          (let [cast-mode (:object/cast-mode obj)
                destination (or (:mode/on-resolve cast-mode) :graveyard)]
            ;; Fizzle check: if any target is no longer legal, skip effects
            (if (targeting/all-targets-legal? game-db object-id)
              ;; Targets legal - resolve normally with stored targets
              (let [resolved-effects (mapv #(resolve-effect-with-stored-target % stored-targets) effects-list)
                    db-after-effects (reduce (fn [d effect]
                                               (effects/execute-effect d player-id effect))
                                             game-db
                                             resolved-effects)
                    db-final (zones/move-to-zone db-after-effects object-id destination)]
                {:db db-final
                 :pending-selection nil})
              ;; Fizzle: targets invalid, skip effects, move spell off stack
              {:db (zones/move-to-zone game-db object-id destination)
               :pending-selection nil}))

          ;; Cast-time targeting with object target (e.g., Recoup): check fizzle, resolve with object-id
          has-stored-object-targets
          (let [cast-mode (:object/cast-mode obj)
                destination (or (:mode/on-resolve cast-mode) :graveyard)]
            ;; Fizzle check: if any target is no longer legal, skip effects
            (if (targeting/all-targets-legal? game-db object-id)
              ;; Targets legal - resolve with object-id for effects that need stored targets
              {:db (rules/resolve-spell game-db player-id object-id)
               :pending-selection nil}
              ;; Fizzle: targets invalid, skip effects, move spell off stack
              {:db (zones/move-to-zone game-db object-id destination)
               :pending-selection nil}))

          ;; No selection effect and no stored targets - resolve normally
          (nil? selection-idx)
          {:db (rules/resolve-spell game-db player-id object-id)
           :pending-selection nil}

          ;; Has selection effect without stored targets - pause for selection
          :else
          (let [[effects-before selection-effect effects-after]
                (split-effects-around-index effects-list selection-idx)
                ;; Execute effects before selection (pass object-id for effects like :exile-self)
                db-after-before (reduce (fn [d effect]
                                          (effects/execute-effect d player-id effect object-id))
                                        game-db
                                        effects-before)
                ;; Build selection state via multimethod dispatch
                pending-selection (core/build-selection-for-effect
                                    db-after-before player-id object-id
                                    selection-effect (vec effects-after))]
            {:db db-after-before
             :pending-selection pending-selection}))))))


;; =====================================================
;; Stack-Item Ability Resolution
;; =====================================================

(defn resolve-stack-item-ability-with-selection
  "Resolve a stack-item for an activated ability, pausing if a selection effect is encountered.
   Uses resolve-effect-target for all target resolution.

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
        selection-idx (find-selection-effect-index effects-list)
        stored-player (:player stored-targets)
        has-stored-player-target (and stored-player
                                      selection-idx
                                      (= :any-player (:effect/target (nth effects-list selection-idx))))]
    (cond
      ;; Stored targets for player targeting
      has-stored-player-target
      (let [[effects-before player-target-effect effects-after]
            (split-effects-around-index effects-list selection-idx)
            resolved-player-effect (stack/resolve-effect-target player-target-effect source-id controller stored-targets)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d controller
                                                              (stack/resolve-effect-target effect source-id controller nil)))
                                    game-db effects-before)
            db-after-player-effect (effects/execute-effect db-after-before controller resolved-player-effect)
            next-selection-idx (find-selection-effect-index effects-after)]
        (if next-selection-idx
          (let [[effects-before-next next-selection-effect effects-after-next]
                (split-effects-around-index effects-after next-selection-idx)
                db-after-before-next (reduce (fn [d effect]
                                               (effects/execute-effect d controller effect))
                                             db-after-player-effect effects-before-next)
                pending-selection (when (= :player (:effect/selection next-selection-effect))
                                    {:selection/type :discard
                                     :selection/player-id stored-player
                                     :selection/select-count (:effect/count next-selection-effect)
                                     :selection/selected #{}
                                     :selection/stack-item-eid stack-item-eid
                                     :selection/source-type :stack-item
                                     :selection/remaining-effects effects-after-next
                                     :selection/validation :exact
                                     :selection/auto-confirm? false})]
            {:db db-after-before-next :pending-selection pending-selection})
          (let [db-after-all (reduce (fn [d effect]
                                       (effects/execute-effect d controller effect))
                                     db-after-player-effect effects-after)]
            {:db db-after-all :pending-selection nil})))

      ;; No selection effect
      (nil? selection-idx)
      (let [db-after-effects (reduce
                               (fn [d effect]
                                 (let [resolved (stack/resolve-effect-target effect source-id controller stored-targets)]
                                   (effects/execute-effect d controller resolved source-id)))
                               game-db effects-list)]
        {:db db-after-effects :pending-selection nil})

      ;; Has selection effect without stored targets
      :else
      (let [[effects-before selection-effect effects-after]
            (split-effects-around-index effects-list selection-idx)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d controller
                                                              (stack/resolve-effect-target effect source-id controller nil)))
                                    game-db effects-before)
            pending-selection (cond
                                ;; Player target or known builder type: use multimethod + fixup
                                (or (= :any-player (:effect/target selection-effect))
                                    (= :tutor (:effect/type selection-effect)))
                                (when-let [sel (core/build-selection-for-effect
                                                 db-after-before controller stack-item-eid
                                                 selection-effect effects-after)]
                                  (-> sel
                                      (dissoc :selection/spell-id)
                                      (assoc :selection/stack-item-eid stack-item-eid
                                             :selection/source-type :stack-item)))

                                ;; Discard selection: inline (player-id is controller for abilities)
                                (= :player (:effect/selection selection-effect))
                                {:selection/type :discard
                                 :selection/player-id controller
                                 :selection/select-count (:effect/count selection-effect)
                                 :selection/selected #{}
                                 :selection/stack-item-eid stack-item-eid
                                 :selection/source-type :stack-item
                                 :selection/remaining-effects effects-after
                                 :selection/validation :exact
                                 :selection/auto-confirm? false}

                                :else nil)]
        {:db db-after-before :pending-selection pending-selection}))))
