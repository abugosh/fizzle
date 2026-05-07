(ns fizzle.events.scenario-init-test
  "Tests for init-from-scenario: builds a playable Datascript game from a scenario config.
   Tests verify the shared callees (ADR-035 parallel path) produce correct game state."
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [clojure.set]
    [datascript.core :as d]
    [datascript.db :as ds-db]
    [fizzle.db.queries :as q]
    [fizzle.events.scenario :as scenario]))


;; === Mock localStorage ===

(def ^:private mock-store (atom {}))


(defn- create-mock-storage
  []
  #js {:getItem  (fn [key] (get @mock-store key nil))
       :setItem  (fn [key value] (swap! mock-store assoc key value) nil)
       :removeItem (fn [key] (swap! mock-store dissoc key) nil)
       :clear    (fn [] (reset! mock-store {}) nil)
       :length   0
       :key      (fn [_] nil)})


(set! js/localStorage (create-mock-storage))


(use-fixtures :each
  {:before (fn []
             (set! js/localStorage (create-mock-storage))
             (reset! mock-store {}))
   :after  (fn [] (reset! mock-store {}))})


;; === Minimal scenario fixtures ===

(def ^:private minimal-scenario
  "One card in hand, two in library — simplest possible scenario."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000001"
   :scenario/title   "Minimal"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 3}]
                      :zones       {:hand [:dark-ritual]
                                    :graveyard []
                                    :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private custom-mana-scenario
  "Scenario with non-empty mana pool."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000002"
   :scenario/title   "Custom Mana"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :mana-pool   {:white 0 :blue 0 :black 3 :red 0 :green 0 :colorless 0}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private custom-life-scenario
  "Scenario with non-default life totals."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000003"
   :scenario/title   "Custom Life"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        7}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       13}
   :scenario/phase   :main1})


(def ^:private library-top-scenario
  "Scenario with library-top ordering specified."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000004"
   :scenario/title   "Library Top"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 3}
                                    {:card/id :lotus-petal :count 3}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top [:dark-ritual :lotus-petal]
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private empty-zones-scenario
  "Scenario with no explicit zone assignments — all cards go to library."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000005"
   :scenario/title   "Empty Zones"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private bot-archetype-scenario
  "Scenario with non-goldfish bot archetype."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000006"
   :scenario/title   "Burn Bot"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :burn
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private custom-phase-scenario
  "Scenario with non-default game phase."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000007"
   :scenario/title   "Combat Phase"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :combat})


;; === Tests ===

(deftest test-init-returns-valid-app-db
  (testing "init-from-scenario returns app-db with a valid Datascript DB"
    (let [app-db (scenario/init-from-scenario minimal-scenario)
          game-db (:game/db app-db)]
      (is (instance? ds-db/DB game-db)
          "game/db must be a valid Datascript database")
      (is (pos-int? (q/get-player-eid game-db :player-1))
          "player-1 must exist in the initialized db")
      (is (pos-int? (q/get-player-eid game-db :player-2))
          "player-2 must exist in the initialized db")
      (is (map? (q/get-game-state game-db))
          "game state entity must exist in the initialized db"))))


(deftest test-init-active-screen-is-game
  (testing "init-from-scenario returns :active-screen :game (skips opening-hand)"
    (let [app-db (scenario/init-from-scenario minimal-scenario)]
      (is (= :game (:active-screen app-db))
          "active-screen should be :game, not :opening-hand"))))


(deftest test-init-hand-has-specified-card
  (testing "init-from-scenario: card specified in :zones :hand appears in hand"
    (let [app-db (scenario/init-from-scenario minimal-scenario)
          db (:game/db app-db)
          hand (q/get-objects-in-zone db :player-1 :hand)]
      (is (= 1 (count hand))
          "hand should have 1 card as specified by scenario")
      (is (= :dark-ritual (get-in (first hand) [:object/card :card/id]))
          "hand card should be dark-ritual"))))


