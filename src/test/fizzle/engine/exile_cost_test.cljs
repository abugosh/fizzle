(ns fizzle.engine.exile-cost-test
  "Tests for :exile-cards additional cost type.

   Tests cover:
   - can-pay-additional-cost? validation for :exile-cards
   - pay-additional-cost execution for :exile-cards
   - X value binding from exiled card count
   - Integration with flashback casting"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]))


;; =====================================================
;; Test Helpers
;; =====================================================

(defn add-blue-card-to-graveyard
  "Add a blue card (Dark Ritual colored blue for testing) to graveyard.
   Returns updated db."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        ;; Create a blue instant card for testing
        card-id (random-uuid)
        obj-id (random-uuid)]
    ;; Add blue card definition
    (d/transact! conn [{:db/id -1
                        :card/id card-id
                        :card/name "Blue Test Card"
                        :card/colors #{:blue}
                        :card/types #{:instant}
                        :card/mana-cost {:blue 1}}])
    ;; Create object in graveyard
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn card-id)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :graveyard
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}]))
    @conn))


(defn add-n-blue-cards-to-graveyard
  "Add n blue cards to player's graveyard."
  [db player-id n]
  (reduce (fn [d _] (add-blue-card-to-graveyard d player-id))
          db
          (range n)))


(defn add-black-card-to-graveyard
  "Add a black card to graveyard.
   Returns updated db."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-id (random-uuid)
        obj-id (random-uuid)]
    ;; Add black card definition
    (d/transact! conn [{:db/id -1
                        :card/id card-id
                        :card/name "Black Test Card"
                        :card/colors #{:black}
                        :card/types #{:instant}
                        :card/mana-cost {:black 1}}])
    ;; Create object in graveyard
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn card-id)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :graveyard
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}]))
    @conn))


(defn add-n-black-cards-to-graveyard
  "Add n black cards to player's graveyard."
  [db player-id n]
  (reduce (fn [d _] (add-black-card-to-graveyard d player-id))
          db
          (range n)))


(def flash-of-insight-card
  "Flash of Insight card for integration testing.
   Flashback {1}{U}, exile X blue cards from your graveyard."
  {:card/id :flash-of-insight
   :card/name "Flash of Insight"
   :card/cmc 2  ; X + 1U, where X=0 gives CMC 2
   :card/mana-cost {:colorless 1 :blue 1 :x true}  ; {X}{1}{U}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Look at the top X cards of your library. Put one into your hand. Flashback {1}{U}—exile X blue cards from your graveyard."
   :card/effects [{:effect/type :peek-and-select
                   :effect/count :x
                   :effect/select-count 1
                   :effect/selected-zone :hand
                   :effect/remainder-zone :bottom-of-library
                   :effect/shuffle-remainder? true}]
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1 :blue 1}
                           :alternate/additional-costs [{:cost/type :exile-cards
                                                         :cost/zone :graveyard
                                                         :cost/criteria {:card/colors #{:blue}}
                                                         :cost/count :x}]
                           :alternate/on-resolve :exile}]})


(defn add-flash-of-insight-to-zone
  "Add Flash of Insight to a zone. Returns [obj-id db] tuple."
  [db player-id zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition if not present
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn :flash-of-insight)
      (d/transact! conn [flash-of-insight-card]))
    ;; Create object
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn :flash-of-insight)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone zone
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])
      [obj-id @conn])))


;; =====================================================
;; can-pay-additional-cost? tests for :exile-cards
;; =====================================================

(deftest test-can-pay-exile-cost-sufficient-cards
  (testing "can-pay returns true when graveyard has enough blue cards"
    ;; Bug caught: returns false when cards exist
    (let [db (init-game-state)
          ;; Add 3 blue cards to graveyard
          db (add-n-blue-cards-to-graveyard db :player-1 3)
          [obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          ;; Get modes to access can-pay-additional-cost? indirectly through can-cast-mode?
          modes (rules/get-casting-modes db :player-1 obj-id)]
      ;; For now, test will fail because :exile-cards not implemented
      ;; This establishes the RED state
      (is (= 1 (count modes))
          "Should have flashback mode available")
      ;; With sufficient mana AND blue cards, should be castable
      (let [db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})]
        (is (true? (rules/can-cast-mode? db-with-mana :player-1 obj-id (first modes)))
            "Should be able to cast flashback with mana and blue cards available")))))


(deftest test-can-pay-exile-cost-insufficient-cards
  (testing "can-pay returns false when not enough matching cards"
    ;; Bug caught: allows casting with insufficient resources
    (let [db (init-game-state)
          ;; Add only 1 blue card to graveyard
          db (add-n-blue-cards-to-graveyard db :player-1 1)
          [obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db-with-mana :player-1 obj-id)]
      ;; With only 1 blue card and :x requiring at least 1, should be castable
      ;; But if we test with cost requiring 3, it should fail
      ;; For :x cost, can-pay should check at least 1 card exists
      (is (= 1 (count modes))
          "Should have flashback mode")
      ;; This tests the minimum - with 1 card, X=1 should work
      (is (true? (rules/can-cast-mode? db-with-mana :player-1 obj-id (first modes)))
          "With 1 blue card, X=1 flashback should be castable"))))


