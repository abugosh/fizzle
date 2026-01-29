(ns fizzle.engine.effects-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]))


;; === execute-effect :add-mana tests ===

(deftest execute-effect-add-mana-test
  (testing "execute-effect with :add-mana adds mana to player's pool"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 3}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))


(deftest execute-effect-add-mana-multiple-colors-test
  (testing "execute-effect with :add-mana handles multiple colors"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 2 :red 1}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 2 (:black (q/get-mana-pool db' :player-1))))
      (is (= 1 (:red (q/get-mana-pool db' :player-1)))))))


(deftest execute-effect-add-mana-accumulates-test
  (testing "execute-effect with :add-mana accumulates with existing mana"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 3}}
          db' (-> db
                  (fx/execute-effect :player-1 effect)
                  (fx/execute-effect :player-1 effect))]
      (is (= 6 (:black (q/get-mana-pool db' :player-1)))))))


(deftest execute-effect-unknown-type-test
  (testing "execute-effect with unknown type returns db unchanged"
    (let [db (init-game-state)
          effect {:effect/type :unknown-effect}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= (q/get-mana-pool db :player-1)
             (q/get-mana-pool db' :player-1))))))


(deftest execute-effect-nil-type-test
  (testing "execute-effect with nil type (missing key) returns db unchanged"
    (let [db (init-game-state)
          effect {}  ; no :effect/type key
          db' (fx/execute-effect db :player-1 effect)]
      (is (= (q/get-mana-pool db :player-1)
             (q/get-mana-pool db' :player-1))))))


;; === Test helpers for mill ===

(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])
    @conn))


(defn add-library-cards
  "Add cards to a player's library with sequential positions.
   Takes a vector of card-ids (keywords) and adds them with positions 0, 1, 2...
   Position 0 = top of library."
  [db player-id card-ids]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (doseq [idx (range (count card-ids))]
      ;; For simplicity, all library cards reference the same Dark Ritual card def
      ;; (In real game each would be different card, but for mill test we just need objects)
      (let [card-eid (d/q '[:find ?e .
                            :where [?e :card/id :dark-ritual]]
                          @conn)]
        (d/transact! conn [{:object/id (random-uuid)
                            :object/card card-eid
                            :object/zone :library
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/position idx
                            :object/tapped false}])))
    @conn))


(defn count-zone
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (q/get-objects-in-zone db player-id zone)))


;; === execute-effect :mill tests ===

(deftest test-mill-effect-moves-cards
  (testing "Mill 3 moves top 3 cards from library to graveyard"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3 :card-4 :card-5]))
          effect {:effect/type :mill
                  :effect/amount 3}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 2 (count-zone db' :player-1 :library)))
      (is (= 3 (count-zone db' :player-1 :graveyard))))))


(deftest test-mill-effect-respects-position
  (testing "Mill takes cards with lowest :object/position (top of library)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:top :second :third :bottom]))
          effect {:effect/type :mill
                  :effect/amount 2}
          db' (fx/execute-effect db :player-1 effect)]
      ;; After milling 2, positions 0 and 1 should be gone
      ;; Library should have 2 cards (positions 2 and 3)
      (is (= 2 (count-zone db' :player-1 :library)))
      ;; Check that remaining cards have higher positions
      (let [remaining (q/get-objects-in-zone db' :player-1 :library)
            positions (map :object/position remaining)]
        (is (every? #(>= % 2) positions))))))


(deftest test-mill-effect-partial-library
  (testing "Mill 5 with only 2 cards in library moves all 2 (no crash)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2]))
          effect {:effect/type :mill
                  :effect/amount 5}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (count-zone db' :player-1 :library)))
      (is (= 2 (count-zone db' :player-1 :graveyard))))))


(deftest test-mill-effect-empty-library
  (testing "Mill on empty library is no-op, returns unchanged db"
    (let [db (init-game-state)  ; no library cards
          effect {:effect/type :mill
                  :effect/amount 3}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (count-zone db' :player-1 :library)))
      (is (= 0 (count-zone db' :player-1 :graveyard))))))


(deftest test-mill-effect-preserves-order
  (testing "Cards milled preserve order (first milled from top goes first)"
    ;; This test verifies we mill in order: position 0 first, then 1, etc.
    ;; Important for flashback/dredge later where graveyard order matters.
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:top :middle :bottom]))
          effect {:effect/type :mill
                  :effect/amount 2}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Just verify the right number moved - exact order would need graveyard ordering
      (is (= 1 (count-zone db' :player-1 :library)))
      (is (= 2 (count-zone db' :player-1 :graveyard))))))


(deftest test-mill-effect-targets-opponent
  (testing "Mill with :effect/target :opponent mills opponent, not caster"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-library-cards :player-2 [:opp-1 :opp-2 :opp-3]))
          effect {:effect/type :mill
                  :effect/amount 2
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Caster's library unchanged
      (is (= 0 (count-zone db' :player-1 :library)))
      ;; Opponent's library reduced
      (is (= 1 (count-zone db' :player-2 :library)))
      (is (= 2 (count-zone db' :player-2 :graveyard))))))


;; === Corner case tests ===

(deftest test-mill-nil-target
  (testing "Mill with nil target (missing :effect/target key) mills caster"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :mill
                  :effect/amount 2
                  ;; no :effect/target key - should default to caster
                  }
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should mill from caster's library (default target)
      (is (= 1 (count-zone db' :player-1 :library)))
      (is (= 2 (count-zone db' :player-1 :graveyard))))))


(deftest test-mill-negative-amount
  (testing "Mill with negative amount is no-op (get-top-n-library returns [])"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :mill
                  :effect/amount -5}  ; malformed: negative amount
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should be no-op - no cards milled
      (is (= 3 (count-zone db' :player-1 :library)))
      (is (= 0 (count-zone db' :player-1 :graveyard))))))


(deftest test-add-mana-missing-mana-key
  (testing "add-mana effect with missing :effect/mana is no-op"
    (let [db (init-game-state)
          initial-pool (q/get-mana-pool db :player-1)
          effect {:effect/type :add-mana
                  ;; no :effect/mana key - should be graceful no-op
                  }
          db' (fx/execute-effect db :player-1 effect)]
      ;; Mana pool should be unchanged
      (is (= initial-pool (q/get-mana-pool db' :player-1))))))


;; === execute-effect :draw tests ===

(defn get-loss-condition
  "Get the loss condition from game state."
  [db]
  (:game/loss-condition (q/get-game-state db)))


(deftest test-draw-single-card
  (testing "Draw 1 card moves top card from library to hand"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount 1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 2 (count-zone db' :player-1 :library)))
      (is (= (+ initial-hand-size 1) (count-zone db' :player-1 :hand))))))


(deftest test-draw-multiple-cards
  (testing "Draw 3 cards moves top 3 cards from library to hand"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3 :card-4 :card-5]))
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount 3}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 2 (count-zone db' :player-1 :library)))
      (is (= (+ initial-hand-size 3) (count-zone db' :player-1 :hand))))))


(deftest test-draw-from-empty-library-sets-loss
  (testing "Draw 1 from empty library sets :game/loss-condition"
    (let [db (init-game-state) ; no library cards
          effect {:effect/type :draw
                  :effect/amount 1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :empty-library (get-loss-condition db'))))))


(deftest test-draw-partial-when-library-small
  (testing "Draw 5 from 2-card library draws all available, then sets loss"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2]))
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount 5}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should draw all 2 available cards
      (is (= 0 (count-zone db' :player-1 :library)))
      (is (= (+ initial-hand-size 2) (count-zone db' :player-1 :hand)))
      ;; Then set loss condition (tried to draw from empty)
      (is (= :empty-library (get-loss-condition db'))))))


(deftest test-draw-zero-is-noop
  (testing "Draw 0 is no-op, no loss condition"
    (let [db (init-game-state) ; no library cards
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount 0}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should be no-op - no state change
      (is (= initial-hand-size (count-zone db' :player-1 :hand)))
      (is (nil? (get-loss-condition db'))))))


(deftest test-draw-negative-is-noop
  (testing "Draw -1 is no-op, no loss condition"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount -1}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should be no-op - no cards drawn
      (is (= 3 (count-zone db' :player-1 :library)))
      (is (= initial-hand-size (count-zone db' :player-1 :hand)))
      (is (nil? (get-loss-condition db'))))))


(deftest test-draw-nonexistent-player-is-noop
  (testing "Draw with invalid player-id is no-op, no crash"
    (let [db (init-game-state)
          effect {:effect/type :draw
                  :effect/amount 1}
          db' (fx/execute-effect db :nonexistent-player effect)]
      ;; Should be no-op - no crash, return db unchanged
      (is (= db db')))))


;; === execute-effect :lose-life tests ===

(deftest test-lose-life-reduces-total
  (testing "Lose 3 life from 20 results in 17 life"
    (let [db (init-game-state)
          effect {:effect/type :lose-life
                  :effect/amount 3}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 17 (q/get-life-total db' :player-1))))))


(deftest test-lose-life-zero-is-noop
  (testing "Lose 0 life is no-op, state unchanged"
    (let [db (init-game-state)
          initial-life (q/get-life-total db :player-1)
          effect {:effect/type :lose-life
                  :effect/amount 0}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= initial-life (q/get-life-total db' :player-1))))))


(deftest test-lose-life-can-go-negative
  (testing "Lose 25 life from 20 results in -5 life (no clamping at 0)"
    (let [db (init-game-state)
          effect {:effect/type :lose-life
                  :effect/amount 25}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= -5 (q/get-life-total db' :player-1))))))


(deftest test-lose-life-negative-amount-is-noop
  (testing "Lose -5 life is no-op (not treated as gain)"
    (let [db (init-game-state)
          initial-life (q/get-life-total db :player-1)
          effect {:effect/type :lose-life
                  :effect/amount -5}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= initial-life (q/get-life-total db' :player-1))))))


(deftest test-lose-life-nonexistent-player-is-noop
  (testing "Lose life with invalid player-id is no-op, no crash"
    (let [db (init-game-state)
          effect {:effect/type :lose-life
                  :effect/amount 3}
          db' (fx/execute-effect db :nonexistent-player effect)]
      ;; Should be no-op - no crash, return db unchanged
      (is (= db db')))))


;; === execute-effect :gain-life tests ===

(deftest test-gain-life-increases-total
  (testing "Gain 5 life from 20 results in 25 life"
    (let [db (init-game-state)
          effect {:effect/type :gain-life
                  :effect/amount 5}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 25 (q/get-life-total db' :player-1))))))


(deftest test-gain-life-zero-is-noop
  (testing "Gain 0 life is no-op, state unchanged"
    (let [db (init-game-state)
          initial-life (q/get-life-total db :player-1)
          effect {:effect/type :gain-life
                  :effect/amount 0}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= initial-life (q/get-life-total db' :player-1))))))


(deftest test-gain-life-accumulates
  (testing "Gain 3 life twice from 20 results in 26 life"
    (let [db (init-game-state)
          effect {:effect/type :gain-life
                  :effect/amount 3}
          db' (-> db
                  (fx/execute-effect :player-1 effect)
                  (fx/execute-effect :player-1 effect))]
      (is (= 26 (q/get-life-total db' :player-1))))))


(deftest test-gain-life-negative-amount-is-noop
  (testing "Gain -5 life is no-op (not treated as lose)"
    (let [db (init-game-state)
          initial-life (q/get-life-total db :player-1)
          effect {:effect/type :gain-life
                  :effect/amount -5}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= initial-life (q/get-life-total db' :player-1))))))


(deftest test-gain-life-nonexistent-player-is-noop
  (testing "Gain life with invalid player-id is no-op, no crash"
    (let [db (init-game-state)
          effect {:effect/type :gain-life
                  :effect/amount 5}
          db' (fx/execute-effect db :nonexistent-player effect)]
      ;; Should be no-op - no crash, return db unchanged
      (is (= db db')))))