(deftest test-init-library-has-remaining-cards
  (testing "init-from-scenario: cards not in zones go to library"
    (let [app-db (scenario/init-from-scenario minimal-scenario)
          db (:game/db app-db)
          library (q/get-objects-in-zone db :player-1 :library)]
      ;; Deck has 3 dark-rituals, 1 is in hand, 2 remain in library
      (is (= 2 (count library))
          "library should have 2 remaining cards"))))


(deftest test-deck-accounting-hand-card-not-in-library
  (testing "card assigned to hand is NOT also present in library"
    (let [app-db (scenario/init-from-scenario minimal-scenario)
          db (:game/db app-db)
          hand (q/get-objects-in-zone db :player-1 :hand)
          library (q/get-objects-in-zone db :player-1 :library)
          hand-ids (set (map :object/id hand))
          lib-ids (set (map :object/id library))]
      (is (empty? (clojure.set/intersection hand-ids lib-ids))
          "no object ID should appear in both hand and library"))))


(deftest test-init-custom-mana-pool
  (testing "init-from-scenario sets :player/mana-pool from scenario config"
    (let [app-db (scenario/init-from-scenario custom-mana-scenario)
          db (:game/db app-db)
          pool (q/get-mana-pool db :player-1)]
      (is (= 3 (:black pool))
          "black mana should be 3 as specified in scenario")
      (is (= 0 (:white pool))
          "white mana should be 0"))))


(deftest test-init-custom-life
  (testing "init-from-scenario sets life totals from scenario config"
    (let [app-db (scenario/init-from-scenario custom-life-scenario)
          db (:game/db app-db)]
      (is (= 7 (q/get-life-total db :player-1))
          "player-1 life should be 7 as specified in scenario")
      (is (= 13 (q/get-life-total db :player-2))
          "player-2 life should be 13 as specified in scenario"))))


(deftest test-init-library-top-ordering
  (testing "init-from-scenario: library-top cards appear at positions 0..N-1"
    (let [app-db (scenario/init-from-scenario library-top-scenario)
          db (:game/db app-db)
          library (q/get-objects-in-zone db :player-1 :library)
          sorted-lib (sort-by :object/position library)
          top-two (take 2 sorted-lib)]
      (is (= 6 (count library))
          "library should have all 6 deck cards (none in other zones)")
      (is (= :dark-ritual (get-in (first top-two) [:object/card :card/id]))
          "position-0 card should be dark-ritual (first in library-top)")
      (is (= :lotus-petal (get-in (second top-two) [:object/card :card/id]))
          "position-1 card should be lotus-petal (second in library-top)"))))


(deftest test-init-empty-zones-all-cards-in-library
  (testing "init-from-scenario with all empty zones: all deck cards go to library"
    (let [app-db (scenario/init-from-scenario empty-zones-scenario)
          db (:game/db app-db)
          library (q/get-objects-in-zone db :player-1 :library)
          hand (q/get-objects-in-zone db :player-1 :hand)
          graveyard (q/get-objects-in-zone db :player-1 :graveyard)]
      (is (= 4 (count library))
          "library should have all 4 deck cards")
      (is (= 0 (count hand))
          "hand should be empty")
      (is (= 0 (count graveyard))
          "graveyard should be empty"))))


(deftest test-init-bot-archetype
  (testing "init-from-scenario: opponent has correct :player/bot-archetype"
    (let [app-db (scenario/init-from-scenario bot-archetype-scenario)
          db (:game/db app-db)
          opp-eid (q/get-player-eid db :player-2)
          archetype (:player/bot-archetype (d/pull db [:player/bot-archetype] opp-eid))]
      (is (= :burn archetype)
          "opponent bot-archetype should be :burn as specified"))))


(deftest test-init-phase-setting
  (testing "init-from-scenario: game phase matches scenario config"
    (let [app-db (scenario/init-from-scenario custom-phase-scenario)
          db (:game/db app-db)
          game-state (q/get-game-state db)]
      (is (= :combat (:game/phase game-state))
          "game phase should be :combat as specified in scenario"))))


