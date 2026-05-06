(ns fizzle.events.scenario-deck-test
  "Tests for scenario deck selection and mutation events.
   Tests operate directly on handler functions for pure unit testing."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.scenario :as scenario]))


;; === Fixtures ===

(def ^:private iggy-pop-deck
  [{:card/id :dark-ritual :count 4}
   {:card/id :swamp :count 4}])


(def ^:private empty-db {})


(def ^:private db-with-editing
  {:scenario/editing {:scenario/player   {:deck []}
                      :scenario/opponent {:archetype :goldfish :deck []}}})


;; === ::select-player-deck ===

(deftest test-select-player-deck-populates-deck
  (testing "select-player-deck sets player deck on :scenario/editing"
    (let [result (scenario/select-player-deck-handler
                   db-with-editing
                   [nil iggy-pop-deck])]
      (is (= iggy-pop-deck
             (get-in result [:scenario/editing :scenario/player :deck]))
          "player deck should be set to provided deck list"))))


(deftest test-select-player-deck-creates-editing-when-absent
  (testing "select-player-deck initialises :scenario/editing when not present"
    (let [result (scenario/select-player-deck-handler
                   empty-db
                   [nil iggy-pop-deck])]
      (is (= iggy-pop-deck
             (get-in result [:scenario/editing :scenario/player :deck]))
          "player deck should be populated even with no prior editing"))))


;; === ::select-bot-archetype ===

(deftest test-select-bot-archetype-sets-archetype-and-deck
  (testing "select-bot-archetype sets opponent archetype and populates deck"
    (let [result (scenario/select-bot-archetype-handler
                   db-with-editing
                   [nil :burn])]
      (is (= :burn
             (get-in result [:scenario/editing :scenario/opponent :archetype]))
          "opponent archetype should be :burn")
      (is (seq (get-in result [:scenario/editing :scenario/opponent :deck]))
          "opponent deck should be non-empty"))))


(deftest test-select-bot-archetype-deck-matches-bot-definition
  (testing "deck populated by select-bot-archetype matches bot-deck output"
    (let [result (scenario/select-bot-archetype-handler
                   db-with-editing
                   [nil :goldfish])]
      (let [deck (get-in result [:scenario/editing :scenario/opponent :deck])]
        (is (vector? deck) "deck should be a vector")
        (is (every? #(and (:card/id %) (:count %)) deck)
            "each entry should have :card/id and :count")))))


;; === ::add-card ===

(deftest test-add-card-increments-existing-entry
  (testing "add-card increments count for card already in player deck"
    (let [db (assoc-in db-with-editing
                       [:scenario/editing :scenario/player :deck]
                       [{:card/id :dark-ritual :count 2}])
          result (scenario/add-card-handler
                   db
                   [nil {:side :player :card-id :dark-ritual}])]
      (is (= 3
             (some #(when (= :dark-ritual (:card/id %)) (:count %))
                   (get-in result [:scenario/editing :scenario/player :deck])))
          "count should increment from 2 to 3"))))


(deftest test-add-card-adds-new-entry
  (testing "add-card adds new entry for card not yet in player deck"
    (let [result (scenario/add-card-handler
                   db-with-editing
                   [nil {:side :player :card-id :dark-ritual}])]
      (is (= 1
             (some #(when (= :dark-ritual (:card/id %)) (:count %))
                   (get-in result [:scenario/editing :scenario/player :deck])))
          "new card should appear with count 1"))))


(deftest test-add-card-enforces-four-copy-limit-for-non-basic
  (testing "add-card does not exceed 4 copies for non-basic cards"
    (let [db (assoc-in db-with-editing
                       [:scenario/editing :scenario/player :deck]
                       [{:card/id :dark-ritual :count 4}])
          result (scenario/add-card-handler
                   db
                   [nil {:side :player :card-id :dark-ritual}])]
      (is (= 4
             (some #(when (= :dark-ritual (:card/id %)) (:count %))
                   (get-in result [:scenario/editing :scenario/player :deck])))
          "non-basic should be capped at 4 copies"))))


(deftest test-add-card-allows-unlimited-basics
  (testing "add-card allows more than 4 copies of basic lands"
    (let [db (assoc-in db-with-editing
                       [:scenario/editing :scenario/player :deck]
                       [{:card/id :swamp :count 4}])
          result (scenario/add-card-handler
                   db
                   [nil {:side :player :card-id :swamp}])]
      (is (= 5
             (some #(when (= :swamp (:card/id %)) (:count %))
                   (get-in result [:scenario/editing :scenario/player :deck])))
          "basic land should be allowed past 4 copies"))))


(deftest test-add-card-opponent-side
  (testing "add-card works for opponent side"
    (let [result (scenario/add-card-handler
                   db-with-editing
                   [nil {:side :opponent :card-id :mountain}])]
      (is (= 1
             (some #(when (= :mountain (:card/id %)) (:count %))
                   (get-in result [:scenario/editing :scenario/opponent :deck])))
          "card should be added to opponent deck"))))


;; === ::remove-card ===

(deftest test-remove-card-decrements-count
  (testing "remove-card decrements count from existing entry"
    (let [db (assoc-in db-with-editing
                       [:scenario/editing :scenario/player :deck]
                       [{:card/id :dark-ritual :count 3}])
          result (scenario/remove-card-handler
                   db
                   [nil {:side :player :card-id :dark-ritual}])]
      (is (= 2
             (some #(when (= :dark-ritual (:card/id %)) (:count %))
                   (get-in result [:scenario/editing :scenario/player :deck])))
          "count should decrement from 3 to 2"))))


(deftest test-remove-card-at-one-removes-entry
  (testing "remove-card with count=1 removes the entry entirely"
    (let [db (assoc-in db-with-editing
                       [:scenario/editing :scenario/player :deck]
                       [{:card/id :dark-ritual :count 1}])
          result (scenario/remove-card-handler
                   db
                   [nil {:side :player :card-id :dark-ritual}])]
      (is (nil? (some #(when (= :dark-ritual (:card/id %)) %)
                      (get-in result [:scenario/editing :scenario/player :deck])))
          "card with count=1 should be removed from deck"))))


(deftest test-remove-card-absent-is-noop
  (testing "remove-card for a card not in deck is a no-op"
    (let [db (assoc-in db-with-editing
                       [:scenario/editing :scenario/player :deck]
                       [{:card/id :dark-ritual :count 2}])
          result (scenario/remove-card-handler
                   db
                   [nil {:side :player :card-id :counterspell}])]
      (is (= [{:card/id :dark-ritual :count 2}]
             (get-in result [:scenario/editing :scenario/player :deck]))
          "deck should be unchanged when removing absent card"))))


;; === available-cards (pure helper) ===

(deftest test-available-cards-excludes-cards-at-four-copies
  (testing "available-cards excludes non-basic cards already at 4 copies"
    (let [deck [{:card/id :dark-ritual :count 4}
                {:card/id :swamp :count 2}]
          available (scenario/available-cards deck)]
      (is (not (contains? (set (map :card/id available)) :dark-ritual))
          "dark-ritual at 4 copies should not be in available-cards")
      (is (contains? (set (map :card/id available)) :swamp)
          "swamp with 2 copies should still be in available-cards"))))


(deftest test-available-cards-includes-basics-at-four-or-more
  (testing "available-cards always includes basic lands regardless of count"
    (let [deck [{:card/id :swamp :count 10}]
          available (scenario/available-cards deck)]
      (is (contains? (set (map :card/id available)) :swamp)
          "basic land should remain available even at 10 copies"))))


(deftest test-available-cards-only-contains-registry-cards
  (testing "available-cards returns only cards present in the card registry"
    (let [deck []
          available (scenario/available-cards deck)]
      (is (every? :card/id available)
          "every entry should have a :card/id")
      (is (every? :card/name available)
          "every entry should have a :card/name"))))