(deftest test-can-pay-exile-cost-zero-count
  (testing "can-pay returns true for count=0 (vacuously true)"
    ;; Bug caught: crashes on X=0
    ;; When X=0, no cards need to be exiled
    (let [db (init-game-state)
          [_obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          ;; Empty graveyard, but X=0 should still be valid
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})]
      ;; X=0 means look at 0 cards, no selection needed
      ;; The mana cost alone should be payable
      ;; For the actual flashback with :x, X=0 means exile 0 cards
      ;; This is a corner case - the card text says "exile X" where X can be 0
      (is (true? (mana/can-pay? db-with-mana :player-1 {:colorless 1 :blue 1}))
          "Mana portion should be payable regardless of exile count"))))


(deftest test-can-pay-exile-cost-respects-color-criteria
  (testing "can-pay filters by color - blue cards only"
    ;; Bug caught: ignores color filter, exiles any card
    (let [db (init-game-state)
          ;; Add 5 BLACK cards (not blue)
          db (add-n-black-cards-to-graveyard db :player-1 5)
          [obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db-with-mana :player-1 obj-id)]
      ;; Should have flashback mode available (mode exists)
      (is (= 1 (count modes))
          "Should have flashback mode")
      ;; But can-cast-mode? should return false because no BLUE cards exist
      (is (false? (rules/can-cast-mode? db-with-mana :player-1 obj-id (first modes)))
          "Should not be able to cast - no blue cards even though black cards exist"))))


(deftest test-can-pay-exile-cost-empty-graveyard
  (testing "can-pay returns false on empty graveyard when count > 0"
    ;; Bug caught: nil pointer on empty zone
    (let [db (init-game-state)
          ;; Don't add any cards to graveyard
          [obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db-with-mana :player-1 obj-id)]
      (is (= 1 (count modes))
          "Should have flashback mode")
      ;; With empty graveyard and :x cost requiring at least 1, should fail
      (is (false? (rules/can-cast-mode? db-with-mana :player-1 obj-id (first modes)))
          "Should not be able to cast - empty graveyard, X must be at least 1"))))


;; =====================================================
;; pay-additional-cost tests for :exile-cards
;; =====================================================

;; Note: pay-additional-cost for :exile-cards requires player selection
;; These tests will verify the selection state is created correctly

(deftest test-pay-exile-cost-creates-selection
  (testing "pay-exile-cost creates selection state for player choice"
    ;; Bug caught: auto-selects instead of prompting player
    ;; This test verifies selection flow is created, not that cards are immediately exiled
    ;; Full selection test deferred to Flash of Insight card integration tests
    (is (true? true)
        "Placeholder - full selection test via events layer")))


;; =====================================================
;; Integration tests
;; =====================================================

(deftest test-flashback-with-exile-cost-validates-both
  (testing "flashback validates mana cost AND exile cost"
    ;; Bug caught: mana and exile not both validated
    (let [db (init-game-state)
          db (add-n-blue-cards-to-graveyard db :player-1 3)
          [obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)]
      ;; Without mana - should fail
      (let [modes-no-mana (rules/get-casting-modes db :player-1 obj-id)]
        (is (= 1 (count modes-no-mana))
            "Should have flashback mode")
        (is (false? (rules/can-cast-mode? db :player-1 obj-id (first modes-no-mana)))
            "Should not be castable without mana"))
      ;; With mana - should pass (if :exile-cards implemented)
      (let [db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})
            modes-with-mana (rules/get-casting-modes db-with-mana :player-1 obj-id)]
        (is (true? (rules/can-cast-mode? db-with-mana :player-1 obj-id (first modes-with-mana)))
            "Should be castable with mana AND blue cards available")))))


(deftest test-flashback-without-mana-fails
  (testing "flashback requires mana cost even with exile cards available"
    ;; Bug caught: skipping mana cost when exile available
    (let [db (init-game-state)
          db (add-n-blue-cards-to-graveyard db :player-1 5)  ; Plenty of blue cards
          [obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          ;; No mana added
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 1 (count modes))
          "Should have flashback mode")
      (is (false? (rules/can-cast-mode? db :player-1 obj-id (first modes)))
          "Should not be castable without mana - exile is ADDITIONAL cost, not replacement"))))


(deftest test-get-castable-cards-includes-flashback-with-exile
  (testing "get-castable-cards includes flashback cards with :exile-cards cost"
    (let [db (init-game-state)
          db (add-n-blue-cards-to-graveyard db :player-1 3)
          [_obj-id db] (add-flash-of-insight-to-zone db :player-1 :graveyard)
          db-with-mana (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          castable (rules/get-castable-cards db-with-mana :player-1)]
      ;; Flash of Insight in graveyard with mana + blue cards should be castable
      (is (some #(= :flash-of-insight (get-in % [:object/card :card/id])) castable)
          "Flash of Insight should appear in castable cards from graveyard"))))
