(ns fizzle.cards.blue.impulse-test
  "Tests for Impulse card.

   Impulse: {1}{U} - Instant
   Look at the top four cards of your library. Put one of them into your
   hand and the rest on the bottom of your library in any order.

   Ruling (2004-10-04): This is not a draw.
   Ruling (2004-10-04): Due to errata, you no longer shuffle your library."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.impulse :as impulse]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Impulse" / {1}{U} / Instant
(deftest impulse-card-definition-test
  (testing "Impulse card fields match Scryfall data"
    (let [card impulse/card]
      (is (= :impulse (:card/id card)))
      (is (= "Impulse" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "Look at the top four cards of your library. Put one of them into your hand and the rest on the bottom of your library in any order."
             (:card/text card)))))

  ;; Oracle: "Look at the top four cards...Put one...hand...rest on the bottom...in any order"
  (testing "Impulse uses peek-and-select with order-remainder"
    (let [effects (:card/effects impulse/card)]
      (is (= 1 (count effects))
          "Should have exactly 1 effect")
      (let [effect (first effects)]
        (is (= :peek-and-select (:effect/type effect)))
        (is (= 4 (:effect/count effect))
            "Peek at top 4 cards")
        (is (= 1 (:effect/select-count effect))
            "Select exactly 1 card")
        (is (= :hand (:effect/selected-zone effect))
            "Selected card goes to hand")
        (is (= :bottom-of-library (:effect/remainder-zone effect))
            "Remainder goes to bottom of library")
        (is (true? (:effect/order-remainder? effect))
            "Remainder should be player-ordered (any order)")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Look at the top four cards of your library."
(deftest impulse-cast-resolve-creates-peek-selection-test
  (testing "Cast with 1U, resolve creates peek-and-select selection for top 4"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note]
                                                :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          _ (is (= :stack (th/get-object-zone db-cast imp-id))
                "Should be on stack after casting")
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= :peek-and-select (:selection/type sel))
          "Selection type should be :peek-and-select")
      (is (= 4 (count (:selection/candidates sel)))
          "Should peek at 4 cards")
      (is (= (set (take 4 lib-ids)) (:selection/candidates sel))
          "Peeked cards should be the top 4 of library")
      (is (= 1 (:selection/select-count sel))
          "Should select exactly 1 card")
      (is (true? (:selection/order-remainder? sel))
          "Should chain to order-bottom for remainder"))))


;; Oracle: "Put one of them into your hand and the rest on the bottom...in any order."
(deftest impulse-peek-select-chains-to-order-bottom-test
  (testing "After selecting 1, chains to order-bottom for remaining 3"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note]
                                                :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)
          selected-card (first lib-ids)
          ;; Confirm peek-and-select with 1 card selected via production path
          {:keys [db selection]} (th/confirm-selection
                                   (:db result) sel #{selected-card})]
      ;; Selected card should be in hand
      (is (= :hand (th/get-object-zone db selected-card))
          "Selected card should be moved to hand")
      ;; Should chain to order-bottom for remaining 3
      (is (some? selection)
          "Should chain to a pending selection")
      (is (= :order-bottom (:selection/type selection))
          "Chained selection should be :order-bottom")
      (is (= 3 (count (:selection/candidates selection)))
          "Order-bottom should have 3 cards (the non-selected peeked cards)"))))


(deftest impulse-full-resolution-order-bottom-test
  ;; Oracle: "rest on the bottom of your library in any order"
  (testing "After order-bottom, remainder cards are at bottom of library"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note]
                                                :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)
          selected-card (first lib-ids)
          ;; Confirm peek-and-select via production path
          {:keys [db selection]} (th/confirm-selection
                                   (:db result) sel #{selected-card})
          remainder-ids (vec (:selection/candidates selection))
          ;; Confirm order-bottom with a specific ordering via production path
          order-sel (assoc selection :selection/ordered remainder-ids)
          {:keys [db]} (th/confirm-selection db order-sel #{})]
      ;; All 3 remainder cards should still be in library
      (doseq [r-id remainder-ids]
        (is (= :library (th/get-object-zone db r-id))
            "Remainder card should be in library"))
      ;; The 5th card (not peeked) should still be in library too
      (is (= :library (th/get-object-zone db (nth lib-ids 4)))
          "Unpeeked card should still be in library"))))


