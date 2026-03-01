(ns fizzle.events.selection.hierarchy-test
  "Tests for selection hierarchy and generic zone-pick builder/executor.
   Covers:
   - Hierarchy routing: derive declarations route effect types to patterns
   - Generic zone-pick builder: output shape for :discard effect
   - Generic zone-pick executor: moves cards to target zone
   - Hierarchy precedence: custom executors take precedence over generic
   - Cleanup discard regression: cleanup path still works after migration"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Generic Zone-Pick Builder Tests
;; =====================================================

(deftest test-zone-pick-builder-output-shape-for-discard
  (testing "Generic zone-pick builder produces correct selection for :discard effect"
    (let [db (th/create-test-db)
          effect {:effect/type :discard
                  :effect/count 2
                  :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-123 effect [])]
      (is (= :discard (:selection/type sel))
          "selection/type should be the original effect type, NOT :zone-pick")
      (is (= :zone-pick (:selection/pattern sel))
          "selection/pattern should be :zone-pick for future view dispatch")
      (is (= 2 (:selection/select-count sel))
          "select-count should match :effect/count")
      (is (= :hand (:selection/zone sel))
          "zone should default to :hand for discard")
      (is (= :graveyard (:selection/target-zone sel))
          "target-zone should default to :graveyard for discard")
      (is (= :exact (:selection/validation sel))
          "validation should be :exact")
      (is (= #{} (:selection/selected sel))
          "selected should start empty")
      (is (= :player-1 (:selection/player-id sel))
          "player-id should be passed through")
      (is (= :spell-123 (:selection/spell-id sel))
          "spell-id should be passed through")
      (is (= [] (:selection/remaining-effects sel))
          "remaining-effects should be passed through")
      (is (false? (:selection/auto-confirm? sel))
          "auto-confirm should be false"))))


(deftest test-zone-pick-builder-reads-effect-count
  (testing "Generic zone-pick builder reads :effect/count for select-count"
    (let [db (th/create-test-db)
          effect {:effect/type :discard :effect/count 3 :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [:remaining])]
      (is (= 3 (:selection/select-count sel)))
      (is (= [:remaining] (:selection/remaining-effects sel))))))


;; =====================================================
;; Generic Zone-Pick Executor Tests
;; =====================================================

(deftest test-zone-pick-executor-moves-cards-to-target-zone
  (testing "Generic zone-pick executor moves selected cards to :selection/target-zone"
    (let [db (th/create-test-db)
          [db id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db id2] (th/add-card-to-zone db :cabal-ritual :hand :player-1)
          selection {:selection/type :zone-pick
                     :selection/selected #{id1 id2}
                     :selection/target-zone :graveyard}
          result (core/execute-confirmed-selection db selection)]
      (is (= :graveyard (th/get-object-zone (:db result) id1))
          "First card should be moved to graveyard")
      (is (= :graveyard (th/get-object-zone (:db result) id2))
          "Second card should be moved to graveyard"))))


(deftest test-zone-pick-executor-respects-target-zone
  (testing "Generic zone-pick executor uses :selection/target-zone (not hardcoded graveyard)"
    (let [db (th/create-test-db)
          [db id1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          selection {:selection/type :zone-pick
                     :selection/selected #{id1}
                     :selection/target-zone :hand}
          result (core/execute-confirmed-selection db selection)]
      (is (= :hand (th/get-object-zone (:db result) id1))
          "Card should be moved to :hand per target-zone"))))


;; =====================================================
;; Hierarchy Routing Tests
;; =====================================================

(deftest test-discard-routes-to-zone-pick-builder-via-hierarchy
  (testing ":discard effect routes to :zone-pick builder via derive hierarchy"
    (let [db (th/create-test-db)
          effect {:effect/type :discard :effect/count 1 :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= :zone-pick (:selection/pattern sel))
          ":discard should route to zone-pick builder and get :zone-pick pattern"))))


(deftest test-custom-builders-unaffected-by-hierarchy
  (testing "Custom builders (chain-bounce) still handle their own effects"
    (let [db (th/create-test-db)
          effect {:effect/type :chain-bounce
                  :chain/controller :player-1
                  :chain/target-id :some-target}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= :chain-bounce (:selection/type sel))
          ":chain-bounce should still use its custom builder")
      (is (nil? (:selection/pattern sel))
          "Custom builder should not set :selection/pattern"))))


(deftest test-discard-executor-takes-precedence-over-zone-pick
  (testing ":discard executor (custom) takes precedence over :zone-pick executor in hierarchy"
    (let [db (th/create-test-db)
          [db id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Execute with :discard type — should use custom :discard executor
          ;; (which does NOT check :selection/target-zone)
          selection {:selection/type :discard
                     :selection/selected #{id1}}
          result (core/execute-confirmed-selection db selection)]
      ;; The :discard executor hardcodes graveyard, proving it took precedence
      (is (= :graveyard (th/get-object-zone (:db result) id1))
          ":discard executor should be used (moves to graveyard)"))))


;; =====================================================
;; Cleanup Discard Regression Tests
;; =====================================================

(deftest test-cleanup-discard-still-works-after-migration
  (testing "Cleanup discard selection built directly in game.cljs still works"
    (let [db (th/create-test-db)
          ;; Add 9 cards to hand to exceed max hand size of 7
          [db _id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id2] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id3] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id4] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id5] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id6] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id7] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id8] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _id9] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result (game/begin-cleanup db :player-1)
          selection (:pending-selection result)]
      (is (some? selection)
          "Cleanup should create pending selection")
      (is (= :discard (:selection/type selection))
          "Selection type should be :discard")
      (is (true? (:selection/cleanup? selection))
          "Should have cleanup flag")
      (is (= :finalized (:selection/lifecycle selection))
          "Cleanup discard should have finalized lifecycle"))))


;; =====================================================
;; Full Integration: Careful Study discard via hierarchy
;; =====================================================

(deftest test-careful-study-discard-via-hierarchy
  (testing "Careful Study: draw -> discard selection through hierarchy -> confirm"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                 :player-1)
          [db cs-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          db-cast (rules/cast-spell db :player-1 cs-id)
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Selection should have correct shape from generic builder
      (is (= :discard (:selection/type selection))
          "Selection type preserved as :discard")
      (is (= :zone-pick (:selection/pattern selection))
          "Selection pattern should be :zone-pick from generic builder")
      (is (= 2 (:selection/select-count selection))
          "Should require discarding 2 cards")
      ;; Select and confirm discard
      (let [hand-cards (q/get-hand db :player-1)
            card-ids (set (map :object/id hand-cards))
            {:keys [db]} (th/confirm-selection db selection card-ids)]
        (is (= 0 (th/get-hand-count db :player-1))
            "Hand should be empty after discarding 2 cards")
        (is (= 3 (th/get-zone-count db :graveyard :player-1))
            "Graveyard should have 2 discarded + Careful Study")))))
