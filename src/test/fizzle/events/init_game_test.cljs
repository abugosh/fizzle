(ns fizzle.events.init-game-test
  "Tests for game initialization - player library and starting hand."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.db :as ds-db]
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
      ;; Rituals (8)
      (is (= 4 (get card-counts :dark-ritual))
          "Should have 4 Dark Rituals in deck")
      (is (= 4 (get card-counts :cabal-ritual))
          "Should have 4 Cabal Rituals in deck")
      ;; Win condition (4)
      (is (= 4 (get card-counts :brain-freeze))
          "Should have 4 Brain Freezes in deck")
      ;; Lands (12)
      (is (= 3 (get card-counts :city-of-brass))
          "Should have 3 City of Brass in deck")
      (is (= 4 (get card-counts :gemstone-mine))
          "Should have 4 Gemstone Mines in deck")
      (is (= 3 (get card-counts :polluted-delta))
          "Should have 3 Polluted Deltas in deck")
      (is (= 1 (get card-counts :underground-river))
          "Should have 1 Underground River in deck")
      (is (= 2 (get card-counts :cephalid-coliseum))
          "Should have 2 Cephalid Coliseums in deck")
      (is (= 2 (get card-counts :island))
          "Should have 2 Islands in deck")
      (is (= 1 (get card-counts :swamp))
          "Should have 1 Swamp in deck")
      ;; Mana acceleration (8)
      (is (= 4 (get card-counts :lotus-petal))
          "Should have 4 Lotus Petals in deck")
      (is (= 4 (get card-counts :lions-eye-diamond))
          "Should have 4 Lion's Eye Diamonds in deck")
      ;; Card filtering (9)
      (is (= 2 (get card-counts :careful-study))
          "Should have 2 Careful Studies in deck")
      (is (= 3 (get card-counts :mental-note))
          "Should have 3 Mental Notes in deck")
      (is (= 4 (get card-counts :opt))
          "Should have 4 Opts in deck")
      ;; Tutors (4)
      (is (= 4 (get card-counts :intuition))
          "Should have 4 Intuitions in deck")
      ;; Flashback / Card advantage (4)
      (is (= 3 (get card-counts :deep-analysis))
          "Should have 3 Deep Analysis in deck")
      (is (= 1 (get card-counts :recoup))
          "Should have 1 Recoup in deck")
      ;; Graveyard recursion (4)
      (is (= 4 (get card-counts :ill-gotten-gains))
          "Should have 4 Ill-Gotten Gains in deck")
      ;; Substitutes for unimplemented cards (3)
      ;; TODO: Replace with Flash of Insight, Orim's Chant, Ray of Revelation
      (is (= 3 (get card-counts :merchant-scroll))
          "Should have 3 Merchant Scrolls (substitutes) in deck"))))


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


;; === Game State Initialization Validation Tests ===

(deftest test-init-game-state-returns-non-nil-db
  ;; Bug caught: Crash on nil db when game state not properly initialized
  (testing "init-game-state returns valid, non-nil game db"
    (let [app-db (game/init-game-state)
          game-db (:game/db app-db)]
      ;; The game db must never be nil
      (is (some? game-db)
          "Game db must not be nil after initialization")
      ;; The game db must be a datascript db
      (is (instance? ds-db/DB game-db)
          "Game db must be a datascript database")
      ;; Essential player state must exist
      (is (some? (q/get-player-eid game-db :player-1))
          "Player 1 must exist in initialized db")
      ;; Game state entity must exist
      (is (some? (first (q/get-game-state game-db)))
          "Game state must exist in initialized db"))))
