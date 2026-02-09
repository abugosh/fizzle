(ns fizzle.subs.setup-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.events.setup :as setup]
    [fizzle.subs.setup :as subs]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- sub-value
  "Get subscription value by resetting app-db and deref'ing the subscription."
  [db sub-vec]
  (reset! rf-db/app-db db)
  @(rf/subscribe sub-vec))


(deftest test-deck-valid-true-for-60-15
  (testing "deck-valid? returns true for 60 main, 15 side"
    (let [db (setup/init-setup-handler {})]
      (is (true? (sub-value db [::subs/deck-valid?]))
          "Should be valid for default iggy-pop deck (60/15)"))))


(deftest test-deck-valid-false-for-59
  (testing "deck-valid? returns false when main has 59 cards"
    (let [db (-> (setup/init-setup-handler {})
                 ;; Move one card from side to main (side=14, main=61) then no...
                 ;; Actually we need main at 59. Move from main to side but side is 15.
                 ;; Move one from side to main first (side=14, main=61)
                 ;; then move two from main to side (side=16... no, test deck-valid for 59)
                 ;; Simplest: just set the main-deck directly
                 (assoc :setup/main-deck (rest (:deck/main cards/iggy-pop-decklist))))]
      ;; Now main has fewer than 60
      (is (false? (sub-value db [::subs/deck-valid?]))
          "Should be invalid when main != 60"))))


(deftest test-deck-valid-true-for-oversized
  (testing "deck-valid? returns true when main > 60"
    (let [db (-> (setup/init-setup-handler {})
                 (assoc :setup/main-deck
                        (conj (:deck/main cards/iggy-pop-decklist)
                              {:card/id :dark-ritual :count 2})))]
      (is (true? (sub-value db [::subs/deck-valid?]))
          "Should be valid for oversized main deck"))))


(deftest test-main-count-sums-correctly
  (testing "main-count sums all :count values"
    (let [db (setup/init-setup-handler {})]
      (is (= 60 (sub-value db [::subs/main-count]))
          "Main count should be 60 for default deck"))))


(deftest test-current-main-grouped-groups-by-type
  (testing "current-main-grouped groups cards by card type"
    (let [db (setup/init-setup-handler {})
          grouped (sub-value db [::subs/current-main-grouped])]
      ;; Should have land, instant, and sorcery groups at minimum
      (is (some? (:land grouped))
          "Should have land group")
      (is (some? (:instant grouped))
          "Should have instant group")
      (is (some? (:sorcery grouped))
          "Should have sorcery group")
      ;; Verify a specific card appears in the right group
      (let [land-ids (set (map :card/id (:land grouped)))]
        (is (contains? land-ids :island)
            "Island should be in land group")))))


;; === Must-Contain Subscriptions ===

(deftest test-must-contain-sub-returns-map
  (testing "must-contain subscription returns the must-contain map from db"
    (let [db (-> (setup/init-setup-handler {})
                 (assoc :setup/must-contain {:dark-ritual 2 :cabal-ritual 1}))]
      (is (= {:dark-ritual 2 :cabal-ritual 1}
             (sub-value db [::subs/must-contain]))
          "Should return the must-contain map"))))


(deftest test-must-contain-cards-subscription
  (testing "must-contain-cards returns enriched vec with card names and max counts"
    (let [db (-> (setup/init-setup-handler {})
                 (assoc :setup/must-contain {:dark-ritual 2 :cabal-ritual 1}))
          result (sub-value db [::subs/must-contain-cards])
          by-id (into {} (map (juxt :card/id identity) result))]
      (is (= 2 (count result))
          "Should have 2 entries")
      (is (= "Dark Ritual" (:card/name (get by-id :dark-ritual)))
          "Should include card name")
      (is (= 2 (:count (get by-id :dark-ritual)))
          "Should include count")
      (is (= 4 (:max-count (get by-id :dark-ritual)))
          "Should include max-count from main deck"))))
