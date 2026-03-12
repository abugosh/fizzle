(ns fizzle.sharing.card-index
  "Stable integer ↔ card-id index for compact URL encoding.

   Assigns a dense, zero-based integer to every card in the registry.
   Ordering is alphabetical by card-id name so the mapping is deterministic
   and does not change as long as the card pool is unchanged.

   If the card pool grows, new cards are inserted at their alphabetical
   position; existing indices for earlier cards are unaffected only if
   new cards sort after all existing ones. When a card's index must change
   (insertion before existing cards), any persisted URL snapshots that
   reference that card will decode incorrectly. This is acceptable during
   early development; a version field in the snapshot envelope guards
   against silent corruption."
  (:require
    [fizzle.engine.cards :as cards]))


(def card->int
  "Map from :card/id keyword to stable integer index.
   Sorted alphabetically by card-id name."
  (->> cards/all-cards
       (map :card/id)
       sort
       (map-indexed (fn [i id] [id i]))
       (into {})))


(def int->card
  "Inverse of card->int. Map from integer index to :card/id keyword."
  (into {} (map (fn [[k v]] [v k]) card->int)))


(def card-count
  "Total number of cards in the index."
  (count card->int))


(defn encode
  "Return the integer index for card-id, or nil if not in the registry."
  [card-id]
  (get card->int card-id))


(defn decode
  "Return the card-id keyword for integer index n, or nil if out of range."
  [n]
  (get int->card n))
