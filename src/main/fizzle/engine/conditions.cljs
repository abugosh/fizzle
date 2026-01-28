(ns fizzle.engine.conditions
  "Condition evaluators for Fizzle.

   Conditions are used by cards with conditional effects (e.g., threshold).
   All functions are pure: (db, player-id) -> boolean"
  (:require
    [fizzle.db.queries :as q]))


(defn threshold?
  "Check if a player has threshold (7+ cards in their graveyard).

   Arguments:
     db - Datascript database value
     player-id - The player to check

   Returns:
     true if player has 7 or more cards in graveyard, false otherwise."
  [db player-id]
  (let [graveyard (q/get-objects-in-zone db player-id :graveyard)
        count (or (count graveyard) 0)]
    (>= count 7)))
