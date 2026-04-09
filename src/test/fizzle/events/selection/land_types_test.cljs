(ns fizzle.events.selection.land-types-test
  "Pattern B (multimethod production-path slice) tests for
   events/selection/land_types.cljs defmethods.

   Covers all 4 defmethod registrations:

   build-selection-for-effect (1):
     :change-land-types  - builder for 2-step land type change chain

   execute-confirmed-selection (2):
     :land-type-source   - no-op executor (stores nothing, lifecycle :chaining triggers chain)
     :land-type-target   - applies :land-type-override grants to matching lands

   build-chain-selection (1):
     :land-type-source   - produces :land-type-target selection, excludes chosen source type

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test.

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or via th/confirm-selection for :selected-field selections).

   Cards used:
     :vision-charm  - sole card with :change-land-types effect (Mode 2)"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    ;; Direct card requires — NO cards.registry / cards.all-cards lookups
    [fizzle.cards.blue.vision-charm :as vision-charm]
    [fizzle.cards.lands.basic-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.land-types :as land-types]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection.core :as sel-core]
    ;; Load land-types defmethods so they register on the multimethods (side-effect require)
    [fizzle.events.selection.land-types]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Setup
;; =====================================================

(defn- setup-db
  "Create a minimal game-db with player-1 and card registry loaded.
   Optional opts: {:mana {:blue 1} :add-opponent? true}"
  ([]
   (setup-db {}))
  ([opts]
   (let [db (th/create-test-db (select-keys opts [:mana]))]
     (if (:add-opponent? opts)
       (th/add-opponent db)
       db))))


;; =====================================================
;; build-selection-for-effect :change-land-types
;; Entry builder — produces :land-type-source selection.
;; Real card: Vision Charm Mode 2 ({U} — change land types until EOT)
;; =====================================================

(deftest change-land-types-builder-creates-correct-shape
  (testing ":change-land-types builder produces :land-type-source selection with correct shape"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          effect {:effect/type :change-land-types}
          remaining-effects []
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect remaining-effects)]
      (is (= :land-type-source (:selection/type sel))
          "Builder should produce :land-type-source selection type")
      (is (= :chaining (:selection/lifecycle sel))
          "Lifecycle should be :chaining (chains to :land-type-target after source picked)")
      (is (= :player-1 (:selection/player-id sel))
          "Selecting player should be :player-1 (caster)")
      (is (= obj-id (:selection/spell-id sel))
          ":selection/spell-id should reference the casting object")
      (is (= 1 (:selection/select-count sel))
          "Select count should be 1")
      (is (= :exact (:selection/validation sel))
          "Validation should be :exact")
      (is (= land-types/basic-land-type-keys (:selection/options sel))
          "Options should be all 5 basic land type keys"))))


(deftest change-land-types-builder-stores-remaining-effects
  (testing ":change-land-types builder preserves remaining-effects on the selection"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          effect {:effect/type :change-land-types}
          remaining [{:effect/type :draw :effect/amount 1}
                     {:effect/type :mill :effect/amount 2}]
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect remaining)]
      (is (= remaining (:selection/remaining-effects sel))
          "Builder should store remaining effects on :selection/remaining-effects"))))


;; =====================================================
;; execute-confirmed-selection :land-type-source
;; No-op executor: source type stored in selection map, not in db.
;; Chaining lifecycle causes build-chain-selection to run next.
;; Real card: Vision Charm Mode 2 — source step
;; =====================================================

