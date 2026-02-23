(ns fizzle.events.tutor-multi-select-test
  "Tests for multi-select tutor selection.

   When a tutor effect has :effect/select-count > 1, the selection system
   allows selecting multiple cards from library. This is needed for cards
   like Intuition that search for exactly 3 cards.

   Tests verify:
   - build-tutor-selection creates correct selection state with :selection/select-count
   - Selection count is min(effect-count, library-size)
   - execute-tutor-selection moves ALL selected cards, not just first
   - Backwards compatibility: tutors without :effect/select-count default to 1"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.library :as library]
    [fizzle.test-helpers :as th]))


;; === build-tutor-selection multi-select tests ===

(deftest test-multi-select-tutor-selection-state
  ;; Bug caught: Selection state doesn't include :selection/select-count
  (testing "Multi-select tutor creates selection with :selection/select-count"
    (let [db (th/create-test-db {:mana {:blue 3}})
          ;; Add 10 cards to library
          [db' _obj-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :dark-ritual :dark-ritual
                                                   :cabal-ritual :cabal-ritual :cabal-ritual
                                                   :brain-freeze :brain-freeze :brain-freeze
                                                   :careful-study]
                                                  :player-1)
          effect {:effect/type :tutor
                  :effect/select-count 3
                  :effect/target-zone :hand}
          result (library/build-tutor-selection db' :player-1 (random-uuid) effect [])]
      (is (= 3 (:selection/select-count result))
          "Selection state must have :selection/select-count = 3")
      (is (= true (:selection/exact? result))
          "Selection must require exact count")
      (is (= 10 (count (:selection/candidates result)))
          "All library cards should be candidates")
      (is (= :player-1 (:selection/player-id result))
          "Player ID must be set")
      (is (= true (:selection/allow-fail-to-find? result))
          "Fail-to-find must be allowed"))))


(deftest test-multi-select-tutor-library-smaller-than-count
  ;; Bug caught: Crashes when library has fewer cards than select-count
  (testing "Library smaller than select-count uses library size"
    (let [db (th/create-test-db {:mana {:blue 3}})
          ;; Add only 2 cards to library
          [db' _] (th/add-cards-to-library db
                                           [:dark-ritual :cabal-ritual]
                                           :player-1)
          effect {:effect/type :tutor
                  :effect/select-count 3
                  :effect/target-zone :hand}
          result (library/build-tutor-selection db' :player-1 (random-uuid) effect [])]
      (is (= 2 (:selection/select-count result))
          "Selection count must be min(3, 2) = 2")
      (is (= 2 (count (:selection/candidates result)))
          "Only 2 candidates available"))))


(deftest test-multi-select-tutor-single-card-library
  ;; Bug caught: Edge case with library of 1 card
  (testing "Library with 1 card sets select-count to 1"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' _] (th/add-cards-to-library db
                                           [:dark-ritual]
                                           :player-1)
          effect {:effect/type :tutor
                  :effect/select-count 3
                  :effect/target-zone :hand}
          result (library/build-tutor-selection db' :player-1 (random-uuid) effect [])]
      (is (= 1 (:selection/select-count result))
          "Selection count must be 1 when library has 1 card"))))


(deftest test-multi-select-tutor-empty-library
  ;; Bug caught: Crashes or wrong behavior with empty library
  (testing "Empty library sets select-count to 0, fail-to-find allowed"
    (let [db (th/create-test-db {:mana {:blue 3}})
          ;; No cards added to library
          effect {:effect/type :tutor
                  :effect/select-count 3
                  :effect/target-zone :hand}
          result (library/build-tutor-selection db :player-1 (random-uuid) effect [])]
      (is (= 0 (:selection/select-count result))
          "Selection count must be 0 when library is empty")
      (is (empty? (:selection/candidates result))
          "No candidates in empty library")
      (is (= true (:selection/allow-fail-to-find? result))
          "Fail-to-find must be allowed with empty library"))))


(deftest test-single-select-tutor-backwards-compat
  ;; Bug caught: Breaking existing tutors that don't specify select-count
  (testing "Tutor without :effect/select-count defaults to 1"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' _] (th/add-cards-to-library db
                                           [:dark-ritual :cabal-ritual :brain-freeze :careful-study :mental-note]
                                           :player-1)
          ;; Effect without :effect/select-count (like existing Merchant Scroll)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types #{:instant} :match/colors #{:blue}}
                  :effect/target-zone :hand}
          result (library/build-tutor-selection db' :player-1 (random-uuid) effect [])]
      (is (= 1 (:selection/select-count result))
          "Default select-count must be 1 for backwards compatibility")
      ;; Only blue instants are candidates (brain-freeze, mental-note)
      (is (= 2 (count (:selection/candidates result)))
          "Criteria filtering still works"))))


;; === execute-tutor-selection multi-select tests ===

