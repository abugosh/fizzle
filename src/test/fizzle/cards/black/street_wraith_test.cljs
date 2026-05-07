(ns fizzle.cards.black.street-wraith-test
  "Tests for Street Wraith.

   Street Wraith: {3}{B}{B} 3/4 Creature — Wraith
   Swampwalk (This creature can't be blocked as long as defending player controls a Swamp.)
   Cycling—Pay 2 life. (Pay 2 life, Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — creature enters battlefield
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   E. Cycling tests — pay 2 life from hand, discard self, draw 1
   F. Cannot-cycle guards — insufficient life, wrong zone
   G. Edge cases — cycle at exactly 2 life, empty library, cannot cycle at 1 life"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.street-wraith :as street-wraith]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "Swampwalk\nCycling—Pay 2 life."
(deftest street-wraith-card-definition-test
  (testing "Street Wraith card data is correct"
    (let [card street-wraith/card]
      (is (= :street-wraith (:card/id card)))
      (is (= "Street Wraith" (:card/name card)))
      (is (= 5 (:card/cmc card)))
      (is (= {:colorless 3 :black 2} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:wraith} (:card/subtypes card)))
      (is (nil? (:card/supertypes card)))
      (is (= 3 (:card/power card)))
      (is (= 4 (:card/toughness card)))
      (is (= #{:swampwalk} (:card/keywords card)))
      (is (= "Swampwalk (This creature can't be blocked as long as defending player controls a Swamp.)\nCycling—Pay 2 life. (Pay 2 life, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Street Wraith has one cycling ability and no spell effects"
    (is (= [] (:card/effects street-wraith/card)))
    (is (nil? (:card/triggers street-wraith/card)))
    (is (= 1 (count (:card/abilities street-wraith/card))))
    (let [cycling-ability (first (:card/abilities street-wraith/card))]
      (is (= :cycling (:ability/type cycling-ability)))
      (is (= :hand (:ability/zone cycling-ability)))
      (is (= {:discard-self true :pay-life 2} (:ability/cost cycling-ability)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-ability)))
      (is (= "Cycling—Pay 2 life" (:ability/description cycling-ability))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: Creature — enters the battlefield on resolution
(deftest street-wraith-enters-battlefield-test
  (testing "Street Wraith enters battlefield as 3/4 with summoning sickness"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 2}})
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj)))
      (is (= 3 (:object/power obj)))
      (is (= 4 (:object/toughness obj)))
      (is (true? (:object/summoning-sick obj)))
      (is (= 0 (:object/damage-marked obj))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Mana cost {3}{B}{B}
(deftest street-wraith-cannot-cast-without-mana-test
  (testing "Cannot cast without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest street-wraith-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with only black mana (missing colorless)"
    (let [db (th/create-test-db {:mana {:black 2}})
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest street-wraith-cannot-cast-from-graveyard-test
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 2}})
          [db obj-id] (th/add-card-to-zone db :street-wraith :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Casting a spell increments storm count
(deftest street-wraith-increments-storm-count-test
  (testing "Casting Street Wraith increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 2}})
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)))
      (let [db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1)))))))


;; =====================================================
;; E. Cycling Tests
;; =====================================================

;; Oracle: "Cycling—Pay 2 life. (Pay 2 life, Discard this card: Draw a card.)"
;; Path: activate-ability (index 0) → no mana allocation → resolve-top → draw
;; pay-life cost has no generic mana, so no mana allocation selection needed.
(deftest street-wraith-cycle-from-hand-test
  (testing "Cycling from hand: pay 2 life, discard self, draw 1"
    (let [db (th/create-test-db {:life 20})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          _ (is (= 20 (q/get-life-total db :player-1)) "Precondition: 20 life")
          ;; Cycling ability is at index 0 (only ability)
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (nil? (:pending-selection result))
                "Cycling—Pay 2 life: no mana allocation selection needed")
          db (:db result)]
      ;; Costs paid at activation: discard-self (card to graveyard) + pay-life 2
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Cycled card should be in graveyard after activation")
      (is (= 18 (q/get-life-total db :player-1))
          "Life should decrease by 2 after paying cycling cost")
      ;; Resolve the draw effect
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card after resolution")
        (is (= 18 (q/get-life-total db :player-1))
            "Life should remain 18 after draw resolution")))))


;; =====================================================
;; F. Cannot-Cycle Guards
;; =====================================================

;; Oracle: Cycling—Pay 2 life — requires exactly 2+ life to activate
(deftest street-wraith-cannot-cycle-with-1-life-test
  (testing "Cannot cycle with only 1 life"
    (let [db (th/create-test-db {:life 1})
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails")
      (is (= 1 (q/get-life-total result-db :player-1))
          "Life should be unchanged when cycling fails"))))


(deftest street-wraith-cannot-cycle-with-0-life-test
  (testing "Cannot cycle with 0 life"
    (let [db (th/create-test-db {:life 0})
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails"))))


(deftest street-wraith-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:life 20})
          [db obj-id] (th/add-card-to-zone db :street-wraith :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "Card should remain on battlefield when cycling fails"))))


(deftest street-wraith-cannot-cycle-from-graveyard-test
  (testing "Cannot cycle from graveyard"
    (let [db (th/create-test-db {:life 20})
          [db obj-id] (th/add-card-to-zone db :street-wraith :graveyard :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in graveyard when cycling fails"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

;; Oracle: "Pay 2 life" — cycling to exactly 0 life is legal in MTG
(deftest street-wraith-cycle-at-exactly-2-life-test
  (testing "Cycling at exactly 2 life reduces life to 0"
    (let [db (th/create-test-db {:life 2})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (nil? (:pending-selection result))
                "Activation should succeed at exactly 2 life")
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Cycled card should be in graveyard")
      (is (= 0 (q/get-life-total db :player-1))
          "Life should be 0 after cycling at exactly 2 life")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card")))))


;; Oracle: "Discard this card: Draw a card." — with empty library, draw fails gracefully
(deftest street-wraith-cycle-with-empty-library-test
  (testing "Cycling with empty library discards but draws nothing"
    (let [db (th/create-test-db {:life 20})
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (nil? (:pending-selection result))
                "Activation should succeed")
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Cycled card should be in graveyard after activation")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 0 (th/get-hand-count db :player-1))
            "Hand should be empty (discarded 1, drew 0 from empty library)")
        (is (= 18 (q/get-life-total db :player-1))
            "Life should be 18 after cycling with empty library")))))


;; Oracle: Cycling is an ability activation, not casting a spell
(deftest street-wraith-cycling-does-not-increment-storm-test
  (testing "Cycling does not increment storm count"
    (let [db (th/create-test-db {:life 20})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :street-wraith :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)) "Storm count should start at 0")
          result (abilities/activate-ability db :player-1 obj-id 0)
          db (:db result)]
      (is (= 0 (q/get-storm-count db :player-1))
          "Storm count should remain 0 after cycling activation")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 0 (q/get-storm-count db :player-1))
            "Storm count should remain 0 after cycling resolves")))))
