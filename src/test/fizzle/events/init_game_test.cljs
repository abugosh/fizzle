(ns fizzle.events.init-game-test
  "Tests for game initialization - player library and starting hand."
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [clojure.set]
    [datascript.core :as d]
    [datascript.db :as ds-db]
    [fizzle.bots.protocol :as bot]
    [fizzle.db.queries :as q]
    [fizzle.db.storage :as storage]
    [fizzle.events.init :as game-init]
    [fizzle.events.setup :as setup]))


;; Mock localStorage so stops tests are isolated from any shared global state
(def ^:private mock-storage (atom {}))


(def ^:private create-mock-storage
  (fn []
    #js {:getItem (fn [key] (get @mock-storage key nil))
         :setItem (fn [key value] (swap! mock-storage assoc key value) nil)
         :removeItem (fn [key] (swap! mock-storage dissoc key) nil)
         :clear (fn [] (reset! mock-storage {}) nil)
         :length 0
         :key (fn [_] nil)}))


(set! js/localStorage (create-mock-storage))


(use-fixtures :each
  {:before (fn []
             (set! js/localStorage (create-mock-storage))
             (reset! mock-storage {}))
   :after (fn [] (reset! mock-storage {}))})


;; === Test helpers ===

(defn init-and-get-db
  "Initialize game and return the datascript db.
   Calls the init-game-state function directly with default config."
  []
  (:game/db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                        :bot-deck (bot/bot-deck :goldfish)})))


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
      ;; Protection (1)
      (is (= 1 (get card-counts :orims-chant))
          "Should have 1 Orim's Chant in deck")
      ;; Enchantment interaction (1)
      (is (= 1 (get card-counts :ray-of-revelation))
          "Should have 1 Ray of Revelation in deck")
      ;; Card selection (1)
      (is (= 1 (get card-counts :flash-of-insight))
          "Should have 1 Flash of Insight in deck"))))


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
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})
          game-db (:game/db app-db)]
      ;; The game db must be a datascript db
      (is (instance? ds-db/DB game-db)
          "Game db must be a datascript database")
      ;; Essential player state must exist
      (is (pos-int? (q/get-player-eid game-db :player-1))
          "Player 1 must exist in initialized db")
      ;; Game state entity must exist
      (is (map? (q/get-game-state game-db))
          "Game state must exist in initialized db"))))


(deftest test-init-game-state-structural-validation
  ;; Bug caught: wrong initial game state values
  (testing "init-game-state sets correct starting values per MTG rules"
    (let [db (init-and-get-db)
          game-state (q/get-game-state db)]
      ;; Life totals
      (is (= 20 (q/get-life-total db :player-1))
          "Player should start at 20 life")
      ;; Turn and phase
      (is (= 1 (:game/turn game-state))
          "Game should start on turn 1")
      (is (= :main1 (:game/phase game-state))
          "Game should start in main phase 1")
      ;; Land plays
      (let [player-eid (q/get-player-eid db :player-1)
            land-plays (d/q '[:find ?plays .
                              :in $ ?e
                              :where [?e :player/land-plays-left ?plays]]
                            db player-eid)]
        (is (= 1 land-plays)
            "Player should start with 1 land play"))
      ;; Mana pool starts empty
      (let [pool (q/get-mana-pool db :player-1)]
        (is (every? zero? (vals pool))
            "Mana pool should start with all zeros"))
      ;; Storm count starts at 0
      (is (= 0 (q/get-storm-count db :player-1))
          "Storm count should start at 0"))))


(deftest test-init-game-state-includes-active-screen
  (testing "init-game-state returns :active-screen :opening-hand"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})]
      (is (= :opening-hand (:active-screen app-db))
          "Active screen should be :opening-hand"))))


;; === Decklist constant tests ===

(deftest test-iggy-pop-decklist-main-has-60-cards
  (testing "iggy-pop-decklist main deck sums to exactly 60 cards"
    (let [main (:deck/main setup/iggy-pop-decklist)
          total (reduce + (map :count main))]
      (is (= 60 total)
          "Main deck should have exactly 60 cards"))))


(deftest test-iggy-pop-decklist-side-has-15-cards
  (testing "iggy-pop-decklist sideboard sums to exactly 15 cards"
    (let [side (:deck/side setup/iggy-pop-decklist)
          total (reduce + (map :count side))]
      (is (= 15 total)
          "Sideboard should have exactly 15 cards"))))


;; === Config-based init tests ===

(deftest test-init-game-state-with-custom-deck
  (testing "init-game-state works with a custom small deck config"
    (let [custom-deck [{:card/id :dark-ritual :count 4}
                       {:card/id :island :count 3}]
          app-db (game-init/init-game-state {:main-deck custom-deck})
          db (:game/db app-db)
          library (get-library-objects db :player-1)
          hand (get-hand-objects db :player-1)]
      (is (= 7 (+ (count library) (count hand)))
          "Total cards should equal deck size (7)"))))


