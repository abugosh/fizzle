(ns fizzle.cards.mental-note-test
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
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-mental-note-card-definition
  (testing "Mental Note type and cost"
    (is (= #{:instant} (:card/types cards/mental-note))
        "Mental Note should be an instant")
    (is (= {:blue 1} (:card/mana-cost cards/mental-note))
        "Mental Note should cost {U}"))

  (testing "Mental Note has mill 2 and draw 1 effects in correct order"
    (let [card-effects (:card/effects cards/mental-note)]
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


;; === Mill Effect Tests ===

(deftest test-mental-note-mills-from-own-library
  (testing "Mental Note mills 2 cards from caster's own library"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                  :player-1)
          _ (is (= 4 (th/get-zone-count db' :library :player-1)) "Precondition: 4 cards in library")
          _ (is (= 0 (th/get-zone-count db' :graveyard :player-1)) "Precondition: graveyard empty")
          ;; Execute mill effect directly
          mill-effect {:effect/type :mill
                       :effect/amount 2}
          db-after-mill (effects/execute-effect db' :player-1 mill-effect)]
      (is (= 2 (th/get-zone-count db-after-mill :library :player-1))
          "Library should have 2 cards remaining")
      (is (= 2 (th/get-zone-count db-after-mill :graveyard :player-1))
          "Graveyard should have 2 cards"))))


(deftest test-mental-note-mills-top-cards
  (testing "Mental Note mills from top of library (lowest position)"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db' lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze]
                                                 :player-1)
          top-card-id (first lib-ids)
          second-card-id (second lib-ids)
          bottom-card-id (nth lib-ids 2)
          ;; Execute mill effect
          mill-effect {:effect/type :mill
                       :effect/amount 2}
          db-after-mill (effects/execute-effect db' :player-1 mill-effect)]
      ;; Top 2 cards should be in graveyard
      (is (= :graveyard (th/get-object-zone db-after-mill top-card-id))
          "Top card should be milled to graveyard")
      (is (= :graveyard (th/get-object-zone db-after-mill second-card-id))
          "Second card should be milled to graveyard")
      ;; Bottom card should still be in library
      (is (= :library (th/get-object-zone db-after-mill bottom-card-id))
          "Bottom card should remain in library"))))


;; === Draw Effect Tests ===

(deftest test-mental-note-draws-one-card
  (testing "Mental Note draws 1 card after milling"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze]
                                                  :player-1)
          initial-hand-count (th/get-hand-count db' :player-1)
          _ (is (= 0 initial-hand-count) "Precondition: hand empty")
          ;; Execute draw effect directly
          draw-effect {:effect/type :draw
                       :effect/amount 1}
          db-after-draw (effects/execute-effect db' :player-1 draw-effect)]
      (is (= 1 (th/get-hand-count db-after-draw :player-1))
          "Hand should have 1 card after drawing"))))


;; === Combined Effect Tests ===

(deftest test-mental-note-full-resolution
  (testing "Mental Note mills 2, then draws 1 when cast and resolved"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze :island :swamp]
                                                  :player-1)
          ;; Add Mental Note to hand
          [db'' mn-id] (th/add-card-to-zone db' :mental-note :hand :player-1)
          _ (is (= 5 (th/get-zone-count db'' :library :player-1)) "Precondition: 5 cards in library")
          _ (is (= 1 (th/get-hand-count db'' :player-1)) "Precondition: 1 card in hand (Mental Note)")
          _ (is (= 0 (th/get-zone-count db'' :graveyard :player-1)) "Precondition: graveyard empty")
          ;; Cast Mental Note
          db-after-cast (rules/cast-spell db'' :player-1 mn-id)
          _ (is (= :stack (th/get-object-zone db-after-cast mn-id))
                "Mental Note should be on stack after casting")
          ;; Resolve spell
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 mn-id)]
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
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual]
                                                  :player-1)
          ;; Add Mental Note to hand
          [db'' mn-id] (th/add-card-to-zone db' :mental-note :hand :player-1)
          _ (is (= 2 (th/get-zone-count db'' :library :player-1)) "Precondition: 2 cards in library")
          ;; Cast and resolve
          db-after-cast (rules/cast-spell db'' :player-1 mn-id)
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 mn-id)]
      ;; Both cards milled
      (is (= 0 (th/get-zone-count db-after-resolve :library :player-1))
          "Library should be empty after milling 2")
      ;; Graveyard has 2 milled + Mental Note
      (is (= 3 (th/get-zone-count db-after-resolve :graveyard :player-1))
          "Graveyard should have 3 cards (2 milled + Mental Note)")
      ;; Draw from empty library should set loss condition
      (is (= :empty-library (:game/loss-condition (q/get-game-state db-after-resolve)))
          "Should set loss condition when drawing from empty library"))))


(deftest test-mental-note-contributes-to-threshold
  (testing "Mental Note adds 3 cards to graveyard (2 milled + itself)"
    ;; This is important for Iggy Pop: Mental Note helps reach threshold
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                  :player-1)
          [db'' mn-id] (th/add-card-to-zone db' :mental-note :hand :player-1)
          _ (is (= 0 (th/get-zone-count db'' :graveyard :player-1)) "Precondition: graveyard empty")
          db-after-cast (rules/cast-spell db'' :player-1 mn-id)
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 mn-id)]
      (is (= 3 (th/get-zone-count db-after-resolve :graveyard :player-1))
          "Mental Note should contribute 3 cards to threshold count"))))
