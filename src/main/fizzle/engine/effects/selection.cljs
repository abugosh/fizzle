(ns fizzle.engine.effects.selection
  "Effects that defer to player selection: discard, tutor, scry,
   peek-and-select, peek-and-reorder, discard-from-revealed-hand."
  (:require
    [fizzle.engine.effects :as effects]))


(defmethod effects/execute-effect-impl :discard
  [db _player-id effect _object-id]
  (let [selection (get effect :effect/selection :player)]
    (case selection
      :player {:db db :needs-selection effect}
      {:db db :needs-selection effect})))


(defmethod effects/execute-effect-impl :tutor
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod effects/execute-effect-impl :scry
  [db _player-id effect _object-id]
  (let [amount (or (:effect/amount effect) 0)]
    (if (<= amount 0)
      db
      {:db db :needs-selection effect})))


(defmethod effects/execute-effect-impl :peek-and-select
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod effects/execute-effect-impl :peek-and-reorder
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod effects/execute-effect-impl :discard-from-revealed-hand
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})
