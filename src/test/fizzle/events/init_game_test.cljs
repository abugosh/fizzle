(ns fizzle.events.init-game-test
  "Tests for game initialization - player library and starting hand."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn init-and-get-db
  "Initialize game and return the datascript db.
   Calls the init-game-state function directly."
  []
  (:game/db (game/init-game-state)))


(defn get-library-objects
  "Get all objects in player's library."
  [db player-id]
  (q/get-objects-in-zone db player-id :library))


(defn get-hand-objects
  "Get all objects in player's hand."
  [db player-id]
  (q/get-objects-in-zone db player-id :hand))


;; === Library and deck initialization tests ===

(deftest test-total-deck-has-60-cards
  (testing "total deck (library + hand) has 60 cards"
    (let [db (init-and-get-db)
          library (get-library-objects db :player-1)
          hand (get-hand-objects db :player-1)]
      (is (= 60 (+ (count library) (count hand)))
          "Library + hand should total 60 cards"))))


(deftest test-deck-cards-have-correct-distribution
  (testing "deck (library + hand) contains 12x each of 5 card types"
    (let [db (init-and-get-db)
          library (get-library-objects db :player-1)
          hand (get-hand-objects db :player-1)
          all-cards (concat library hand)
          ;; Group by card-id and count
          card-counts (->> all-cards
                           (map #(get-in % [:object/card :card/id]))
                           frequencies)]
      (is (= 12 (get card-counts :dark-ritual))
          "Should have 12 Dark Rituals in deck")
      (is (= 12 (get card-counts :cabal-ritual))
          "Should have 12 Cabal Rituals in deck")
      (is (= 12 (get card-counts :brain-freeze))
          "Should have 12 Brain Freezes in deck")
      (is (= 12 (get card-counts :city-of-brass))
          "Should have 12 City of Brass in deck")
      (is (= 12 (get card-counts :gemstone-mine))
          "Should have 12 Gemstone Mines in deck"))))


;; === Starting hand tests ===

(deftest test-player-starts-with-7-card-hand
  (testing "player starts with 7-card hand drawn from library"
    (let [db (init-and-get-db)
          hand (get-hand-objects db :player-1)]
      (is (= 7 (count hand))
          "Player hand should have 7 cards"))))


(deftest test-library-has-53-cards-after-initial-draw
  (testing "library has 53 cards remaining after 7-card draw"
    (let [db (init-and-get-db)
          library (get-library-objects db :player-1)]
      ;; After drawing 7, 60 - 7 = 53 remain
      (is (= 53 (count library))
          "Library should have 53 cards after initial draw"))))
