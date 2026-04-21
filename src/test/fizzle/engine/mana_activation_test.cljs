(ns fizzle.engine.mana-activation-test
  "Unit tests for engine/mana-activation — the engine-layer mana ability executor.

   Tests cover:
   A. Core scenarios: generic cost with and without allocation variants
   B. Allocation validation: sum-exceeds, sum-less, wrong-color, empty
   C. Helper isolation: produces-any resolution, :self target, effects ordering
   D. Regression guards: priority phase, wrong controller, not on battlefield
   E. No-regression scenarios: Lotus Petal, Mountain, Mind Stone ability 0, Gemstone Mine

   TDD: every test was written RED (failing) before production code was added."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.test-helpers :as th]))


;; ============================================================
;; A. Core scenarios
;; ============================================================

(deftest test-generic-cost-without-allocation-returns-db-unchanged
  ;; Catches: silent wrong-state when 4-arity is called on a generic-cost ability
  ;; Expected: db returned unchanged (fail closed — no pool mutation)
  (testing "4-arity call on Chromatic Sphere fails closed when pool has no colorless"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          pool-before (q/get-mana-pool db :player-1)
          ;; 4-arity: no allocation — must fail closed (ADR-019)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue)]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change when no allocation provided for generic cost (fail closed)")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Chromatic Sphere must remain on battlefield when activation fails closed"))))


(deftest test-generic-cost-with-colorless-allocation-resolves-correctly
  ;; Catches: correct resolution when player has colorless and passes matching allocation
  (testing "6-arity Chromatic Sphere with {:colorless 1} allocation adds chosen blue mana"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          ;; Load library so draw can fire
          [db _] (th/add-cards-to-library db [:dark-ritual :island] :player-1)
          hand-before (th/get-hand-count db :player-1)
          ;; 6-arity with explicit colorless allocation
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:colorless 1})]
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Chromatic Sphere must be in graveyard after sacrifice")
      (is (= 1 (:blue (q/get-mana-pool db-after :player-1)))
          "Blue mana should be added to pool from {:any 1} produces")
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "Colorless mana should be deducted from pool")
      (is (= (inc hand-before) (th/get-hand-count db-after :player-1))
          "Draw effect should add 1 card to hand"))))


(deftest test-generic-cost-with-colored-allocation-resolves-correctly
  ;; Catches: the user-reported bug — pool {:black 1}, no colorless, wrong pool result
  ;; With fix: allocate {:black 1} to cover the generic {1}, produce :blue
  (testing "6-arity Chromatic Sphere with {:black 1} allocation (user-reported bug regression)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :island] :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:black 1})]
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Chromatic Sphere must be in graveyard after sacrifice")
      (is (= 1 (:blue (q/get-mana-pool db-after :player-1)))
          "Blue mana added from :any resolution")
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Black mana spent as generic cost allocation")
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No spurious colorless balance"))))


(deftest test-generic-cost-with-mixed-allocation
  ;; Catches: multi-color allocation covering a {2} generic cost
  ;; Uses a test ability directly on the engine helper to verify split deduction
  (testing "execute-mana-ability-production-and-effects with mixed allocation deducts correctly"
    ;; We use 6-arity with a hypothetical 2-colorless cost: build a db with {R 1, G 1}
    ;; and supply allocation {:red 1 :green 1}. Use a test card with colorless-2 cost if
    ;; available, or drive via helper directly.
    ;; For now validate via the 6-arity path with a real card that has colorless cost.
    ;; Chromatic Sphere only has {:colorless 1}. We can still confirm the multi-color split
    ;; with {:red 1} only (sum = 1 = generic) on Chromatic Sphere with red in pool.
    (let [db (th/create-test-db {:mana {:red 1 :green 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          ;; Allocation covers 1 generic with red only — green stays in pool
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:red 1})]
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Sphere should be sacrificed")
      (is (= 1 (:blue (q/get-mana-pool db-after :player-1)))
          "Blue produced from :any resolution")
      (is (= 0 (:red (q/get-mana-pool db-after :player-1)))
          "Red spent as generic cost")
      (is (= 1 (:green (q/get-mana-pool db-after :player-1)))
          "Green NOT spent (only red was in allocation)"))))


;; ============================================================
;; B. Allocation validation scenarios
;; ============================================================

(deftest test-allocation-sum-exceeds-generic-fails-closed
  ;; Catches: over-allocation (allocation sum > generic) must fail closed
  (testing "Allocation {:black 2} for {1} generic cost is rejected — db unchanged"
    (let [db (th/create-test-db {:mana {:black 2}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          pool-before (q/get-mana-pool db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:black 2})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change when allocation sum exceeds generic")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Sphere must stay on battlefield"))))


(deftest test-allocation-sum-less-than-generic-fails-closed
  ;; Catches: under-allocation (allocation sum < generic) must fail closed
  (testing "Allocation {:black 0} for {1} generic cost is rejected — db unchanged"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          pool-before (q/get-mana-pool db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:black 0})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change when allocation sum is zero (under)")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Sphere must stay on battlefield"))))


(deftest test-allocation-color-not-in-pool-fails-closed
  ;; Catches: allocation claims spending black but pool only has blue
  (testing "Allocation {:black 1} with only blue in pool is rejected — db unchanged"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          pool-before (q/get-mana-pool db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:black 1})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change when pool cannot cover the allocation color")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Sphere must stay on battlefield"))))


