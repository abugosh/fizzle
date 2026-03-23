(ns fizzle.cards.blue.accumulated-knowledge-test
  "Tests for Accumulated Knowledge card.

   Accumulated Knowledge: {1}{U} - Instant
   Draw a card, then draw cards equal to the number of cards named
   Accumulated Knowledge in all graveyards.

   Ruling (2018-03-16): Because Accumulated Knowledge is still on the
   stack as you perform its instructions, it isn't in your graveyard
   and won't add to the number of cards drawn."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.accumulated-knowledge :as ak]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Accumulated Knowledge" / {1}{U} / Instant
(deftest accumulated-knowledge-card-definition-test
  (testing "AK card fields match Scryfall data"
    (let [card ak/card]
      (is (= :accumulated-knowledge (:card/id card)))
      (is (= "Accumulated Knowledge" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "Draw a card, then draw cards equal to the number of cards named Accumulated Knowledge in all graveyards."
             (:card/text card)))))

  ;; Oracle: "Draw a card, then draw cards equal to the number..."
  (testing "AK uses dynamic draw amount"
    (let [effects (:card/effects ak/card)]
      (is (= 1 (count effects))
          "Should have exactly 1 effect")
      (let [effect (first effects)]
        (is (= :draw (:effect/type effect))
            "Effect type should be :draw")
        (is (map? (:effect/amount effect))
            "Amount should be a dynamic map, not a static integer")
        (is (= :count-named-in-zone (get-in effect [:effect/amount :dynamic/type]))
            "Dynamic type should be :count-named-in-zone")
        (is (= :graveyard (get-in effect [:effect/amount :dynamic/zone]))
            "Should count cards in graveyards")
        (is (= 1 (get-in effect [:effect/amount :dynamic/plus]))
            "Should add 1 to the count (the base draw)")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Draw a card, then draw cards equal to the number of cards named
;; Accumulated Knowledge in all graveyards."
;; With 0 AK in graveyards: draw 0+1 = 1
(deftest accumulated-knowledge-cast-resolve-draws-one-test
  (testing "Cast AK with no AK in graveyard: draws 1 card"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          initial-hand (th/get-hand-count db :player-1)
          _ (is (= 1 initial-hand) "Precondition: hand has 1 card (AK)")
          db-cast (rules/cast-spell db :player-1 ak-id)
          _ (is (= :stack (th/get-object-zone db-cast ak-id))
                "AK should be on stack after casting")
          result (resolution/resolve-one-item db-cast)]
      ;; Should draw 1 card (0 AK in graveyards + 1)
      (is (= 1 (th/get-hand-count (:db result) :player-1))
          "Should draw 1 card with 0 AK in graveyards"))))


;; === C. Cannot-Cast Guards ===

;; Oracle: mana cost {1}{U} — cannot cast without mana
(deftest accumulated-knowledge-cannot-cast-without-mana-test
  (testing "Cannot cast AK without mana"
    (let [db (th/create-test-db)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 ak-id))
          "Should not be castable without mana"))))


(deftest accumulated-knowledge-cannot-cast-with-only-blue-test
  (testing "Cannot cast AK with only 1 blue (needs 1U)"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 ak-id))
          "Should not be castable with only 1 blue mana"))))


(deftest accumulated-knowledge-cannot-cast-from-graveyard-test
  (testing "Cannot cast AK from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 ak-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest accumulated-knowledge-increments-storm-count-test
  (testing "Casting AK increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-cast (rules/cast-spell db :player-1 ak-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === G. Edge Cases (critical for dynamic amount) ===

;; Oracle: "...equal to the number of cards named Accumulated Knowledge in all graveyards"
;; 1 AK in graveyard → draw 1+1 = 2
(deftest accumulated-knowledge-one-in-graveyard-draws-two-test
  (testing "1 AK in graveyard: draws 2 cards"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          ;; Put 1 AK in graveyard
          [db _gy-ids] (th/add-cards-to-graveyard db [:accumulated-knowledge] :player-1)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          db-cast (rules/cast-spell db :player-1 ak-id)
          result (resolution/resolve-one-item db-cast)]
      ;; Should draw 2 cards (1 AK in graveyard + 1)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "Should draw 2 cards with 1 AK in graveyard"))))


;; 2 AK in graveyard → draw 2+1 = 3
(deftest accumulated-knowledge-two-in-graveyard-draws-three-test
  (testing "2 AK in graveyards: draws 3 cards"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          ;; Put 2 AK in graveyard
          [db _gy-ids] (th/add-cards-to-graveyard db
                                                  [:accumulated-knowledge :accumulated-knowledge]
                                                  :player-1)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          db-cast (rules/cast-spell db :player-1 ak-id)
          result (resolution/resolve-one-item db-cast)]
      ;; Should draw 3 cards (2 AK in graveyard + 1)
      (is (= 3 (th/get-hand-count (:db result) :player-1))
          "Should draw 3 cards with 2 AK in graveyards"))))


