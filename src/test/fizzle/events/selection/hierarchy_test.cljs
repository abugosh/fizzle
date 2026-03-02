(ns fizzle.events.selection.hierarchy-test
  "Tests for selection hierarchy and generic builders/executors.
   Covers:
   - Hierarchy routing: derive declarations route effect types to patterns
   - Generic zone-pick builder: output shape for :discard and :graveyard-return
   - Generic zone-pick executor: moves cards to target zone
   - Hierarchy precedence: custom executors take precedence over generic
   - Zone-pick config: per-type zone/validation overrides
   - Cleanup discard regression: cleanup path still works after migration
   - Accumulator hierarchy: :storm-split, :x-mana-cost, :mana-allocation
   - Integration tests: Careful Study, Ill-Gotten Gains"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.events.selection.storm :as storm]
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


;; =====================================================
;; Zone-Pick Builder: :graveyard-return
;; =====================================================

(deftest test-zone-pick-builder-graveyard-return-shape
  (testing "Generic zone-pick builder produces correct selection for :graveyard-return"
    (let [db (th/create-test-db)
          [db gy1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db gy2] (th/add-card-to-zone db :cabal-ritual :graveyard :player-1)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [:remaining])]
      (is (= :graveyard-return (:selection/type sel))
          "selection/type should be :graveyard-return (mapped from :return-from-graveyard effect)")
      (is (= :zone-pick (:selection/pattern sel))
          "selection/pattern should be :zone-pick")
      (is (= :graveyard (:selection/zone sel))
          "zone should be :graveyard (not default :hand)")
      (is (= :hand (:selection/target-zone sel))
          "target-zone should be :hand (graveyard -> hand)")
      (is (= :at-most (:selection/validation sel))
          "validation should be :at-most (can pick 0 to N)")
      (is (= 0 (:selection/min-count sel))
          "min-count should be 0")
      (is (= 2 (:selection/select-count sel))
          "select-count should match :effect/count")
      (is (= #{gy1 gy2} (:selection/candidate-ids sel))
          "candidate-ids should contain graveyard card object IDs")
      (is (= :player-1 (:selection/player-id sel))
          "player-id should be the target player")
      (is (= :spell-1 (:selection/spell-id sel))
          "spell-id should be passed through")
      (is (= [:remaining] (:selection/remaining-effects sel))
          "remaining-effects should be passed through")
      (is (= #{} (:selection/selected sel))
          "selected should start empty")
      (is (false? (:selection/auto-confirm? sel))
          "auto-confirm should be false"))))


(deftest test-zone-pick-builder-graveyard-return-empty-graveyard
  (testing "Generic zone-pick builder with empty graveyard returns empty candidate-ids"
    (let [db (th/create-test-db)
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= #{} (:selection/candidate-ids sel))
          "candidate-ids should be empty set, not nil")
      (is (= :graveyard-return (:selection/type sel))
          "selection/type should still be :graveyard-return"))))


(deftest test-zone-pick-builder-graveyard-return-opponent-target
  (testing "Generic zone-pick builder resolves :opponent target for :graveyard-return"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db opp-gy1] (th/add-card-to-zone db :dark-ritual :graveyard :player-2)
          [db opp-gy2] (th/add-card-to-zone db :cabal-ritual :graveyard :player-2)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/selection :player
                  :effect/target :opponent}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= :player-2 (:selection/player-id sel))
          "player-id should be resolved to opponent :player-2")
      (is (= #{opp-gy1 opp-gy2} (:selection/candidate-ids sel))
          "candidate-ids should be from opponent's graveyard"))))