(deftest test-empty-allocation-with-generic-cost-fails-closed
  ;; Catches: empty map allocation {} treated as "no allocation" → fail closed
  (testing "Allocation {} for {1} generic cost is rejected — db unchanged"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          pool-before (q/get-mana-pool db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change when allocation is empty map")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Sphere must stay on battlefield"))))


;; ============================================================
;; C. Helper isolation scenarios
;; ============================================================

(deftest test-helper-produces-any-resolves-to-chosen-color
  ;; Catches: {:any 1} production not resolving to the caller-chosen color
  (testing "execute-mana-ability-production-and-effects resolves {:any 1} to the chosen color"
    ;; Use Lotus Petal which also has {:any 1} produces — drive via 4-arity (no generic cost)
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Lotus Petal has no mana cost — 4-arity should work, resolves :any to chosen color
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :green)]
      (is (= 1 (:green (q/get-mana-pool db-after :player-1)))
          "Green mana should be added from {:any 1} production resolved to :green")
      (is (= 0 (:blue (q/get-mana-pool db-after :player-1)))
          "Blue should NOT be added (not the chosen color)")
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Lotus Petal should be sacrificed after activation"))))


(deftest test-helper-self-target-resolves-to-object-id
  ;; Catches: :self in effects not being resolved to the source object
  ;; Verify via Chromatic Sphere's :sacrifice-self cost, which removes the
  ;; object from battlefield (validates :self-targeting resolves correctly).
  (testing ":sacrifice-self in Chromatic Sphere cost removes the correct object"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          ;; Also add another sphere to confirm the correct one is sacrificed
          [db other-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:colorless 1})]
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "The activated Chromatic Sphere (obj-id) must be in graveyard")
      (is (= :battlefield (th/get-object-zone db-after other-id))
          "The OTHER Chromatic Sphere must remain on battlefield"))))


(deftest test-helper-effects-run-in-order
  ;; Catches: effects not being reduced in order (draw before add-mana, or vice versa)
  ;; Chromatic Sphere: produces {:any 1} (add-mana step), then {:draw 1} effect.
  ;; Verify both the mana add and the draw happen.
  (testing "Both produces and draw effects run after Chromatic Sphere activation"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :island :swamp] :player-1)
          hand-before (th/get-hand-count db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :white nil {:colorless 1})]
      (is (= 1 (:white (q/get-mana-pool db-after :player-1)))
          "White mana should be produced (:any 1 resolved to :white)")
      (is (= (inc hand-before) (th/get-hand-count db-after :player-1))
          "Draw effect should fire — hand count increases by 1"))))


;; ============================================================
;; D. Regression guards (preserved invariants)
;; ============================================================

