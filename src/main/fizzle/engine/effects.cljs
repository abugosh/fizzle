(ns fizzle.engine.effects
  "Effect interpreter for Fizzle.

   Dispatches on :effect/type to execute card effects.
   All functions are pure: (db, args) -> db"
  (:require [fizzle.engine.mana :as mana]))

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
