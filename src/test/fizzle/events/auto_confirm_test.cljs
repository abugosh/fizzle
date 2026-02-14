(ns fizzle.events.auto-confirm-test
  "Tests for auto-confirm behavior in ::toggle-selection.

   When a single-select selection is completed (not deselected), the toggle
   handler should automatically confirm the selection without requiring a
   separate Confirm click.

   Auto-confirm applies to:
   - tutor (select-count=1)
   - cast-time-targeting (always select-count=1)
   - player-target (always select-count=1)
   - ability-targeting (always select-count=1)

   Auto-confirm does NOT apply to:
   - tutor (select-count > 1, e.g. Intuition)
   - discard (including cleanup), scry, pile-choice
   - graveyard-return, peek-and-select, exile-cards-cost, x-mana-cost
   - Deselecting (clicking already-selected item)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection :as selection]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; === Test helpers ===

(defn- create-game-db
  "Create a game state with mana and opponent for auto-confirm tests."
  []
  (-> (th/create-test-db {:mana {:blue 3 :black 3}})
      th/add-opponent))


(defn- dispatch-sync-on-db
  "Set rf-db, dispatch synchronously, return new rf-db."
  [db event]
  (reset! rf-db/app-db db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; === Single-select tutor auto-confirm ===

(deftest test-single-select-tutor-auto-confirms
  (testing "Single-select tutor auto-confirms on card selection"
    (let [game-db (create-game-db)
          [game-db' [bf-id _dr-id]] (th/add-cards-to-library game-db
                                                             [:brain-freeze :dark-ritual]
                                                             :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id _dr-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection bf-id])]
      ;; After auto-confirm, pending-selection should be cleared
      (is (nil? (:game/pending-selection result))
          "Auto-confirm should clear pending selection")
      ;; Card should be in hand
      (is (= :hand (th/get-object-zone (:game/db result) bf-id))
          "Selected card should be moved to hand"))))


(deftest test-multi-select-tutor-does-not-auto-confirm
  (testing "Multi-select tutor does NOT auto-confirm on first card"
    (let [game-db (create-game-db)
          [game-db' [obj1 obj2 obj3]] (th/add-cards-to-library game-db
                                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                                               :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 3
                   :selection/exact? true
                   :selection/candidates #{obj1 obj2 obj3}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection obj1])]
      ;; Pending selection should still be present
      (is (= #{obj1} (get-in result [:game/pending-selection :selection/selected]))
          "Card should be added to selection"))))


(deftest test-tutor-deselect-does-not-auto-confirm
  (testing "Deselecting a tutor card does NOT auto-confirm"
    (let [game-db (create-game-db)
          [game-db' [bf-id _dr-id]] (th/add-cards-to-library game-db
                                                             [:brain-freeze :dark-ritual]
                                                             :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{bf-id}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id _dr-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection bf-id])]
      ;; Deselecting should NOT auto-confirm
      (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
          "Card should be deselected"))))


;; === Targeting auto-confirm ===

(deftest test-player-target-auto-confirms
  (testing "Player target auto-confirms on player selection"
    (let [game-db (create-game-db)
          pending {:selection/type :player-target
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/valid-targets #{:player-1 :player-2}
                   :selection/spell-id (random-uuid)
                   :selection/target-effect {:effect/type :drain
                                             :effect/amount 2
                                             :effect/target :any-player}
                   :selection/remaining-effects []
                   :selection/validation :exact
                   :selection/auto-confirm? true}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection :player-2])]
      ;; After auto-confirm, pending-selection should be cleared
      (is (nil? (:game/pending-selection result))
          "Player target should auto-confirm"))))


(deftest test-player-target-deselect-does-not-auto-confirm
  (testing "Deselecting player target does NOT auto-confirm"
    (let [game-db (create-game-db)
          pending {:selection/type :player-target
                   :selection/player-id :player-1
                   :selection/selected #{:player-2}
                   :selection/select-count 1
                   :selection/valid-targets #{:player-1 :player-2}
                   :selection/spell-id (random-uuid)
                   :selection/target-effect {:effect/type :drain
                                             :effect/amount 2
                                             :effect/target :any-player}
                   :selection/remaining-effects []
                   :selection/validation :exact
                   :selection/auto-confirm? true}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection :player-2])]
      ;; Deselect should NOT auto-confirm
      (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
          "Player should be deselected"))))


