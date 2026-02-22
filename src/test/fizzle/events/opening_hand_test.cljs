(ns fizzle.events.opening-hand-test
  "Tests for opening hand events: mulligan, keep, bottom-card selection."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.events.game :as game]
    [fizzle.events.opening-hand :as opening-hand]
    [fizzle.events.setup :as setup]))


;; === Test helpers ===

(defn create-opening-hand-app-db
  "Create an app-db in the :opening-hand state.
   Optionally accepts :must-contain map."
  ([] (create-opening-hand-app-db {}))
  ([must-contain]
   (game/init-game-state {:main-deck (:deck/main setup/iggy-pop-decklist)
                          :must-contain must-contain})))


(defn hand-objects
  "Get all hand objects from an app-db."
  [app-db]
  (q/get-objects-in-zone (:game/db app-db) :player-1 :hand))


(defn library-objects
  "Get all library objects from an app-db."
  [app-db]
  (q/get-objects-in-zone (:game/db app-db) :player-1 :library))


(defn hand-card-ids
  "Get card-ids from hand objects."
  [app-db]
  (map #(get-in % [:object/card :card/id]) (hand-objects app-db)))


;; === Mulligan handler tests ===

(deftest test-mulligan-returns-seven-cards
  (testing "after mulligan, hand has exactly 7 cards"
    (let [app-db (create-opening-hand-app-db)
          result (opening-hand/mulligan-handler app-db)]
      (is (= 7 (count (hand-objects result)))
          "Hand should have 7 cards after mulligan"))))


(deftest test-mulligan-preserves-library-count
  (testing "library count unchanged after mulligan (cards returned then re-drawn)"
    (let [app-db (create-opening-hand-app-db)
          lib-before (count (library-objects app-db))
          result (opening-hand/mulligan-handler app-db)
          lib-after (count (library-objects result))]
      (is (= lib-before lib-after)
          "Library count should be the same before and after mulligan"))))


(deftest test-mulligan-increments-count
  (testing "mulligan-count goes from 0 to 1 to 2"
    (let [app-db (create-opening-hand-app-db)
          after-1 (opening-hand/mulligan-handler app-db)
          after-2 (opening-hand/mulligan-handler after-1)]
      (is (= 0 (:opening-hand/mulligan-count app-db))
          "Initial mulligan count should be 0")
      (is (= 1 (:opening-hand/mulligan-count after-1))
          "After first mulligan, count should be 1")
      (is (= 2 (:opening-hand/mulligan-count after-2))
          "After second mulligan, count should be 2"))))


(deftest test-mulligan-preserves-sculpted-card-ids
  (testing "after mulligan with must-contain {:dark-ritual 2}, hand has 2 dark-rituals"
    (let [app-db (create-opening-hand-app-db {:dark-ritual 2})
          result (opening-hand/mulligan-handler app-db)
          dr-count (count (filter #(= :dark-ritual %) (hand-card-ids result)))]
      (is (= 2 dr-count)
          "Hand should contain exactly 2 Dark Rituals after mulligan"))))


(deftest test-mulligan-sculpted-ids-track-current-hand
  (testing "after mulligan, sculpted-ids reference objects currently in hand"
    (let [app-db (create-opening-hand-app-db {:dark-ritual 2})
          result (opening-hand/mulligan-handler app-db)
          new-ids (:opening-hand/sculpted-ids result)
          hand (hand-objects result)
          hand-obj-ids (set (map :object/id hand))
          sculpted-in-hand (filter #(contains? new-ids (:object/id %)) hand)]
      (is (= 2 (count new-ids))
          "Should have 2 sculpted IDs after mulligan")
      (is (every? #(contains? hand-obj-ids %) new-ids)
          "All sculpted IDs must reference objects in hand")
      (is (every? #(= :dark-ritual (get-in % [:object/card :card/id])) sculpted-in-hand)
          "All sculpted objects should be Dark Rituals"))))


(deftest test-mulligan-with-empty-must-contain
  (testing "mulligan with no sculpting still returns 7 random cards"
    (let [app-db (create-opening-hand-app-db {})
          result (opening-hand/mulligan-handler app-db)]
      (is (= 7 (count (hand-objects result)))
          "Hand should have 7 cards")
      (is (= #{} (:opening-hand/sculpted-ids result))
          "Sculpted IDs should be empty"))))


(deftest test-mulligan-with-all-seven-sculpted
  (testing "must-contain totaling 7 — mulligan returns same 7 card-ids"
    (let [app-db (create-opening-hand-app-db {:dark-ritual 4 :cabal-ritual 3})
          result (opening-hand/mulligan-handler app-db)
          ids (frequencies (hand-card-ids result))]
      (is (= 7 (count (hand-objects result)))
          "Hand should have 7 cards")
      (is (= 4 (get ids :dark-ritual))
          "Should have 4 Dark Rituals")
      (is (= 3 (get ids :cabal-ritual))
          "Should have 3 Cabal Rituals"))))


(deftest test-mulligan-stays-on-opening-hand
  (testing ":active-screen remains :opening-hand, phase stays :viewing"
    (let [app-db (create-opening-hand-app-db)
          result (opening-hand/mulligan-handler app-db)]
      (is (= :opening-hand (:active-screen result))
          "Should stay on opening-hand screen")
      (is (= :viewing (:opening-hand/phase result))
          "Phase should remain :viewing"))))


(deftest test-consecutive-mulligans-produce-different-hands
  (testing "second mulligan draws different cards than first (respects shuffle)"
    (let [app-db (create-opening-hand-app-db)
          after-1 (opening-hand/mulligan-handler app-db)
          hand-1 (set (map :object/id (hand-objects after-1)))
          after-2 (opening-hand/mulligan-handler after-1)
          hand-2 (set (map :object/id (hand-objects after-2)))]
      (is (not= hand-1 hand-2)
          "Consecutive mulligans should produce different hands"))))


;; === Keep handler tests ===

(deftest test-keep-zero-mulligans-transitions-to-game
  (testing "keep with 0 mulligans transitions :active-screen to :game"
    (let [app-db (create-opening-hand-app-db)
          result (opening-hand/keep-handler app-db)]
      (is (= :game (:active-screen result))
          "Active screen should be :game"))))


(deftest test-keep-zero-mulligans-preserves-game-db
  (testing "keep with 0 mulligans preserves :game/db unchanged"
    (let [app-db (create-opening-hand-app-db)
          result (opening-hand/keep-handler app-db)]
      (is (identical? (:game/db app-db) (:game/db result))
          "Game db should be identical (unchanged)"))))


(deftest test-keep-with-mulligans-enters-bottoming
  (testing "keep after mulligans enters :bottoming phase"
    (let [app-db (create-opening-hand-app-db)
          after-mull (opening-hand/mulligan-handler app-db)
          result (opening-hand/keep-handler after-mull)]
      (is (= :opening-hand (:active-screen result))
          "Should stay on opening-hand screen")
      (is (= :bottoming (:opening-hand/phase result))
          "Phase should be :bottoming")
      (is (= #{} (:opening-hand/bottom-selection result))
          "Bottom selection should be initialized to empty set"))))


(deftest test-keep-preserves-hand
  (testing "hand cards unchanged during keep"
    (let [app-db (create-opening-hand-app-db)
          hand-before (set (map :object/id (hand-objects app-db)))
          result (opening-hand/keep-handler app-db)
          hand-after (set (map :object/id (hand-objects result)))]
      (is (= hand-before hand-after)
          "Hand object IDs should be unchanged"))))


(deftest test-keep-clears-opening-hand-keys
  (testing "keep with 0 mulligans clears all opening-hand keys"
    (let [app-db (create-opening-hand-app-db)
          result (opening-hand/keep-handler app-db)]
      (is (nil? (:opening-hand/mulligan-count result))
          "mulligan-count should be cleared")
      (is (nil? (:opening-hand/sculpted-ids result))
          "sculpted-ids should be cleared")
      (is (nil? (:opening-hand/phase result))
          "phase should be cleared")
      (is (nil? (:opening-hand/must-contain result))
          "must-contain should be cleared")
      (is (nil? (:opening-hand/bottom-selection result))
          "bottom-selection should be cleared"))))


;; === Toggle bottom selection tests ===

(defn create-bottoming-app-db
  "Create an app-db in the :bottoming phase (after 1 mulligan + keep)."
  []
  (let [app-db (create-opening-hand-app-db)
        after-mull (opening-hand/mulligan-handler app-db)]
    (opening-hand/keep-handler after-mull)))


(deftest test-toggle-adds-to-selection
  (testing "toggle adds object-id to bottom-selection"
    (let [app-db (create-bottoming-app-db)
          obj-id (:object/id (first (hand-objects app-db)))
          result (opening-hand/toggle-bottom-selection-handler app-db obj-id)]
      (is (contains? (:opening-hand/bottom-selection result) obj-id)
          "Object should be in bottom-selection"))))


(deftest test-toggle-removes-if-selected
  (testing "toggling already-selected id removes it"
    (let [app-db (create-bottoming-app-db)
          obj-id (:object/id (first (hand-objects app-db)))
          with-added (opening-hand/toggle-bottom-selection-handler app-db obj-id)
          result (opening-hand/toggle-bottom-selection-handler with-added obj-id)]
      (is (not (contains? (:opening-hand/bottom-selection result) obj-id))
          "Object should be removed from bottom-selection"))))


(deftest test-toggle-noop-when-not-bottoming
  (testing "toggle is no-op when phase is :viewing"
    (let [app-db (create-opening-hand-app-db)
          obj-id (:object/id (first (hand-objects app-db)))
          result (opening-hand/toggle-bottom-selection-handler app-db obj-id)]
      (is (nil? (:opening-hand/bottom-selection result))
          "Bottom selection should remain nil in :viewing phase"))))


;; === Confirm bottom selection tests ===

(deftest test-confirm-bottom-moves-cards
  (testing "selected cards move from hand to library"
    (let [app-db (create-bottoming-app-db)
          hand (hand-objects app-db)
          obj-id (:object/id (first hand))
          with-selected (assoc app-db :opening-hand/bottom-selection #{obj-id})
          result (opening-hand/confirm-bottom-handler with-selected)
          result-hand-ids (set (map :object/id (hand-objects result)))
          result-lib-ids (set (map :object/id (library-objects result)))]
      (is (= 6 (count (hand-objects result)))
          "Hand should have 6 cards (7 - 1 bottomed)")
      (is (not (contains? result-hand-ids obj-id))
          "Bottomed card should not be in hand")
      (is (contains? result-lib-ids obj-id)
          "Bottomed card should be in library"))))


(deftest test-confirm-bottom-noop-wrong-count
  (testing "confirm is no-op if selected count != mulligan-count"
    (let [app-db (create-bottoming-app-db)
          ;; mulligan-count is 1, but we select 0 cards
          result (opening-hand/confirm-bottom-handler app-db)]
      (is (= :opening-hand (:active-screen result))
          "Should still be on opening-hand screen")
      (is (= :bottoming (:opening-hand/phase result))
          "Phase should still be :bottoming"))))


(deftest test-confirm-bottom-transitions-to-game
  (testing "confirm transitions :active-screen to :game"
    (let [app-db (create-bottoming-app-db)
          obj-id (:object/id (first (hand-objects app-db)))
          with-selected (assoc app-db :opening-hand/bottom-selection #{obj-id})
          result (opening-hand/confirm-bottom-handler with-selected)]
      (is (= :game (:active-screen result))
          "Active screen should be :game"))))


(deftest test-confirm-bottom-clears-keys
  (testing "confirm clears all opening-hand keys"
    (let [app-db (create-bottoming-app-db)
          obj-id (:object/id (first (hand-objects app-db)))
          with-selected (assoc app-db :opening-hand/bottom-selection #{obj-id})
          result (opening-hand/confirm-bottom-handler with-selected)]
      (is (nil? (:opening-hand/mulligan-count result))
          "mulligan-count should be cleared")
      (is (nil? (:opening-hand/sculpted-ids result))
          "sculpted-ids should be cleared")
      (is (nil? (:opening-hand/phase result))
          "phase should be cleared")
      (is (nil? (:opening-hand/must-contain result))
          "must-contain should be cleared")
      (is (nil? (:opening-hand/bottom-selection result))
          "bottom-selection should be cleared"))))


(deftest test-confirm-bottom-specific-cards
  (testing "specific selected cards are in library and NOT in hand"
    (let [app-db (create-bottoming-app-db)
          hand (hand-objects app-db)
          obj-id (:object/id (first hand))
          with-selected (assoc app-db :opening-hand/bottom-selection #{obj-id})
          result (opening-hand/confirm-bottom-handler with-selected)
          result-hand-ids (set (map :object/id (hand-objects result)))]
      (is (not (contains? result-hand-ids obj-id))
          "Selected card should NOT be in hand after confirm"))))
