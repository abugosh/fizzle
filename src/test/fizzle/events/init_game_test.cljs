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
  (testing "deck (library + hand) contains expected card distribution"
    (let [db (init-and-get-db)
          library (get-library-objects db :player-1)
          hand (get-hand-objects db :player-1)
          all-cards (concat library hand)
          ;; Group by card-id and count
          card-counts (->> all-cards
                           (map #(get-in % [:object/card :card/id]))
                           frequencies)]
      ;; 8x rituals
      (is (= 8 (get card-counts :dark-ritual))
          "Should have 8 Dark Rituals in deck")
      (is (= 8 (get card-counts :cabal-ritual))
          "Should have 8 Cabal Rituals in deck")
      ;; 4x win conditions and rainbow lands
      (is (= 4 (get card-counts :brain-freeze))
          "Should have 4 Brain Freezes in deck")
      (is (= 4 (get card-counts :city-of-brass))
          "Should have 4 City of Brass in deck")
      (is (= 4 (get card-counts :gemstone-mine))
          "Should have 4 Gemstone Mines in deck")
      ;; 4x fetchlands
      (is (= 4 (get card-counts :polluted-delta))
          "Should have 4 Polluted Deltas in deck")
      ;; 2x Cephalid Coliseum (threshold draw/discard)
      (is (= 2 (get card-counts :cephalid-coliseum))
          "Should have 2 Cephalid Coliseums in deck")
      ;; 4x Underground River (pain land for U/B)
      (is (= 4 (get card-counts :underground-river))
          "Should have 4 Underground Rivers in deck")
      ;; 2x basic islands
      (is (= 2 (get card-counts :island))
          "Should have 2 Islands in deck")
      ;; 4x mana acceleration
      (is (= 4 (get card-counts :lotus-petal))
          "Should have 4 Lotus Petals in deck")
      (is (= 4 (get card-counts :lions-eye-diamond))
          "Should have 4 Lion's Eye Diamonds in deck")
      ;; 4x card filtering
      (is (= 4 (get card-counts :careful-study))
          "Should have 4 Careful Studies in deck")
      (is (= 4 (get card-counts :mental-note))
          "Should have 4 Mental Notes in deck")
      ;; 2x tutors
      (is (= 2 (get card-counts :merchant-scroll))
          "Should have 2 Merchant Scrolls in deck")
      ;; 2x flashback card advantage
      (is (= 2 (get card-counts :deep-analysis))
          "Should have 2 Deep Analysis in deck"))))


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
