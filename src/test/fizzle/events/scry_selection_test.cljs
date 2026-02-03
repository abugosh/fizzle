(ns fizzle.events.scry-selection-test
  "Tests for scry selection state creation in game.cljs.

   When a spell with scry effect resolves, the selection system creates
   :selection/type :scry state for the UI to render a selection modal.

   Tests verify:
   - build-scry-selection creates correct selection state structure
   - Top N cards from library are correctly selected
   - Edge cases: library < N, empty library, missing amount
   - Remaining effects preserved for Opt pattern (scry then draw)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn add-library-cards
  "Add cards to a player's library with sequential positions.
   Takes a vector of card-ids (keywords) and adds them with positions 0, 1, 2...
   Position 0 = top of library.
   Returns updated db."
  [db player-id card-ids]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (doseq [idx (range (count card-ids))]
      ;; Use Dark Ritual card def for simplicity
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


;; === build-scry-selection tests ===

(deftest test-build-scry-selection-scry-1
  ;; Bug caught: Selection state missing or wrong type
  (testing "Scry 1 creates selection state with 1 card"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b :c]))
          effect {:effect/type :scry :effect/amount 1}
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])]
      (is (= :scry (:selection/type result))
          "Selection type must be :scry")
      (is (= 1 (count (:selection/cards result)))
          "Scry 1 should have 1 card")
      (is (= :player-1 (:selection/player-id result))
          "Player ID must be set"))))


(deftest test-build-scry-selection-scry-2-correct-order
  ;; Bug caught: Wrong cards selected (not top N by position)
  (testing "Scry 2 gets correct 2 cards from top of library"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:top :second :third]))
          effect {:effect/type :scry :effect/amount 2}
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])
          card-ids (:selection/cards result)
          ;; Get the actual top 2 cards from library for comparison
          expected-ids (q/get-top-n-library db :player-1 2)]
      (is (= 2 (count card-ids))
          "Scry 2 should have 2 cards")
      (is (= expected-ids card-ids)
          "Cards should match top 2 from library in correct order"))))


(deftest test-build-scry-selection-library-smaller-than-amount
  ;; Bug caught: Crash or incorrect behavior when library smaller than scry amount
  (testing "Library < N returns available cards only"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:only-card]))
          effect {:effect/type :scry :effect/amount 3}
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])]
      (is (= 1 (count (:selection/cards result)))
          "Scry 3 with 1 card in library should scry 1"))))


(deftest test-build-scry-selection-empty-library
  ;; Bug caught: Crash on empty library or creating invalid selection
  (testing "Empty library returns nil (no selection needed)"
    (let [db (init-game-state)  ; no library cards
          effect {:effect/type :scry :effect/amount 2}
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])]
      (is (nil? result)
          "Empty library should return nil (no selection needed)"))))


(deftest test-build-scry-selection-preserves-remaining-effects
  ;; Bug caught: Draw effect lost after scry selection
  (testing "Remaining effects preserved for Opt pattern (scry then draw)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          scry-effect {:effect/type :scry :effect/amount 1}
          draw-effect {:effect/type :draw :effect/amount 1}
          result (game/build-scry-selection db :player-1 (random-uuid) scry-effect [draw-effect])]
      (is (= [draw-effect] (:selection/remaining-effects result))
          "Remaining effects must be preserved for execution after scry"))))


(deftest test-build-scry-selection-piles-initialized
  ;; Bug caught: UI crashes if piles not initialized
  (testing "Selection state has empty top and bottom piles initialized"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :scry :effect/amount 1}
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])]
      (is (= [] (:selection/top-pile result))
          "Top pile should be initialized to empty vector")
      (is (= [] (:selection/bottom-pile result))
          "Bottom pile should be initialized to empty vector"))))


(deftest test-build-scry-selection-spell-id-set
  ;; Bug caught: Missing spell-id prevents proper cleanup after selection
  (testing "Selection state includes spell-id for cleanup"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :scry :effect/amount 1}
          spell-id (random-uuid)
          result (game/build-scry-selection db :player-1 spell-id effect [])]
      (is (= spell-id (:selection/spell-id result))
          "Spell ID must be preserved for cleanup"))))


(deftest test-build-scry-selection-amount-zero-returns-nil
  ;; Bug caught: Scry 0 creating invalid selection state
  (testing "Scry 0 returns nil (no selection needed)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :scry :effect/amount 0}
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])]
      (is (nil? result)
          "Scry 0 should return nil (no selection needed)"))))


(deftest test-build-scry-selection-missing-amount-returns-nil
  ;; Bug caught: NPE on nil amount
  (testing "Missing amount returns nil (no selection needed)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :scry}  ; no :effect/amount
          result (game/build-scry-selection db :player-1 (random-uuid) effect [])]
      (is (nil? result)
          "Missing amount should return nil (no selection needed)"))))
