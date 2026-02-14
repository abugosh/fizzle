(ns fizzle.events.selection.zone-ops
  "Zone operation selection domains: discard (unified) and graveyard-return.

   Discard unification: :cleanup-discard is removed as a separate type.
   The :discard type now checks :selection/cleanup? flag:
     - false/nil → standard discard (wrapper handles remaining-effects)
     - true → cleanup discard (expire grants, return finalized)"
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Selection Builders
;; =====================================================

(defn build-discard-selection
  "Build pending selection state for a discard effect."
  [player-id object-id discard-effect effects-after]
  {:selection/zone :hand
   :selection/card-source :hand
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
   Player can select 0 to :effect/count cards."
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
     :selection/card-source :zone
     :selection/select-count (:effect/count effect)
     :selection/min-count 0
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


;; =====================================================
;; Confirm Selection Multimethod
;; =====================================================

(defmethod core/execute-confirmed-selection :discard
  [game-db selection]
  (let [selected (:selection/selected selection)
        db-after-discard (reduce (fn [gdb obj-id]
                                   (zones/move-to-zone gdb obj-id :graveyard))
                                 game-db
                                 selected)]
    (if (:selection/cleanup? selection)
      ;; Cleanup path: expire grants and return finalized
      (let [game-state (queries/get-game-state db-after-discard)
            current-turn (:game/turn game-state)
            db-final (grants/expire-grants db-after-discard current-turn :cleanup)]
        {:db db-final :finalized? true})
      ;; Standard path: wrapper handles remaining-effects
      {:db db-after-discard})))


(defmethod core/execute-confirmed-selection :graveyard-return
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zones/move-to-zone gdb obj-id :hand))
                 game-db
                 selected)}))