(deftest test-zone-pick-builder-graveyard-return-self-target
  (testing "Generic zone-pick builder resolves :self target for :graveyard-return"
    (let [db (th/create-test-db)
          [db gy1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          effect {:effect/type :return-from-graveyard
                  :effect/count 1
                  :effect/selection :player
                  :effect/target :self}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= :player-1 (:selection/player-id sel))
          "player-id should be resolved to caster :player-1")
      (is (= #{gy1} (:selection/candidate-ids sel))
          "candidate-ids should be from caster's graveyard"))))


(deftest test-zone-pick-builder-graveyard-return-default-target
  (testing "Generic zone-pick builder defaults target to caster when not specified"
    (let [db (th/create-test-db)
          [db gy1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          effect {:effect/type :return-from-graveyard
                  :effect/count 1
                  :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= :player-1 (:selection/player-id sel))
          "player-id should default to caster")
      (is (= #{gy1} (:selection/candidate-ids sel))
          "candidate-ids should be from caster's graveyard"))))


;; =====================================================
;; Zone-Pick Executor: :graveyard-return via generic
;; =====================================================

(deftest test-zone-pick-executor-graveyard-to-hand
  (testing "Generic zone-pick executor moves selected cards from graveyard to hand"
    (let [db (th/create-test-db)
          [db id1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db id2] (th/add-card-to-zone db :cabal-ritual :graveyard :player-1)
          ;; :graveyard-return should dispatch to generic :zone-pick executor
          ;; since we removed the custom executor
          selection {:selection/type :graveyard-return
                     :selection/selected #{id1 id2}
                     :selection/target-zone :hand}
          result (core/execute-confirmed-selection db selection)]
      (is (= :hand (th/get-object-zone (:db result) id1))
          "First card should be moved to hand")
      (is (= :hand (th/get-object-zone (:db result) id2))
          "Second card should be moved to hand"))))


;; =====================================================
;; Hierarchy Routing: :graveyard-return
;; =====================================================

(deftest test-graveyard-return-routes-to-zone-pick-builder
  (testing ":graveyard-return routes to zone-pick builder via hierarchy"
    (let [db (th/create-test-db)
          effect {:effect/type :return-from-graveyard
                  :effect/count 1
                  :effect/selection :player}
          sel (core/build-selection-for-effect db :player-1 :spell-1 effect [])]
      (is (= :zone-pick (:selection/pattern sel))
          ":graveyard-return should route to zone-pick builder"))))


;; =====================================================
;; Integration: Ill-Gotten Gains graveyard-return
;; =====================================================

(deftest test-ill-gotten-gains-graveyard-return-via-hierarchy
  (testing "Ill-Gotten Gains: full production path with graveyard-return through hierarchy"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db igg-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; Add 2 cards to caster's graveyard (pre-existing)
          [db gy1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db gy2] (th/add-card-to-zone db :cabal-ritual :graveyard :player-1)
          db (mana/add-mana db :player-1 {:black 4})
          db-cast (rules/cast-spell db :player-1 igg-id)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; Should have graveyard-return selection from generic builder
      (is (= :graveyard-return (:selection/type sel))
          "Selection type should be :graveyard-return")
      (is (= :zone-pick (:selection/pattern sel))
          "Selection pattern should be :zone-pick from generic builder")
      (is (= 3 (:selection/select-count sel))
          "Max selection count should be 3")
      (is (= 0 (:selection/min-count sel))
          "Min selection count should be 0")
      ;; Pre-existing GY cards should be candidates
      (is (contains? (:selection/candidate-ids sel) gy1)
          "Pre-existing GY card 1 should be candidate")
      (is (contains? (:selection/candidate-ids sel) gy2)
          "Pre-existing GY card 2 should be candidate")
      ;; Confirm selection: return 2 cards from graveyard to hand
      (let [{:keys [db]} (th/confirm-selection (:db result) sel #{gy1 gy2})]
        (is (= :hand (th/get-object-zone db gy1))
            "Card 1 should be returned to hand")
        (is (= :hand (th/get-object-zone db gy2))
            "Card 2 should be returned to hand")))))


;; =====================================================
;; Accumulator Hierarchy Tests
;; =====================================================

(deftest test-storm-split-isa-accumulator
  (testing ":storm-split derives from :accumulator in selection hierarchy"
    (is (isa? core/selection-hierarchy :storm-split :accumulator)
        ":storm-split should be a child of :accumulator")))


(deftest test-x-mana-cost-isa-accumulator
  (testing ":x-mana-cost derives from :accumulator in selection hierarchy"
    (is (isa? core/selection-hierarchy :x-mana-cost :accumulator)
        ":x-mana-cost should be a child of :accumulator")))


(deftest test-mana-allocation-isa-accumulator
  (testing ":mana-allocation derives from :accumulator in selection hierarchy"
    (is (isa? core/selection-hierarchy :mana-allocation :accumulator)
        ":mana-allocation should be a child of :accumulator")))


(deftest test-accumulator-types-not-zone-pick
  (testing "Accumulator types are NOT children of :zone-pick"
    (is (not (isa? core/selection-hierarchy :storm-split :zone-pick))
        ":storm-split should not derive from :zone-pick")
    (is (not (isa? core/selection-hierarchy :x-mana-cost :zone-pick))
        ":x-mana-cost should not derive from :zone-pick")
    (is (not (isa? core/selection-hierarchy :mana-allocation :zone-pick))
        ":mana-allocation should not derive from :zone-pick")))


(deftest test-storm-split-builder-sets-accumulator-pattern
  (testing "build-storm-split-selection sets :selection/pattern :accumulator"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (storm/build-storm-split-selection db-with-storm :player-1 storm-si)]
      (is (= :accumulator (:selection/pattern selection))
          "Storm split builder should set pattern to :accumulator"))))


(deftest test-x-mana-builder-sets-accumulator-pattern
  (testing "build-x-mana-selection sets :selection/pattern :accumulator"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:colorless 5}))
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 1 :x true}}
          selection (sel-costs/build-x-mana-selection db :player-1 spell-id mode)]
      (is (= :accumulator (:selection/pattern selection))
          "X mana builder should set pattern to :accumulator"))))


(deftest test-mana-allocation-builder-sets-accumulator-pattern
  (testing "build-mana-allocation-selection sets :selection/pattern :accumulator"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3 :blue 2}))
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 2}}
          resolved-cost {:colorless 2}
          selection (sel-costs/build-mana-allocation-selection db :player-1 spell-id mode resolved-cost)]
      (is (= :accumulator (:selection/pattern selection))
          "Mana allocation builder should set pattern to :accumulator"))))


(deftest test-accumulator-custom-executors-take-precedence
  (testing "Custom accumulator executors dispatch correctly (not overridden by generic)"
    ;; storm-split has custom executor that creates copies — verify it still works
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 2}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection {:selection/type :storm-split
                     :selection/copy-count 2
                     :selection/valid-targets [:opponent :player-1]
                     :selection/allocation {:opponent 2 :player-1 0}
                     :selection/source-object-id source-id
                     :selection/controller-id :player-1
                     :selection/stack-item-eid (:db/id storm-si)
                     :selection/selected #{}
                     :selection/validation :always}
          result (core/execute-confirmed-selection db-with-storm selection)
          copies (filter :object/is-copy
                         (q/get-objects-in-zone (:db result) :player-1 :stack))]
      (is (= 2 (count copies))
          "Storm-split custom executor should create 2 copies (not generic no-op)"))))