(deftest test-init-history-keys-present
  (testing "init-from-scenario includes history keys in returned app-db"
    (let [app-db (scenario/init-from-scenario minimal-scenario)]
      (is (= [] (:history/main app-db)) "history/main should be empty vector")
      (is (= {} (:history/forks app-db)) "history/forks should be empty map")
      (is (nil? (:history/current-branch app-db)) "history/current-branch should be nil")
      (is (= -1 (:history/position app-db)) "history/position should be -1"))))


;; === extract-scenario-from-game ===

(def ^:private multi-zone-scenario
  "Scenario with cards in hand, graveyard, and battlefield."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000010"
   :scenario/title   "Multi Zone"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 2}
                                    {:card/id :lotus-petal :count 2}
                                    {:card/id :swamp :count 2}]
                      :zones       {:hand         [:dark-ritual]
                                    :graveyard    [:lotus-petal]
                                    :battlefield  [:swamp]}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(deftest test-extract-scenario-hand-is-non-empty
  (testing "extract-scenario-from-game returns non-empty hand zone when cards are in hand"
    (let [app-db (scenario/init-from-scenario multi-zone-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "Extracted")
          hand (get-in extracted [:scenario/player :zones :hand])]
      (is (seq hand)
          "extracted player hand should be non-empty")
      (is (= [:dark-ritual] hand)
          "extracted hand should contain dark-ritual"))))


(deftest test-extract-scenario-graveyard-is-non-empty
  (testing "extract-scenario-from-game returns non-empty graveyard zone when cards are there"
    (let [app-db (scenario/init-from-scenario multi-zone-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "Extracted")
          gy (get-in extracted [:scenario/player :zones :graveyard])]
      (is (seq gy)
          "extracted player graveyard should be non-empty")
      (is (= [:lotus-petal] gy)
          "extracted graveyard should contain lotus-petal"))))


(deftest test-extract-scenario-battlefield-is-non-empty
  (testing "extract-scenario-from-game returns non-empty battlefield zone when cards are there"
    (let [app-db (scenario/init-from-scenario multi-zone-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "Extracted")
          bf (get-in extracted [:scenario/player :zones :battlefield])]
      (is (seq bf)
          "extracted player battlefield should be non-empty")
      (is (= [:swamp] bf)
          "extracted battlefield should contain swamp"))))


(deftest test-extract-scenario-title-preserved
  (testing "extract-scenario-from-game preserves the provided title"
    (let [app-db (scenario/init-from-scenario minimal-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "My Title")]
      (is (= "My Title" (:scenario/title extracted))
          "extracted scenario title should match provided title"))))


(deftest test-extract-scenario-deck-reconstructed
  (testing "extract-scenario-from-game reconstructs deck from all zones"
    (let [app-db (scenario/init-from-scenario multi-zone-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "Extracted")
          deck (get-in extracted [:scenario/player :deck])
          card-ids (set (map :card/id deck))]
      (is (seq deck)
          "extracted player deck should be non-empty")
      (is (contains? card-ids :dark-ritual)
          "deck should include dark-ritual")
      (is (contains? card-ids :lotus-petal)
          "deck should include lotus-petal")
      (is (contains? card-ids :swamp)
          "deck should include swamp"))))


;; === Regression: fizzle-scur — duplicate card-ids in same zone ===

