(ns fizzle.engine.cards
  "Card pool queries for Fizzle.

   Routes card data through the Interpretation Core (engine layer) so that
   consumers (subs, events) don't import card pool namespaces directly."
  (:require
    [fizzle.cards.iggy-pop :as iggy-pop]))


(def all-cards
  "All card definitions available in the card pool."
  iggy-pop/all-cards)


(def card-by-id
  "Lookup map from :card/id keyword to card definition."
  (into {} (map (juxt :card/id identity) all-cards)))