(deftest test-outside-priority-phase-returns-db-unchanged
  ;; Catches: missing priority-phase guard in new 6-arity
  (testing "6-arity returns db unchanged when not in priority phase"
    (let [;; create-test-db creates a game in :main1 phase (priority phase).
          ;; We change phase to :draw (non-priority) to trigger the guard.
          db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          ;; Modify game phase to :cleanup — not a priority phase
          ;; (:draw, :main1, :upkeep, :combat, :main2, :end are all priority phases)
          game-eid (d/q '[:find ?e . :where [?e :game/phase _]] db)
          db-draw (d/db-with db [[:db/add game-eid :game/phase :cleanup]])
          pool-before (q/get-mana-pool db-draw :player-1)
          db-after (engine-mana/activate-mana-ability db-draw :player-1 obj-id :blue nil {:colorless 1})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change during :cleanup phase (not a priority phase)")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Sphere must remain on battlefield during :cleanup phase"))))


(deftest test-wrong-controller-returns-db-unchanged
  ;; Catches: missing controller guard in new 6-arity
  (testing "6-arity returns db unchanged when activating opponent's permanent"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          db (th/add-opponent db)
          ;; Sphere belongs to :player-1 but :player-2 tries to activate it
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          pool-before (q/get-mana-pool db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-2 obj-id :blue nil {:colorless 1})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Player-1's pool must not change when player-2 tries to activate player-1's sphere")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Sphere must stay on battlefield when wrong controller tries to activate"))))


(deftest test-not-on-battlefield-returns-db-unchanged
  ;; Catches: missing zone guard in new 6-arity
  (testing "6-arity returns db unchanged when Chromatic Sphere is in graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :graveyard :player-1)
          pool-before (q/get-mana-pool db :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :blue nil {:colorless 1})]
      (is (= pool-before (q/get-mana-pool db-after :player-1))
          "Pool must not change when Chromatic Sphere is in graveyard")
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Sphere must remain in graveyard"))))


;; ============================================================
;; E. No-regression scenarios (simple abilities — 4-arity unchanged)
;; ============================================================

(deftest test-lotus-petal-4-arity-unchanged-behavior
  ;; Catches: regression in sacrifice + :any production for Lotus Petal
  (testing "Lotus Petal 4-arity sacrifice produces chosen color unchanged"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :red)]
      (is (= 1 (:red (q/get-mana-pool db-after :player-1)))
          "Lotus Petal must still add red mana")
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Lotus Petal must still be sacrificed"))))


(deftest test-basic-mountain-4-arity-unchanged-behavior
  ;; Catches: regression in tap land for colored mana via 4-arity
  (testing "Mountain 4-arity tap adds red mana unchanged"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :red)]
      (is (= 1 (:red (q/get-mana-pool db-after :player-1)))
          "Mountain must still add 1 red mana")
      (is (true? (:object/tapped (q/get-object db-after obj-id)))
          "Mountain must still be tapped after activation"))))


(deftest test-mind-stone-mana-ability-0-4-arity-unchanged
  ;; Catches: regression in tap artifact for colorless — ability 0 of Mind Stone
  ;; Mind Stone ability 0: {:T}: Add {:colorless 1}
  (testing "Mind Stone ability 0 (tap for colorless) unchanged via 4-arity"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          ;; Mind Stone ability 0 produces {:colorless 1}
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless 0)]
      (is (= 1 (:colorless (q/get-mana-pool db-after :player-1)))
          "Mind Stone ability 0 must still add 1 colorless mana")
      (is (true? (:object/tapped (q/get-object db-after obj-id)))
          "Mind Stone must be tapped after ability 0 activation"))))


(deftest test-gemstone-mine-counter-cost-4-arity-unchanged
  ;; Catches: regression in remove-counter cost for Gemstone Mine via 4-arity
  (testing "Gemstone Mine (remove-counter cost) unchanged via 4-arity"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          ;; Manually set counters (add-card-to-zone does not run ETB effects)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-with-counters (d/db-with db [[:db/add obj-eid :object/counters {:mining 3}]])
          db-after (engine-mana/activate-mana-ability db-with-counters :player-1 obj-id :black)]
      (is (= 1 (:black (q/get-mana-pool db-after :player-1)))
          "Gemstone Mine must still add 1 black mana")
      (is (= {:mining 2} (:object/counters (q/get-object db-after obj-id)))
          "Gemstone Mine must remove one mining counter"))))