;; === Must-Contain / Opening Hand Tests ===

(deftest test-init-empty-must-contain-returns-opening-hand
  (testing "init-game-state with empty must-contain returns :opening-hand"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {}})]
      (is (= :opening-hand (:active-screen app-db))
          "Should return :opening-hand screen")
      (is (= 7 (count (get-hand-objects (:game/db app-db) :player-1)))
          "Hand should have 7 cards")
      (is (= 53 (count (get-library-objects (:game/db app-db) :player-1)))
          "Library should have 53 cards"))))


(deftest test-init-must-contain-places-sculpted-cards
  (testing "init-game-state with :must-contain {:dark-ritual 2} places 2 dark-rituals in hand"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {:dark-ritual 2}})
          db (:game/db app-db)
          hand (get-hand-objects db :player-1)
          hand-card-ids (map #(get-in % [:object/card :card/id]) hand)
          dr-count (count (filter #(= :dark-ritual %) hand-card-ids))]
      (is (= 7 (count hand))
          "Hand should still have 7 cards")
      (is (= 2 dr-count)
          "Hand should contain exactly 2 Dark Rituals")
      (is (= 53 (count (get-library-objects db :player-1)))
          "Library should have 53 cards"))))


(deftest test-init-must-contain-all-seven-sculpted
  (testing "init-game-state with must-contain totaling 7 places all sculpted, 0 random"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {:dark-ritual 4 :cabal-ritual 3}})
          db (:game/db app-db)
          hand (get-hand-objects db :player-1)
          hand-card-ids (frequencies (map #(get-in % [:object/card :card/id]) hand))]
      (is (= 7 (count hand))
          "Hand should have 7 cards")
      (is (= 4 (get hand-card-ids :dark-ritual))
          "Hand should have 4 Dark Rituals")
      (is (= 3 (get hand-card-ids :cabal-ritual))
          "Hand should have 3 Cabal Rituals")
      (is (= 53 (count (get-library-objects db :player-1)))
          "Library should have 53 cards"))))


(deftest test-init-must-contain-single-copy-card
  (testing "init-game-state with :must-contain {:orims-chant 1} places 1 in hand"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {:orims-chant 1}})
          db (:game/db app-db)
          hand (get-hand-objects db :player-1)
          hand-card-ids (map #(get-in % [:object/card :card/id]) hand)
          oc-count (count (filter #(= :orims-chant %) hand-card-ids))]
      (is (= 7 (count hand))
          "Hand should have 7 cards")
      (is (= 1 oc-count)
          "Hand should contain exactly 1 Orim's Chant"))))


(deftest test-init-opening-hand-mulligan-count-starts-at-zero
  (testing ":opening-hand/mulligan-count initialized to 0"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})]
      (is (= 0 (:opening-hand/mulligan-count app-db))
          "Mulligan count should start at 0"))))


(deftest test-init-opening-hand-phase-starts-at-viewing
  (testing ":opening-hand/phase initialized to :viewing"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})]
      (is (= :viewing (:opening-hand/phase app-db))
          "Opening hand phase should start at :viewing"))))


(deftest test-init-opening-hand-sculpted-ids-empty-when-no-must-contain
  (testing ":opening-hand/sculpted-ids is empty set when no must-contain"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {}})]
      (is (= #{} (:opening-hand/sculpted-ids app-db))
          "Sculpted IDs should be empty set"))))


(deftest test-init-opening-hand-sculpted-ids-match-sculpted-objects
  (testing ":opening-hand/sculpted-ids contains UUIDs of sculpted hand objects"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {:dark-ritual 2}})
          db (:game/db app-db)
          sculpted-ids (:opening-hand/sculpted-ids app-db)
          hand (get-hand-objects db :player-1)
          ;; Find hand objects that match sculpted IDs
          sculpted-objs (filter #(contains? sculpted-ids (:object/id %)) hand)]
      (is (= 2 (count sculpted-ids))
          "Should have 2 sculpted IDs")
      (is (= 2 (count sculpted-objs))
          "Should find 2 matching objects in hand")
      (is (every? #(= :dark-ritual (get-in % [:object/card :card/id])) sculpted-objs)
          "All sculpted objects should be Dark Rituals"))))


(deftest test-init-sculpted-cards-not-in-library
  (testing "sculpted cards are not present in library"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain {:dark-ritual 2}})
          db (:game/db app-db)
          sculpted-ids (:opening-hand/sculpted-ids app-db)
          library (get-library-objects db :player-1)
          lib-obj-ids (set (map :object/id library))]
      (is (empty? (clojure.set/intersection sculpted-ids lib-obj-ids))
          "No sculpted object IDs should appear in library"))))