(def ^:private duplicate-hand-scenario
  "Scenario with 2x Dark Ritual in hand to test deduplication bug."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000020"
   :scenario/title   "Duplicate Hand"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}
                                    {:card/id :swamp :count 2}]
                      :zones       {:hand [:dark-ritual :dark-ritual]
                                    :graveyard []
                                    :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(deftest test-extract-scenario-preserves-duplicate-cards-in-hand
  (testing "extract-scenario-from-game returns all copies of a card in the same zone"
    (let [app-db (scenario/init-from-scenario duplicate-hand-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "Duplicates")
          hand (get-in extracted [:scenario/player :zones :hand])]
      (is (= 2 (count hand))
          "hand should contain 2 cards (not deduplicated)")
      (is (= [:dark-ritual :dark-ritual] (sort hand))
          "both copies of dark-ritual should be present"))))


(deftest test-extract-scenario-preserves-duplicate-cards-in-deck-count
  (testing "extract-scenario-from-game reconstructs correct card counts in deck"
    (let [app-db (scenario/init-from-scenario duplicate-hand-scenario)
          game-db (:game/db app-db)
          extracted (scenario/extract-scenario-from-game game-db "Duplicates")
          deck (get-in extracted [:scenario/player :deck])
          dr-entry (first (filter #(= :dark-ritual (:card/id %)) deck))]
      (is (= 4 (:count dr-entry))
          "deck should show 4x Dark Ritual (all copies across all zones)"))))


;; === :random-draw integration tests (fizzle-mb4k) ===

(defn- count-objects-in-zone
  [game-db player-id zone]
  (count (d/q '[:find [?e ...]
                :in $ ?pid ?zone
                :where
                [?p :player/id ?pid]
                [?e :object/owner ?p]
                [?e :object/zone ?zone]]
              game-db player-id zone)))


(def ^:private random-draw-zero-scenario
  "Deck with 7 cards, no zones, no random-draw — all 7 go to library."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000030"
   :scenario/title   "Random Draw Zero"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}
                                    {:card/id :lotus-petal :count 3}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private random-draw-three-scenario
  "Deck with 7 cards, random-draw 3 — 3 go to hand, 4 stay in library."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000031"
   :scenario/title   "Random Draw Three"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}
                                    {:card/id :lotus-petal :count 3}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :random-draw 3
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private random-draw-exceeds-pool-scenario
  "Deck with 3 cards, random-draw 10 — draws all 3, no error."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000032"
   :scenario/title   "Random Draw Exceeds Pool"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 3}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top []
                      :random-draw 10
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private random-draw-with-library-top-scenario
  "Deck with 7 cards, library-top 2, random-draw 3 — library-top not in draw pool."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000033"
   :scenario/title   "Random Draw With Library Top"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}
                                    {:card/id :lotus-petal :count 3}]
                      :zones       {:hand [] :graveyard [] :battlefield []}
                      :library-top [:dark-ritual :lotus-petal]
                      :random-draw 3
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(def ^:private random-draw-with-zones-scenario
  "Deck 7 cards, 2 in hand zone, random-draw 2 — zone cards not in random draw pool."
  {:scenario/id      #uuid "aaaaaaaa-0000-0000-0000-000000000034"
   :scenario/title   "Random Draw With Zones"
   :scenario/player  {:deck        [{:card/id :dark-ritual :count 4}
                                    {:card/id :lotus-petal :count 3}]
                      :zones       {:hand [:dark-ritual :lotus-petal]
                                    :graveyard []
                                    :battlefield []}
                      :library-top []
                      :random-draw 2
                      :mana-pool   {}
                      :life        20}
   :scenario/opponent {:archetype  :goldfish
                       :deck       []
                       :zones      {:hand [] :graveyard [] :battlefield []}
                       :library-top []
                       :mana-pool  {}
                       :life       20}
   :scenario/phase   :main1})


(deftest test-random-draw-zero-leaves-library-unchanged
  (testing "random-draw 0 (absent key): all deck cards go to library, hand is empty"
    (let [app-db (scenario/init-from-scenario random-draw-zero-scenario)
          game-db (:game/db app-db)]
      (is (= 0 (count-objects-in-zone game-db :player-1 :hand))
          "hand should be empty when random-draw is not set")
      (is (= 7 (count-objects-in-zone game-db :player-1 :library))
          "library should have all 7 cards"))))


