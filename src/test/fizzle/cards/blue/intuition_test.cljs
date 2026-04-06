(ns fizzle.cards.blue.intuition-test
  "Tests for Intuition card definition.

   Intuition: 2U - Instant
   Search your library for three cards and reveal them. Target opponent
   chooses one. Put that card into your hand and the rest into your graveyard.
   Then shuffle.

   This tests:
   - Correct mana cost and attributes
   - Effect structure with :effect/select-count 3 and :effect/pile-choice
   - Integration with multi-select tutor and pile choice systems"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.intuition :as intuition]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest intuition-card-definition-test
  (testing "Intuition identity and core fields"
    (let [card intuition/card]
      (is (= :intuition (:card/id card))
          "Card ID should be :intuition")
      (is (= "Intuition" (:card/name card))
          "Card name should match oracle")
      (is (= 3 (:card/cmc card))
          "CMC should be 3")
      (is (= {:colorless 2 :blue 1} (:card/mana-cost card))
          "Mana cost must be {2}{U}")
      (is (= #{:blue} (:card/colors card))
          "Colors must be #{:blue}")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Search your library for three cards and reveal them. Target opponent chooses one. Put that card into your hand and the rest into your graveyard. Then shuffle."
             (:card/text card))
          "Card text should match oracle"))))


(deftest intuition-effect-structure-test
  (testing "Intuition effect has required tutor parameters"
    ;; Bug caught: Missing parameters cause tutor/pile-choice to fail
    (let [effect (first (:card/effects intuition/card))]
      (is (= :tutor (:effect/type effect))
          "Effect type must be :tutor")
      (is (= 3 (:effect/select-count effect))
          "select-count must be 3")
      (is (= :hand (:effect/target-zone effect))
          "target-zone must be :hand")
      (is (= {:hand 1 :graveyard :rest} (:effect/pile-choice effect))
          "pile-choice must specify 1 to hand, rest to graveyard"))))


;; === C. Cannot-Cast Guards ===

(deftest intuition-cannot-cast-without-mana-test
  (testing "Cannot cast Intuition without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :intuition :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest intuition-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Intuition with only 2 mana (needs {2}{U})"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db obj-id] (th/add-card-to-zone db :intuition :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 2 mana"))))


(deftest intuition-cannot-cast-from-graveyard-test
  (testing "Cannot cast Intuition from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :intuition :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest intuition-increments-storm-count-test
  (testing "Casting Intuition increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :brain-freeze] :player-1)
          [db obj-id] (th/add-card-to-zone db :intuition :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Intuition"))))


(deftest test-intuition-full-flow-integration
  ;; Bug caught: Card definition doesn't work with selection system
  (testing "Intuition triggers tutor selection via production cast-resolve path"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _card-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze
                                                   :careful-study :mental-note]
                                                  :player-1)
          [db int-id] (th/add-card-to-zone db :intuition :hand :player-1)
          ;; Cast and resolve through production path
          db-cast (rules/cast-spell db :player-1 int-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      ;; Verify tutor selection was created
      (is (= :tutor (:selection/type selection))
          "Selection type should be :tutor")
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
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; Should have pending selection (tutor)
      (is (= :tutor (:selection/type sel))
          "Selection type should be :tutor")
      ;; With only 2 cards, can only find up to 2
      (is (<= (count (:selection/candidate-ids sel)) 2)
          "Should have at most 2 candidates (only 2 cards in library)"))))
