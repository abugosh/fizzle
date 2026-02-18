(ns fizzle.subs.setup
  (:require
    [clojure.string :as str]
    [fizzle.cards.iggy-pop :as cards]
    [re-frame.core :as rf]))


;; Card type lookup: card-id -> card definition
(def ^:private card-lookup
  (into {} (map (juxt :card/id identity) cards/all-cards)))


;; Layer 2: extraction subscriptions

(rf/reg-sub ::setup-config
            (fn [db _]
              (select-keys db [:setup/selected-deck :setup/main-deck :setup/sideboard
                               :setup/clock-turns :setup/must-contain
                               :setup/presets :setup/last-preset])))


(rf/reg-sub ::imported-decks
            (fn [db _]
              (:setup/imported-decks db)))


(rf/reg-sub ::available-decks
            :<- [::imported-decks]
            (fn [imported-decks _]
              (into [{:deck/id :iggy-pop :deck/name "Iggy Pop"}]
                    (map (fn [[_ deck]]
                           {:deck/id (:deck/id deck)
                            :deck/name (:deck/name deck)
                            :deck/source :imported}))
                    (or imported-decks {}))))


(rf/reg-sub ::selected-deck
            (fn [db _] (:setup/selected-deck db)))


(rf/reg-sub ::current-main
            (fn [db _] (:setup/main-deck db)))


(rf/reg-sub ::current-side
            (fn [db _] (:setup/sideboard db)))


(rf/reg-sub ::main-count
            (fn [db _]
              (reduce + 0 (map :count (or (:setup/main-deck db) [])))))


(rf/reg-sub ::side-count
            (fn [db _]
              (reduce + 0 (map :count (or (:setup/sideboard db) [])))))


(rf/reg-sub ::saved-presets
            (fn [db _]
              (keys (:setup/presets db))))


(rf/reg-sub ::clock-turns
            (fn [db _] (:setup/clock-turns db)))


(rf/reg-sub ::bot-archetype
            (fn [db _] (:setup/bot-archetype db)))


(rf/reg-sub ::last-preset
            (fn [db _] (:setup/last-preset db)))


;; Layer 3: derived subscriptions

(rf/reg-sub ::deck-valid?
            :<- [::main-count]
            (fn [main-count _]
              (>= main-count 60)))


(rf/reg-sub ::current-main-grouped
            :<- [::current-main]
            (fn [main-deck _]
              (when main-deck
                (let [entries (map (fn [{:keys [card/id count]}]
                                     (let [card-def (get card-lookup id)]
                                       {:card/id id
                                        :card/name (:card/name card-def)
                                        :count count
                                        :card/types (:card/types card-def)}))
                                   main-deck)
                      classify (fn [{:keys [card/types]}]
                                 (cond
                                   (contains? types :land) :land
                                   (contains? types :instant) :instant
                                   (contains? types :sorcery) :sorcery
                                   (contains? types :artifact) :artifact
                                   (contains? types :creature) :creature
                                   (contains? types :enchantment) :enchantment
                                   :else :other))]
                  (group-by classify entries)))))


(rf/reg-sub ::current-side-named
            :<- [::current-side]
            (fn [side-deck _]
              (when side-deck
                (mapv (fn [{:keys [card/id count]}]
                        (let [card-def (get card-lookup id)]
                          {:card/id id
                           :card/name (:card/name card-def)
                           :count count}))
                      side-deck))))


(rf/reg-sub ::must-contain
            (fn [db _]
              (:setup/must-contain db)))


(rf/reg-sub ::import-modal
            (fn [db _]
              (:setup/import-modal db)))


(rf/reg-sub ::import-valid?
            :<- [::import-modal]
            (fn [modal _]
              (and (some? modal)
                   (not (str/blank? (:name modal)))
                   (not (str/blank? (:text modal))))))


(rf/reg-sub ::must-contain-cards
            :<- [::must-contain]
            :<- [::current-main]
            (fn [[must-contain main-deck] _]
              (let [main-by-id (into {} (map (juxt :card/id identity) main-deck))]
                (mapv (fn [[card-id cnt]]
                        (let [card-def (get card-lookup card-id)
                              main-entry (get main-by-id card-id)]
                          {:card/id card-id
                           :card/name (:card/name card-def)
                           :count cnt
                           :max-count (:count main-entry)}))
                      must-contain))))
