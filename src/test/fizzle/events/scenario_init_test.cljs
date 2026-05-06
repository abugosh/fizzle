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