(deftest land-type-source-executor-is-no-op
  (testing ":land-type-source executor returns {:db game-db} unchanged — chains to :land-type-target"
    (let [db (setup-db)
          [db1 island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db2 obj-id] (th/add-card-to-zone db1 :vision-charm :hand :player-1)
          db2 (mana/add-mana db2 :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          ;; Set chosen mode BEFORE cast-spell (as per vision_charm_test.cljs pattern)
          obj-eid (q/get-object-eid db2 obj-id)
          db3 (d/db-with db2 [[:db/add obj-eid :object/chosen-mode mode-2]])
          db-on-stack (rules/cast-spell db3 :player-1 obj-id)
          {:keys [db selection]} (th/resolve-top db-on-stack)]
      (is (= :land-type-source (:selection/type selection))
          "Precondition: resolve returns :land-type-source selection")
      ;; Confirm source type :island — executor should chain without writing grants
      (let [{:keys [db selection]} (th/confirm-selection db selection #{:island})]
        (is (= :land-type-target (:selection/type selection))
            "After source confirm, should chain to :land-type-target")
        ;; Source executor must not write grants — grants only come from :land-type-target executor
        (is (= 0 (count (grants/get-grants-by-type db island-id :land-type-override)))
            "No :land-type-override grants should exist after :land-type-source step (executor is no-op)")))))


;; =====================================================
;; build-chain-selection :land-type-source
;; Chain builder: produces :land-type-target with source-type excluded from options.
;; =====================================================

(deftest land-type-source-chain-produces-correct-land-type-target
  (testing ":land-type-source chain builder produces :land-type-target with correct shape"
    (let [db (setup-db)
          [db1 obj-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          effect {:effect/type :change-land-types}
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])
          {:keys [selection]} (th/confirm-selection db1 sel #{:island})]
      (is (= :land-type-target (:selection/type selection))
          "Chain builder should produce :land-type-target selection")
      (is (= :standard (:selection/lifecycle selection))
          "Target selection lifecycle should be :standard")
      (is (= :player-1 (:selection/player-id selection))
          ":selection/player-id should propagate from source selection")
      (is (= obj-id (:selection/spell-id selection))
          ":selection/spell-id should propagate from source selection")
      (is (= :island (:selection/source-type selection))
          ":selection/source-type should record the chosen source type")
      (is (= 1 (:selection/select-count selection))
          "Target selection should require selecting exactly 1 type")
      (is (= :exact (:selection/validation selection))
          "Target selection validation should be :exact"))))


(deftest land-type-source-chain-excludes-source-type-from-options
  (testing ":land-type-source chain builder excludes chosen source type from target options"
    (doseq [source-type land-types/basic-land-type-keys]
      (let [db (setup-db)
            [db1 obj-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
            effect {:effect/type :change-land-types}
            sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect [])
            {:keys [selection]} (th/confirm-selection db1 sel #{source-type})]
        (is (not (contains? (set (:selection/options selection)) source-type))
            (str "Source type " source-type " should be excluded from target options"))
        (is (= 4 (count (:selection/options selection)))
            (str "After choosing " source-type " as source, target options should have 4 remaining types"))))))


;; =====================================================
;; execute-confirmed-selection :land-type-target
;; Applies :land-type-override grants to all matching lands on battlefield.
;; =====================================================

(deftest land-type-target-executor-applies-grant-to-matching-land
  (testing ":land-type-target executor applies :land-type-override grant with correct data"
    (let [db (setup-db)
          ;; Put an island and a swamp on the battlefield
          [db1 island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db2 swamp-id] (th/add-card-to-zone db1 :swamp :battlefield :player-1)
          [db3 obj-id] (th/add-card-to-zone db2 :vision-charm :hand :player-1)
          db3 (mana/add-mana db3 :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          obj-eid (q/get-object-eid db3 obj-id)
          db4 (d/db-with db3 [[:db/add obj-eid :object/chosen-mode mode-2]])
          db-on-stack (rules/cast-spell db4 :player-1 obj-id)
          {:keys [db selection]} (th/resolve-top db-on-stack)
          _ (is (= :land-type-source (:selection/type selection))
                "Precondition: resolve returns :land-type-source selection")
          ;; Step 1: confirm source = :island
          {:keys [db selection]} (th/confirm-selection db selection #{:island})
          _ (is (= :land-type-target (:selection/type selection))
                "Precondition: chained to :land-type-target")
          ;; Step 2: confirm target = :mountain — exercises :land-type-target executor
          {:keys [db]} (th/confirm-selection db selection #{:mountain})
          island-grants (grants/get-grants-by-type db island-id :land-type-override)
          swamp-grants (grants/get-grants-by-type db swamp-id :land-type-override)]
      (is (= 1 (count island-grants))
          "Island should have exactly 1 :land-type-override grant")
      (is (= 0 (count swamp-grants))
          "Swamp should have no :land-type-override grant — :swamp does not match source :island")
      (let [grant (first island-grants)]
        (is (= :island (get-in grant [:grant/data :original-subtype]))
            "Grant should record :island as original-subtype")
        (is (= :mountain (get-in grant [:grant/data :new-subtype]))
            "Grant should record :mountain as new-subtype")
        (is (= {:red 1} (get-in grant [:grant/data :new-produces]))
            "Grant new-produces should be {:red 1} (mountain produces red)")))))


(deftest land-type-target-executor-grants-affect-both-players-lands
  (testing ":land-type-target executor grants affect matching lands for BOTH players"
    (let [db (setup-db {:add-opponent? true})
          ;; Player-1 has an island, player-2 has an island and a forest
          [db1 p1-island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db2 p2-island-id] (th/add-card-to-zone db1 :island :battlefield :player-2)
          [db3 p2-forest-id] (th/add-card-to-zone db2 :forest :battlefield :player-2)
          [db4 obj-id] (th/add-card-to-zone db3 :vision-charm :hand :player-1)
          db4 (mana/add-mana db4 :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          obj-eid (q/get-object-eid db4 obj-id)
          db5 (d/db-with db4 [[:db/add obj-eid :object/chosen-mode mode-2]])
          db-on-stack (rules/cast-spell db5 :player-1 obj-id)
          {:keys [db selection]} (th/resolve-top db-on-stack)
          {:keys [db selection]} (th/confirm-selection db selection #{:island})
          {:keys [db]} (th/confirm-selection db selection #{:swamp})]
      (is (= 1 (count (grants/get-grants-by-type db p1-island-id :land-type-override)))
          "Player-1's island should receive a :land-type-override grant")
      (is (= 1 (count (grants/get-grants-by-type db p2-island-id :land-type-override)))
          "Player-2's island should also receive a :land-type-override grant")
      (is (= 0 (count (grants/get-grants-by-type db p2-forest-id :land-type-override)))
          "Player-2's forest should not receive a grant — :forest does not match source :island"))))


(deftest land-type-target-executor-no-matching-lands-is-no-op
  (testing ":land-type-target executor applies no grants when no lands match source type"
    (let [db (setup-db)
          ;; Only a swamp on battlefield — no plains
          [db1 swamp-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          [db2 obj-id] (th/add-card-to-zone db1 :vision-charm :hand :player-1)
          db2 (mana/add-mana db2 :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          obj-eid (q/get-object-eid db2 obj-id)
          db3 (d/db-with db2 [[:db/add obj-eid :object/chosen-mode mode-2]])
          db-on-stack (rules/cast-spell db3 :player-1 obj-id)
          {:keys [db selection]} (th/resolve-top db-on-stack)
          ;; Pick :plains as source — but no plains are on battlefield
          {:keys [db selection]} (th/confirm-selection db selection #{:plains})
          {:keys [db]} (th/confirm-selection db selection #{:island})]
      ;; Swamp should have no grants — it's not a plains
      (is (= 0 (count (grants/get-grants-by-type db swamp-id :land-type-override)))
          "Swamp should have no grant when source :plains has no matching lands on battlefield"))))


;; =====================================================
;; Full round-trip: :change-land-types → source confirm → chain → target confirm
;; Tests all 4 defmethods in sequence as they execute in production.
;; =====================================================

(deftest change-land-types-full-round-trip
  (testing "Full 4-defmethod round-trip: builder → source executor → chain → target executor"
    (let [db (setup-db {:add-opponent? true})
          ;; Multiple lands on battlefield
          [db1 p1-forest-id] (th/add-card-to-zone db :forest :battlefield :player-1)
          [db2 p1-plains-id] (th/add-card-to-zone db1 :plains :battlefield :player-1)
          [db3 p2-forest-id] (th/add-card-to-zone db2 :forest :battlefield :player-2)
          [db4 obj-id] (th/add-card-to-zone db3 :vision-charm :hand :player-1)
          db4 (mana/add-mana db4 :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          obj-eid (q/get-object-eid db4 obj-id)
          db5 (d/db-with db4 [[:db/add obj-eid :object/chosen-mode mode-2]])
          ;; === Step 1: Cast Vision Charm Mode 2 → :land-type-source selection ===
          db-on-stack (rules/cast-spell db5 :player-1 obj-id)
          {:keys [db selection]} (th/resolve-top db-on-stack)]
      ;; Verify builder output
      (is (= :land-type-source (:selection/type selection))
          "Resolve should produce :land-type-source selection")
      (is (= land-types/basic-land-type-keys (:selection/options selection))
          "Builder: options should be all 5 basic land type keys")
      (is (= :chaining (:selection/lifecycle selection))
          "Builder: lifecycle should be :chaining")
      ;; === Step 2: Confirm source :forest — exercises :land-type-source executor + chain ===
      (let [{:keys [db selection]} (th/confirm-selection db selection #{:forest})]
        ;; Verify chain builder output
        (is (= :land-type-target (:selection/type selection))
            "After source confirm, should chain to :land-type-target")
        (is (= :forest (:selection/source-type selection))
            "Chain builder: :selection/source-type should be :forest")
        (is (not (contains? (set (:selection/options selection)) :forest))
            "Chain builder: :forest should be excluded from target options")
        (is (= 4 (count (:selection/options selection)))
            "Chain builder: exactly 4 target options (excluding source :forest)")
        ;; === Step 3: Confirm target :plains — exercises :land-type-target executor ===
        (let [{:keys [db]} (th/confirm-selection db selection #{:plains})
              p1-forest-grants (grants/get-grants-by-type db p1-forest-id :land-type-override)
              p2-forest-grants (grants/get-grants-by-type db p2-forest-id :land-type-override)
              p1-plains-grants (grants/get-grants-by-type db p1-plains-id :land-type-override)]
          (is (= 1 (count p1-forest-grants))
              "Player-1's forest should receive grant (matches source :forest)")
          (is (= 1 (count p2-forest-grants))
              "Player-2's forest should receive grant (both players' matching lands affected)")
          (is (= 0 (count p1-plains-grants))
              "Player-1's plains should NOT receive grant (is target type, not source type)")
          (let [grant (first p1-forest-grants)]
            (is (= :forest (get-in grant [:grant/data :original-subtype]))
                "Grant original-subtype should be :forest")
            (is (= :plains (get-in grant [:grant/data :new-subtype]))
                "Grant new-subtype should be :plains")
            (is (= {:white 1} (get-in grant [:grant/data :new-produces]))
                "Grant new-produces should be {:white 1} (plains produces white)")))))))