(deftest test-random-draw-three-puts-three-in-hand
  (testing "random-draw 3: hand has 3 random cards, library has the remaining 4"
    (let [app-db (scenario/init-from-scenario random-draw-three-scenario)
          game-db (:game/db app-db)]
      (is (= 3 (count-objects-in-zone game-db :player-1 :hand))
          "hand should have exactly 3 randomly drawn cards")
      (is (= 4 (count-objects-in-zone game-db :player-1 :library))
          "library should have the remaining 4 cards"))))


(deftest test-random-draw-total-cards-preserved
  (testing "random-draw: total cards in hand + library equals total deck size"
    (let [app-db (scenario/init-from-scenario random-draw-three-scenario)
          game-db (:game/db app-db)
          hand-count (count-objects-in-zone game-db :player-1 :hand)
          library-count (count-objects-in-zone game-db :player-1 :library)]
      (is (= 7 (+ hand-count library-count))
          "total cards across hand and library should equal the full deck size"))))


(deftest test-random-draw-exceeds-pool-draws-all-available
  (testing "random-draw > pool size: draws all available cards, no error"
    (let [app-db (scenario/init-from-scenario random-draw-exceeds-pool-scenario)
          game-db (:game/db app-db)]
      (is (= 3 (count-objects-in-zone game-db :player-1 :hand))
          "hand should have all 3 cards when random-draw exceeds pool size")
      (is (= 0 (count-objects-in-zone game-db :player-1 :library))
          "library should be empty when all cards were drawn"))))


(deftest test-random-draw-does-not-include-library-top-cards
  (testing "random-draw pool excludes library-top cards; library starts with top-ids"
    (let [app-db (scenario/init-from-scenario random-draw-with-library-top-scenario)
          game-db (:game/db app-db)
          hand-count (count-objects-in-zone game-db :player-1 :hand)
          library-count (count-objects-in-zone game-db :player-1 :library)
          library (q/get-objects-in-zone game-db :player-1 :library)
          sorted-lib (sort-by :object/position library)
          pos0-card (get-in (first sorted-lib) [:object/card :card/id])
          pos1-card (get-in (second sorted-lib) [:object/card :card/id])]
      ;; Deck=7, library-top=2, random-draw=3 draws from remaining 5
      ;; hand=3 (random draw), library=4 (2 library-top + 2 rest)
      (is (= 3 hand-count)
          "hand should have 3 randomly drawn cards")
      (is (= 4 library-count)
          "library should have 4 cards (2 library-top + 2 remaining)")
      (is (= :dark-ritual pos0-card)
          "position-0 in library should be the first library-top card")
      (is (= :lotus-petal pos1-card)
          "position-1 in library should be the second library-top card"))))


(deftest test-random-draw-does-not-include-zone-assigned-cards
  (testing "random-draw pool excludes zone-assigned cards; zone cards appear in their zones"
    (let [app-db (scenario/init-from-scenario random-draw-with-zones-scenario)
          game-db (:game/db app-db)
          hand-count (count-objects-in-zone game-db :player-1 :hand)
          library-count (count-objects-in-zone game-db :player-1 :library)]
      ;; Deck=7, zones hand=2, pool=5, random-draw=2 → hand total=4, library=3
      (is (= 4 hand-count)
          "hand should have 2 zone-assigned + 2 randomly drawn cards")
      (is (= 3 library-count)
          "library should have the 3 remaining cards"))))


(deftest test-random-draw-cards-not-duplicated-in-library
  (testing "cards drawn via random-draw do not also appear in library"
    (let [app-db (scenario/init-from-scenario random-draw-three-scenario)
          game-db (:game/db app-db)
          hand (q/get-objects-in-zone game-db :player-1 :hand)
          library (q/get-objects-in-zone game-db :player-1 :library)
          hand-obj-ids (set (map :object/id hand))
          lib-obj-ids (set (map :object/id library))]
      (is (empty? (clojure.set/intersection hand-obj-ids lib-obj-ids))
          "no object ID should appear in both hand and library"))))