(deftest test-init-opening-hand-must-contain-stored
  (testing ":opening-hand/must-contain stored in app-db matching input"
    (let [mc {:dark-ritual 2 :lions-eye-diamond 1}
          app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                             :must-contain mc})]
      (is (= mc (:opening-hand/must-contain app-db))
          "Must-contain config should be stored in app-db"))))


(deftest test-init-opening-hand-must-contain-empty
  (testing ":opening-hand/must-contain stored as {} when not specified"
    (let [app-db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)})]
      (is (= {} (:opening-hand/must-contain app-db))
          "Must-contain should default to empty map"))))


(deftest test-init-must-contain-small-custom-deck
  (testing "must-contain works with small custom deck"
    (let [app-db (game-init/init-game-state {:main-deck [{:card/id :dark-ritual :count 4}
                                                         {:card/id :island :count 3}]
                                             :must-contain {:dark-ritual 2}})
          db (:game/db app-db)
          hand (get-hand-objects db :player-1)
          hand-card-ids (frequencies (map #(get-in % [:object/card :card/id]) hand))]
      (is (= 7 (count hand))
          "Hand should have 7 cards (entire deck)")
      (is (= 4 (get hand-card-ids :dark-ritual 0))
          "Hand should have all 4 Dark Rituals (entire 7-card deck is drawn)"))))


;; === Opponent initialization tests (bot-deck driven) ===

(deftest test-opponent-starts-with-empty-battlefield
  (testing "opponent starts with no cards on battlefield (goldfish plays from hand)"
    (let [db (init-and-get-db)
          battlefield (q/get-objects-in-zone db :player-2 :battlefield)]
      (is (= 0 (count battlefield))
          "Opponent should have no permanents on battlefield"))))


(deftest test-opponent-starts-with-7-card-hand
  (testing "opponent starts with 7-card opening hand drawn from library"
    (let [db (init-and-get-db)
          hand (get-hand-objects db :player-2)]
      (is (= 7 (count hand))
          "Opponent hand should have 7 cards"))))


(deftest test-opponent-library-populated-from-bot-deck
  (testing "opponent library has 53 cards (60 minus 7-card hand)"
    (let [db (init-and-get-db)
          library (get-library-objects db :player-2)]
      (is (= 53 (count library))
          "Opponent library should have 53 cards after drawing 7"))))


(deftest test-opponent-library-contains-basic-lands
  (testing "opponent library contains basic lands from goldfish deck"
    (let [db (init-and-get-db)
          library (get-library-objects db :player-2)
          card-types (set (map #(get-in % [:object/card :card/id]) library))]
      (is (contains? card-types :plains) "Should contain Plains")
      (is (contains? card-types :island) "Should contain Island")
      (is (contains? card-types :swamp) "Should contain Swamp")
      (is (contains? card-types :mountain) "Should contain Mountain")
      (is (contains? card-types :forest) "Should contain Forest"))))


(deftest test-player-battlefield-empty
  (testing "player battlefield starts empty (isolation check)"
    (let [db (init-and-get-db)
          battlefield (q/get-objects-in-zone db :player-1 :battlefield)]
      (is (= 0 (count battlefield))
          "Player battlefield should start empty"))))


;; === Phase stops initialization tests ===

(deftest test-init-loads-opponent-stops-onto-human-entity
  (testing "After game init, human entity has :player/opponent-stops from localStorage"
    ;; Save specific opponent-stops to localStorage before init
    (storage/save-stops! {:player #{:main1 :main2} :opponent-stops #{:end :upkeep}})
    (let [db (:game/db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                                   :bot-deck (bot/bot-deck :goldfish)}))
          human-eid (q/get-player-eid db :player-1)
          opp-stops (:player/opponent-stops (d/pull db [:player/opponent-stops] human-eid))]
      (is (= #{:end :upkeep} opp-stops)
          "Human entity should have :player/opponent-stops loaded from localStorage"))))


(deftest test-init-bot-stops-unaffected-by-localstorage
  (testing "After game init, bot entity's :player/stops is purely bot-derived, not from localStorage"
    ;; Save opponent-stops to localStorage — should NOT leak to bot entity
    (storage/save-stops! {:player #{:main1} :opponent-stops #{:end :combat}})
    (let [db (:game/db (game-init/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                                                   :bot-deck (bot/bot-deck :goldfish)}))
          bot-eid (q/get-player-eid db :player-2)
          bot-stops (:player/stops (d/pull db [:player/stops] bot-eid))]
      (is (= (bot/bot-stops :goldfish) bot-stops)
          "Bot's :player/stops should equal goldfish bot-derived stops, not localStorage opponent-stops")
      (is (not (contains? bot-stops :end))
          ":end should not appear in bot stops — it came from localStorage opponent-stops, not phase-actions"))))