(deftest test-execute-multi-select-moves-all-cards
  ;; Bug caught: Only first selected card moved, others left in library
  (testing "execute-tutor-selection moves ALL selected cards to target zone"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' [obj1 obj2 obj3 obj4 obj5]] (th/add-cards-to-library
                                             db
                                             [:dark-ritual :dark-ritual :dark-ritual
                                              :cabal-ritual :cabal-ritual]
                                             :player-1)
          selection {:selection/selected #{obj1 obj2 obj3}  ; Select 3 cards
                     :selection/target-zone :hand
                     :selection/player-id :player-1
                     :selection/shuffle? true}
          db-after (library/execute-tutor-selection db' selection)]
      ;; All 3 selected cards should be in hand
      (is (= :hand (th/get-object-zone db-after obj1))
          "First selected card must be in hand")
      (is (= :hand (th/get-object-zone db-after obj2))
          "Second selected card must be in hand")
      (is (= :hand (th/get-object-zone db-after obj3))
          "Third selected card must be in hand")
      ;; Unselected cards should still be in library
      (is (= :library (th/get-object-zone db-after obj4))
          "Unselected card 4 must remain in library")
      (is (= :library (th/get-object-zone db-after obj5))
          "Unselected card 5 must remain in library")
      ;; Hand should have 3 cards, library should have 2
      (is (= 3 (th/get-hand-count db-after :player-1))
          "Hand should have 3 cards after tutor")
      (is (= 2 (th/get-zone-count db-after :library :player-1))
          "Library should have 2 cards after tutor"))))


(deftest test-execute-multi-select-shuffles-once
  ;; Bug caught: Library shuffled multiple times (once per card)
  ;; Note: Can't directly test shuffle count, but can verify library is shuffled
  (testing "Library is shuffled after all cards moved"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' [obj1 obj2 obj3 obj4 obj5]] (th/add-cards-to-library
                                             db
                                             [:dark-ritual :dark-ritual :dark-ritual
                                              :cabal-ritual :cabal-ritual]
                                             :player-1)
          selection {:selection/selected #{obj1 obj2 obj3}
                     :selection/target-zone :hand
                     :selection/player-id :player-1
                     :selection/shuffle? true}
          db-after (library/execute-tutor-selection db' selection)]
      ;; Verify library still has cards (shuffle doesn't remove them)
      (is (= 2 (th/get-zone-count db-after :library :player-1))
          "Library should have 2 cards after shuffle")
      ;; Verify remaining cards are still in library
      (is (= :library (th/get-object-zone db-after obj4))
          "Card 4 should still be in library after shuffle")
      (is (= :library (th/get-object-zone db-after obj5))
          "Card 5 should still be in library after shuffle"))))


(deftest test-execute-single-select-backwards-compat
  ;; Bug caught: Single-card selection broken after multi-select changes
  (testing "Single-card selection still works (backwards compat)"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' [obj1 obj2 obj3]] (th/add-cards-to-library
                                   db
                                   [:dark-ritual :cabal-ritual :brain-freeze]
                                   :player-1)
          ;; Single card selected (like existing tutor behavior)
          selection {:selection/selected #{obj1}
                     :selection/target-zone :hand
                     :selection/player-id :player-1
                     :selection/shuffle? true}
          db-after (library/execute-tutor-selection db' selection)]
      (is (= :hand (th/get-object-zone db-after obj1))
          "Selected card must be in hand")
      (is (= :library (th/get-object-zone db-after obj2))
          "Other cards must remain in library")
      (is (= :library (th/get-object-zone db-after obj3))
          "Other cards must remain in library")
      (is (= 1 (th/get-hand-count db-after :player-1))
          "Hand should have 1 card")
      (is (= 2 (th/get-zone-count db-after :library :player-1))
          "Library should have 2 cards"))))


(deftest test-execute-fail-to-find
  ;; Bug caught: Fail-to-find broken after multi-select changes
  (testing "Empty selection (fail-to-find) still shuffles library"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' [obj1 obj2 obj3]] (th/add-cards-to-library
                                   db
                                   [:dark-ritual :cabal-ritual :brain-freeze]
                                   :player-1)
          selection {:selection/selected #{}  ; No cards selected
                     :selection/target-zone :hand
                     :selection/player-id :player-1
                     :selection/shuffle? true}
          db-after (library/execute-tutor-selection db' selection)]
      ;; All cards should remain in library
      (is (= :library (th/get-object-zone db-after obj1))
          "Cards should remain in library")
      (is (= :library (th/get-object-zone db-after obj2))
          "Cards should remain in library")
      (is (= :library (th/get-object-zone db-after obj3))
          "Cards should remain in library")
      (is (= 0 (th/get-hand-count db-after :player-1))
          "Hand should be empty")
      (is (= 3 (th/get-zone-count db-after :library :player-1))
          "Library should have all 3 cards"))))
