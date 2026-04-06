(ns fizzle.cards.blue.mental-note-test
  "Tests for Mental Note instant card.

   Mental Note: U - Instant
   Put the top two cards of your library into your graveyard, then draw a card.

   This tests:
   - Card definition (type, cost, effects)
   - Mill 2 from own library
   - Draw 1 card after milling
   - Effect ordering (mill first, then draw)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.mental-note :as mental-note]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-mental-note-card-definition
  (testing "Mental Note identity and core fields"
    (is (= :mental-note (:card/id mental-note/card))
        "Card id should be :mental-note")
    (is (= "Mental Note" (:card/name mental-note/card))
        "Card name should be 'Mental Note'")
    (is (= 1 (:card/cmc mental-note/card))
        "CMC should be 1")
    (is (= #{:blue} (:card/colors mental-note/card))
        "Colors should be #{:blue}")
    (is (= #{:instant} (:card/types mental-note/card))
        "Mental Note should be an instant")
    (is (= {:blue 1} (:card/mana-cost mental-note/card))
        "Mental Note should cost {U}")
    (is (= "Put the top two cards of your library into your graveyard, then draw a card."
           (:card/text mental-note/card))
        "Card text should match"))

  (testing "Mental Note has mill 2 and draw 1 effects in correct order"
    (let [card-effects (:card/effects mental-note/card)]
      (is (= 2 (count card-effects))
          "Mental Note should have 2 effects")
      (let [mill-effect (first card-effects)
            draw-effect (second card-effects)]
        (is (= :mill (:effect/type mill-effect))
            "First effect should be :mill")
        (is (= 2 (:effect/amount mill-effect))
            "Mill effect should mill 2")
        (is (= :draw (:effect/type draw-effect))
            "Second effect should be :draw")
        (is (= 1 (:effect/amount draw-effect))
            "Draw effect should draw 1")))))


;; === C. Cannot-Cast Guards ===

(deftest mental-note-cannot-cast-without-mana-test
  (testing "Cannot cast Mental Note without blue mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mental-note :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest mental-note-cannot-cast-from-graveyard-test
  (testing "Cannot cast Mental Note from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :mental-note :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest mental-note-increments-storm-count-test
  (testing "Casting Mental Note increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :island] :player-1)
          [db obj-id] (th/add-card-to-zone db :mental-note :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting Mental Note"))))


;; === Mill Position Tests ===

(deftest test-mental-note-mills-top-cards-via-production-path
  (testing "Mental Note mills the top 2 cards (not bottom) and draws the next"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                :player-1)
          top-card-id (first lib-ids)
          second-card-id (second lib-ids)
          third-card-id (nth lib-ids 2)
          bottom-card-id (nth lib-ids 3)
          [db mn-id] (th/add-card-to-zone db :mental-note :hand :player-1)
          db-after-resolve (th/cast-and-resolve db :player-1 mn-id)]
      ;; Top 2 cards should be milled to graveyard
      (is (= :graveyard (th/get-object-zone db-after-resolve top-card-id))
          "Top card should be milled to graveyard")
      (is (= :graveyard (th/get-object-zone db-after-resolve second-card-id))
          "Second card should be milled to graveyard")
      ;; Third card should be drawn to hand
      (is (= :hand (th/get-object-zone db-after-resolve third-card-id))
          "Third card should be drawn to hand")
      ;; Bottom card should still be in library
      (is (= :library (th/get-object-zone db-after-resolve bottom-card-id))
          "Bottom card should remain in library"))))


;; === Combined Effect Tests ===

(deftest test-mental-note-full-resolution
  (testing "Mental Note mills 2, then draws 1 when cast and resolved"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze :island :swamp]
                                                 :player-1)
          ;; Add Mental Note to hand
          [db mn-id] (th/add-card-to-zone db :mental-note :hand :player-1)
          _ (is (= 5 (th/get-zone-count db :library :player-1)) "Precondition: 5 cards in library")
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand (Mental Note)")
          _ (is (= 0 (th/get-zone-count db :graveyard :player-1)) "Precondition: graveyard empty")
          ;; Cast and resolve Mental Note
          db-after-resolve (th/cast-and-resolve db :player-1 mn-id)]
      ;; Mental Note goes to graveyard after resolution
      (is (= :graveyard (th/get-object-zone db-after-resolve mn-id))
          "Mental Note should be in graveyard after resolution")
      ;; 2 cards milled from library
      (is (= 2 (th/get-zone-count db-after-resolve :library :player-1))
          "Library should have 2 cards (5 - 2 milled - 1 drawn)")
      ;; 1 card drawn to hand
      (is (= 1 (th/get-hand-count db-after-resolve :player-1))
          "Hand should have 1 card (drew 1 after Mental Note left)")
      ;; 3 cards in graveyard (2 milled + Mental Note itself)
      (is (= 3 (th/get-zone-count db-after-resolve :graveyard :player-1))
          "Graveyard should have 3 cards (2 milled + Mental Note)"))))


;; === Edge Case Tests ===

(deftest test-mental-note-with-small-library
  (testing "Mental Note with only 1 card in library mills what's available, draws what remains"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Only 2 cards in library (will mill both, then fail to draw)
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual]
                                                 :player-1)
          ;; Add Mental Note to hand
          [db mn-id] (th/add-card-to-zone db :mental-note :hand :player-1)
          _ (is (= 2 (th/get-zone-count db :library :player-1)) "Precondition: 2 cards in library")
          ;; Cast and resolve
          db-after-resolve (th/cast-and-resolve db :player-1 mn-id)]
      ;; Both cards milled
      (is (= 0 (th/get-zone-count db-after-resolve :library :player-1))
          "Library should be empty after milling 2")
      ;; Graveyard has 2 milled + Mental Note
      (is (= 3 (th/get-zone-count db-after-resolve :graveyard :player-1))
          "Graveyard should have 3 cards (2 milled + Mental Note)")
      ;; Draw from empty library should set loss condition (via SBA)
      (let [db-after-sba (sba/check-and-execute-sbas db-after-resolve)]
        (is (= :empty-library (:game/loss-condition (q/get-game-state db-after-sba)))
            "Should set loss condition when drawing from empty library")))))


(deftest test-mental-note-contributes-to-threshold
  (testing "Mental Note adds 3 cards to graveyard (2 milled + itself)"
    ;; This is important for Iggy Pop: Mental Note helps reach threshold
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                 :player-1)
          [db mn-id] (th/add-card-to-zone db :mental-note :hand :player-1)
          _ (is (= 0 (th/get-zone-count db :graveyard :player-1)) "Precondition: graveyard empty")
          db-after-resolve (th/cast-and-resolve db :player-1 mn-id)]
      (is (= 3 (th/get-zone-count db-after-resolve :graveyard :player-1))
          "Mental Note should contribute 3 cards to threshold count"))))
