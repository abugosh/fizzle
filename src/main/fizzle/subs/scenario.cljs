(ns fizzle.subs.scenario
  "Re-frame subscriptions for scenario state."
  (:require
    [fizzle.engine.cards :as cards]
    [fizzle.events.scenario :as scenario-events]
    [re-frame.core :as rf]))


(rf/reg-sub
  ::all-scenarios
  (fn [db _]
    (:scenario/library db)))


(rf/reg-sub
  ::editing
  (fn [db _]
    (:scenario/editing db)))


(rf/reg-sub
  ::editing-player
  :<- [::editing]
  (fn [editing _]
    (:scenario/player editing)))


(rf/reg-sub
  ::editing-opponent
  :<- [::editing]
  (fn [editing _]
    (:scenario/opponent editing)))


(rf/reg-sub
  ::active-view
  (fn [db _]
    (:scenario/active-view db)))


;; === Deck display subscriptions ===

(def type-order [:land :instant :sorcery :artifact :creature :enchantment :other])


(defn- classify-card
  "Return the display type bucket for a card entry."
  [{:keys [card/types]}]
  (cond
    (contains? types :land) :land
    (contains? types :instant) :instant
    (contains? types :sorcery) :sorcery
    (contains? types :artifact) :artifact
    (contains? types :creature) :creature
    (contains? types :enchantment) :enchantment
    :else :other))


(defn- deck->grouped
  "Convert a deck vector into a grouped map keyed by card type bucket.
   Each entry is enriched with :card/name and :card/types from the registry."
  [deck]
  (when (seq deck)
    (group-by classify-card
              (mapv (fn [{:keys [card/id count]}]
                      (let [card-def (get cards/card-by-id id)]
                        {:card/id    id
                         :card/name  (:card/name card-def)
                         :card/types (:card/types card-def)
                         :count      count}))
                    deck))))


(rf/reg-sub
  ::player-deck-grouped
  :<- [::editing-player]
  (fn [player _]
    (deck->grouped (:deck player))))


(rf/reg-sub
  ::opponent-deck-grouped
  :<- [::editing-opponent]
  (fn [opponent _]
    (deck->grouped (:deck opponent))))


(rf/reg-sub
  ::available-cards
  :<- [::editing-player]
  (fn [player _]
    (scenario-events/available-cards (or (:deck player) []))))