;; Oracle: "in all graveyards" — opponent's graveyard counts
(deftest accumulated-knowledge-opponent-graveyard-counts-test
  (testing "AK in opponent's graveyard counts toward draw amount"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          ;; Put 1 AK in opponent's graveyard
          [db _gy-ids] (th/add-cards-to-graveyard db [:accumulated-knowledge] :player-2)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          db-cast (rules/cast-spell db :player-1 ak-id)
          result (resolution/resolve-one-item db-cast)]
      ;; Should draw 2 cards (1 AK in opponent's graveyard + 1)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "Should draw 2 cards with 1 AK in opponent's graveyard"))))


;; Oracle: "in all graveyards" — sum across both players
(deftest accumulated-knowledge-both-graveyards-sum-test
  (testing "AK in both players' graveyards are summed"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          db (th/add-opponent db)
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          ;; Put 1 AK in player-1's graveyard, 1 in player-2's
          [db _gy1] (th/add-cards-to-graveyard db [:accumulated-knowledge] :player-1)
          [db _gy2] (th/add-cards-to-graveyard db [:accumulated-knowledge] :player-2)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          db-cast (rules/cast-spell db :player-1 ak-id)
          result (resolution/resolve-one-item db-cast)]
      ;; Should draw 3 cards (1 in own gy + 1 in opp gy + 1 base)
      (is (= 3 (th/get-hand-count (:db result) :player-1))
          "Should draw 3 cards with AK in both graveyards"))))


;; Ruling (2018-03-16): "Because Accumulated Knowledge is still on the stack
;; as you perform its instructions, it isn't in your graveyard and won't add
;; to the number of cards drawn."
(deftest accumulated-knowledge-on-stack-does-not-count-test
  (testing "AK on stack (being resolved) does NOT count as in graveyard"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          ;; No AK in any graveyard — the one being cast is on the stack
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          _ (is (= :hand (th/get-object-zone db ak-id))
                "Precondition: AK is in hand")
          db-cast (rules/cast-spell db :player-1 ak-id)
          _ (is (= :stack (th/get-object-zone db-cast ak-id))
                "Precondition: AK is on stack")
          result (resolution/resolve-one-item db-cast)]
      ;; AK on stack should not count — draws 0+1 = 1
      (is (= 1 (th/get-hand-count (:db result) :player-1))
          "AK on stack should not count toward draw amount"))))


;; Oracle: "Draw a card, then draw cards equal to..."
;; If library has fewer cards than draw amount, draws available + loss condition
(deftest accumulated-knowledge-partial-library-test
  (testing "Library has fewer cards than draw amount"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          ;; Only 1 card in library, but should draw 3
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _gy-ids] (th/add-cards-to-graveyard db
                                                  [:accumulated-knowledge :accumulated-knowledge]
                                                  :player-1)
          [db ak-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          db-cast (rules/cast-spell db :player-1 ak-id)
          result (resolution/resolve-one-item db-cast)]
      ;; Should draw the 1 available card
      (is (= 1 (th/get-hand-count (:db result) :player-1))
          "Should draw available cards")
      ;; Loss condition should be set (tried to draw 3 but only 1 available)
      (let [db-after-sba (sba/check-and-execute-sbas (:db result))]
        (is (= :empty-library (:game/loss-condition (q/get-game-state db-after-sba)))
            "Should set loss condition when library runs out")))))


;; After AK resolves and goes to graveyard, the next AK draw count increases
(deftest accumulated-knowledge-sequential-cast-increases-test
  (testing "After first AK resolves to graveyard, second AK draws more"
    (let [db (th/create-test-db {:mana {:blue 2 :colorless 2}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note :opt
                                                  :dark-ritual :cabal-ritual]
                                                 :player-1)
          ;; Cast first AK
          [db ak1-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          db-cast-1 (rules/cast-spell db :player-1 ak1-id)
          result-1 (resolution/resolve-one-item db-cast-1)
          ;; First AK: 0 in graveyard + 1 = draw 1
          _ (is (= 1 (th/get-hand-count (:db result-1) :player-1))
                "First AK should draw 1")
          ;; After resolving, AK1 should be in graveyard
          _ (is (= :graveyard (th/get-object-zone (:db result-1) ak1-id))
                "First AK should be in graveyard after resolution")
          ;; Cast second AK
          [db2 ak2-id] (th/add-card-to-zone (:db result-1) :accumulated-knowledge :hand :player-1)
          db2 (-> db2
                  (th/add-card-to-zone :island :battlefield :player-1)
                  first
                  (th/add-card-to-zone :island :battlefield :player-1)
                  first)
          ;; Need to re-add mana for second cast
          db2-with-mana (let [conn (d/conn-from-db db2)
                              player-eid (q/get-player-eid db2 :player-1)]
                          (d/transact! conn
                                       [[:db/add player-eid :player/mana-pool
                                         {:white 0 :blue 1 :black 0 :red 0 :green 0 :colorless 1}]])
                          @conn)
          db-cast-2 (rules/cast-spell db2-with-mana :player-1 ak2-id)
          result-2 (resolution/resolve-one-item db-cast-2)
          ;; After first AK resolved, hand had 1 card. Second AK was added to hand (+1 = 2).
          ;; Then casting AK2 removes it from hand (hand = 1). Second AK draws 2 (1 AK in gy + 1).
          ;; So final hand = 1 + 2 = 3
          final-hand (th/get-hand-count (:db result-2) :player-1)]
      (is (= 3 final-hand)
          "Second AK should draw 2 (1 AK in graveyard + 1)"))))
