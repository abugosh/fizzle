(ns fizzle.events.selection.mana-ability-test
  "Unit tests for fizzle.events.selection.mana-ability module.

   Tests cover the three public functions:
   - open-mana-allocation-for-mana-ability
   - confirm-mana-ability-mana-allocation
   - activate-mana-ability-with-generic-mana"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.events.selection.mana-ability :as mana-ability]
    [fizzle.test-helpers :as th]))


;; === A. open-mana-allocation-for-mana-ability ===

(deftest test-open-stores-chosen-color-in-context
  ;; Catches: context-key mismatch between open and confirm.
  (testing "open stores the mana-color the player chose in :selection/context"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          result (mana-ability/open-mana-allocation-for-mana-ability
                   db :player-1 obj-id :blue 0)
          sel (:pending-selection result)]
      (is (some? sel)
          "Pending selection should be non-nil for a generic-cost ability")
      (is (= :blue (get-in sel [:selection/context :mana-ability/chosen-color]))
          "Context must store the player's chosen output color (:blue)"))))


(deftest test-open-stores-all-four-context-keys
  ;; Catches: future refactor dropping a required context key.
  (testing "open stores all four required namespaced context keys"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          result (mana-ability/open-mana-allocation-for-mana-ability
                   db :player-1 obj-id :green 0)
          ctx (get-in result [:pending-selection :selection/context])]
      (is (= obj-id (:mana-ability/object-id ctx))
          "Context must contain :mana-ability/object-id")
      (is (= 0 (:mana-ability/ability-index ctx))
          "Context must contain :mana-ability/ability-index = 0")
      (is (= :green (:mana-ability/chosen-color ctx))
          "Context must contain :mana-ability/chosen-color = :green")
      (is (= 1 (:mana-ability/generic-count ctx))
          "Context must contain :mana-ability/generic-count = 1 (the {1} in Sphere's cost)"))))


(deftest test-open-pays-tap-and-sacrifice-before-selection
  ;; Catches: non-mana cost payment order bug (costs paid after selection would corrupt state).
  (testing "open pays tap + sacrifice before returning pending selection"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          _ (is (= :battlefield (th/get-object-zone db obj-id))
                "Precondition: Sphere is on battlefield")
          result (mana-ability/open-mana-allocation-for-mana-ability
                   db :player-1 obj-id :red 0)
          db-after (:db result)]
      (is (= :graveyard (th/get-object-zone db-after obj-id))
          "Sphere must be in graveyard after non-mana costs paid (tap + sacrifice-self)")
      (is (some? (:pending-selection result))
          "Pending selection must be non-nil after costs paid"))))


(deftest test-open-returns-nil-selection-when-no-generic-cost
  ;; Catches: incorrectly opening selection for simple (no-generic) mana abilities.
  (testing "open returns nil pending-selection for a mana ability without generic cost"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          result (mana-ability/open-mana-allocation-for-mana-ability
                   db :player-1 obj-id :blue 0)]
      (is (nil? (:pending-selection result))
          "No selection should open for a free (no generic cost) mana ability"))))


;; === B. confirm-mana-ability-mana-allocation ===

(deftest test-confirm-fails-closed-on-missing-context-keys
  ;; Catches: confirm blowing up or silently producing wrong state when context is incomplete.
  (testing "confirm returns db unchanged when required context keys are missing"
    (let [db (th/create-test-db {:mana {:black 1}})
          ;; Build a selection with only chosen-color — missing object-id, ability-index, generic-count
          incomplete-selection {:selection/player-id :player-1
                                :selection/allocation {:black 1}
                                :selection/original-cost {:colorless 1}
                                :selection/context {:mana-ability/chosen-color :blue}}
          result (mana-ability/confirm-mana-ability-mana-allocation db incomplete-selection)]
      (is (= db (:db result))
          "db must be returned unchanged when context keys are missing (fail closed)"))))


(deftest test-confirm-deducts-allocated-colors-and-adds-chosen
  ;; Catches: confirm not deducting the spent mana or not adding the produced mana.
  ;; This is the core correctness test for the confirm executor.
  (testing "confirm deducts allocated colors and adds produced color to pool"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:island :island :island] :player-1)
          ;; First call open to pay non-mana costs (tap + sac)
          open-result (mana-ability/open-mana-allocation-for-mana-ability
                        db :player-1 obj-id :blue 0)
          db-after-open (:db open-result)
          sel (:pending-selection open-result)
          ;; Simulate the player allocating :black toward the {1} generic cost
          sel-with-allocation (assoc sel :selection/allocation {:black 1})
          confirm-result (mana-ability/confirm-mana-ability-mana-allocation
                           db-after-open sel-with-allocation)
          db-final (:db confirm-result)
          pool (q/get-mana-pool db-final :player-1)]
      (is (= 0 (:black pool 0))
          "Black mana should be deducted (spent as {1} cost)")
      (is (= 1 (:blue pool 0))
          "Blue mana should be added (produced by Sphere choosing :blue)")
      (is (= 1 (th/get-hand-count db-final :player-1))
          "Draw effect should have fired: hand should have 1 card")
      (is (= :graveyard (th/get-object-zone db-final obj-id))
          "Sphere should remain in graveyard (sacrificed during open)"))))


;; === C. activate-mana-ability-with-generic-mana ===

(deftest test-activate-with-generic-mana-routes-chromatic-sphere-to-selection
  ;; Catches: routing that fails to detect generic cost and bypasses the selection flow.
  (testing "activate-mana-ability-with-generic-mana opens selection for Chromatic Sphere"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :battlefield :player-1)
          result (mana-ability/activate-mana-ability-with-generic-mana
                   db :player-1 obj-id :blue 0)]
      (is (some? (:pending-selection result))
          "Chromatic Sphere (generic cost) must route to selection, not direct engine")
      (is (= :mana-allocation (:selection/domain (:pending-selection result)))
          "Selection type must be :mana-allocation"))))


(deftest test-activate-with-generic-mana-delegates-simple-ability
  ;; Catches: regression where simple abilities (no generic cost) get selection
  ;; instead of direct engine delegation.
  (testing "activate-mana-ability-with-generic-mana delegates simple ability directly to engine"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          _ (is (= 0 (get (q/get-mana-pool db :player-1) :blue 0))
                "Precondition: no blue mana in pool")
          result (mana-ability/activate-mana-ability-with-generic-mana
                   db :player-1 obj-id :blue 0)
          pool (q/get-mana-pool (:db result) :player-1)]
      (is (nil? (:pending-selection result))
          "Simple mana ability (no generic cost) must not open selection")
      (is (= 1 (:blue pool 0))
          "Lotus Petal mana must be added directly via engine path"))))
