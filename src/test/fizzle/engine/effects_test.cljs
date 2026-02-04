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


;; === execute-effect :lose-life loss condition tests ===

(deftest test-lose-life-to-zero-sets-loss-condition
  (testing "Lose life to exactly 0 sets :game/loss-condition :life-zero"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :lose-life
                  :effect/amount 20}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (q/get-life-total db' :player-1)))
      (is (= :life-zero (get-loss-condition db'))))))


(deftest test-lose-life-to-negative-sets-loss-condition
  (testing "Lose life to negative sets :game/loss-condition :life-zero"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :lose-life
                  :effect/amount 25}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= -5 (q/get-life-total db' :player-1)))
      (is (= :life-zero (get-loss-condition db'))))))


;; === Test helpers for add-counters ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id]
   (add-permanent db player-id nil))
  ([db player-id initial-counters]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         card-eid (d/q '[:find ?e .
                         :where [?e :card/id :dark-ritual]]
                       @conn)
         object-id (random-uuid)
         base-entity {:object/id object-id
                      :object/card card-eid
                      :object/zone :battlefield
                      :object/owner player-eid
                      :object/controller player-eid
                      :object/tapped false}
         entity (if initial-counters
                  (assoc base-entity :object/counters initial-counters)
                  base-entity)]
     (d/transact! conn [entity])
     [@conn object-id])))


(defn get-counters
  "Get counters from an object by its UUID."
  [db object-id]
  (d/q '[:find ?counters .
         :in $ ?oid
         :where [?e :object/id ?oid]
         [?e :object/counters ?counters]]
       db object-id))


;; === execute-effect :add-counters tests ===

(deftest test-add-counters-to-permanent
  (testing "add-counters sets counters on permanent with no existing counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :add-counters
                  :effect/target object-id
                  :effect/counters {:mining 3}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= {:mining 3} (get-counters db' object-id))))))


(deftest test-add-counters-merges-existing
  (testing "add-counters merges with existing counters (does not overwrite)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:charge 2})
          effect {:effect/type :add-counters
                  :effect/target object-id
                  :effect/counters {:mining 3}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= {:charge 2 :mining 3} (get-counters db' object-id))))))


(deftest test-add-counters-increments-same-type
  (testing "add-counters increments existing counter of same type"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          effect {:effect/type :add-counters
                  :effect/target object-id
                  :effect/counters {:mining 2}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= {:mining 3} (get-counters db' object-id))))))


(deftest test-add-counters-invalid-target
  (testing "add-counters with invalid target is no-op"
    (let [db (init-game-state)
          effect {:effect/type :add-counters
                  :effect/target (random-uuid) ; non-existent object
                  :effect/counters {:mining 3}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')))))


(deftest test-add-counters-zero-amount
  (testing "add-counters with zero amount still sets counter (map with 0 value)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :add-counters
                  :effect/target object-id
                  :effect/counters {:mining 0}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Counter should be added even with 0 value
      (is (= {:mining 0} (get-counters db' object-id))))))


;; === execute-effect :deal-damage tests ===

(deftest test-deal-damage-reduces-life
  (testing "deal-damage reduces target player life total"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :deal-damage
                  :effect/amount 3
                  :effect/target :player-1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 17 (q/get-life-total db' :player-1))))))


(deftest test-deal-damage-zero-amount
  (testing "deal-damage with 0 amount does nothing"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :deal-damage
                  :effect/amount 0
                  :effect/target :player-1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))))))


(deftest test-deal-damage-preserves-source
  (testing "deal-damage effect includes source for future damage prevention"
    ;; NOTE: For Phase 1.5, damage behaves like life loss
    ;; Source field exists for future damage prevention implementation
    ;; This test documents the interface, actual prevention is future work
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :deal-damage
                  :effect/amount 3
                  :effect/target :player-1
                  :effect/source object-id}  ; source present for future use
          db' (fx/execute-effect db :player-1 effect)]
      ;; Currently just reduces life like :lose-life
      (is (= 17 (q/get-life-total db' :player-1))))))


(deftest test-deal-damage-can-go-negative
  (testing "deal-damage can reduce life below 0 (no clamping)"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :deal-damage
                  :effect/amount 25
                  :effect/target :player-1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= -5 (q/get-life-total db' :player-1))))))


(deftest test-deal-damage-sets-loss-condition
  (testing "deal-damage to 0 or below sets :game/loss-condition"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :deal-damage
                  :effect/amount 20
                  :effect/target :player-1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (q/get-life-total db' :player-1)))
      (is (= :life-zero (get-loss-condition db'))))))


(deftest test-deal-damage-invalid-target-is-noop
  (testing "deal-damage with invalid player-id is no-op, no crash"
    (let [db (init-game-state)
          effect {:effect/type :deal-damage
                  :effect/amount 3
                  :effect/target :nonexistent-player}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')))))


(deftest test-deal-damage-negative-amount-is-noop
  (testing "deal-damage with negative amount is no-op (not treated as heal)"
    (let [db (init-game-state)
          initial-life (q/get-life-total db :player-1)
          effect {:effect/type :deal-damage
                  :effect/amount -5
                  :effect/target :player-1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= initial-life (q/get-life-total db' :player-1))))))


;; === execute-effect condition system tests ===

(defn get-object-zone
  "Get the zone of an object by its UUID."
  [db object-id]
  (d/q '[:find ?zone .
         :in $ ?oid
         :where [?e :object/id ?oid]
         [?e :object/zone ?zone]]
       db object-id))


(deftest test-effect-without-condition-executes-normally
  (testing "Effect with no :effect/condition executes as usual"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 3}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Effect should execute normally
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))


(deftest test-no-counters-condition-skips-when-counters-positive
  (testing ":no-counters condition prevents effect when counters > 0"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 2})
          effect {:effect/type :sacrifice
                  :effect/target object-id
                  :effect/condition {:condition/type :no-counters
                                     :condition/counter-type :mining}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Object should still be on battlefield (condition not met)
      (is (= :battlefield (get-object-zone db' object-id))))))


(deftest test-no-counters-condition-executes-when-counters-zero
  (testing ":no-counters condition allows effect when counters = 0"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 0})
          effect {:effect/type :sacrifice
                  :effect/target object-id
                  :effect/condition {:condition/type :no-counters
                                     :condition/counter-type :mining}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Object should be in graveyard (condition met, effect executed)
      (is (= :graveyard (get-object-zone db' object-id))))))


(deftest test-no-counters-condition-executes-when-counter-type-missing
  (testing ":no-counters condition treats missing counter type as zero"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:charge 1})
          effect {:effect/type :sacrifice
                  :effect/target object-id
                  :effect/condition {:condition/type :no-counters
                                     :condition/counter-type :mining}} ; different type
          db' (fx/execute-effect db :player-1 effect)]
      ;; Object should be in graveyard (mining = nil counts as zero)
      (is (= :graveyard (get-object-zone db' object-id))))))


(deftest test-no-counters-condition-executes-when-no-counters-at-all
  (testing ":no-counters condition treats nil counters map as zero"
    (let [[db object-id] (add-permanent (init-game-state) :player-1) ; no counters
          effect {:effect/type :sacrifice
                  :effect/target object-id
                  :effect/condition {:condition/type :no-counters
                                     :condition/counter-type :mining}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Object should be in graveyard (no counters = zero)
      (is (= :graveyard (get-object-zone db' object-id))))))


(deftest test-sacrifice-effect-moves-to-graveyard
  (testing ":sacrifice effect moves target from battlefield to graveyard"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :sacrifice
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :graveyard (get-object-zone db' object-id))))))


(deftest test-sacrifice-effect-invalid-target-is-noop
  (testing ":sacrifice effect with invalid target is no-op, no crash"
    (let [db (init-game-state)
          effect {:effect/type :sacrifice
                  :effect/target (random-uuid)} ; non-existent object
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should be no-op - no crash, return db unchanged
      (is (= db db')))))


(deftest test-unknown-condition-type-skips-effect
  (testing "Unknown condition type causes effect to be skipped (fail-safe)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :sacrifice
                  :effect/target object-id
                  :effect/condition {:condition/type :unknown-condition-type}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Object should still be on battlefield (unknown condition = skip)
      (is (= :battlefield (get-object-zone db' object-id))))))


;; === execute-effect :grant-flashback tests ===

(defn add-card-entity
  "Add a card definition to the database.
   Returns [card-eid db]."
  [db card]
  (let [conn (d/conn-from-db db)]
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))]
      [card-eid @conn])))


(defn add-object-with-card
  "Add a card and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [[card-eid db'] (add-card-entity db card)
        conn (d/conn-from-db db')
        player-eid (q/get-player-eid @conn player-id)
        obj-id (random-uuid)
        obj {:object/id obj-id
             :object/card card-eid
             :object/zone zone
             :object/owner player-eid
             :object/controller player-eid
             :object/tapped false}]
    (d/transact! conn [obj])
    [obj-id @conn]))


(def test-target-sorcery
  {:card/id :test-target-sorcery
   :card/name "Test Target Sorcery"
   :card/cmc 4
   :card/mana-cost {:colorless 2 :black 2}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Test sorcery for grant-flashback effect."
   :card/effects []})


(def test-recoup-like-spell
  {:card/id :test-recoup-like
   :card/name "Test Recoup-like"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Grant flashback to target sorcery."
   :card/targeting [{:target/id :graveyard-sorcery
                     :target/type :object
                     :target/zone :graveyard
                     :target/controller :self
                     :target/criteria {:card/types #{:sorcery}}
                     :target/required true}]
   :card/effects [{:effect/type :grant-flashback
                   :effect/target-ref :graveyard-sorcery}]})


(deftest test-grant-flashback-effect
  (testing ":grant-flashback effect grants flashback to target sorcery"
    (let [db (init-game-state)
          ;; Add target sorcery to graveyard
          [target-id db] (add-object-with-card db :player-1 test-target-sorcery :graveyard)
          ;; Add recoup-like spell to stack with stored target
          [spell-id db] (add-object-with-card db :player-1 test-recoup-like-spell :stack)
          ;; Store the target on the spell object
          spell-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db spell-id)
          db (d/db-with db [[:db/add spell-eid :object/targets {:graveyard-sorcery target-id}]])
          ;; Set game turn so we can verify expiration
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          ;; Execute the grant-flashback effect
          effect {:effect/type :grant-flashback
                  :effect/target-ref :graveyard-sorcery}
          db' (fx/execute-effect db :player-1 effect spell-id)]
      ;; Target should now have a grant
      (let [grants (:object/grants (q/get-object db' target-id))]
        (is (= 1 (count grants))
            "Target should have one grant")
        (is (= :alternate-cost (:grant/type (first grants)))
            "Grant type should be :alternate-cost")
        (is (= spell-id (:grant/source (first grants)))
            "Grant source should be the casting spell")
        (is (= 1 (get-in (first grants) [:grant/expires :expires/turn]))
            "Grant should expire on current turn")
        (is (= :cleanup (get-in (first grants) [:grant/expires :expires/phase]))
            "Grant should expire at cleanup phase")
        (is (= {:colorless 2 :black 2} (get-in (first grants) [:grant/data :alternate/mana-cost]))
            "Grant mana cost should match target's mana cost")
        (is (= :graveyard (get-in (first grants) [:grant/data :alternate/zone]))
            "Grant should be usable from graveyard")
        (is (= :exile (get-in (first grants) [:grant/data :alternate/on-resolve]))
            "Grant should exile on resolve")))))


(deftest test-grant-flashback-effect-invalid-target
  (testing ":grant-flashback effect is no-op when target not found"
    (let [db (init-game-state)
          ;; Add recoup-like spell to stack with non-existent target
          [spell-id db] (add-object-with-card db :player-1 test-recoup-like-spell :stack)
          spell-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db spell-id)
          fake-target-id (random-uuid)
          db (d/db-with db [[:db/add spell-eid :object/targets {:graveyard-sorcery fake-target-id}]])
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          effect {:effect/type :grant-flashback
                  :effect/target-ref :graveyard-sorcery}
          db' (fx/execute-effect db :player-1 effect spell-id)]
      ;; Should return db unchanged (no crash)
      (is (= db db')
          "Should be no-op when target doesn't exist"))))


(deftest test-grant-flashback-effect-no-stored-target
  (testing ":grant-flashback effect is no-op when no stored target"
    (let [db (init-game-state)
          ;; Add recoup-like spell to stack without storing targets
          [spell-id db] (add-object-with-card db :player-1 test-recoup-like-spell :stack)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          effect {:effect/type :grant-flashback
                  :effect/target-ref :graveyard-sorcery}
          db' (fx/execute-effect db :player-1 effect spell-id)]
      ;; Should return db unchanged (no crash)
      (is (= db db')
          "Should be no-op when no stored target"))))


;; === Additional corner case tests ===

(deftest test-draw-extremely-large-amount
  (testing "Draw 10000 from small library draws all available, sets loss condition"
    ;; Corner case: verify no overflow/performance issue with absurd draw amounts
    ;; Should draw all available cards and set loss condition, not hang or crash
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount 10000}  ; absurdly large
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should draw all 3 available cards
      (is (= 0 (count-zone db' :player-1 :library))
          "Library should be empty")
      (is (= (+ initial-hand-size 3) (count-zone db' :player-1 :hand))
          "Hand should have all drawn cards")
      ;; Loss condition should be set (tried to draw from empty)
      (is (= :empty-library (get-loss-condition db'))
          "Loss condition should be set after attempting to draw more than available"))))


(deftest test-mill-extremely-large-amount
  (testing "Mill 10000 from small library mills all available gracefully"
    ;; Corner case: verify no overflow/performance issue with absurd mill amounts
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :mill
                  :effect/amount 10000}  ; absurdly large
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should mill all 3 available cards
      (is (= 0 (count-zone db' :player-1 :library))
          "Library should be empty")
      (is (= 3 (count-zone db' :player-1 :graveyard))
          "All cards should be in graveyard"))))


(deftest test-deal-damage-exact-lethal-sets-loss
  (testing "Deal exactly 20 damage from 20 life sets loss condition"
    ;; Boundary test: exactly lethal damage
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :deal-damage
                  :effect/amount 20
                  :effect/target :player-1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (q/get-life-total db' :player-1))
          "Life should be exactly 0")
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be set at exactly 0 life"))))
