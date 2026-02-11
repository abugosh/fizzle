(ns fizzle.events.selection
  "Selection state management for player choices during spell/ability resolution.

  Selection Types (all use :selection/type as discriminator):
  ──────────────────────────────────────────────────────────
  1. Tutor (:type :tutor) → library.cljs
  2. Discard (:type :discard)
  3. Cleanup Discard (:type :cleanup-discard)
  4. Graveyard Return (:type :graveyard-return)
  5. Scry (:type :scry) → library.cljs
  6. Pile Choice (:type :pile-choice) → library.cljs
  7. Peek-and-Select (:type :peek-and-select) → library.cljs
  8. Exile Cards Cost (:type :exile-cards-cost)
  9. X Mana Cost (:type :x-mana-cost)
  10. Targeting (:type :cast-time-targeting / :ability-targeting) → targeting.cljs
  11. Player Target (:type :player-target) → targeting.cljs

  Common keys (all namespaced :selection/*):
    selected, spell-id, remaining-effects, player-id, source-type, stack-item-eid"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]
    [re-frame.core :as rf]))


;; =====================================================
;; X Cost Selection Helpers
;; =====================================================

(defn has-exile-cards-x-cost?
  "Check if mode has an :exile-cards additional cost with :cost/count :x"
  [mode]
  (some (fn [cost]
          (and (= :exile-cards (:cost/type cost))
               (= :x (:cost/count cost))))
        (:mode/additional-costs mode)))


(defn get-exile-cards-x-cost
  "Get the :exile-cards additional cost with :cost/count :x from a mode"
  [mode]
  (first (filter (fn [cost]
                   (and (= :exile-cards (:cost/type cost))
                        (= :x (:cost/count cost))))
                 (:mode/additional-costs mode))))


(defn has-mana-x-cost?
  "Check if mode has :x true in its mana cost"
  [mode]
  (true? (get-in mode [:mode/mana-cost :x])))


;; =====================================================
;; Selection Builders
;; =====================================================

(defn build-exile-cards-selection
  "Build pending selection for exile-cards additional cost with :x count.
   Player selects which cards to exile (1 or more), and the count becomes X.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with :exile-cards cost
     exile-cost - The :exile-cards cost map

   Returns selection state for choosing cards to exile."
  [game-db player-id object-id mode exile-cost]
  (let [zone (:cost/zone exile-cost)
        criteria (:cost/criteria exile-cost)
        ;; Get available cards matching criteria
        available (queries/query-zone-by-criteria game-db player-id zone criteria)
        ;; Exclude the spell being cast (can't exile itself)
        candidates (filterv #(not= object-id (:object/id %)) available)
        candidate-ids (set (map :object/id candidates))]
    (when (seq candidate-ids)
      {:selection/zone zone
       :selection/type :exile-cards-cost
       :selection/candidates candidate-ids
       :selection/select-count 1  ; Minimum 1, but can select more
       :selection/exact? false    ; Can select any number >= 1
       :selection/allow-fail-to-find? false  ; Must select at least 1
       :selection/selected #{}
       :selection/player-id player-id
       :selection/spell-id object-id
       :selection/mode mode       ; Store mode for casting after selection
       :selection/exile-cost exile-cost
       :selection/validation :at-least-one
       :selection/auto-confirm? false})))


(defn build-x-mana-selection
  "Build pending selection for X mana cost.
   Player selects how much to pay for X.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with X in mana cost

   Returns selection state for choosing X value."
  [game-db player-id object-id mode]
  (let [pool (queries/get-mana-pool game-db player-id)
        mana-cost (:mode/mana-cost mode)
        ;; Fixed costs (non-X portion)
        fixed-colorless (get mana-cost :colorless 0)
        fixed-colored (dissoc mana-cost :colorless :x)
        ;; Calculate remaining mana after paying fixed costs
        pool-after-colored (merge-with - pool fixed-colored)
        total-remaining (max 0 (- (reduce + 0 (vals pool-after-colored)) fixed-colorless))
        ;; Max X is what's left after paying fixed costs
        max-x total-remaining]
    {:selection/zone :mana-pool
     :selection/type :x-mana-cost
     :selection/player-id player-id
     :selection/spell-id object-id
     :selection/mode mode
     :selection/max-x max-x
     :selection/selected-x 0  ; Default to 0, player increments
     :selection/validation :always
     :selection/auto-confirm? false}))


(defn- build-discard-selection
  "Build pending selection state for a discard effect."
  [player-id object-id discard-effect effects-after]
  {:selection/zone :hand
   :selection/select-count (:effect/count discard-effect)
   :selection/player-id player-id
   :selection/selected #{}
   :selection/spell-id object-id
   :selection/remaining-effects effects-after
   :selection/type :discard
   :selection/validation :exact
   :selection/auto-confirm? false})


(defn build-graveyard-selection
  "Build pending selection state for returning cards from graveyard.
   Unlike tutor (hidden zone), graveyard is public - no fail-to-find option.
   Player can select 0 to :effect/count cards.

   Arguments:
     game-db - Datascript game database
     player-id - Caster's player-id (used to resolve :self target)
     object-id - Spell-id for cleanup after selection confirmed
     effect - The :return-from-graveyard effect map with:
       :effect/target - :self, :opponent, or player-id (defaults to caster)
       :effect/count - Max cards to return (0 to N selection)
       :effect/selection - Should be :player for this to be called
     effects-after - Vector of effects to execute after selection

   Returns selection state map for UI to render graveyard selection."
  [game-db player-id object-id effect effects-after]
  (let [target (get effect :effect/target player-id)
        target-player (cond
                        (= target :opponent) (queries/get-opponent-id game-db player-id)
                        (= target :self) player-id
                        :else target)
        gy-cards (or (queries/get-objects-in-zone game-db target-player :graveyard) [])
        candidate-ids (set (map :object/id gy-cards))]
    {:selection/type :graveyard-return
     :selection/zone :graveyard
     :selection/select-count (:effect/count effect)
     :selection/min-count 0  ; Can select 0 cards (up to N)
     :selection/player-id target-player
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/candidate-ids candidate-ids
     :selection/validation :at-most
     :selection/auto-confirm? false}))


;; =====================================================
;; Builder Multimethod Registrations
;; =====================================================

(defmethod core/build-selection-for-effect :discard
  [_db player-id object-id effect remaining]
  (build-discard-selection player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :return-from-graveyard
  [db player-id object-id effect remaining]
  (build-graveyard-selection db player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :default
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
;; Confirm Selection Multimethod
;; =====================================================

;; Multimethod definition moved to core.cljs — defmethods register on core/execute-confirmed-selection


(defmethod core/execute-confirmed-selection :discard
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zones/move-to-zone gdb obj-id :graveyard))
                 game-db
                 selected)}))


(defmethod core/execute-confirmed-selection :cleanup-discard
  [game-db selection]
  (let [selected (:selection/selected selection)
        db-after-discard (reduce (fn [d obj-id]
                                   (zones/move-to-zone d obj-id :graveyard))
                                 game-db
                                 selected)
        game-state (queries/get-game-state db-after-discard)
        current-turn (:game/turn game-state)
        db-final (grants/expire-grants db-after-discard current-turn :cleanup)]
    {:db db-final :finalized? true}))


(defmethod core/execute-confirmed-selection :graveyard-return
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zones/move-to-zone gdb obj-id :hand))
                 game-db
                 selected)}))


(defmethod core/execute-confirmed-selection :exile-cards-cost
  [game-db selection]
  (let [selected (:selection/selected selection)
        selected-count (count selected)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        db-after-exile (reduce (fn [d card-id]
                                 (zones/move-to-zone d card-id :exile))
                               game-db
                               selected)
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db-after-exile object-id)
        db-with-x (d/db-with db-after-exile
                             [[:db/add obj-eid :object/x-value selected-count]])
        db-after-cast (rules/cast-spell-mode db-with-x player-id object-id mode)]
    {:db db-after-cast :finalized? true :clear-selected-card? true}))


(defmethod core/execute-confirmed-selection :x-mana-cost
  [game-db selection]
  (let [x-value (:selection/selected-x selection)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     game-db object-id)
        db-with-x (d/db-with game-db
                             [[:db/add obj-eid :object/x-value x-value]])
        resolved-mana-cost (mana/resolve-x-cost (:mode/mana-cost mode) x-value)
        mode-with-resolved-cost (assoc mode :mode/mana-cost resolved-mana-cost)
        db-after-cast (rules/cast-spell-mode db-with-x player-id object-id mode-with-resolved-cost)]
    {:db db-after-cast :finalized? true :clear-selected-card? true}))


;; =====================================================
;; Re-frame Event Handlers
;; =====================================================

(rf/reg-event-db
  ::set-pending-selection
  (fn [db [_ selection-state]]
    (assoc db :game/pending-selection selection-state)))


(rf/reg-event-db
  ::toggle-selection
  (fn [db [_ id]]
    (core/toggle-selection-impl db id)))


(rf/reg-event-db
  ::confirm-selection
  (fn [db _]
    (core/confirm-selection-handler db)))


(rf/reg-event-db
  ::cancel-selection
  (fn [db _]
    ;; Cancel clears selection but keeps the pending state
    ;; Player must still make a valid selection
    (assoc-in db [:game/pending-selection :selection/selected] #{})))


;; === X Cost Selection System ===
;; For spells with X in mana cost or exile-cards additional cost with :x count


(rf/reg-event-db
  ::cancel-exile-cards-selection
  (fn [db _]
    (dissoc db :game/pending-selection)))


(rf/reg-event-db
  ::increment-x-value
  (fn [db _]
    (let [selection (:game/pending-selection db)
          current-x (or (:selection/selected-x selection) 0)
          max-x (or (:selection/max-x selection) 0)]
      (if (< current-x max-x)
        (assoc-in db [:game/pending-selection :selection/selected-x] (inc current-x))
        db))))


(rf/reg-event-db
  ::decrement-x-value
  (fn [db _]
    (let [selection (:game/pending-selection db)
          current-x (or (:selection/selected-x selection) 0)]
      (if (pos? current-x)
        (assoc-in db [:game/pending-selection :selection/selected-x] (dec current-x))
        db))))


(rf/reg-event-db
  ::cancel-x-mana-selection
  (fn [db _]
    (dissoc db :game/pending-selection)))
