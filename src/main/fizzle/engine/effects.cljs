(ns fizzle.engine.effects
  "Effect interpreter for Fizzle.

   Dispatches on :effect/type to execute card effects.
   All functions are pure: (db, args) -> db"
  (:require
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
