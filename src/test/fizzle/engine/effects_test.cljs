(ns fizzle.engine.effects-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.effects :as fx]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.objects :as objects]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.zones :as zones]
    [fizzle.events.abilities :as ability-events]
    [fizzle.test-helpers :as th]))


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
      (is (= db db')
          "Entire db should be identical when effect type is unknown"))))


(deftest execute-effect-nil-type-test
  (testing "execute-effect with nil type (missing key) returns db unchanged"
    (let [db (init-game-state)
          effect {}  ; no :effect/type key
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "Entire db should be identical when effect type is nil"))))


;; === Test helpers for mill ===

(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (d/db-with db [{:player/id :player-2
                  :player/name "Opponent"
                  :player/life 20
                  :player/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
                  :player/storm-count 0
                  :player/land-plays-left 1
                  :player/is-opponent true}]))


(defn add-library-cards
  "Add cards to a player's library with sequential positions.
   Takes a vector of card-ids (keywords) and adds them with positions 0, 1, 2...
   Position 0 = top of library."
  [db player-id card-ids]
  (let [player-eid (q/get-player-eid db player-id)
        ;; All library cards use Dark Ritual card def (mill tests only care about zone/count)
        card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)
        card-data (d/pull db '[:card/types :card/power :card/toughness] card-eid)]
    (reduce
      (fn [acc-db idx]
        (d/db-with acc-db [(objects/build-object-tx acc-db card-eid card-data :library player-eid idx)]))
      db
      (range (count card-ids)))))


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
  (testing "Cards milled from top of library end up in graveyard"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:top :middle :bottom]))
          top-two (q/get-top-n-library db :player-1 2)
          remaining-id (last (q/get-top-n-library db :player-1 3))
          effect {:effect/type :mill
                  :effect/amount 2}
          db' (fx/execute-effect db :player-1 effect)
          graveyard-ids (set (map :object/id
                                  (q/get-objects-in-zone db' :player-1 :graveyard)))
          library-ids (set (map :object/id
                                (q/get-objects-in-zone db' :player-1 :library)))]
      (is (= 1 (count-zone db' :player-1 :library)))
      (is (= 2 (count-zone db' :player-1 :graveyard)))
      (is (every? graveyard-ids (set top-two))
          "Top 2 library cards should be in graveyard")
      (is (contains? library-ids remaining-id)
          "Bottom card should remain in library"))))


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


(deftest test-add-mana-negative-amount
  (testing "add-mana with negative value: documents current behavior"
    ;; Corner case: negative mana amounts in effect.
    ;; Current behavior: merge-with + allows negative values, which subtract.
    ;; This test documents the behavior - not necessarily desired, but known.
    ;; Bug it catches: unexpected negative mana pool values.
    (let [db (init-game-state)
          ;; First add some mana so we have a baseline
          db (fx/execute-effect db :player-1 {:effect/type :add-mana
                                              :effect/mana {:black 5}})
          _ (is (= 5 (:black (q/get-mana-pool db :player-1))))
          ;; Now try to add negative mana
          effect {:effect/type :add-mana
                  :effect/mana {:black -3}}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Document current behavior: negative add works like subtraction
      ;; Pool was 5, adding -3 gives 2
      (is (= 2 (:black (q/get-mana-pool db' :player-1)))
          "Current behavior: negative mana in add-mana subtracts from pool"))))


;; === execute-effect :draw tests ===

(defn get-loss-condition
  "Get the loss condition from game state."
  [db]
  (:game/loss-condition (q/get-game-state db)))


(defn get-winner
  "Get the winner player-id from game state.
   Returns nil if no winner set."
  [db]
  (let [game (q/get-game-state db)
        winner-ref (:game/winner game)]
    (when winner-ref
      (d/q '[:find ?pid .
             :in $ ?eid
             :where [?eid :player/id ?pid]]
           db (:db/id winner-ref)))))


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
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= :empty-library (get-loss-condition db'))))))


(deftest test-draw-partial-when-library-small
  (testing "Draw 5 from 2-card library draws all available, then sets loss"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2]))
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :draw
                  :effect/amount 5}
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
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


(deftest test-draw-opponent-target
  (testing ":draw with :effect/target :opponent draws for opponent"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-library-cards :player-2 [:card-1 :card-2 :card-3]))
          initial-hand (count-zone db :player-2 :hand)
          effect {:effect/type :draw
                  :effect/amount 1
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= (inc initial-hand) (count-zone db' :player-2 :hand))
          "Opponent should have drawn 1 card")
      (is (= 2 (count-zone db' :player-2 :library))
          "Opponent library should have 2 cards remaining"))))


(deftest test-draw-opponent-multiple
  (testing ":draw with :effect/target :opponent draws multiple for opponent"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-library-cards :player-2 [:card-1 :card-2 :card-3]))
          initial-hand (count-zone db :player-2 :hand)
          effect {:effect/type :draw
                  :effect/amount 2
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= (+ initial-hand 2) (count-zone db' :player-2 :hand))
          "Opponent should have drawn 2 cards"))))


(deftest test-draw-opponent-empty-library-sets-loss
  (testing ":draw with :opponent target from empty library sets drew-from-empty"
    (let [db (-> (init-game-state)
                 (add-opponent))
          effect {:effect/type :draw
                  :effect/amount 1
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect)
          opp-eid (q/get-player-eid db' :player-2)
          drew-empty (d/q '[:find ?v .
                            :in $ ?e
                            :where [?e :player/drew-from-empty ?v]]
                          db' opp-eid)]
      (is (true? drew-empty)
          "Opponent should have drew-from-empty flag set"))))


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
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= 0 (q/get-life-total db' :player-1)))
      (is (= :life-zero (get-loss-condition db'))))))


(deftest test-lose-life-to-negative-sets-loss-condition
  (testing "Lose life to negative sets :game/loss-condition :life-zero"
    (let [db (init-game-state) ; starts at 20 life
          effect {:effect/type :lose-life
                  :effect/amount 25}
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= -5 (q/get-life-total db' :player-1)))
      (is (= :life-zero (get-loss-condition db'))))))


;; === Test helpers for add-counters ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id]
   (add-permanent db player-id nil))
  ([db player-id initial-counters]
   (let [player-eid (q/get-player-eid db player-id)
         card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)
         card-data (d/pull db '[:card/types :card/power :card/toughness] card-eid)
         object-id (random-uuid)
         entity (cond-> (objects/build-object-tx db card-eid card-data :battlefield player-eid 0
                                                 :id object-id)
                  initial-counters (assoc :object/counters initial-counters))]
     [(d/db-with db [entity]) object-id])))


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
  (testing "deal-damage with source object reduces life and leaves source intact"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :deal-damage
                  :effect/amount 3
                  :effect/target :player-1
                  :effect/source object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 17 (q/get-life-total db' :player-1)))
      (is (= :battlefield (:object/zone (q/get-object db' object-id)))
          "Source object should still exist on battlefield"))))


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
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
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
  (let [db (if (d/q '[:find ?e .
                      :in $ ?cid
                      :where [?e :card/id ?cid]]
                    db (:card/id card))
             db
             (d/db-with db [card]))
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db (:card/id card))]
    [card-eid db]))


(defn add-object-with-card
  "Add a card and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [[card-eid db'] (add-card-entity db card)
        player-eid (q/get-player-eid db' player-id)
        obj-id (random-uuid)
        obj {:object/id obj-id
             :object/card card-eid
             :object/zone zone
             :object/owner player-eid
             :object/controller player-eid
             :object/tapped false}]
    [obj-id (d/db-with db' [obj])]))


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
                     :target/criteria {:match/types #{:sorcery}}
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
          ;; Set game turn so we can verify expiration
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          ;; Execute the grant-flashback effect with pre-resolved target
          effect {:effect/type :grant-flashback
                  :effect/target target-id}
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
          fake-target-id (random-uuid)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          ;; Pre-resolved target that doesn't exist in db
          effect {:effect/type :grant-flashback
                  :effect/target fake-target-id}
          db' (fx/execute-effect db :player-1 effect spell-id)]
      ;; Should return db unchanged (no crash)
      (is (= db db')
          "Should be no-op when target doesn't exist"))))


(deftest test-grant-flashback-effect-no-stored-target
  (testing ":grant-flashback effect is no-op when no stored target"
    (let [db (init-game-state)
          ;; Add recoup-like spell to stack without target
          [spell-id db] (add-object-with-card db :player-1 test-recoup-like-spell :stack)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          ;; No target pre-resolved
          effect {:effect/type :grant-flashback}
          db' (fx/execute-effect db :player-1 effect spell-id)]
      ;; Should return db unchanged (no crash)
      (is (= db db')
          "Should be no-op when no stored target"))))


;; === execute-effect :exile-self tests ===

(deftest test-exile-self-moves-spell-to-exile-zone
  (testing ":exile-self effect moves source object to exile zone"
    ;; Catches: effect doesn't move to correct zone
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ;; Verify starting zone is battlefield
          _ (is (= :battlefield (get-object-zone db object-id)))
          effect {:effect/type :exile-self}
          db' (fx/execute-effect db :player-1 effect object-id)]
      ;; Object should now be in exile zone
      (is (= :exile (get-object-zone db' object-id))))))


(deftest test-exile-self-with-nil-object-id-returns-db-unchanged
  (testing ":exile-self effect with nil object-id returns db unchanged"
    ;; Catches: nil pointer exception if object-id not guarded
    (let [db (init-game-state)
          effect {:effect/type :exile-self}
          db' (fx/execute-effect db :player-1 effect nil)]
      ;; Should be no-op, no crash
      (is (= db db')))))


(deftest test-exile-self-with-nonexistent-object-returns-db-unchanged
  (testing ":exile-self effect with nonexistent object-id returns db unchanged"
    ;; Catches: missing guard clause for object existence
    (let [db (init-game-state)
          fake-object-id (random-uuid)
          effect {:effect/type :exile-self}
          db' (fx/execute-effect db :player-1 effect fake-object-id)]
      ;; Should be no-op, no crash
      (is (= db db')))))


;; === Test helpers for discard-hand ===

(defn add-hand-cards
  "Add cards to a player's hand.
   Takes a count of cards to add (all referencing Dark Ritual card def)."
  [db player-id count]
  (let [player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (reduce
      (fn [acc-db _]
        (d/db-with acc-db [{:object/id (random-uuid)
                            :object/card card-eid
                            :object/zone :hand
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}]))
      db
      (range count))))


;; === execute-effect :discard-hand tests ===

(deftest test-discard-hand-moves-all-cards-to-graveyard
  (testing ":discard-hand moves all cards from hand to graveyard"
    ;; Catches: not moving all cards
    ;; Note: init-game-state creates 1 Dark Ritual in hand
    (let [db (-> (init-game-state)
                 (add-hand-cards :player-1 4))
          initial-hand-size (count-zone db :player-1 :hand)  ; Should be 5 (1 + 4)
          effect {:effect/type :discard-hand}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (count-zone db' :player-1 :hand))
          "Hand should be empty after discard-hand")
      (is (= initial-hand-size (count-zone db' :player-1 :graveyard))
          "All cards should be in graveyard"))))


(deftest test-discard-hand-empty-hand-is-noop
  (testing ":discard-hand with empty hand is no-op, no crash"
    ;; Catches: empty hand crash
    ;; Note: init-game-state creates 1 card in hand, so we test with opponent who has none
    (let [db (-> (init-game-state)
                 (add-opponent))  ; opponent has no cards
          initial-gy-size (count-zone db :player-2 :graveyard)
          effect {:effect/type :discard-hand
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should be no-op - no crash, graveyard unchanged
      (is (= 0 (count-zone db' :player-2 :hand)))
      (is (= initial-gy-size (count-zone db' :player-2 :graveyard))))))


(deftest test-discard-hand-targets-opponent
  (testing ":discard-hand with :effect/target :opponent discards opponent's hand"
    ;; Catches: wrong target
    ;; Note: init-game-state creates 1 card in player-1's hand
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-hand-cards :player-1 2)
                 (add-hand-cards :player-2 3))
          caster-initial-hand (count-zone db :player-1 :hand)  ; 1 + 2 = 3
          effect {:effect/type :discard-hand
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Caster's hand unchanged
      (is (= caster-initial-hand (count-zone db' :player-1 :hand))
          "Caster's hand should be unchanged")
      ;; Opponent's hand discarded
      (is (= 0 (count-zone db' :player-2 :hand))
          "Opponent's hand should be empty")
      (is (= 3 (count-zone db' :player-2 :graveyard))
          "Opponent's cards should be in graveyard"))))


(deftest test-discard-hand-defaults-to-caster
  (testing ":discard-hand with no :effect/target defaults to caster"
    ;; Catches: missing default
    ;; Note: init-game-state creates 1 card in player-1's hand
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-hand-cards :player-1 3)
                 (add-hand-cards :player-2 2))
          caster-initial-hand (count-zone db :player-1 :hand)  ; 1 + 3 = 4
          effect {:effect/type :discard-hand
                  ;; no :effect/target - should default to caster
                  }
          db' (fx/execute-effect db :player-1 effect)]
      ;; Caster's hand discarded (default target)
      (is (= 0 (count-zone db' :player-1 :hand))
          "Caster's hand should be discarded")
      (is (= caster-initial-hand (count-zone db' :player-1 :graveyard))
          "Caster's cards should be in graveyard")
      ;; Opponent's hand unchanged
      (is (= 2 (count-zone db' :player-2 :hand))
          "Opponent's hand should be unchanged"))))


(deftest test-discard-hand-with-self-target
  (testing ":discard-hand with :effect/target :self discards caster's hand"
    ;; Catches: :self keyword not handled
    ;; Note: init-game-state creates 1 card in player-1's hand
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-hand-cards :player-1 3)
                 (add-hand-cards :player-2 2))
          caster-initial-hand (count-zone db :player-1 :hand)  ; 1 + 3 = 4
          effect {:effect/type :discard-hand
                  :effect/target :self}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Caster's hand discarded (explicit :self target)
      (is (= 0 (count-zone db' :player-1 :hand))
          "Caster's hand should be discarded")
      (is (= caster-initial-hand (count-zone db' :player-1 :graveyard))
          "Caster's cards should be in graveyard")
      ;; Opponent's hand unchanged
      (is (= 2 (count-zone db' :player-2 :hand))
          "Opponent's hand should be unchanged"))))


;; === Test helpers for return-from-graveyard ===

(defn add-graveyard-cards
  "Add cards to a player's graveyard.
   Takes a count of cards to add (all referencing Dark Ritual card def).
   Returns [db added-object-ids] tuple."
  [db player-id count]
  (let [player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        [final-db obj-ids]
        (reduce
          (fn [[acc-db ids] _]
            (let [obj-id (random-uuid)]
              [(d/db-with acc-db [{:object/id obj-id
                                   :object/card card-eid
                                   :object/zone :graveyard
                                   :object/owner player-eid
                                   :object/controller player-eid
                                   :object/tapped false}])
               (conj ids obj-id)]))
          [db []]
          (range count))]
    [final-db obj-ids]))


;; === execute-effect :return-from-graveyard tests ===

(deftest test-return-from-graveyard-player-selection-returns-db-unchanged
  (testing ":return-from-graveyard with :selection :player returns db unchanged (UI handles)"
    ;; Catches: effect incorrectly modifies db instead of deferring to UI
    (let [[db _gy-ids] (add-graveyard-cards (init-game-state) :player-1 3)
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :player}
          db' (fx/execute-effect db :player-1 effect)]
      ;; db should be unchanged - player selection happens at app-db level
      (is (= 3 (count-zone db' :player-1 :graveyard))
          "Graveyard should be unchanged (UI handles selection)")
      (is (= db db')
          "db should be identical (no modifications for player selection)"))))


(deftest test-return-from-graveyard-random-selects-up-to-count
  (testing ":return-from-graveyard with :selection :random moves up to count cards to hand"
    ;; Catches: random selection ignores count limit
    (let [[db _gy-ids] (add-graveyard-cards (init-game-state) :player-1 5)
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :random}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should move exactly 3 cards from graveyard to hand
      (is (= 2 (count-zone db' :player-1 :graveyard))
          "Graveyard should have 2 cards remaining")
      (is (= (+ initial-hand-size 3) (count-zone db' :player-1 :hand))
          "Hand should have 3 more cards"))))


(deftest test-return-from-graveyard-random-empty-graveyard-is-noop
  (testing ":return-from-graveyard with empty graveyard is no-op, no crash"
    ;; Catches: nil pointer on empty graveyard
    (let [db (init-game-state) ; no graveyard cards
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :random}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should be no-op - no crash
      (is (= 0 (count-zone db' :player-1 :graveyard))
          "Graveyard should still be empty")
      (is (= initial-hand-size (count-zone db' :player-1 :hand))
          "Hand should be unchanged"))))


(deftest test-return-from-graveyard-random-fewer-than-count-returns-all
  (testing ":return-from-graveyard returns all available when graveyard has fewer than count"
    ;; Catches: crashes when fewer cards available than requested
    (let [[db _gy-ids] (add-graveyard-cards (init-game-state) :player-1 2) ; only 2 cards
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :return-from-graveyard
                  :effect/count 5 ; requesting more than available
                  :effect/selection :random}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Should move all 2 available cards (no crash)
      (is (= 0 (count-zone db' :player-1 :graveyard))
          "Graveyard should be empty")
      (is (= (+ initial-hand-size 2) (count-zone db' :player-1 :hand))
          "Hand should have all 2 cards"))))


(deftest test-return-from-graveyard-targets-opponent
  (testing ":return-from-graveyard with :effect/target :opponent affects opponent's graveyard"
    ;; Catches: :opponent target not resolved to actual player-id
    (let [db (-> (init-game-state)
                 (add-opponent))
          [db _gy-ids] (add-graveyard-cards db :player-2 4)
          caster-initial-gy (count-zone db :player-1 :graveyard)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/target :opponent
                  :effect/selection :random}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Caster's graveyard unchanged
      (is (= caster-initial-gy (count-zone db' :player-1 :graveyard))
          "Caster's graveyard should be unchanged")
      ;; Opponent's graveyard reduced
      (is (= 2 (count-zone db' :player-2 :graveyard))
          "Opponent's graveyard should have 2 cards remaining")
      ;; Opponent's hand increased
      (is (= 2 (count-zone db' :player-2 :hand))
          "Opponent's hand should have 2 cards"))))


(deftest test-return-from-graveyard-with-self-target
  (testing ":return-from-graveyard with :effect/target :self resolves to caster"
    ;; Catches: :self keyword passed directly to queries instead of resolving
    (let [db (-> (init-game-state)
                 (add-opponent))
          [db _gy-ids] (add-graveyard-cards db :player-1 3)
          [db _opp-ids] (add-graveyard-cards db :player-2 2)
          initial-hand-size (count-zone db :player-1 :hand)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/target :self
                  :effect/selection :random}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Caster's graveyard affected
      (is (= 1 (count-zone db' :player-1 :graveyard))
          "Caster's graveyard should have 1 card remaining")
      (is (= (+ initial-hand-size 2) (count-zone db' :player-1 :hand))
          "Caster's hand should have 2 more cards")
      ;; Opponent's graveyard unchanged
      (is (= 2 (count-zone db' :player-2 :graveyard))
          "Opponent's graveyard should be unchanged"))))


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
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
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
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= 0 (q/get-life-total db' :player-1))
          "Life should be exactly 0")
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be set at exactly 0 life"))))


;; === Test helpers for add-restriction ===

(defn add-spell-with-target
  "Create a spell object on the stack with target stored in :object/targets.
   Returns [object-id updated-db]."
  [db controller-id object-id target-player]
  (let [player-eid (q/get-player-eid db controller-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    [object-id (d/db-with db [{:object/id object-id
                               :object/card card-eid
                               :object/zone :stack
                               :object/owner player-eid
                               :object/controller player-eid
                               :object/targets {:player target-player}
                               :object/tapped false}])]))


;; === execute-effect :add-restriction tests ===

(deftest test-add-restriction-creates-grant-on-target-player
  (testing "creates restriction grant on target player"
    ;; Catches: grant not created or wrong player
    (let [db (-> (init-game-state)
                 (add-opponent))
          source-id (random-uuid)
          [source-id db] (add-spell-with-target db :player-1 source-id :player-2)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect source-id)
          player-grants (grants/get-player-grants db' :player-2)]
      (is (= 1 (count player-grants))
          "Should create one grant on opponent")
      (is (= :restriction (:grant/type (first player-grants)))
          "Grant type should be :restriction")
      (is (= :cannot-cast-spells (get-in (first player-grants) [:grant/data :restriction/type]))
          "Restriction type should be :cannot-cast-spells"))))


(deftest test-add-restriction-expires-at-cleanup
  (testing "restriction grant expires at end of current turn"
    ;; Catches: wrong expiration turn or phase
    (let [db (-> (init-game-state)
                 (add-opponent))
          ;; Set game to turn 3
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 3]])
          source-id (random-uuid)
          [source-id db] (add-spell-with-target db :player-1 source-id :player-2)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect source-id)
          grant (first (grants/get-player-grants db' :player-2))]
      (is (= 3 (get-in grant [:grant/expires :expires/turn]))
          "Should expire on turn 3")
      (is (= :cleanup (get-in grant [:grant/expires :expires/phase]))
          "Should expire at cleanup phase"))))


(deftest test-add-restriction-cannot-attack-type
  (testing "can create cannot-attack restriction"
    ;; Catches: hardcoded to only support one restriction type
    (let [db (-> (init-game-state)
                 (add-opponent))
          source-id (random-uuid)
          [source-id db] (add-spell-with-target db :player-1 source-id :player-2)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-attack
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect source-id)
          grant (first (grants/get-player-grants db' :player-2))]
      (is (= :cannot-attack (get-in grant [:grant/data :restriction/type]))
          "Restriction type should be :cannot-attack"))))


(deftest test-add-restriction-targets-self
  (testing "targets controller when effect specifies :self"
    ;; Catches: :self target not resolved to player-id
    (let [db (init-game-state)
          source-id (random-uuid)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :self}
          db' (fx/execute-effect db :player-1 effect source-id)]
      (is (= 1 (count (grants/get-player-grants db' :player-1)))
          "Should create grant on controller (self)"))))


(deftest test-add-restriction-targets-opponent
  (testing "targets opponent when effect specifies :opponent"
    ;; Catches: :opponent target not resolved via get-opponent-id
    (let [db (-> (init-game-state)
                 (add-opponent))
          source-id (random-uuid)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :opponent}
          db' (fx/execute-effect db :player-1 effect source-id)]
      ;; Player 1 should have no grants
      (is (= 0 (count (grants/get-player-grants db' :player-1)))
          "Controller should not have grants")
      ;; Player 2 should have the grant
      (is (= 1 (count (grants/get-player-grants db' :player-2)))
          "Opponent should have the grant"))))


(deftest test-add-restriction-invalid-target-is-noop
  (testing "returns db unchanged when target player doesn't exist"
    ;; Catches: missing guard clause for invalid player
    (let [db (init-game-state)
          source-id (random-uuid)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :nonexistent-player}
          db' (fx/execute-effect db :player-1 effect source-id)]
      (is (= db db')
          "Should be no-op for invalid target"))))


(deftest test-add-restriction-stores-source
  (testing "grant includes source object-id for tracking"
    ;; Catches: missing source tracking on grant
    (let [db (-> (init-game-state)
                 (add-opponent))
          source-id (random-uuid)
          [source-id db] (add-spell-with-target db :player-1 source-id :player-2)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect source-id)
          grant (first (grants/get-player-grants db' :player-2))]
      (is (= source-id (:grant/source grant))
          "Grant should track source object ID"))))


(deftest test-add-restriction-default-turn-when-nil
  (testing "defaults to turn 1 when game turn is nil"
    ;; Retract :game/turn to exercise the (or (:game/turn game) 1) nil path
    (let [base-db (-> (init-game-state)
                      (add-opponent))
          game-eid (d/q '[:find ?g . :where [?g :game/id _]] base-db)
          db (d/db-with base-db [[:db/retract game-eid :game/turn 1]])
          source-id (random-uuid)
          [source-id db] (add-spell-with-target db :player-1 source-id :player-2)
          effect {:effect/type :add-restriction
                  :restriction/type :cannot-cast-spells
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect source-id)
          grant (first (grants/get-player-grants db' :player-2))]
      (is (nil? (:game/turn (q/get-game-state db)))
          "Precondition: game/turn should be nil")
      (is (= 1 (get-in grant [:grant/expires :expires/turn]))
          "Should default to turn 1 when game/turn is nil"))))


;; === Test helpers for :destroy effect ===

(defn add-spell-on-stack-with-targets
  "Add a spell object on the stack with stored targets.
   Returns [object-id updated-db] tuple."
  [db player-id targets-map]
  (let [player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        object-id (random-uuid)]
    [object-id (d/db-with db [{:object/id object-id
                               :object/card card-eid
                               :object/zone :stack
                               :object/owner player-eid
                               :object/controller player-eid
                               :object/targets targets-map
                               :object/tapped false}])]))


;; === execute-effect :destroy tests ===

(deftest test-destroy-effect-moves-target-to-graveyard
  (testing ":destroy moves target permanent from battlefield to graveyard"
    ;; Catches: effect doesn't move target to correct zone
    (let [[db target-id] (add-permanent (init-game-state) :player-1)
          ;; Verify starting zone is battlefield
          _ (is (= :battlefield (get-object-zone db target-id)))
          ;; Pre-resolved target in effect
          effect {:effect/type :destroy
                  :effect/target target-id}
          db' (fx/execute-effect db :player-1 effect nil)]
      ;; Target should now be in graveyard
      (is (= :graveyard (get-object-zone db' target-id))
          "Target should be moved to graveyard"))))


(deftest test-destroy-effect-uses-owner-graveyard-not-controller
  (testing ":destroy moves target to OWNER's graveyard, not controller's"
    ;; Catches: using controller's graveyard instead of owner's
    ;; This matters for stolen permanents (Control Magic effects)
    (let [db (-> (init-game-state)
                 (add-opponent))
          ;; Add permanent owned by player-1
          [db target-id] (add-permanent db :player-1)
          ;; Verify it's on battlefield
          _ (is (= :battlefield (get-object-zone db target-id)))
          ;; Pre-resolved target in effect
          effect {:effect/type :destroy
                  :effect/target target-id}
          ;; Player-2 casts the destroy effect
          db' (fx/execute-effect db :player-2 effect nil)]
      ;; Target goes to owner's (player-1's) graveyard
      (is (= :graveyard (get-object-zone db' target-id))
          "Target should be in graveyard")
      ;; Verify it's in player-1's graveyard (owner), not player-2's
      (is (= 1 (count-zone db' :player-1 :graveyard))
          "Owner's graveyard should have the destroyed permanent")
      (is (= 0 (count-zone db' :player-2 :graveyard))
          "Caster's graveyard should be empty (not owner)"))))


(deftest test-destroy-effect-nil-target-is-noop
  (testing ":destroy with nil target returns db unchanged"
    ;; Catches: nil pointer exception if target not guarded
    (let [[db target-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :destroy
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect nil)]
      ;; Should be no-op, target still on battlefield
      (is (= :battlefield (get-object-zone db' target-id))
          "Target should still be on battlefield"))))


(deftest test-destroy-effect-no-target-is-noop
  (testing ":destroy with no :effect/target returns db unchanged"
    ;; Catches: missing guard for no target
    (let [[db target-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :destroy}  ; No :effect/target
          db' (fx/execute-effect db :player-1 effect nil)]
      ;; Should be no-op, target still on battlefield
      (is (= :battlefield (get-object-zone db' target-id))
          "Target should still be on battlefield"))))


(deftest test-destroy-effect-missing-target-object-is-noop
  (testing ":destroy with nonexistent target object returns db unchanged"
    ;; Catches: missing guard for target object that no longer exists
    (let [db (init-game-state)
          fake-target-id (random-uuid)  ; Non-existent object
          ;; Pre-resolved target that doesn't exist in db
          effect {:effect/type :destroy
                  :effect/target fake-target-id}
          db' (fx/execute-effect db :player-1 effect nil)]
      ;; Should be no-op, no crash
      (is (= db db')
          "db should be unchanged when target doesn't exist"))))


;; === Winner determination tests ===

(deftest test-lose-life-lethal-sets-winner
  (testing "lose-life reducing opponent to 0 sets :game/winner to player"
    ;; Catches: winner not being set at all when loss condition fires
    (let [db (-> (init-game-state)
                 (add-opponent))
          effect {:effect/type :lose-life
                  :effect/amount 20
                  :effect/target :player-2}
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be set")
      (is (= :player-1 (get-winner db'))
          "Player 1 should be the winner when opponent loses"))))


(deftest test-deal-damage-lethal-sets-winner
  (testing "deal-damage reducing opponent to 0 sets :game/winner to player"
    ;; Catches: deal-damage call site not passing losing player
    (let [db (-> (init-game-state)
                 (add-opponent))
          effect {:effect/type :deal-damage
                  :effect/amount 20
                  :effect/target :player-2}
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be set")
      (is (= :player-1 (get-winner db'))
          "Player 1 should be the winner when opponent takes lethal damage"))))


(deftest test-draw-empty-library-sets-winner
  (testing "draw from empty library sets :game/winner to opponent"
    ;; Catches: draw call site not passing losing player
    (let [db (-> (init-game-state)
                 (add-opponent))
          effect {:effect/type :draw
                  :effect/amount 1}
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= :empty-library (get-loss-condition db'))
          "Loss condition should be empty-library")
      (is (= :player-2 (get-winner db'))
          "Opponent should win when player draws from empty library"))))


(deftest test-player-self-loss-sets-opponent-as-winner
  (testing "player losing own life to 0 sets opponent as winner"
    ;; Catches: winner determination direction (loser vs winner confusion)
    (let [db (-> (init-game-state)
                 (add-opponent))
          effect {:effect/type :lose-life
                  :effect/amount 20}  ; targets self (player-1) by default
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be set")
      (is (= :player-2 (get-winner db'))
          "Opponent should win when player loses own life"))))


(deftest test-loss-condition-without-opponent-no-crash
  (testing "loss condition without opponent sets loss but no winner, no crash"
    ;; Catches: NPE when get-opponent-id returns nil
    (let [db (init-game-state) ; no opponent in test init
          effect {:effect/type :lose-life
                  :effect/amount 20}
          db' (-> (fx/execute-effect db :player-1 effect)
                  (sba/check-and-execute-sbas))]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should still be set")
      (is (nil? (get-winner db'))
          "Winner should be nil when no opponent exists"))))


(deftest test-loss-condition-idempotent
  (testing "triggering loss condition twice doesn't corrupt state"
    ;; Catches: double-trigger corruption when opponent already dead
    (let [db (-> (init-game-state)
                 (add-opponent))
          ;; First hit: reduce opponent to 0
          effect1 {:effect/type :lose-life
                   :effect/amount 20
                   :effect/target :player-2}
          db' (-> (fx/execute-effect db :player-1 effect1)
                  (sba/check-and-execute-sbas))
          ;; Second hit: reduce opponent to -5
          effect2 {:effect/type :lose-life
                   :effect/amount 5
                   :effect/target :player-2}
          db'' (-> (fx/execute-effect db' :player-1 effect2)
                   (sba/check-and-execute-sbas))]
      (is (= :life-zero (get-loss-condition db''))
          "Loss condition should still be :life-zero")
      (is (= :player-1 (get-winner db''))
          "Winner should still be player-1")
      (is (= -5 (q/get-life-total db'' :player-2))
          "Life should be -5 after second hit"))))


;; === execute-effect-checked: tagged return values for interactive effects ===

(deftest execute-effect-checked-discard-player-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :discard with :player selection"
    (let [db (init-game-state)
          effect {:effect/type :discard
                  :effect/count 1
                  :effect/selection :player}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (map? result) "Result should be a map")
      (is (contains? result :db) "Result should contain :db")
      (is (contains? result :needs-selection) "Result should contain :needs-selection")
      (is (= :discard (:effect/type (:needs-selection result)))
          "needs-selection should contain the effect data")
      (is (= db (:db result)) "db should be unchanged for interactive effects"))))


(deftest execute-effect-checked-tutor-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :tutor"
    (let [db (init-game-state)
          effect {:effect/type :tutor
                  :effect/criteria {:match/types [:instant]}}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection))
      (is (= :tutor (:effect/type (:needs-selection result)))))))


(deftest execute-effect-checked-scry-positive-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :scry with amount > 0"
    (let [db (init-game-state)
          effect {:effect/type :scry :effect/amount 2}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection))
      (is (= :scry (:effect/type (:needs-selection result)))))))


(deftest execute-effect-checked-scry-zero-returns-plain-db
  (testing "execute-effect-checked returns plain {:db db} for :scry with amount 0"
    (let [db (init-game-state)
          effect {:effect/type :scry :effect/amount 0}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :db))
      (is (not (contains? result :needs-selection))
          "scry 0 should not need selection"))))


(deftest execute-effect-checked-peek-and-select-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :peek-and-select"
    (let [db (init-game-state)
          effect {:effect/type :peek-and-select :effect/count 3}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection))
      (is (= :peek-and-select (:effect/type (:needs-selection result)))))))


(deftest execute-effect-checked-return-from-graveyard-player-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :return-from-graveyard with :player"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :player}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection))
      (is (= :return-from-graveyard (:effect/type (:needs-selection result)))))))


(deftest execute-effect-checked-return-from-graveyard-random-returns-plain-db
  (testing "execute-effect-checked returns plain {:db db'} for :return-from-graveyard :random"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/count 1
                  :effect/selection :random}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :db))
      (is (not (contains? result :needs-selection))
          ":random selection should not need player interaction"))))


(deftest execute-effect-checked-non-interactive-returns-plain-db
  (testing "execute-effect-checked returns plain {:db db'} for non-interactive effects"
    (let [db (init-game-state)
          effect {:effect/type :add-mana :effect/mana {:black 3}}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :db))
      (is (not (contains? result :needs-selection)))
      (is (= 3 (:black (q/get-mana-pool (:db result) :player-1)))
          "Effect should still be applied in :db"))))


(deftest execute-effect-checked-condition-met-returns-needs-selection
  (testing "execute-effect-checked with met condition still returns :needs-selection"
    (let [db (init-game-state)
          ;; Add 7 cards to library, then move them to graveyard for threshold
          [db-with-lib _lib-ids] (th/add-cards-to-library db
                                                          [:dark-ritual :dark-ritual :dark-ritual
                                                           :dark-ritual :dark-ritual :dark-ritual
                                                           :dark-ritual]
                                                          :player-1)
          db-with-gy (reduce (fn [d _]
                               (let [top (first (q/get-top-n-library d :player-1 1))]
                                 (if top
                                   (zones/move-to-zone* d top :graveyard)
                                   d)))
                             db-with-lib (range 7))
          effect {:effect/type :discard
                  :effect/count 1
                  :effect/selection :player
                  :effect/condition {:condition/type :threshold}}
          result (fx/execute-effect-checked db-with-gy :player-1 effect)]
      (is (contains? result :needs-selection)
          "When condition met, interactive effect should signal needs-selection"))))


(deftest execute-effect-checked-condition-unmet-returns-plain-db
  (testing "execute-effect-checked with unmet condition returns plain {:db db}"
    (let [db (init-game-state)
          ;; Empty graveyard - threshold NOT met
          effect {:effect/type :discard
                  :effect/count 1
                  :effect/selection :player
                  :effect/condition {:condition/type :threshold}}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (not (contains? result :needs-selection))
          "When condition not met, should not need selection")
      (is (= db (:db result)) "db should be unchanged"))))


;; === reduce-effects: sequential execution with interactive pause ===

(deftest reduce-effects-all-non-interactive
  (testing "reduce-effects executes all non-interactive effects and returns {:db db'}"
    (let [db (init-game-state)
          effects [{:effect/type :add-mana :effect/mana {:black 3}}
                   {:effect/type :add-mana :effect/mana {:red 2}}]
          result (fx/reduce-effects db :player-1 effects)]
      (is (contains? result :db))
      (is (not (contains? result :needs-selection)))
      (is (= 3 (:black (q/get-mana-pool (:db result) :player-1))))
      (is (= 2 (:red (q/get-mana-pool (:db result) :player-1)))))))


(deftest reduce-effects-pauses-on-interactive
  (testing "reduce-effects pauses when an interactive effect is encountered"
    (let [db (init-game-state)
          effects [{:effect/type :add-mana :effect/mana {:black 3}}
                   {:effect/type :discard :effect/count 1 :effect/selection :player}
                   {:effect/type :add-mana :effect/mana {:red 2}}]
          result (fx/reduce-effects db :player-1 effects)]
      (is (contains? result :needs-selection)
          "Should pause at the interactive effect")
      (is (= :discard (:effect/type (:needs-selection result)))
          "Should signal the interactive effect")
      (is (= 3 (:black (q/get-mana-pool (:db result) :player-1)))
          "Effects before interactive should be executed")
      (is (zero? (or (:red (q/get-mana-pool (:db result) :player-1)) 0))
          "Effects after interactive should NOT be executed")
      (is (= [{:effect/type :add-mana :effect/mana {:red 2}}]
             (:remaining-effects result))
          "Remaining effects should be returned"))))


(deftest reduce-effects-empty-list
  (testing "reduce-effects with empty effects returns {:db db} unchanged"
    (let [db (init-game-state)
          result (fx/reduce-effects db :player-1 [])]
      (is (contains? result :db))
      (is (not (contains? result :needs-selection)))
      (is (= db (:db result))))))


;; === resolve-dynamic-value tests ===

(deftest resolve-dynamic-value-static-integer-passes-through
  (testing "static integer amount passes through unchanged"
    (let [db (th/create-test-db)]
      (is (= 5 (fx/resolve-dynamic-value db :player-1 5 nil)))
      (is (= 0 (fx/resolve-dynamic-value db :player-1 0 nil)))
      (is (= 1 (fx/resolve-dynamic-value db :player-1 1 nil))))))


(deftest resolve-dynamic-value-count-named-in-zone
  (testing "dynamic map resolves count-named-in-zone correctly"
    (let [db (th/create-test-db)
          ;; Add 3 Dark Rituals to graveyard
          [db _ids] (th/add-cards-to-graveyard db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          ;; Create a Dark Ritual object so we can get its card name
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          dynamic {:dynamic/type :count-named-in-zone
                   :dynamic/zone :graveyard}]
      (is (= 3 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-count-named-in-zone-with-plus
  (testing "dynamic with :dynamic/plus adds offset"
    (let [db (th/create-test-db)
          ;; Add 2 Dark Rituals to graveyard
          [db _ids] (th/add-cards-to-graveyard db [:dark-ritual :dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          dynamic {:dynamic/type :count-named-in-zone
                   :dynamic/zone :graveyard
                   :dynamic/plus 1}]
      (is (= 3 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-zero-cards-returns-plus
  (testing "zero cards in zone returns :dynamic/plus value"
    (let [db (th/create-test-db)
          ;; No cards in graveyard
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          dynamic {:dynamic/type :count-named-in-zone
                   :dynamic/zone :graveyard
                   :dynamic/plus 2}]
      (is (= 2 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-counts-across-players
  (testing "cards across multiple players' graveyards are counted"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add 2 Dark Rituals to player-1 graveyard
          [db _ids] (th/add-cards-to-graveyard db [:dark-ritual :dark-ritual] :player-1)
          ;; Add 1 Dark Ritual to player-2 graveyard
          [db _ids] (th/add-cards-to-graveyard db [:dark-ritual] :player-2)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          dynamic {:dynamic/type :count-named-in-zone
                   :dynamic/zone :graveyard}]
      ;; Should count ALL Dark Rituals in ALL graveyards = 3
      (is (= 3 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-non-integer-non-map-defaults-to-zero
  (testing "non-integer non-map values default to 0"
    (let [db (th/create-test-db)]
      (is (= 0 (fx/resolve-dynamic-value db :player-1 nil nil)))
      (is (= 0 (fx/resolve-dynamic-value db :player-1 "bad" nil))))))


;; === resolve-dynamic-value :chosen-x tests ===

(deftest resolve-dynamic-value-chosen-x-reads-from-stack-item
  (testing ":chosen-x reads :stack-item/chosen-x from the spell's stack item"
    (let [db (th/create-test-db {:mana {:black 5}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          obj-eid (q/get-object-eid db obj-id)
          db (stack/create-stack-item db {:stack-item/type :spell
                                          :stack-item/controller :player-1
                                          :stack-item/source obj-id
                                          :stack-item/object-ref obj-eid
                                          :stack-item/chosen-x 7})
          dynamic {:dynamic/type :chosen-x}]
      (is (= 7 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-chosen-x-defaults-to-zero-when-missing
  (testing ":chosen-x returns 0 when stack item has no :stack-item/chosen-x"
    (let [db (th/create-test-db {:mana {:black 5}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          obj-eid (q/get-object-eid db obj-id)
          db (stack/create-stack-item db {:stack-item/type :spell
                                          :stack-item/controller :player-1
                                          :stack-item/source obj-id
                                          :stack-item/object-ref obj-eid})
          dynamic {:dynamic/type :chosen-x}]
      (is (= 0 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-chosen-x-no-stack-item-returns-zero
  (testing ":chosen-x returns 0 when object has no associated stack item"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          dynamic {:dynamic/type :chosen-x}]
      (is (= 0 (fx/resolve-dynamic-value db :player-1 dynamic obj-id))))))


(deftest resolve-dynamic-value-chosen-x-nil-object-returns-zero
  (testing ":chosen-x returns 0 when object-id is nil"
    (let [db (th/create-test-db)
          dynamic {:dynamic/type :chosen-x}]
      (is (= 0 (fx/resolve-dynamic-value db :player-1 dynamic nil))))))


;; === draw with dynamic amount tests ===

(deftest draw-with-dynamic-amount-resolves-at-execution
  (testing ":draw effect resolves dynamic amount from game state"
    (let [db (th/create-test-db)
          ;; Add 3 Dark Rituals to graveyard (simulating AK in graveyard)
          [db _ids] (th/add-cards-to-graveyard db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          ;; Add a Dark Ritual to hand as the source object
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Add cards to library to draw from
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual :dark-ritual :dark-ritual] :player-1)
          initial-hand (th/get-hand-count db :player-1)
          effect {:effect/type :draw
                  :effect/amount {:dynamic/type :count-named-in-zone
                                  :dynamic/zone :graveyard}}
          db' (fx/execute-effect db :player-1 effect obj-id)]
      ;; Should draw 3 cards (matching graveyard count)
      (is (= (+ initial-hand 3) (th/get-hand-count db' :player-1))))))


;; === execute-effect :grant-mana-ability tests ===

(def rain-of-filth-ability
  {:ability/type :mana
   :ability/cost {:sacrifice-self true}
   :ability/produces {:black 1}
   :ability/effects [{:effect/type :add-mana
                      :effect/mana {:black 1}}]})


(deftest test-grant-mana-ability-adds-grants-to-lands
  (testing ":grant-mana-ability adds ability grants to all controlled lands"
    (let [db (th/create-test-db)
          [db land1-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land2-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          effect {:effect/type :grant-mana-ability
                  :effect/target :controlled-lands
                  :effect/ability rain-of-filth-ability}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Both lands should have grants
      (let [land1-grants (q/get-grants db' land1-id)
            land2-grants (q/get-grants db' land2-id)]
        (is (= 1 (count land1-grants)))
        (is (= 1 (count land2-grants)))
        (is (= :ability (:grant/type (first land1-grants))))
        (is (= :mana (:ability/type (:grant/data (first land1-grants)))))))))


(deftest test-grant-mana-ability-no-lands-is-noop
  (testing ":grant-mana-ability with no lands on battlefield is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :grant-mana-ability
                  :effect/target :controlled-lands
                  :effect/ability rain-of-filth-ability}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')))))


(deftest test-grant-mana-ability-grants-expire-at-cleanup
  (testing "grants from :grant-mana-ability expire at cleanup phase"
    (let [db (th/create-test-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          effect {:effect/type :grant-mana-ability
                  :effect/target :controlled-lands
                  :effect/ability rain-of-filth-ability}
          db' (fx/execute-effect db :player-1 effect)]
      ;; Verify grant exists before expiry
      (is (= 1 (count (q/get-grants db' land-id))))
      ;; Expire grants at cleanup
      (let [db-expired (grants/expire-grants db' 1 :cleanup)]
        (is (= 0 (count (q/get-grants db-expired land-id))))))))


(deftest test-activate-granted-mana-ability-sacrifices-and-adds-mana
  (testing "activating granted mana ability sacrifices land and adds mana"
    (let [db (th/create-test-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          effect {:effect/type :grant-mana-ability
                  :effect/target :controlled-lands
                  :effect/ability rain-of-filth-ability}
          db' (fx/execute-effect db :player-1 effect)
          grant-id (:grant/id (first (q/get-grants db' land-id)))
          db'' (ability-events/activate-granted-mana-ability db' :player-1 land-id grant-id)]
      ;; Land should be in graveyard (sacrificed)
      (is (= :graveyard (:object/zone (q/get-object db'' land-id))))
      ;; Mana pool should have black mana
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))))))


(deftest test-activate-granted-ability-works-on-tapped-lands
  (testing "granted mana abilities can be activated on tapped lands (no :tap cost)"
    (let [db (th/create-test-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Tap the land
          obj-eid (q/get-object-eid db land-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])
          ;; Grant ability
          effect {:effect/type :grant-mana-ability
                  :effect/target :controlled-lands
                  :effect/ability rain-of-filth-ability}
          db' (fx/execute-effect db :player-1 effect)
          grant-id (:grant/id (first (q/get-grants db' land-id)))
          db'' (ability-events/activate-granted-mana-ability db' :player-1 land-id grant-id)]
      ;; Should work even though tapped
      (is (= :graveyard (:object/zone (q/get-object db'' land-id))))
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))))))


(deftest test-grant-mana-ability-multiple-lands-independent-grants
  (testing "each land gets an independent grant"
    (let [db (th/create-test-db)
          [db land1-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land2-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          effect {:effect/type :grant-mana-ability
                  :effect/target :controlled-lands
                  :effect/ability rain-of-filth-ability}
          db' (fx/execute-effect db :player-1 effect)
          ;; Activate granted ability on first land
          grant1-id (:grant/id (first (q/get-grants db' land1-id)))
          db'' (ability-events/activate-granted-mana-ability db' :player-1 land1-id grant1-id)]
      ;; First land in graveyard
      (is (= :graveyard (:object/zone (q/get-object db'' land1-id))))
      ;; Second land still on battlefield with its grant intact
      (is (= :battlefield (:object/zone (q/get-object db'' land2-id))))
      (is (= 1 (count (q/get-grants db'' land2-id)))))))


;; === Test helpers for :bounce-all and :lose-life-equal-to-toughness ===

(defn add-artifact-to-battlefield
  "Add a Lotus Petal (artifact) to the battlefield for a player.
   Returns [db object-id]."
  [db player-id]
  (th/add-card-to-zone db :lotus-petal :battlefield player-id))


(defn add-creature-to-battlefield
  "Add a creature card to the battlefield for a player.
   card-id must be a registered creature card (e.g., :nimble-mongoose, :xantid-swarm).
   Returns [db object-id]."
  [db player-id card-id]
  (th/add-card-to-zone db card-id :battlefield player-id))


;; === execute-effect :bounce-all tests ===

(deftest test-bounce-all-returns-artifacts-to-hand
  (testing ":bounce-all returns all artifacts from target player's battlefield to hand"
    ;; Catches: basic :bounce-all logic failure
    (let [db (th/create-test-db)
          [db art1-id] (add-artifact-to-battlefield db :player-1)
          [db art2-id] (add-artifact-to-battlefield db :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db art1-id))))
          _ (is (= :battlefield (:object/zone (q/get-object db art2-id))))
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (:object/zone (q/get-object db' art1-id)))
          "First artifact should be in hand")
      (is (= :hand (:object/zone (q/get-object db' art2-id)))
          "Second artifact should be in hand"))))


(deftest test-bounce-all-does-not-bounce-non-artifacts
  (testing ":bounce-all with {:match/types #{:artifact}} does not bounce lands or instants on battlefield"
    ;; Catches: missing criteria filtering (bouncing everything)
    (let [db (th/create-test-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :battlefield (:object/zone (q/get-object db' land-id)))
          "Land should NOT be bounced by :bounce-all with artifact criteria"))))


(deftest test-bounce-all-does-not-bounce-opponent-artifacts
  (testing ":bounce-all with :effect/target :player-1 does not bounce player-2's artifacts"
    ;; Catches: wrong player targeting (bouncing both players' artifacts)
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db art1-id] (add-artifact-to-battlefield db :player-1)
          [db art2-id] (add-artifact-to-battlefield db :player-2)
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (:object/zone (q/get-object db' art1-id)))
          "Player-1's artifact should be bounced")
      (is (= :battlefield (:object/zone (q/get-object db' art2-id)))
          "Player-2's artifact should NOT be bounced"))))


(deftest test-bounce-all-empty-battlefield-is-noop
  (testing ":bounce-all with empty battlefield returns db unchanged without error"
    ;; Catches: NPE on empty battlefield or nil from get-objects-in-zone
    (let [db (th/create-test-db)
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :battlefield)))
          "Battlefield should still be empty")
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :hand)))
          "Hand should still be empty"))))


(deftest test-bounce-all-three-plus-artifacts-all-returned
  (testing ":bounce-all returns 3+ artifacts simultaneously"
    ;; Catches: reduce accumulator bug (only processing first item)
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db art1-id] (add-artifact-to-battlefield db :player-1)
          [db art2-id] (add-artifact-to-battlefield db :player-2)
          [db art3-id] (add-artifact-to-battlefield db :player-1)
          [db art4-id] (add-artifact-to-battlefield db :player-1)
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (:object/zone (q/get-object db' art1-id)))
          "First artifact should be in hand")
      (is (= :hand (:object/zone (q/get-object db' art3-id)))
          "Third artifact should be in hand")
      (is (= :hand (:object/zone (q/get-object db' art4-id)))
          "Fourth artifact should be in hand")
      (is (= :battlefield (:object/zone (q/get-object db' art2-id)))
          "Player-2's artifact should stay on battlefield"))))


(deftest test-bounce-all-mixed-battlefield-only-artifacts-bounce
  (testing ":bounce-all with mixed battlefield: only artifacts bounce"
    ;; Catches: criteria bleed (bouncing non-matching objects)
    (let [db (th/create-test-db)
          [db art-id] (add-artifact-to-battlefield db :player-1)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (:object/zone (q/get-object db' art-id)))
          "Artifact should be in hand")
      (is (= :battlefield (:object/zone (q/get-object db' land-id)))
          "Land should remain on battlefield"))))


(deftest test-bounce-all-no-matching-permanents-stays-unchanged
  (testing ":bounce-all with no matching artifacts bounces nothing"
    ;; Catches: empty-match handling errors
    (let [db (th/create-test-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          effect {:effect/type :bounce-all
                  :effect/target :player-1
                  :effect/criteria {:match/types #{:artifact}}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :battlefield (:object/zone (q/get-object db' land-id)))
          "Land should remain on battlefield when no artifacts exist")
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :hand)))
          "Hand should be empty when no artifacts matched"))))


;; === execute-effect :lose-life-equal-to-toughness tests ===

(deftest test-lose-life-equal-to-toughness-basic
  (testing "caster loses life equal to target creature's toughness (e.g., 3 for 2/3)"
    ;; Catches: basic logic failure — wrong field read or wrong amount
    (let [[db target-id] (th/add-test-creature (th/create-test-db) :player-1 2 3 :colors #{:green})
          _ (is (= :battlefield (:object/zone (q/get-object db target-id))))
          effect {:effect/type :lose-life-equal-to-toughness
                  :effect/target target-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 17 (q/get-life-total db' :player-1))
          "Player should lose 3 life (creature toughness = 3)"))))


(deftest test-lose-life-equal-to-toughness-zero-toughness
  (testing "target creature with toughness 0 causes no life loss"
    ;; Catches: zero-toughness handling — must be a no-op
    (let [[db target-id] (th/add-test-creature (th/create-test-db) :player-1 2 0 :colors #{:green})
          effect {:effect/type :lose-life-equal-to-toughness
                  :effect/target target-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))
          "Player should lose no life when creature toughness is 0"))))


(deftest test-lose-life-equal-to-toughness-missing-target-is-noop
  (testing "target object not in db — no-op, no crash"
    ;; Catches: NPE when target doesn't exist in db
    (let [db (th/create-test-db)
          nonexistent-id (random-uuid)
          effect {:effect/type :lose-life-equal-to-toughness
                  :effect/target nonexistent-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))
          "Player life should be unchanged when target doesn't exist"))))


(deftest test-lose-life-equal-to-toughness-life-goes-negative
  (testing "life loss can push life below 0 (no floor)"
    ;; Catches: incorrect floor at 0 — MTG allows life to go negative
    (let [db (th/create-test-db {:life 2})
          [db target-id] (th/add-test-creature db :player-1 2 5 :colors #{:green})
          effect {:effect/type :lose-life-equal-to-toughness
                  :effect/target target-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= -3 (q/get-life-total db' :player-1))
          "Player at 2 life losing 5 should be at -3 life"))))


(deftest test-lose-life-equal-to-toughness-after-destroy-sequence
  (testing "reads :card/toughness correctly even after creature is destroyed (Vendetta sequence)"
    ;; Catches: reading :object/toughness (retracted on zone change) vs
    ;; :card/toughness (permanent, on card entity) — Vendetta's destroy-then-life-loss
    ;; After :destroy, creature moves to graveyard and :object/toughness is retracted.
    ;; The :lose-life-equal-to-toughness effect must still read the card toughness.
    (let [[db target-id] (th/add-test-creature (th/create-test-db) :player-1 2 4 :colors #{:green})
          ;; Destroy the creature first (simulating Vendetta's first effect)
          destroy-effect {:effect/type :destroy
                          :effect/target target-id}
          db-after-destroy (fx/execute-effect db :player-1 destroy-effect)
          ;; Verify creature is now in graveyard
          _ (is (= :graveyard (:object/zone (q/get-object db-after-destroy target-id)))
                "Precondition: creature should be in graveyard after destroy")
          ;; Now apply lose-life-equal-to-toughness (second effect of Vendetta)
          life-effect {:effect/type :lose-life-equal-to-toughness
                       :effect/target target-id}
          db' (fx/execute-effect db-after-destroy :player-1 life-effect)]
      (is (= 16 (q/get-life-total db' :player-1))
          "Player should lose 4 life even though creature is already in graveyard"))))


(deftest test-lose-life-equal-to-toughness-high-toughness
  (testing "high toughness creature (0/7) causes 7 life loss"
    ;; Catches: off-by-one or wrong field read for high toughness values
    (let [[db target-id] (th/add-test-creature (th/create-test-db) :player-1 2 7 :colors #{:green})
          effect {:effect/type :lose-life-equal-to-toughness
                  :effect/target target-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 13 (q/get-life-total db' :player-1))
          "Player should lose 7 life for 0/7 creature"))))


(deftest test-lose-life-equal-to-toughness-nil-target-is-noop
  (testing "nil target returns db unchanged"
    ;; Catches: NPE on nil target-id
    (let [db (th/create-test-db)
          effect {:effect/type :lose-life-equal-to-toughness
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))
          "Player life should be unchanged when target is nil"))))


;; === execute-effect :bounce tests ===

(def test-bounce-card
  {:card/id :test-bounce-card
   :card/name "Test Bounce Card"
   :card/cmc 2
   :card/mana-cost {:blue 2}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Return target permanent to its owner's hand."
   :card/effects []})


(deftest test-bounce-effect-moves-to-hand
  (testing ":bounce effect returns object from battlefield to owner's hand"
    ;; Catches: move-to-zone call missing or wrong destination zone
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :bounce
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (get-object-zone db' object-id))
          "Object should be in :hand after bounce"))))


(deftest test-bounce-effect-nonexistent-target-is-noop
  (testing ":bounce with nonexistent target returns db unchanged"
    ;; Catches: missing get-object-eid existence guard
    (let [db (init-game-state)
          effect {:effect/type :bounce
                  :effect/target (random-uuid)}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target object does not exist"))))


(deftest test-bounce-effect-nil-target-is-noop
  (testing ":bounce with nil :effect/target returns db unchanged"
    ;; Catches: missing nil guard before get-object-eid call
    (let [db (init-game-state)
          effect {:effect/type :bounce
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target is nil"))))


(deftest test-bounce-effect-already-in-hand
  (testing ":bounce of object already in hand returns db unchanged (move-to-zone same-zone no-op)"
    ;; Catches: move-to-zone returning changed db on same-zone transition
    ;; Production: bounce calls move-to-zone which is a no-op when source == destination
    (let [[object-id db] (add-object-with-card (init-game-state) :player-1 test-bounce-card :hand)
          effect {:effect/type :bounce
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (get-object-zone db' object-id))
          "Object still in :hand after bounce from hand"))))


;; === execute-effect :phase-out tests ===

(deftest test-phase-out-effect-moves-to-phased-out
  (testing ":phase-out effect changes object zone to :phased-out"
    ;; Catches: zones/phase-out not called or wrong destination zone
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          effect {:effect/type :phase-out
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :phased-out (get-object-zone db' object-id))
          "Object should be in :phased-out zone after phase-out effect"))))


(deftest test-phase-out-effect-nil-target-is-noop
  (testing ":phase-out with nil target returns db unchanged"
    ;; Catches: missing nil guard before get-object-eid call
    (let [db (init-game-state)
          effect {:effect/type :phase-out
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target is nil"))))


(deftest test-phase-out-effect-nonexistent-target-is-noop
  (testing ":phase-out with nonexistent target returns db unchanged"
    ;; Catches: missing existence guard
    (let [db (init-game-state)
          effect {:effect/type :phase-out
                  :effect/target (random-uuid)}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target object does not exist"))))


(deftest test-phase-out-effect-preserves-tapped-state
  (testing ":phase-out preserves :object/tapped — uses direct d/db-with not move-to-zone"
    ;; Catches: regression if someone changes phase-out to use move-to-zone (which
    ;; would retract tapped state). phase-out does direct zone change to preserve state.
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          obj-eid (q/get-object-eid db object-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])
          effect {:effect/type :phase-out
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)
          obj' (q/get-object db' object-id)]
      (is (= :phased-out (:object/zone obj'))
          "Object should be in :phased-out zone")
      (is (true? (:object/tapped obj'))
          ":object/tapped should be preserved after phase-out (not reset)"))))


;; === execute-effect :peek-random-hand tests ===

(deftest test-peek-random-hand-stores-card-name
  (testing ":peek-random-hand stores a card name string from target's hand in :game/peek-result"
    ;; Catches: peek-result not set, set to entity/id instead of name string
    ;; Note: string? + contains? is not tautological here — production could return nil or an entity
    (let [db (-> (init-game-state)
                 (add-opponent))
          [_ db] (add-object-with-card db :player-2 test-bounce-card :hand)
          hand-card-names #{"Test Bounce Card"}
          effect {:effect/type :peek-random-hand
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect)
          result (d/q '[:find ?r . :where [_ :game/peek-result ?r]] db')]
      (is (string? result)
          ":game/peek-result should be a string (card name)")
      (is (contains? hand-card-names result)
          ":game/peek-result should be one of the card names from the target's hand"))))


(deftest test-peek-random-hand-empty-hand-is-noop
  (testing ":peek-random-hand with empty target hand returns db unchanged, no peek-result set"
    ;; Catches: crash from rand-nth on empty seq — empty hand must be a no-op
    (let [db (-> (init-game-state)
                 (add-opponent))
          effect {:effect/type :peek-random-hand
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect)
          result (d/q '[:find ?r . :where [_ :game/peek-result ?r]] db')]
      (is (nil? result)
          ":game/peek-result should not be set when target has empty hand"))))


(deftest test-peek-random-hand-nil-target-is-noop
  (testing ":peek-random-hand with nil :effect/target returns db unchanged"
    ;; Catches: missing nil target guard
    (let [db (init-game-state)
          effect {:effect/type :peek-random-hand
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target is nil"))))


(deftest test-peek-random-hand-peeks-opponent-hand
  (testing ":peek-random-hand can target opponent's hand (player-2)"
    ;; Catches: wrong player resolution or targeting only self
    (let [db (-> (init-game-state)
                 (add-opponent))
          [_ db] (add-object-with-card db :player-2 test-bounce-card :hand)
          effect {:effect/type :peek-random-hand
                  :effect/target :player-2}
          db' (fx/execute-effect db :player-1 effect)
          result (d/q '[:find ?r . :where [_ :game/peek-result ?r]] db')]
      (is (string? result)
          "peek-result should be a card name string from opponent's hand"))))


;; === execute-effect :gain-life-equal-to-cmc tests ===

(def test-cmc3-card
  {:card/id :test-cmc3-card
   :card/name "Test CMC3 Card"
   :card/cmc 3
   :card/mana-cost {:colorless 1 :blue 2}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Test card with CMC 3."
   :card/effects []})


(def test-cmc0-card
  {:card/id :test-cmc0-card
   :card/name "Test CMC0 Card"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:instant}
   :card/text "Test card with CMC 0."
   :card/effects []})


(deftest test-gain-life-equal-to-cmc-happy-path
  (testing ":gain-life-equal-to-cmc gains controller's life by object's CMC"
    ;; Catches: wrong life delta, wrong player (gains for controller not caster)
    (let [[object-id db] (add-object-with-card (init-game-state) :player-1 test-cmc3-card :battlefield)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 23 (q/get-life-total db' :player-1))
          "Controller starts at 20, gains 3 from CMC=3 object → life should be 23"))))


(deftest test-gain-life-equal-to-cmc-zero-is-noop
  (testing ":gain-life-equal-to-cmc with CMC=0 is an explicit no-op (not 'gain 0 life')"
    ;; Catches: missing (<= cmc 0) guard — production explicitly returns db unchanged
    (let [[object-id db] (add-object-with-card (init-game-state) :player-1 test-cmc0-card :battlefield)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))
          "Life should be unchanged when CMC=0 (no-op guard fires)"))))


(deftest test-gain-life-equal-to-cmc-no-cmc-key-is-noop
  (testing ":gain-life-equal-to-cmc with no :card/cmc field defaults to 0 → no-op"
    ;; Catches: NPE on nil cmc — production uses (or cmc 0) then <= 0 guard
    (let [no-cmc-card (dissoc test-cmc3-card :card/cmc :card/id)
          no-cmc-card (assoc no-cmc-card :card/id :test-no-cmc-card)
          [object-id db] (add-object-with-card (init-game-state) :player-1 no-cmc-card :battlefield)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))
          "Life should be unchanged when card has no :card/cmc (defaults to 0 → no-op)"))))


(deftest test-gain-life-equal-to-cmc-nil-target-is-noop
  (testing ":gain-life-equal-to-cmc with nil target returns db unchanged"
    ;; Catches: missing nil guard before get-object call
    (let [db (init-game-state)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 20 (q/get-life-total db' :player-1))
          "Life should be unchanged when target is nil"))))


(deftest test-gain-life-equal-to-cmc-gains-for-controller-not-caster
  (testing ":gain-life-equal-to-cmc gains life for CONTROLLER of object, not the effect executor"
    ;; Catches: using player-id (caster) instead of resolving controller from target object.
    ;; This is a real divergence from most effects — production reads controller from the object.
    ;; Setup: player-2 controls the object; effect executed by player-1.
    ;; Expected: player-2 gains life (controller), player-1 does NOT.
    (let [db (-> (init-game-state)
                 (add-opponent))
          [object-id db] (add-object-with-card db :player-2 test-cmc3-card :battlefield)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target object-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 23 (q/get-life-total db' :player-2))
          "Controller (player-2) should gain 3 life (CMC=3)")
      (is (= 20 (q/get-life-total db' :player-1))
          "Caster/executor (player-1) should NOT gain life"))))


;; === execute-effect :tap-all tests ===

(deftest test-tap-all-taps-untapped-creatures
  (testing ":tap-all taps UNTAPPED creatures of matching permanent-type on target player's battlefield"
    ;; Catches: filter missing tapped-check (all creatures tapped, not just untapped)
    (let [db (init-game-state)
          [db c1-id] (th/add-test-creature db :player-1 2 2)
          [db c2-id] (th/add-test-creature db :player-1 1 1)
          ;; Manually tap c2 so only c1 should be tapped by the effect
          c2-eid (q/get-object-eid db c2-id)
          db (d/db-with db [[:db/add c2-eid :object/tapped true]])
          effect {:effect/type :tap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)
          c1-obj (q/get-object db' c1-id)
          c2-obj (q/get-object db' c2-id)]
      (is (true? (:object/tapped c1-obj))
          "c1 (untapped) should be tapped after :tap-all")
      (is (true? (:object/tapped c2-obj))
          "c2 (already tapped) should remain tapped"))))


(deftest test-tap-all-empty-battlefield-is-noop
  (testing ":tap-all with no objects on battlefield returns db unchanged"
    ;; Catches: crash on empty sequence reduction
    (let [db (init-game-state)
          effect {:effect/type :tap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when no permanents exist"))))


(deftest test-tap-all-no-matching-type-is-noop
  (testing ":tap-all skips objects whose card type does not match :effect/permanent-type"
    ;; Catches: type filter missing (sorcery on battlefield not tapped by :creature tap-all)
    (let [db (init-game-state)
          [db obj-id] (add-permanent db :player-1) ; dark-ritual = sorcery
          effect {:effect/type :tap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature} ; creature filter; sorcery won't match
          db' (fx/execute-effect db :player-1 effect)
          obj (q/get-object db' obj-id)]
      (is (false? (:object/tapped obj))
          "Non-creature permanent should NOT be tapped by :creature tap-all"))))


(deftest test-tap-all-already-tapped-unchanged
  (testing ":tap-all skips already-tapped creatures — only UNTAPPED matching objects are tapped"
    ;; Catches: missing untapped guard (would set tapped true redundantly but more importantly
    ;; confirms the filter is correctly skipping already-tapped objects)
    (let [db (init-game-state)
          [db c1-id] (th/add-test-creature db :player-1 2 2)
          [db c2-id] (th/add-test-creature db :player-1 1 1)
          ;; Tap both creatures before the effect
          c1-eid (q/get-object-eid db c1-id)
          c2-eid (q/get-object-eid db c2-id)
          db (d/db-with db [[:db/add c1-eid :object/tapped true]
                            [:db/add c2-eid :object/tapped true]])
          effect {:effect/type :tap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)]
      ;; db should be identical — no changes when all already tapped
      (is (= db db')
          "db should be unchanged when all matching permanents are already tapped"))))


;; === execute-effect :untap-all tests ===

(deftest test-untap-all-untaps-tapped-creatures
  (testing ":untap-all untaps TAPPED creatures of matching permanent-type on target player's battlefield"
    ;; Catches: filter missing tapped-check (untaps already-untapped objects)
    (let [db (init-game-state)
          [db c1-id] (th/add-test-creature db :player-1 2 2)
          [db c2-id] (th/add-test-creature db :player-1 1 1)
          ;; Tap c1 so only c1 should be untapped by the effect
          c1-eid (q/get-object-eid db c1-id)
          db (d/db-with db [[:db/add c1-eid :object/tapped true]])
          effect {:effect/type :untap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)
          c1-obj (q/get-object db' c1-id)
          c2-obj (q/get-object db' c2-id)]
      (is (false? (:object/tapped c1-obj))
          "c1 (tapped) should be untapped after :untap-all")
      (is (false? (:object/tapped c2-obj))
          "c2 (already untapped) should remain untapped"))))


(deftest test-untap-all-empty-battlefield-is-noop
  (testing ":untap-all with no objects on battlefield returns db unchanged"
    ;; Catches: crash on empty sequence reduction
    (let [db (init-game-state)
          effect {:effect/type :untap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when no permanents exist"))))


(deftest test-untap-all-no-matching-tapped-is-noop
  (testing ":untap-all with no tapped matching creatures is a no-op"
    ;; Catches: missing tapped guard (would try to untap already-untapped objects)
    (let [db (init-game-state)
          [db _c1-id] (th/add-test-creature db :player-1 2 2)
          [db _c2-id] (th/add-test-creature db :player-1 1 1)
          ;; Both creatures start untapped (default)
          effect {:effect/type :untap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when all matching permanents are already untapped"))))


(deftest test-untap-all-type-filter-respected
  (testing ":untap-all only untaps objects matching :effect/permanent-type"
    ;; Catches: type filter missing (tapped land untapped by :creature untap-all)
    ;; Setup: tapped dark-ritual sorcery on battlefield; untap-all :creature should not touch it
    (let [db (init-game-state)
          [db obj-id] (add-permanent db :player-1)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])
          effect {:effect/type :untap-all
                  :effect/target :player-1
                  :effect/permanent-type :creature}
          db' (fx/execute-effect db :player-1 effect)
          obj (q/get-object db' obj-id)]
      (is (true? (:object/tapped obj))
          "Non-creature permanent should NOT be untapped by :creature untap-all"))))


;; === execute-effect :exile-zone tests ===

(deftest test-exile-zone-moves-all-graveyard-to-exile
  (testing ":exile-zone moves all objects in the specified zone to :exile"
    ;; Catches: move-to-zone not called for all objects, or wrong destination
    (let [db (init-game-state)
          [_ db] (add-object-with-card db :player-1 test-bounce-card :graveyard)
          [_ db] (add-object-with-card db :player-1 test-bounce-card :graveyard)
          [_ db] (add-object-with-card db :player-1 test-bounce-card :graveyard)
          effect {:effect/type :exile-zone
                  :effect/target :player-1
                  :effect/zone :graveyard}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (count-zone db' :player-1 :graveyard))
          "Graveyard should be empty after :exile-zone")
      (is (= 3 (count-zone db' :player-1 :exile))
          "All 3 cards should be in exile"))))


(deftest test-exile-zone-empty-zone-is-noop
  (testing ":exile-zone with an empty target zone returns db unchanged"
    ;; Catches: crash on nil/empty reduce — production uses (or ... []) guard
    (let [db (init-game-state)
          effect {:effect/type :exile-zone
                  :effect/target :player-1
                  :effect/zone :graveyard}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target zone is empty"))))


(deftest test-exile-zone-nil-target-is-noop
  (testing ":exile-zone with nil :effect/target returns db unchanged"
    ;; Catches: missing nil guard — production checks (and target-player zone player-eid)
    (let [db (init-game-state)
          effect {:effect/type :exile-zone
                  :effect/target nil
                  :effect/zone :graveyard}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when :effect/target is nil"))))


(deftest test-exile-zone-hand-zone
  (testing ":exile-zone exiles the specified zone — :hand not :graveyard"
    ;; Catches: zone parameter ignored (always exiles graveyard regardless of :effect/zone)
    (let [db (init-game-state)
          [hand-id db] (add-object-with-card db :player-1 test-bounce-card :hand)
          [gy-id db]   (add-object-with-card db :player-1 test-bounce-card :graveyard)
          effect {:effect/type :exile-zone
                  :effect/target :player-1
                  :effect/zone :hand}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :exile (get-object-zone db' hand-id))
          "Hand object should be in :exile after :exile-zone :hand")
      (is (= :graveyard (get-object-zone db' gy-id))
          "Graveyard object should remain in :graveyard (different zone)"))))


;; === execute-effect :create-token tests ===

(def test-soldier-token-def
  {:token/types     #{:creature}
   :token/name      "Soldier"
   :token/power     1
   :token/toughness 1
   :token/subtypes  #{:soldier}
   :token/colors    #{:white}})


(deftest test-create-token-places-object-on-battlefield
  (testing ":create-token creates an object in the owner's battlefield zone"
    ;; Catches: wrong zone, object not created
    (let [db (init-game-state)
          effect {:effect/type  :create-token
                  :effect/token test-soldier-token-def}
          db' (fx/execute-effect db :player-1 effect)
          bf-objects (q/get-objects-in-zone db' :player-1 :battlefield)]
      (is (= 1 (count bf-objects))
          "Exactly one object should be on the battlefield after token creation"))))


(deftest test-create-token-has-correct-power-toughness
  (testing ":create-token sets correct base :object/power and :object/toughness on the created object"
    ;; Catches: P/T not propagated from token-def to object
    (let [db (init-game-state)
          effect {:effect/type  :create-token
                  :effect/token test-soldier-token-def}
          db' (fx/execute-effect db :player-1 effect)
          obj (first (q/get-objects-in-zone db' :player-1 :battlefield))]
      (is (= 1 (:object/power obj))
          "Token power should match :token/power 1")
      (is (= 1 (:object/toughness obj))
          "Token toughness should match :token/toughness 1"))))


(deftest test-create-token-has-summoning-sickness
  (testing ":create-token sets :object/summoning-sick true on newly created token"
    ;; Catches: missing summoning-sick flag (token could attack immediately)
    (let [db (init-game-state)
          effect {:effect/type  :create-token
                  :effect/token test-soldier-token-def}
          db' (fx/execute-effect db :player-1 effect)
          obj (first (q/get-objects-in-zone db' :player-1 :battlefield))]
      (is (true? (:object/summoning-sick obj))
          "Token should have :object/summoning-sick true"))))


(deftest test-create-token-card-entity-has-correct-types
  (testing ":create-token creates a synthetic card entity with :card/types from token def"
    ;; Catches: card entity not created, types not propagated to card entity
    (let [db (init-game-state)
          effect {:effect/type  :create-token
                  :effect/token test-soldier-token-def}
          db' (fx/execute-effect db :player-1 effect)
          obj (first (q/get-objects-in-zone db' :player-1 :battlefield))
          card (:object/card obj)]
      (is (= #{:creature} (set (:card/types card)))
          "Token card entity should have :card/types #{:creature}"))))


(deftest test-create-token-card-entity-has-correct-colors
  (testing ":create-token propagates :token/colors to the synthetic card entity"
    ;; Catches: colors not set on card entity (e.g. for color-matching effects)
    (let [db (init-game-state)
          effect {:effect/type  :create-token
                  :effect/token test-soldier-token-def}
          db' (fx/execute-effect db :player-1 effect)
          obj (first (q/get-objects-in-zone db' :player-1 :battlefield))
          card (:object/card obj)]
      (is (= #{:white} (set (:card/colors card)))
          "Token card entity should have :card/colors #{:white}"))))


(deftest test-create-token-nil-token-def-is-noop
  (testing ":create-token with nil :effect/token — documents crash behavior (no nil guard in production)"
    ;; Catches: nil token-def causes NPE in production (token destructuring is unconditional).
    ;; This test documents that nil token crashes — if a nil guard is added, update to assert db unchanged.
    (let [db (init-game-state)
          effect {:effect/type :create-token :effect/token nil}]
      (is (thrown? js/Error (fx/execute-effect db :player-1 effect))
          "nil :effect/token should throw — production has no nil guard"))))


;; === execute-effect :apply-pt-modifier tests ===

(deftest test-apply-pt-modifier-positive-delta
  (testing ":apply-pt-modifier with +1/+1 increases effective P/T by 1/1"
    ;; Catches: grant not applied, wrong delta sign
    (let [db (init-game-state)
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          effect {:effect/type      :apply-pt-modifier
                  :effect/target    obj-id
                  :effect/power     1
                  :effect/toughness 1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (creatures/effective-power db' obj-id))
          "Effective power should be 2 base + 1 from grant = 3")
      (is (= 3 (creatures/effective-toughness db' obj-id))
          "Effective toughness should be 2 base + 1 from grant = 3"))))


(deftest test-apply-pt-modifier-negative-delta
  (testing ":apply-pt-modifier with -1/-1 decreases effective P/T by 1/1"
    ;; Catches: sign handling — negative deltas must reduce not increase P/T
    (let [db (init-game-state)
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          effect {:effect/type      :apply-pt-modifier
                  :effect/target    obj-id
                  :effect/power     -1
                  :effect/toughness -1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 1 (creatures/effective-power db' obj-id))
          "Effective power should be 2 base - 1 from grant = 1")
      (is (= 1 (creatures/effective-toughness db' obj-id))
          "Effective toughness should be 2 base - 1 from grant = 1"))))


(deftest test-apply-pt-modifier-nil-target-is-noop
  (testing ":apply-pt-modifier with nil :effect/target returns db unchanged"
    ;; Catches: missing nil guard before get-object-eid call
    (let [db (init-game-state)
          effect {:effect/type      :apply-pt-modifier
                  :effect/target    nil
                  :effect/power     2
                  :effect/toughness 2}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target is nil"))))


(deftest test-apply-pt-modifier-nonexistent-target-is-noop
  (testing ":apply-pt-modifier with nonexistent target UUID returns db unchanged"
    ;; Catches: missing existence guard — if object doesn't exist grant must not be created
    (let [db (init-game-state)
          effect {:effect/type      :apply-pt-modifier
                  :effect/target    (random-uuid)
                  :effect/power     2
                  :effect/toughness 2}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target object does not exist"))))


;; === execute-effect :counter-spell tests ===

(def test-counter-spell-target
  {:card/id :test-counter-spell-target
   :card/name "Test Counter Target"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Test spell for counter-spell effect."
   :card/effects []})


(deftest test-counter-spell-direct-counter
  (testing ":counter-spell moves target spell from :stack to :graveyard"
    ;; Catches: counter-target-spell not called, wrong destination zone
    (let [db (init-game-state)
          [spell-id db] (add-object-with-card db :player-1 test-counter-spell-target :stack)
          effect {:effect/type :counter-spell
                  :effect/target spell-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :graveyard (get-object-zone db' spell-id))
          "Countered spell should move to :graveyard"))))


(deftest test-counter-spell-nil-target-is-noop
  (testing ":counter-spell with nil :effect/target returns db unchanged"
    ;; Catches: missing nil guard — production if-not target-id returns db
    (let [db (init-game-state)
          effect {:effect/type :counter-spell
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target is nil"))))


(deftest test-counter-spell-nonexistent-target-is-noop
  (testing ":counter-spell with nonexistent target UUID returns db unchanged"
    ;; Catches: missing get-object existence guard
    (let [db (init-game-state)
          effect {:effect/type :counter-spell
                  :effect/target (random-uuid)}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target does not exist"))))


(deftest test-counter-spell-target-not-on-stack-is-noop
  (testing ":counter-spell with target existing but NOT on stack returns db unchanged"
    ;; Catches: missing zone guard — production checks (not= :stack zone) → returns db
    (let [db (init-game-state)
          ;; Object is in :hand, not :stack
          [obj-id db] (add-object-with-card db :player-1 test-counter-spell-target :hand)
          effect {:effect/type :counter-spell
                  :effect/target obj-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :hand (get-object-zone db' obj-id))
          "Object in :hand should remain in :hand — counter-spell only acts on :stack objects")
      (is (= db db')
          "db should be unchanged when target is not on the stack"))))


(deftest test-counter-spell-unless-pay-returns-tagged-map
  (testing ":counter-spell with :effect/unless-pay returns {:db :needs-selection} map"
    ;; Catches: unless-pay path broken, wrong controller resolution
    ;; NOTE: uses execute-effect-checked (not execute-effect) to get the full tagged result
    ;; execute-effect is a backward-compat wrapper that strips :needs-selection to plain db
    ;; NOTE: tests shape of tagged return only — full selection flow is integration scope
    (let [db (init-game-state)
          [spell-id db] (add-object-with-card db :player-1 test-counter-spell-target :stack)
          effect {:effect/type :counter-spell
                  :effect/target spell-id
                  :effect/unless-pay {:mana {:colorless 2}}}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (map? result)
          "unless-pay path should return a map (tagged return), not plain db")
      (is (contains? result :db)
          "Tagged return should have :db key")
      (is (contains? result :needs-selection)
          "Tagged return should have :needs-selection key")
      (is (= :player-1 (get-in result [:needs-selection :unless-pay/controller]))
          ":unless-pay/controller should be :player-1 (the spell's controller)"))))


;; === execute-effect :counter-ability tests ===

(deftest test-counter-ability-removes-stack-item
  (testing ":counter-ability removes an activated-ability stack item from the stack"
    ;; Catches: remove-stack-item not called, stack item remains after counter
    (let [db (init-game-state)
          ;; Create an activated-ability stack item (use stack/create-stack-item)
          [obj-id db] (add-object-with-card db :player-1 test-counter-spell-target :battlefield)
          ability-item {:stack-item/type :activated-ability
                        :stack-item/controller :player-1
                        :stack-item/source obj-id
                        :stack-item/effects [{:effect/type :add-mana :effect/mana {:blue 1}}]}
          db (stack/create-stack-item db ability-item)
          ;; Find the created stack item's :db/id to use as effect target
          stack-eid (d/q '[:find ?e .
                           :where [?e :stack-item/type :activated-ability]]
                         db)
          effect {:effect/type :counter-ability
                  :effect/target stack-eid}
          db' (fx/execute-effect db :player-1 effect)
          ;; Check the stack item no longer exists
          remaining (d/q '[:find ?e .
                           :where [?e :stack-item/type :activated-ability]]
                         db')]
      (is (nil? remaining)
          "Activated-ability stack item should be removed after :counter-ability"))))


(deftest test-counter-ability-nil-target-is-noop
  (testing ":counter-ability with nil :effect/target returns db unchanged"
    ;; Catches: missing nil guard — production if-not target-eid returns db
    (let [db (init-game-state)
          effect {:effect/type :counter-ability
                  :effect/target nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when target is nil"))))


(deftest test-counter-ability-nonexistent-eid-is-noop
  (testing ":counter-ability with nonexistent entity ID returns db unchanged"
    ;; Catches: missing existence guard — d/pull on nonexistent eid returns nil
    (let [db (init-game-state)
          effect {:effect/type :counter-ability
                  :effect/target 999999}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when entity ID does not exist"))))


(deftest test-counter-ability-spell-type-is-noop
  (testing ":counter-ability on a :spell stack item returns db unchanged (only counters abilities)"
    ;; Catches: missing type guard — production checks (activated-ability or triggered-ability)
    (let [db (init-game-state)
          [obj-id db] (add-object-with-card db :player-1 test-counter-spell-target :stack)
          obj-eid (q/get-object-eid db obj-id)
          spell-item {:stack-item/type :spell
                      :stack-item/controller :player-1
                      :stack-item/source obj-id
                      :stack-item/object-ref obj-eid}
          db (stack/create-stack-item db spell-item)
          spell-eid (d/q '[:find ?e .
                           :where [?e :stack-item/type :spell]]
                         db)
          effect {:effect/type :counter-ability
                  :effect/target spell-eid}
          db' (fx/execute-effect db :player-1 effect)
          ;; Spell stack item should still exist with the same EID
          still-there (d/q '[:find ?e .
                             :where [?e :stack-item/type :spell]]
                           db')]
      (is (= spell-eid still-there)
          "Spell stack item EID should be unchanged — :counter-ability must not remove :spell items"))))


;; === execute-effect :welder-swap tests ===

(def test-welder-artifact-bf
  {:card/id :test-welder-artifact-bf
   :card/name "Test Welder Artifact BF"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Test artifact for welder-swap (battlefield side)."
   :card/effects []})


(def test-welder-artifact-gy
  {:card/id :test-welder-artifact-gy
   :card/name "Test Welder Artifact GY"
   :card/cmc 3
   :card/mana-cost {:colorless 3}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Test artifact for welder-swap (graveyard side)."
   :card/effects []})


(deftest test-welder-swap-happy-path
  (testing ":welder-swap moves battlefield artifact to graveyard and graveyard artifact to battlefield"
    ;; Catches: move-to-zone calls wrong zones, bf/gy targets swapped
    (let [db (init-game-state)
          [bf-id db] (add-object-with-card db :player-1 test-welder-artifact-bf :battlefield)
          [gy-id db] (add-object-with-card db :player-1 test-welder-artifact-gy :graveyard)
          effect {:effect/type :welder-swap
                  :effect/target bf-id
                  :effect/graveyard-id gy-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= :graveyard (get-object-zone db' bf-id))
          "Battlefield artifact should move to :graveyard")
      (is (= :battlefield (get-object-zone db' gy-id))
          "Graveyard artifact should move to :battlefield"))))


(deftest test-welder-swap-missing-bf-target-is-noop
  (testing ":welder-swap with nil :effect/target (battlefield side) returns db unchanged"
    ;; Catches: missing nil guard on bf-id — production if-not (and bf-id gy-id) returns db
    (let [db (init-game-state)
          [gy-id db] (add-object-with-card db :player-1 test-welder-artifact-gy :graveyard)
          effect {:effect/type :welder-swap
                  :effect/target nil
                  :effect/graveyard-id gy-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when battlefield target (:effect/target) is nil"))))


(deftest test-welder-swap-missing-gy-target-is-noop
  (testing ":welder-swap with nil :effect/graveyard-id (graveyard side) returns db unchanged"
    ;; Catches: missing nil guard on gy-id — production if-not (and bf-id gy-id) returns db
    (let [db (init-game-state)
          [bf-id db] (add-object-with-card db :player-1 test-welder-artifact-bf :battlefield)
          effect {:effect/type :welder-swap
                  :effect/target bf-id
                  :effect/graveyard-id nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when graveyard target (:effect/graveyard-id) is nil"))))


(deftest test-welder-swap-both-targets-nil-is-noop
  (testing ":welder-swap with both targets nil returns db unchanged"
    ;; Catches: nil AND check not short-circuiting properly
    (let [db (init-game-state)
          effect {:effect/type :welder-swap
                  :effect/target nil
                  :effect/graveyard-id nil}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when both targets are nil"))))


(deftest test-welder-swap-bf-target-in-wrong-zone-is-noop
  (testing ":welder-swap with battlefield target existing but in wrong zone returns db unchanged"
    ;; Catches: missing zone legality check — production checks bf-legal = (= :battlefield zone)
    (let [db (init-game-state)
          ;; Both objects exist but bf-id is in graveyard (wrong zone)
          [bf-id db] (add-object-with-card db :player-1 test-welder-artifact-bf :graveyard)
          [gy-id db] (add-object-with-card db :player-1 test-welder-artifact-gy :graveyard)
          effect {:effect/type :welder-swap
                  :effect/target bf-id
                  :effect/graveyard-id gy-id}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "db should be unchanged when :effect/target is not on the battlefield"))))


;; === execute-effect :shuffle-from-graveyard-to-library tests ===

(deftest test-shuffle-from-graveyard-to-library-auto-moves-all-cards
  (testing ":auto selection path moves all graveyard cards to library and shuffles"
    ;; Catches: missing defmethod for :shuffle-from-graveyard-to-library
    ;; and missing :auto handling.
    ;; th/add-card-to-zone returns [db obj-id] tuple — must destructure.
    (let [db (-> (init-game-state)
                 (add-opponent))
          [db _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          initial-gy-count (count-zone db :player-1 :graveyard)
          initial-lib-count (count-zone db :player-1 :library)
          effect {:effect/type :shuffle-from-graveyard-to-library
                  :effect/target :player-1
                  :effect/count :all
                  :effect/selection :auto}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 initial-gy-count) "Setup: 3 cards in graveyard")
      (is (= 0 (count-zone db' :player-1 :graveyard))
          "After :auto path: graveyard should be empty")
      (is (= (+ initial-lib-count 3) (count-zone db' :player-1 :library))
          "After :auto path: library gains all 3 former graveyard cards"))))


(deftest test-shuffle-from-graveyard-to-library-player-returns-needs-selection
  (testing ":player selection path returns {:db db :needs-selection effect}"
    ;; Catches: missing :player branch — must signal interaction needed.
    ;; Uses execute-effect-checked (not execute-effect) to get tagged result.
    (let [db (init-game-state)
          effect {:effect/type :shuffle-from-graveyard-to-library
                  :effect/target :player-1
                  :effect/count 3
                  :effect/selection :player}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (map? result) "Player path must return a map, not a plain db")
      (is (contains? result :db) "Result must contain :db key")
      (is (contains? result :needs-selection) "Result must contain :needs-selection key")
      (is (= effect (:needs-selection result)) "needs-selection must be the effect map"))))


(deftest test-shuffle-from-graveyard-to-library-auto-empty-graveyard-is-noop
  (testing ":auto path with empty graveyard is a no-op (0 cards = legal per oracle 'up to')"
    ;; Catches: potential crash on empty reduce or nil gy-cards
    ;; No add-opponent needed — just test player-1 with empty graveyard.
    (let [db (init-game-state)
          initial-lib-count (count-zone db :player-1 :library)
          effect {:effect/type :shuffle-from-graveyard-to-library
                  :effect/target :player-1
                  :effect/count :all
                  :effect/selection :auto}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 0 (count-zone db' :player-1 :graveyard))
          "Graveyard stays empty")
      (is (= initial-lib-count (count-zone db' :player-1 :library))
          "Library unchanged when graveyard was empty"))))
