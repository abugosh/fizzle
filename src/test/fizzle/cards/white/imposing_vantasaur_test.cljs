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
    [fizzle.events.abilities :as abilities]
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
      (is (nil? (:card/cycling card))
          "No :card/cycling key after migration to :card/abilities")
      (is (= "Vigilance\nCycling {1} ({1}, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Imposing Vantasaur has a cycling ability but no spell effects or triggers"
    (is (nil? (:card/effects imposing-vantasaur/card)))
    (is (nil? (:card/triggers imposing-vantasaur/card)))
    (is (= 1 (count (:card/abilities imposing-vantasaur/card))))
    (let [cycling-ability (first (:card/abilities imposing-vantasaur/card))]
      (is (= :cycling (:ability/type cycling-ability)))
      (is (= :hand (:ability/zone cycling-ability)))
      (is (= {:discard-self true :mana {:colorless 1}} (:ability/cost cycling-ability)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-ability))))))


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
;; New path: activate-ability (index 0) → confirm mana allocation → resolve-top → draw
;; The cycling ability has :colorless 1 cost — goes through mana allocation selection.
(deftest imposing-vantasaur-cycle-from-hand-test
  (testing "Cycling from hand: pay {1}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          ;; Cycling ability is at index 0 (only ability)
          ;; Activation: pays :discard-self (card to graveyard), returns mana-alloc selection
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (some? (:pending-selection result))
                "Cycling {1}: mana allocation selection expected")
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)]
      ;; Card should be in graveyard after paying discard-self cost
      (is (= :graveyard (:object/zone (q/get-object db-after-discard obj-id)))
          "Card should be in graveyard after discard-self cost")
      ;; Confirm mana allocation — use colorless 1 from pool
      (let [alloc-sel-with-alloc (assoc alloc-sel :selection/allocation {:colorless 1})
            {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
            ;; Resolve the draw effect
            {:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")
        (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
            "Cycling cost should be paid")))))


(deftest imposing-vantasaur-cycle-with-colored-mana-test
  (testing "Cycling cost {1} can be paid with any color of mana"
    (doseq [[color pool] [[:white {:white 1}]
                          [:blue {:blue 1}]
                          [:black {:black 1}]
                          [:red {:red 1}]
                          [:green {:green 1}]]]
      (let [db (th/create-test-db {:mana pool})
            [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
            [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
            result (abilities/activate-ability db :player-1 obj-id 0)
            db-after-discard (:db result)
            alloc-sel (:pending-selection result)
            alloc-sel-with-alloc (assoc alloc-sel :selection/allocation {color 1})
            {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
            {:keys [db]} (th/resolve-top db)]
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
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails"))))


(deftest imposing-vantasaur-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "Card should remain on battlefield when cycling fails"))))


(deftest imposing-vantasaur-cannot-cycle-from-graveyard-test
  (testing "Cannot cycle from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :imposing-vantasaur :graveyard :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result))
          "No selection when activation fails")
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
          result (abilities/activate-ability db :player-1 obj-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation {:colorless 1})
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          {:keys [db]} (th/resolve-top db)]
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
          result (abilities/activate-ability db :player-1 obj-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation {:colorless 1})
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          {:keys [db]} (th/resolve-top db)]
      (is (= 0 (q/get-storm-count db :player-1))
          "Storm count should remain 0 after cycling"))))