;; === C. Cannot-Cast Guards ===

;; Oracle: mana cost {1}{U} -- cannot cast without U
(deftest impulse-cannot-cast-without-mana-test
  (testing "Cannot cast Impulse without sufficient mana"
    (let [db (th/create-test-db)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 imp-id))
          "Should not be castable without mana"))))


(deftest impulse-cannot-cast-with-only-blue-test
  (testing "Cannot cast Impulse with only 1 blue (needs 1U)"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 imp-id))
          "Should not be castable with only 1 blue mana"))))


;; Instant must be cast from hand
(deftest impulse-cannot-cast-from-graveyard-test
  (testing "Cannot cast Impulse from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db imp-id] (th/add-card-to-zone db :impulse :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 imp-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest impulse-increments-storm-count-test
  (testing "Casting Impulse increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze :careful-study]
                                                 :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Selection Tests ===

;; Oracle: "Put one of them into your hand"
(deftest impulse-select-first-of-four-test
  (testing "Selecting card 1 of 4: card 1 to hand, 3 others chain to order-bottom"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze :careful-study]
                                                :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)
          first-card (first lib-ids)
          {:keys [db selection]} (th/confirm-selection
                                   (:db result) sel #{first-card})]
      (is (= :hand (th/get-object-zone db first-card))
          "First card should be in hand")
      (let [remainder (set (rest (take 4 lib-ids)))]
        (is (= :order-bottom (:selection/type selection))
            "Should chain to order-bottom")
        (is (= remainder (:selection/candidates selection))
            "Order-bottom candidates should be the 3 non-selected peeked cards")))))


;; Oracle: "Put one of them into your hand" — fail-to-find (select 0)
(deftest impulse-fail-to-find-all-to-bottom-test
  (testing "Fail-to-find (select 0): all 4 chain to order-bottom"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze :careful-study]
                                                 :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)
          ;; Select nothing (fail-to-find) via production path
          {:keys [selection]} (th/confirm-selection
                                (:db result) sel #{})]
      ;; Should chain to order-bottom for all 4 candidates
      (is (= :order-bottom (:selection/type selection))
          "Should chain to order-bottom")
      (is (= 4 (count (:selection/candidates selection)))
          "Order-bottom should have all 4 cards"))))


;; === G. Edge Cases ===

;; Oracle: "Look at the top four cards" — with only 2 cards
(deftest impulse-two-card-library-test
  (testing "Library has 2 cards: peek 2, select 1, remainder to bottom"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= :peek-and-select (:selection/type sel))
          "Should still create peek-and-select")
      (is (= 2 (count (:selection/candidates sel)))
          "Should peek at 2 cards (all available)")
      ;; Select 1 — only 1 remainder, no order-bottom chain needed
      (let [selected-card (first lib-ids)
            {:keys [db selection]} (th/confirm-selection
                                     (:db result) sel #{selected-card})]
        (is (= :hand (th/get-object-zone db selected-card))
            "Selected card should be in hand")
        ;; With only 1 remainder, no order-bottom chain (< 2 remainder)
        (is (nil? selection)
            "Should not chain to order-bottom with only 1 remainder card")))))


(deftest impulse-one-card-library-test
  (testing "Library has 1 card: peek 1, can select it"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= :peek-and-select (:selection/type sel))
          "Should still create peek-and-select")
      (is (= 1 (count (:selection/candidates sel)))
          "Should peek at 1 card (all available)")
      (let [only-card (first lib-ids)
            {:keys [db]} (th/confirm-selection
                           (:db result) sel #{only-card})]
        (is (= :hand (th/get-object-zone db only-card))
            "Only card should be moved to hand")))))


(deftest impulse-empty-library-test
  (testing "Empty library: no selection created"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db imp-id] (th/add-card-to-zone db :impulse :hand :player-1)
          db-cast (rules/cast-spell db :player-1 imp-id)
          result (game/resolve-one-item db-cast)]
      (is (nil? (:pending-selection result))
          "No selection should be created for empty library"))))
