(ns fizzle.engine.effects
  "Effect interpreter for Fizzle.

   Dispatches on :effect/type to execute card effects.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.zones :as zones]))


(defmulti execute-effect
  "Execute an effect on the game state.

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys

   Returns:
     New db with effect applied.

   Unknown effect types return db unchanged (no-op)."
  (fn [_db _player-id effect] (:effect/type effect)))


(defmethod execute-effect :default
  [db _player-id _effect]
  ;; Unknown effect types are no-ops
  db)


(defmethod execute-effect :add-mana
  [db player-id effect]
  (mana/add-mana db player-id (:effect/mana effect)))


(defmethod execute-effect :mill
  ;; Mill cards from a player's library to their graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Number of cards to mill
  ;;   :effect/target - Target player (:opponent for opponent, or player-id)
  ;;                    Defaults to caster if not specified.
  ;;
  ;; Handles edge cases:
  ;;   - Empty library: returns db unchanged (no-op)
  ;;   - Partial library: mills all available cards (no crash)
  [db player-id effect]
  (let [target (get effect :effect/target player-id)
        target-player (if (= target :opponent)
                        (q/get-opponent-id db player-id)
                        target)
        amount (:effect/amount effect)
        cards-to-mill (or (q/get-top-n-library db target-player amount) [])]
    (reduce (fn [db' oid]
              (zones/move-to-zone db' oid :graveyard))
            db
            cards-to-mill)))


(defn- set-loss-condition
  "Set the game loss condition. Returns db with :game/loss-condition set."
  [db condition]
  (let [game-eid (d/q '[:find ?e .
                        :where [?e :game/id _]]
                      db)]
    (d/db-with db [[:db/add game-eid :game/loss-condition condition]])))


(defmethod execute-effect :lose-life
  ;; Reduce a player's life total.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Amount of life to lose
  ;;   :effect/target - Target player (defaults to caster)
  ;;
  ;; Handles edge cases:
  ;;   - Amount <= 0: no-op (negative amount is NOT treated as gain)
  ;;   - Invalid player: no-op (returns db unchanged)
  ;;   - Life can go negative (no clamping at 0)
  [db player-id effect]
  (let [amount (get effect :effect/amount 0)
        target (get effect :effect/target player-id)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (- current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
        db))))


(defmethod execute-effect :gain-life
  ;; Increase a player's life total.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Amount of life to gain
  ;;   :effect/target - Target player (defaults to caster)
  ;;
  ;; Handles edge cases:
  ;;   - Amount <= 0: no-op (negative amount is NOT treated as lose)
  ;;   - Invalid player: no-op (returns db unchanged)
  ;;   - No maximum life cap
  [db player-id effect]
  (let [amount (get effect :effect/amount 0)
        target (get effect :effect/target player-id)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (+ current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
        db))))


(defmethod execute-effect :draw
  ;; Draw cards from a player's library to their hand.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Number of cards to draw (default 1)
  ;;   :effect/target - Target player (defaults to caster)
  ;;
  ;; Handles edge cases:
  ;;   - Empty library: sets :game/loss-condition to :empty-library
  ;;   - Partial library: draws all available, then sets loss condition
  ;;   - Draw 0 or negative: no-op, no loss condition
  ;;   - Invalid player: no-op (returns db unchanged)
  [db player-id effect]
  (let [amount (get effect :effect/amount 1)
        target (get effect :effect/target player-id)]
    ;; Guard: draw 0 or negative is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [cards-to-draw (q/get-top-n-library db target amount)]
        (let [actual-drawn (count cards-to-draw)
              ;; Move cards from library to hand
              db-after-draw (reduce (fn [db' oid]
                                      (zones/move-to-zone db' oid :hand))
                                    db
                                    cards-to-draw)]
          ;; If we couldn't draw all requested cards, set loss condition
          (if (< actual-drawn amount)
            (set-loss-condition db-after-draw :empty-library)
            db-after-draw))
        ;; get-top-n-library returns nil if player doesn't exist
        db))))
