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
  8. Exile Cards Cost (:type :exile-cards-cost) → costs.cljs
  9. X Mana Cost (:type :x-mana-cost) → costs.cljs
  10. Targeting (:type :cast-time-targeting / :ability-targeting) → targeting.cljs
  11. Player Target (:type :player-target) → targeting.cljs

  Common keys (all namespaced :selection/*):
    selected, spell-id, remaining-effects, player-id, source-type, stack-item-eid"
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]
    [re-frame.core :as rf]))


;; =====================================================
;; Selection Builders
;; =====================================================

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
