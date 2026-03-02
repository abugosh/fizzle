(ns fizzle.cards.blue.words-of-wisdom-test
  "Tests for Words of Wisdom instant card.

   Words of Wisdom: 1U - Instant
   You draw two cards, then each other player draws a card.

   This tests:
   - Card definition (type, cost, effects)
   - Caster draws 2, opponent draws 1
   - Cannot cast without mana / wrong zone
   - Storm count increment
   - Edge cases with opponent"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.words-of-wisdom :as words-of-wisdom]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest test-card-definition
  (testing "Words of Wisdom has correct fields"
    (let [card words-of-wisdom/card]
      (is (= :words-of-wisdom (:card/id card)))
      (is (= "Words of Wisdom" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "You draw two cards, then each other player draws a card."
             (:card/text card)))))

  (testing "Words of Wisdom has correct effects"
    (let [effects (:card/effects words-of-wisdom/card)]
      (is (= 2 (count effects)))
      (let [draw-self (first effects)
            draw-opp (second effects)]
        (is (= :draw (:effect/type draw-self)))
        (is (= 2 (:effect/amount draw-self)))
        (is (nil? (:effect/target draw-self))
            "First draw targets caster (no explicit target)")
        (is (= :draw (:effect/type draw-opp)))
        (is (= 1 (:effect/amount draw-opp)))
        (is (= :opponent (:effect/target draw-opp)))))))


;; === B. Cast-Resolve Happy Path ===

(deftest test-cast-resolve-caster-draws-two
  (testing "Casting Words of Wisdom draws 2 for caster"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)
          db' (th/cast-and-resolve db :player-1 obj-id)]
      ;; Started with 1 (WoW) in hand, cast it (removed), drew 2 = 2 in hand
      (is (= 2 (th/get-hand-count db' :player-1))
          "Caster should have drawn 2 cards"))))


(deftest test-cast-resolve-opponent-draws-one
  (testing "Casting Words of Wisdom draws 1 for opponent"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)
          initial-opp-hand (th/get-hand-count db :player-2)
          db' (th/cast-and-resolve db :player-1 obj-id)]
      (is (= (+ initial-opp-hand 1) (th/get-hand-count db' :player-2))
          "Opponent should have drawn 1 card"))))


(deftest test-cast-resolve-both-players-draw
  (testing "Both caster and opponent draw correct amounts"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)
          db' (th/cast-and-resolve db :player-1 obj-id)]
      ;; Caster: started with 1 card in hand (WoW), cast it, drew 2 = 2 cards
      (is (= 2 (th/get-hand-count db' :player-1)))
      ;; Opponent: started with 0, drew 1 = 1 card
      (is (= 1 (th/get-hand-count db' :player-2))))))


;; === C. Cannot-Cast Guards ===

(deftest test-cannot-cast-insufficient-mana
  (testing "Cannot cast Words of Wisdom without 1U"
    (let [db (th/create-test-db {:mana {:blue 0}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest test-cannot-cast-only-blue
  (testing "Cannot cast Words of Wisdom with only blue (need 1U)"
    (let [db (th/create-test-db {:mana {:blue 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest test-cannot-cast-wrong-zone
  (testing "Cannot cast Words of Wisdom from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; === D. Storm Count ===

(deftest test-storm-count-increments
  (testing "Casting Words of Wisdom increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db' (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db' :player-1))))))


;; === G. Edge Cases ===

(deftest test-opponent-empty-library
  (testing "Opponent drawing from empty library sets drew-from-empty"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          ;; No library for opponent
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)
          db' (th/cast-and-resolve db :player-1 obj-id)
          db-after-sba (sba/check-and-execute-sbas db')]
      ;; Caster still draws fine
      (is (= 2 (th/get-hand-count db-after-sba :player-1)))
      ;; Opponent should have drew-from-empty loss condition detected
      (is (= :empty-library (:game/loss-condition (q/get-game-state db-after-sba)))))))


(deftest test-caster-small-library
  (testing "Caster with small library draws what's available"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Only 1 card in caster's library
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          [db obj-id] (th/add-card-to-zone db :words-of-wisdom :hand :player-1)
          db' (th/cast-and-resolve db :player-1 obj-id)
          db-after-sba (sba/check-and-execute-sbas db')]
      ;; Caster drew 1 (all available), then drew-from-empty
      (is (= 1 (th/get-hand-count db-after-sba :player-1)))
      ;; Opponent still draws fine
      (is (= 1 (th/get-hand-count db-after-sba :player-2))))))
