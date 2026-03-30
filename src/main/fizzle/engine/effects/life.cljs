(ns fizzle.engine.effects.life
  "Life and damage effects: lose-life, gain-life, deal-damage, gain-life-equal-to-cmc."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.combat :as combat]
    [fizzle.engine.effects :as effects]))


(defmethod effects/execute-effect-impl :lose-life
  [db player-id effect _object-id]
  (let [amount (get effect :effect/amount 0)
        explicit-target (:effect/target effect)
        target (cond
                 (nil? explicit-target) player-id
                 (= explicit-target :opponent) (q/get-opponent-id db player-id)
                 (= explicit-target :self) player-id
                 :else explicit-target)]
    (if (<= amount 0)
      db
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (- current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
        db))))


(defmethod effects/execute-effect-impl :gain-life
  [db player-id effect _object-id]
  (let [amount (get effect :effect/amount 0)
        explicit-target (:effect/target effect)
        target (cond
                 (nil? explicit-target) player-id
                 (= explicit-target :opponent) (q/get-opponent-id db player-id)
                 (= explicit-target :self) player-id
                 :else explicit-target)]
    (if (<= amount 0)
      db
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (+ current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
        db))))


(defmethod effects/execute-effect-impl :deal-damage
  [db player-id effect object-id]
  (let [amount (effects/resolve-dynamic-value db player-id (get effect :effect/amount 0) object-id)
        target (:effect/target effect)]
    (if (<= amount 0)
      db
      (cond
        ;; Player target: reduce life
        (q/get-player-eid db target)
        (let [player-eid (q/get-player-eid db target)
              current-life (q/get-life-total db target)
              new-life (- current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
        ;; Object target (creature on battlefield): mark damage
        (q/get-object db target)
        (combat/mark-damage db target amount)
        ;; Unknown target — no-op
        :else db))))


(defmethod effects/execute-effect-impl :gain-life-equal-to-cmc
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-let [target-obj (q/get-object db target-id)]
        (let [card (:object/card target-obj)
              cmc (or (:card/cmc card) 0)
              controller-eid (:db/id (:object/controller target-obj))
              controller-id (when controller-eid
                              (:player/id (q/pull-safe db [:player/id] controller-eid)))]
          (if (or (<= cmc 0) (nil? controller-id))
            db
            (let [current-life (q/get-life-total db controller-id)
                  new-life (+ current-life cmc)]
              (d/db-with db [[:db/add controller-eid :player/life new-life]]))))
        db))))
