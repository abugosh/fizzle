(ns fizzle.sharing.card-index
  "Stable integer ↔ card-id index for compact URL encoding.

   Assigns a dense, zero-based integer to every card in the registry.
   Index = position in registry/all-cards vector. New cards are always
   appended to the end of all-cards, so existing indices never shift and
   previously encoded snapshots remain decodable."
  (:require
    [fizzle.engine.cards :as cards]))


(def card->int
  "Map from :card/id keyword to stable integer index.
   Index = position in the registry all-cards vector."
  (->> cards/all-cards
       (map-indexed (fn [i card] [(:card/id card) i]))
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
