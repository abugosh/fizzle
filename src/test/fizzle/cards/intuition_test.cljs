(ns fizzle.cards.intuition-test
  "Tests for Intuition card definition.

   Intuition: 2U - Instant
   Search your library for three cards and reveal them. Target opponent
   chooses one. Put that card into your hand and the rest into your graveyard.
   Then shuffle.

   This tests:
   - Intuition card exists in all-cards
   - Correct mana cost and attributes
   - Effect structure with :effect/select-count 3 and :effect/pile-choice
   - Integration with multi-select tutor and pile choice systems"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.events.selection.library :as library]
    [fizzle.test-helpers :as th]))


;; === Tests ===

(deftest test-intuition-card-exists
  ;; Bug caught: Card not added to all-cards vector, won't be loaded
  (testing "Intuition is in all-cards"
    (let [intuition-card (some #(when (= "Intuition" (:card/name %)) %)
                               cards/all-cards)]
      (is (= :intuition (:card/id intuition-card))
          "Card ID must be :intuition"))))


(deftest test-intuition-mana-cost
  ;; Bug caught: Wrong mana cost prevents casting
  (testing "Intuition has correct mana cost 2U"
    (let [intuition-card (some #(when (= :intuition (:card/id %)) %)
                               cards/all-cards)]
      (is (= {:colorless 2 :blue 1} (:card/mana-cost intuition-card))
          "Mana cost must be {2}{U}")
      (is (= 3 (:card/cmc intuition-card))
          "CMC must be 3")
      (is (= #{:blue} (:card/colors intuition-card))
          "Colors must be #{:blue}"))))


(deftest test-intuition-effect-structure
  ;; Bug caught: Missing parameters cause tutor/pile-choice to fail
  (testing "Intuition effect has required tutor parameters"
    (let [intuition-card (some #(when (= :intuition (:card/id %)) %)
                               cards/all-cards)
          effect (first (:card/effects intuition-card))]
      (is (= :tutor (:effect/type effect))
          "Effect type must be :tutor")
      (is (= 3 (:effect/select-count effect))
          "select-count must be 3")
      (is (= :hand (:effect/target-zone effect))
          "target-zone must be :hand")
      (is (= {:hand 1 :graveyard :rest} (:effect/pile-choice effect))
          "pile-choice must specify 1 to hand, rest to graveyard"))))


(deftest test-intuition-full-flow-integration
  ;; Bug caught: Card definition doesn't work with selection system
  (testing "Intuition triggers pile choice after tutor selection"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db' _card-ids] (th/add-cards-to-library db
                                                   [:dark-ritual :cabal-ritual :brain-freeze
                                                    :careful-study :mental-note]
                                                   :player-1)
          intuition-card (some #(when (= :intuition (:card/id %)) %)
                               cards/all-cards)
          intuition-effect (first (:card/effects intuition-card))
          selection (library/build-tutor-selection db' :player-1 (random-uuid)
                                                   intuition-effect [])]
      ;; Verify multi-select tutor
      (is (= 3 (:selection/select-count selection))
          "Intuition must require 3 cards")
      (is (= {:hand 1 :graveyard :rest} (:selection/pile-choice selection))
          "Selection must include pile-choice for second phase"))))


(deftest test-intuition-with-fewer-than-3-cards-in-library
  ;; Bug caught: crash or wrong behavior with insufficient cards
  (testing "Intuition with only 2 cards in library creates tutor with 2 candidates"
    (let [db (th/create-test-db {:mana {:blue 3}})
          ;; Add only 2 cards to library (fewer than Intuition's select-count of 3)
          [db' _card-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          ;; Add Intuition to hand
          [db'' int-id] (th/add-card-to-zone db' :intuition :hand :player-1)
          ;; Cast Intuition (already has 3 blue mana from create-test-db)
          db-cast (rules/cast-spell db'' :player-1 int-id)
          _ (is (= :stack (:object/zone (q/get-object db-cast int-id)))
                "Precondition: Intuition on stack")
          ;; Resolve - should create tutor selection with available cards
          result (game/resolve-one-item db-cast :player-1)
          sel (:pending-selection result)]
      ;; Should have pending selection (tutor)
      (is (= :tutor (:selection/type sel))
          "Selection type should be :tutor")
      ;; With only 2 cards, can only find up to 2
      (is (<= (count (:selection/candidate-ids sel)) 2)
          "Should have at most 2 candidates (only 2 cards in library)"))))
