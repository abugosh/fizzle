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


;; === Zone pool subscriptions ===

(defn compute-zone-pool
  "Given a side config map (with :deck and :zones), return a vector of
   {:card/id kw :card/name str :count n} maps representing cards still
   available in the pool (deck minus zone-assigned cards)."
  [side-config]
  (let [deck   (or (:deck side-config) [])
        zones  (or (:zones side-config) {})
        ;; Count zone-assigned cards per card-id
        zone-assigned (reduce
                        (fn [acc card-id]
                          (update acc card-id (fnil inc 0)))
                        {}
                        (apply concat (vals zones)))]
    (into []
          (keep (fn [{:keys [card/id count]}]
                  (let [assigned  (get zone-assigned id 0)
                        remaining (- count assigned)]
                    (when (pos? remaining)
                      (let [card-def (get cards/card-by-id id)]
                        {:card/id   id
                         :card/name (:card/name card-def)
                         :count     remaining})))))
          deck)))


(rf/reg-sub
  ::player-zone-pool
  :<- [::editing-player]
  (fn [player _]
    (compute-zone-pool player)))


(rf/reg-sub
  ::opponent-zone-pool
  :<- [::editing-opponent]
  (fn [opponent _]
    (compute-zone-pool opponent)))


(rf/reg-sub
  ::player-zones
  :<- [::editing-player]
  (fn [player _]
    (or (:zones player) {})))


(rf/reg-sub
  ::opponent-zones
  :<- [::editing-opponent]
  (fn [opponent _]
    (or (:zones opponent) {})))


;; === Library-top subscriptions ===

(defn unordered-pool
  "Compute the unordered pool: cards from compute-zone-pool that are NOT in library-top.
   This represents the clickable cards available to add to library-top.
   side-config is a map with :deck, :zones, :library-top."
  [side-config]
  (let [zone-pool (compute-zone-pool side-config)
        lib-top   (or (:library-top side-config) [])
        ;; Count how many of each card-id are in library-top
        lib-top-counts (reduce
                         (fn [acc card-id]
                           (update acc card-id (fnil inc 0)))
                         {}
                         lib-top)]
    (into []
          (keep (fn [{:keys [card/id count] :as entry}]
                  (let [in-lib-top (get lib-top-counts id 0)
                        remaining (- count in-lib-top)]
                    (when (pos? remaining)
                      (assoc entry :count remaining))))
                zone-pool))))


(rf/reg-sub
  ::player-library-top
  :<- [::editing-player]
  (fn [player _]
    (or (:library-top player) [])))


(rf/reg-sub
  ::opponent-library-top
  :<- [::editing-opponent]
  (fn [opponent _]
    (or (:library-top opponent) [])))


(rf/reg-sub
  ::player-unordered-pool
  :<- [::editing-player]
  (fn [player _]
    (unordered-pool player)))


(rf/reg-sub
  ::opponent-unordered-pool
  :<- [::editing-opponent]
  (fn [opponent _]
    (unordered-pool opponent)))