(deftest test-invalid-target-rejected
  (testing "Toggling an invalid target is rejected (no selection change)"
    (let [game-db (create-game-db)
          pending {:selection/type :player-target
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/valid-targets #{:player-1 :player-2}
                   :selection/spell-id (random-uuid)
                   :selection/target-effect {:effect/type :drain
                                             :effect/amount 2
                                             :effect/target :any-player}
                   :selection/remaining-effects []
                   :selection/validation :exact
                   :selection/auto-confirm? true}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection :invalid-id])]
      ;; Selection should be unchanged
      (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
          "Selection should remain empty"))))


;; === Non-auto-confirm types (negative tests) ===

(deftest test-discard-does-not-auto-confirm
  (testing "Discard selection does NOT auto-confirm even with select-count=1"
    (let [game-db (create-game-db)
          [game-db' card-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          pending {:selection/type :discard
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/validation :exact
                   :selection/auto-confirm? false}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection card-id])]
      ;; Discard should NOT auto-confirm
      (is (= #{card-id} (get-in result [:game/pending-selection :selection/selected]))
          "Card should be selected but not confirmed"))))


(deftest test-pile-choice-does-not-auto-confirm
  (testing "Pile choice does NOT auto-confirm even with hand-count=1"
    (let [game-db (create-game-db)
          card-id (random-uuid)
          pending {:selection/type :pile-choice
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/hand-count 1
                   :selection/select-count 1
                   :selection/candidates #{card-id}
                   :selection/bottom-pile []
                   :selection/validation :exact
                   :selection/auto-confirm? false}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection card-id])]
      ;; Pile choice should NOT auto-confirm
      (is (some? (:game/pending-selection result))
          "Pile choice should NOT auto-confirm"))))


(deftest test-graveyard-return-does-not-auto-confirm
  (testing "Graveyard return does NOT auto-confirm even with select-count=1"
    (let [game-db (create-game-db)
          [game-db' card-id] (th/add-card-to-zone game-db :dark-ritual :graveyard :player-1)
          pending {:selection/type :graveyard-return
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/min-count 0
                   :selection/target-zone :hand
                   :selection/candidate-ids #{card-id}
                   :selection/validation :at-most
                   :selection/auto-confirm? false}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection card-id])]
      ;; Graveyard return should NOT auto-confirm (user may want 0)
      (is (some? (:game/pending-selection result))
          "Graveyard return should NOT auto-confirm"))))


;; === Find Nothing still works ===

(deftest test-find-nothing-via-confirm
  (testing "Find Nothing (empty selection confirm) still works for tutor"
    (let [game-db (create-game-db)
          [game-db' [bf-id]] (th/add-cards-to-library game-db [:brain-freeze] :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          ;; Dispatch confirm directly with empty selection (Find Nothing)
          result (dispatch-sync-on-db app-db [::selection/confirm-selection])]
      ;; Pending selection cleared
      (is (nil? (:game/pending-selection result))
          "Find Nothing should clear pending selection")
      ;; Card should NOT be in hand
      (is (= :library (th/get-object-zone (:game/db result) bf-id))
          "Card should remain in library on Find Nothing"))))


;; === Tutor single-select replaces (not accumulates) ===

(deftest test-single-select-tutor-replaces-selection
  (testing "Clicking a different card in single-select tutor replaces and auto-confirms"
    (let [game-db (create-game-db)
          [game-db' [bf-id dr-id]] (th/add-cards-to-library game-db
                                                            [:brain-freeze :dark-ritual]
                                                            :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{bf-id}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id dr-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          ;; Click a different card (not deselect — replace)
          result (dispatch-sync-on-db app-db [::selection/toggle-selection dr-id])]
      ;; Auto-confirm should fire since new card was selected (replace, not deselect)
      (is (nil? (:game/pending-selection result))
          "Replacing selection in single-select tutor should auto-confirm")
      ;; The new card (dark ritual) should be in hand
      (is (= :hand (th/get-object-zone (:game/db result) dr-id))
          "Newly selected card should be in hand"))))
