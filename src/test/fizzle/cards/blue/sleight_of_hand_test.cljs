(ns fizzle.cards.blue.sleight-of-hand-test
  "Tests for Sleight of Hand card.

   Sleight of Hand: {U} - Sorcery
   Look at the top two cards of your library. Put one of them into your
   hand and the other on the bottom of your library.

   Ruling (2023-09-01): If there is only one card in your library,
   you put it into your hand."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.sleight-of-hand :as sleight-of-hand]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Sleight of Hand" / {U} / Sorcery
(deftest sleight-of-hand-card-definition-test
  (testing "Sleight of Hand card fields match Scryfall data"
    (let [card sleight-of-hand/card]
      (is (= :sleight-of-hand (:card/id card)))
      (is (= "Sleight of Hand" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:sorcery} (:card/types card)))
      (is (= "Look at the top two cards of your library. Put one of them into your hand and the other on the bottom of your library."
             (:card/text card)))))

  ;; Oracle: "Look at the top two cards...Put one...into your hand...the other on the bottom"
  (testing "Sleight of Hand uses peek-and-select effect"
    (let [effects (:card/effects sleight-of-hand/card)]
      (is (= 1 (count effects))
          "Should have exactly 1 effect")
      (let [effect (first effects)]
        (is (= :peek-and-select (:effect/type effect)))
        (is (= 2 (:effect/count effect))
            "Peek at top 2 cards")
        (is (= 1 (:effect/select-count effect))
            "Select exactly 1 card")
        (is (= :hand (:effect/selected-zone effect))
            "Selected card goes to hand")
        (is (= :bottom-of-library (:effect/remainder-zone effect))
            "Remainder goes to bottom of library")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Look at the top two cards of your library. Put one of them into
;; your hand and the other on the bottom of your library."
(deftest sleight-of-hand-cast-resolve-test
  (testing "Cast with U, resolve creates peek-and-select selection"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze]
                                                :player-1)
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)
          _ (is (= :stack (th/get-object-zone db-cast soh-id))
                "Should be on stack after casting")
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= :peek-and-select (:selection/type sel))
          "Selection type should be :peek-and-select")
      (is (= 2 (count (:selection/candidates sel)))
          "Should peek at 2 cards")
      (is (= (set (take 2 lib-ids)) (:selection/candidates sel))
          "Peeked cards should be the top 2 of library")
      (is (= 1 (:selection/select-count sel))
          "Should select exactly 1 card"))))


(deftest sleight-of-hand-full-resolution-test
  ;; Oracle: "Put one of them into your hand and the other on the bottom"
  (testing "After selection, selected card goes to hand, other to bottom"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze]
                                                :player-1)
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)
          selected-card (first lib-ids)
          {:keys [db]} (th/confirm-selection
                         (:db result) sel #{selected-card})]
      ;; Selected card should be in hand
      (is (= :hand (th/get-object-zone db selected-card))
          "Selected card should be moved to hand")
      ;; Non-selected card should not be in hand
      (let [other-card (second lib-ids)]
        (is (not= :hand (th/get-object-zone db other-card))
            "Non-selected card should not be in hand")))))


;; === C. Cannot-Cast Guards ===

;; Oracle: mana cost {U} — cannot cast without U
(deftest sleight-of-hand-cannot-cast-without-mana-test
  (testing "Cannot cast Sleight of Hand without blue mana"
    (let [db (th/create-test-db)
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 soh-id))
          "Should not be castable without blue mana"))))


;; Sorcery must be cast from hand
(deftest sleight-of-hand-cannot-cast-from-graveyard-test
  (testing "Cannot cast Sleight of Hand from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 soh-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest sleight-of-hand-increments-storm-count-test
  (testing "Casting Sleight of Hand increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Selection Tests ===

;; Oracle: "Put one of them into your hand and the other on the bottom"
(deftest sleight-of-hand-select-first-card-test
  (testing "Selecting card 1 of 2: card 1 to hand, card 2 to bottom"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :brain-freeze]
                                                :player-1)
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)
          first-card (first lib-ids)
          second-card (second lib-ids)
          {:keys [db]} (th/confirm-selection
                         (:db result) sel #{first-card})]
      (is (= :hand (th/get-object-zone db first-card))
          "First card should be in hand")
      (is (= :library (th/get-object-zone db second-card))
          "Second card should be in library (bottom)"))))


(deftest sleight-of-hand-select-second-card-test
  ;; Oracle: "Put one of them into your hand and the other on the bottom"
  (testing "Selecting card 2 of 2: card 2 to hand, card 1 to bottom"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :brain-freeze]
                                                :player-1)
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)
          first-card (first lib-ids)
          second-card (second lib-ids)
          {:keys [db]} (th/confirm-selection
                         (:db result) sel #{second-card})]
      (is (= :hand (th/get-object-zone db second-card))
          "Second card should be in hand")
      (is (= :library (th/get-object-zone db first-card))
          "First card should be in library (bottom)"))))


;; === G. Edge Cases ===

;; Ruling (2023-09-01): "If there is only one card in your library,
;; you put it into your hand."
(deftest sleight-of-hand-one-card-library-test
  (testing "Library has only 1 card: peek 1, select it for hand"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; Should peek at the 1 available card
      (is (= :peek-and-select (:selection/type sel))
          "Should still create peek-and-select selection")
      (is (= 1 (count (:selection/candidates sel)))
          "Should peek at 1 card (all available)")
      ;; Select the 1 card
      (let [only-card (first lib-ids)
            {:keys [db]} (th/confirm-selection
                           (:db result) sel #{only-card})]
        (is (= :hand (th/get-object-zone db only-card))
            "Only card should be moved to hand")))))


(deftest sleight-of-hand-empty-library-test
  (testing "Empty library: no selection created"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; No library cards
          [db soh-id] (th/add-card-to-zone db :sleight-of-hand :hand :player-1)
          db-cast (rules/cast-spell db :player-1 soh-id)
          result (resolution/resolve-one-item db-cast)]
      ;; With empty library, peek has 0 cards — spell resolves without selection
      (is (nil? (:pending-selection result))
          "No selection should be created for empty library"))))
