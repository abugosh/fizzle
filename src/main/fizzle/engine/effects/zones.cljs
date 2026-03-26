(ns fizzle.engine.effects.zones
  "Zone-operation effects: mill, draw, exile-self, discard-hand,
   return-from-graveyard, sacrifice, destroy, exile-zone, bounce."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.zones :as zones]))


(defmethod effects/execute-effect-impl :mill
  [db player-id effect object-id]
  (let [explicit-target (:effect/target effect)
        target-player (cond
                        (nil? explicit-target) player-id
                        (= explicit-target :opponent) (q/get-opponent-id db player-id)
                        :else explicit-target)
        amount (effects/resolve-dynamic-value db player-id (get effect :effect/amount 0) object-id)
        cards-to-mill (or (q/get-top-n-library db target-player amount) [])]
    (reduce (fn [db' oid]
              (zones/move-to-zone db' oid :graveyard))
            db
            cards-to-mill)))


(defmethod effects/execute-effect-impl :draw
  [db player-id effect object-id]
  (let [amount (effects/resolve-dynamic-value db player-id (get effect :effect/amount 1) object-id)
        explicit-target (:effect/target effect)
        target-player (cond
                        (nil? explicit-target) player-id
                        (= explicit-target :opponent) (q/get-opponent-id db player-id)
                        :else explicit-target)]
    (if (<= amount 0)
      db
      (if (= explicit-target :any-player)
        {:db db :needs-selection effect}
        (if-let [cards-to-draw (q/get-top-n-library db target-player amount)]
          (let [actual-drawn (count cards-to-draw)
                db-after-draw (reduce (fn [db' oid]
                                        (zones/move-to-zone db' oid :hand))
                                      db
                                      cards-to-draw)]
            (if (< actual-drawn amount)
              (let [target-eid (q/get-player-eid db-after-draw target-player)]
                (d/db-with db-after-draw [[:db/add target-eid :player/drew-from-empty true]]))
              db-after-draw))
          db)))))


(defmethod effects/execute-effect-impl :exile-self
  [db _player-id _effect object-id]
  (if-not object-id
    db
    (if (q/get-object-eid db object-id)
      (zones/move-to-zone db object-id :exile)
      db)))


(defmethod effects/execute-effect-impl :discard-hand
  [db player-id effect _object-id]
  (let [explicit-target (:effect/target effect)
        target-player (cond
                        (nil? explicit-target) player-id
                        (= explicit-target :opponent) (q/get-opponent-id db player-id)
                        (= explicit-target :self) player-id
                        :else explicit-target)
        hand-cards (q/get-hand db target-player)]
    (reduce (fn [db' obj]
              (zones/move-to-zone db' (:object/id obj) :graveyard))
            db
            (or hand-cards []))))


(defmethod effects/execute-effect-impl :return-from-graveyard
  [db player-id effect _object-id]
  (let [explicit-target (:effect/target effect)
        target-player (cond
                        (nil? explicit-target) player-id
                        (= explicit-target :opponent) (q/get-opponent-id db player-id)
                        (= explicit-target :self) player-id
                        :else explicit-target)
        selection (get effect :effect/selection :player)
        count-limit (get effect :effect/count 0)]
    (case selection
      :random (let [gy-cards (or (q/get-objects-in-zone db target-player :graveyard) [])
                    selected (take count-limit (shuffle gy-cards))]
                (reduce (fn [db' obj]
                          (zones/move-to-zone db' (:object/id obj) :hand))
                        db
                        selected))
      {:db db :needs-selection effect})))


(defmethod effects/execute-effect-impl :sacrifice
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if (q/get-object-eid db target-id)
      (zones/move-to-zone db target-id :graveyard)
      db)))


(defmethod effects/execute-effect-impl :destroy
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-let [_target-obj (q/get-object db target-id)]
        (zones/move-to-zone db target-id :graveyard)
        db))))


(defmethod effects/execute-effect-impl :exile-zone
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)
        zone (:effect/zone effect)]
    (if-not (and target-player zone (q/get-player-eid db target-player))
      db
      (let [zone-objects (or (q/get-objects-in-zone db target-player zone) [])]
        (reduce (fn [db' obj]
                  (zones/move-to-zone db' (:object/id obj) :exile))
                db
                zone-objects)))))


(defmethod effects/execute-effect-impl :bounce
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if (q/get-object-eid db target-id)
        (zones/move-to-zone db target-id :hand)
        db))))


(defmethod effects/execute-effect-impl :untap-lands
  [db _player-id effect _object-id]
  ;; Interactive: player chooses which tapped lands to untap.
  ;; Returns needs-selection to pause effect chain and trigger selection.
  {:db db :needs-selection effect})


(defmethod effects/execute-effect-impl :tap-all
  [db _player-id effect _object-id]
  ;; Mass tap: tap all UNTAPPED permanents of :effect/permanent-type
  ;; controlled by :effect/target player. Non-interactive — no player selection.
  (let [target-player (:effect/target effect)
        perm-type (:effect/permanent-type effect)
        bf-objects (or (q/get-objects-in-zone db target-player :battlefield) [])
        untapped-of-type (filterv (fn [obj]
                                    (and (not (:object/tapped obj))
                                         (contains? (set (get-in obj [:object/card :card/types] #{}))
                                                    perm-type)))
                                  bf-objects)]
    (reduce (fn [db' obj]
              (let [obj-eid (q/get-object-eid db' (:object/id obj))]
                (if obj-eid
                  (d/db-with db' [[:db/add obj-eid :object/tapped true]])
                  db')))
            db
            untapped-of-type)))


(defmethod effects/execute-effect-impl :untap-all
  [db _player-id effect _object-id]
  ;; Mass untap: untap all TAPPED permanents of :effect/permanent-type
  ;; controlled by :effect/target player. Non-interactive — no player selection.
  (let [target-player (:effect/target effect)
        perm-type (:effect/permanent-type effect)
        bf-objects (or (q/get-objects-in-zone db target-player :battlefield) [])
        tapped-of-type (filterv (fn [obj]
                                  (and (:object/tapped obj)
                                       (contains? (set (get-in obj [:object/card :card/types] #{}))
                                                  perm-type)))
                                bf-objects)]
    (reduce (fn [db' obj]
              (let [obj-eid (q/get-object-eid db' (:object/id obj))]
                (if obj-eid
                  (d/db-with db' [[:db/add obj-eid :object/tapped false]])
                  db')))
            db
            tapped-of-type)))


(defmethod effects/execute-effect-impl :welder-swap
  [db _player-id effect _object-id]
  ;; Goblin Welder swap: simultaneously sacrifice battlefield artifact and
  ;; return graveyard artifact to battlefield. Both must be legal at resolution.
  ;; :effect/target = battlefield artifact (to sacrifice)
  ;; :effect/graveyard-id = graveyard artifact (to return)
  (let [bf-id (:effect/target effect)
        gy-id (:effect/graveyard-id effect)]
    (if-not (and bf-id gy-id)
      db
      ;; Check both targets are still in their expected zones
      (let [bf-obj (q/get-object db bf-id)
            gy-obj (q/get-object db gy-id)
            bf-legal (and bf-obj (= :battlefield (:object/zone bf-obj)))
            gy-legal (and gy-obj (= :graveyard (:object/zone gy-obj)))]
        (if (and bf-legal gy-legal)
          ;; Simultaneously: sacrifice bf artifact, return gy artifact to battlefield
          (-> db
              (zones/move-to-zone bf-id :graveyard)
              (zones/move-to-zone gy-id :battlefield))
          ;; Either target is no longer legal — do nothing
          db)))))
