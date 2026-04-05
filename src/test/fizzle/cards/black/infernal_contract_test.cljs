(ns fizzle.cards.black.infernal-contract-test
  "Tests for Infernal Contract - BBB Sorcery: Draw 4 cards, lose half life rounded up.

   Key behaviors:
   - Draws exactly 4 cards
   - Loses ceil(life/2) life at resolution
   - Uses :half-life-rounded-up dynamic resolver
   - Works on even/odd/edge-case life totals"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.infernal-contract :as infernal-contract]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest infernal-contract-card-definition-test
  (testing "Infernal Contract card data is correct"
    (let [card infernal-contract/card]
      (is (= :infernal-contract (:card/id card)))
      (is (= "Infernal Contract" (:card/name card)))
      (is (= 3 (:card/cmc card)))
      (is (= {:black 3} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:sorcery} (:card/types card)))
      (is (= "Draw four cards. You lose half your life total, rounded up." (:card/text card)))
      (is (= 2 (count (:card/effects card)))
          "Should have exactly 2 effects")
      (let [draw-effect (first (:card/effects card))]
        (is (= :draw (:effect/type draw-effect)))
        (is (= 4 (:effect/amount draw-effect))))
      (let [life-effect (second (:card/effects card))]
        (is (= :lose-life (:effect/type life-effect)))
        (is (= {:dynamic/type :half-life-rounded-up} (:effect/amount life-effect)))))))


;; === B. Cast-Resolve Happy Path ===

(deftest infernal-contract-draws-4-cards-test
  (testing "Casting and resolving Infernal Contract draws 4 cards"
    (let [db (th/create-test-db {:mana {:black 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          hand-before (th/get-hand-count db :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          hand-after (th/get-hand-count db :player-1)]
      ;; Started with infernal-contract in hand (1), cast it (-1), drew 4 (+4) = net +3
      (is (= (+ hand-before 3) hand-after)
          "Hand should increase by 3 (drew 4, spent 1 casting)"))))


(deftest infernal-contract-loses-half-life-test
  (testing "Casting and resolving Infernal Contract loses ceil(20/2)=10 life"
    (let [db (th/create-test-db {:mana {:black 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 10 (q/get-life-total db :player-1))
          "Should have 10 life after losing ceil(20/2)=10 from 20"))))


(deftest infernal-contract-goes-to-graveyard-test
  (testing "Infernal Contract moves to graveyard after resolution"
    (let [db (th/create-test-db {:mana {:black 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Infernal Contract should be in graveyard after resolution"))))


;; === C. Cannot-Cast Guards ===

(deftest infernal-contract-cannot-cast-without-mana-test
  (testing "Cannot cast Infernal Contract without BBB"
    (let [db (th/create-test-db {:mana {:black 2} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 2 black mana"))))


(deftest infernal-contract-cannot-cast-from-graveyard-test
  (testing "Cannot cast Infernal Contract from graveyard (no flashback)"
    (let [db (th/create-test-db {:mana {:black 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest infernal-contract-increments-storm-test
  (testing "Casting Infernal Contract increments storm count"
    (let [db (th/create-test-db {:mana {:black 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          storm-before (q/get-storm-count db :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= (inc storm-before) (q/get-storm-count db :player-1))
          "Storm count should increment by 1"))))


;; === G. Edge Cases ===

(deftest infernal-contract-odd-life-rounds-up-test
  (testing "Odd life totals round up correctly"
    (doseq [[starting-life expected-loss expected-remaining]
            [[19 10 9]
             [7  4  3]
             [1  1  0]
             [3  2  1]]]
      (let [db (th/create-test-db {:mana {:black 3} :life starting-life})
            [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
            [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
            db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= expected-remaining (q/get-life-total db :player-1))
            (str "Life " starting-life " -> lose " expected-loss " -> " expected-remaining " remaining"))))))


(deftest infernal-contract-zero-life-noop-test
  (testing "0 life total: loses 0 (ceil(0/2)=0), no change"
    (let [db (th/create-test-db {:mana {:black 3} :life 0})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 0 (q/get-life-total db :player-1))
          "0 life total should stay at 0"))))


(deftest infernal-contract-negative-life-noop-test
  (testing "Negative life total: loses 0 (ceil(-3/2)=-1, guard catches), no change"
    (let [db (th/create-test-db {:mana {:black 3} :life -3})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          [db _] (th/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= -3 (q/get-life-total db :player-1))
          "Negative life total should stay unchanged"))))


(deftest infernal-contract-small-library-test
  (testing "Library with fewer than 4 cards: draws available cards, still loses half life"
    (let [db (th/create-test-db {:mana {:black 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
          ;; Only 2 cards in library
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-1)
          hand-before (th/get-hand-count db :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          hand-after (th/get-hand-count db :player-1)]
      ;; Drew 2 (all available), cast 1 (net +1)
      (is (= (+ hand-before 1) hand-after)
          "Should draw all 2 available cards from library")
      (is (= 10 (q/get-life-total db :player-1))
          "Should still lose half life even with small library"))))
