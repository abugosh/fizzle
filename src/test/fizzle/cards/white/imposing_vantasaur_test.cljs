(ns fizzle.cards.white.imposing-vantasaur-test
  "Tests for Imposing Vantasaur.

   Imposing Vantasaur: {5}{W} 3/6 Creature — Dinosaur
   Vigilance
   Cycling {1} ({1}, Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — creature enters battlefield
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   E. Cycling tests — pay {1} from hand, discard self, draw 1
   F. Cannot-cycle guards — wrong zone, insufficient mana
   G. Edge cases — cycling with empty library, cycling doesn't increment storm"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.white.imposing-vantasaur :as imposing-vantasaur]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.cycling :as cycling]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "Vigilance\nCycling {1}"
(deftest imposing-vantasaur-card-definition-test
  (testing "Imposing Vantasaur card data is correct"
    (let [card imposing-vantasaur/card]
      (is (= :imposing-vantasaur (:card/id card)))
      (is (= "Imposing Vantasaur" (:card/name card)))
      (is (= 6 (:card/cmc card)))
      (is (= {:colorless 5 :white 1} (:card/mana-cost card)))
      (is (= #{:white} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:dinosaur} (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= 3 (:card/power card)))
      (is (= 6 (:card/toughness card)))
      (is (= #{:vigilance} (:card/keywords card)))
      (is (= {:colorless 1} (:card/cycling card)))
      (is (= "Vigilance\nCycling {1} ({1}, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Imposing Vantasaur has no spell effects, triggers, or abilities"
    (is (nil? (:card/effects imposing-vantasaur/card)))
    (is (nil? (:card/triggers imposing-vantasaur/card)))
    (is (nil? (:card/abilities imposing-vantasaur/card)))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: Creature — enters the battlefield on resolution
(deftest imposing-vantasaur-enters-battlefield-test
  (testing "Imposing Vantasaur enters battlefield as 3/6 with summoning sickness"
    (let [db (th/create-test-db {:mana {:colorless 5 :white 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj)))
      (is (= 3 (:object/power obj)))
      (is (= 6 (:object/toughness obj)))
      (is (true? (:object/summoning-sick obj)))
      (is (= 0 (:object/damage-marked obj))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Mana cost {5}{W}
(deftest imposing-vantasaur-cannot-cast-without-mana-test
  (testing "Cannot cast without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest imposing-vantasaur-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with insufficient mana"
    (let [db (th/create-test-db {:mana {:white 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest imposing-vantasaur-cannot-cast-from-graveyard-test
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 5 :white 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Casting a spell increments storm count
(deftest imposing-vantasaur-increments-storm-count-test
  (testing "Casting Imposing Vantasaur increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 5 :white 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)))
      (let [db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1)))))))


;; =====================================================
;; E. Cycling Tests
;; =====================================================

;; Oracle: "Cycling {1} ({1}, Discard this card: Draw a card.)"
(deftest imposing-vantasaur-cycle-from-hand-test
  (testing "Cycling from hand: pay {1}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (cycling/cycle-card db :player-1 obj-id)
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Cycled card should be in graveyard")
      (is (= 1 (th/get-hand-count db :player-1))
          "Should have drawn 1 card (net 0: discarded 1, drew 1)")
      (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
          "Cycling cost should be paid"))))


(deftest imposing-vantasaur-cycle-with-colored-mana-test
  (testing "Cycling cost {1} can be paid with any color of mana"
    (doseq [color [:white :blue :black :red :green]]
      (let [db (th/create-test-db {:mana {color 1}})
            [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
            [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
            result (cycling/cycle-card db :player-1 obj-id)
            db (:db result)]
        (is (= :graveyard (:object/zone (q/get-object db obj-id)))
            (str "Should be able to cycle with " (name color) " mana"))))))


;; =====================================================
;; F. Cannot-Cycle Guards
;; =====================================================

;; Oracle: Cycling requires card to be in hand
(deftest imposing-vantasaur-cannot-cycle-without-mana-test
  (testing "Cannot cycle without sufficient mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          result (cycling/cycle-card db :player-1 obj-id)
          result-db (:db result)]
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails"))))


(deftest imposing-vantasaur-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :battlefield :player-1)
          result (cycling/cycle-card db :player-1 obj-id)
          result-db (:db result)]
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "Card should remain on battlefield when cycling fails"))))


(deftest imposing-vantasaur-cannot-cycle-from-graveyard-test
  (testing "Cannot cycle from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :graveyard :player-1)
          result (cycling/cycle-card db :player-1 obj-id)
          result-db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in graveyard when cycling fails"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

;; Oracle: "Cycling {1} ({1}, Discard this card: Draw a card.)"
;; Edge case: cycling with empty library still discards the card
(deftest imposing-vantasaur-cycle-with-empty-library-test
  (testing "Cycling with empty library discards but draws nothing"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (cycling/cycle-card db :player-1 obj-id)
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Cycled card should be in graveyard")
      (is (= 0 (th/get-hand-count db :player-1))
          "Hand should be empty (discarded 1, drew 0 from empty library)"))))


;; Oracle: Cycling is not casting a spell
(deftest imposing-vantasaur-cycling-does-not-increment-storm-test
  (testing "Cycling does not increment storm count"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)) "Storm count should start at 0")
          result (cycling/cycle-card db :player-1 obj-id)
          db (:db result)]
      (is (= 0 (q/get-storm-count db :player-1))
          "Storm count should remain 0 after cycling"))))
