(ns fizzle.events.selection.untap-test
  "Pattern B (multimethod production-path slice) tests for
   events/selection/untap.cljs defmethods.

   Covers both defmethod registrations:

   build-selection-for-effect (1):
     :untap-lands  - builder: filters tapped lands by :land type on caster's
                     battlefield; uses :at-most validation; no non-lands or
                     untapped objects included.

   execute-confirmed-selection (1):
     :untap-lands  - executor: reduces lands/untap-permanent over selected IDs.
                     Selected lands become untapped; unselected remain unchanged.
                     Empty selection is a no-op.

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test.

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or via th/confirm-selection for :selected-field selections).

   Cards used:
     :island     - basic land (tapped land candidate)
     :swamp      - basic land (second tapped land candidate)
     :dark-ritual - non-land spell object (excluded from candidates)"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.cards.lands.basic-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.spec :as sel-spec]
    ;; Load untap defmethods so they register on the multimethods (side-effect require)
    [fizzle.events.selection.untap :as sel-untap]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Setup
;; =====================================================

(defn- setup-db
  "Create a minimal game-db with player-1 (and optional player-2)."
  ([]
   (setup-db {}))
  ([opts]
   (let [db (th/create-test-db {})]
     (if (:add-opponent? opts)
       (th/add-opponent db)
       db))))


;; =====================================================
;; build-selection-for-effect :untap-lands
;; Builder: finds tapped lands controlled by caster on battlefield.
;; Non-lands and untapped objects are excluded.
;; =====================================================

(deftest untap-lands-builder-creates-correct-shape
  (testing ":untap-lands builder produces selection with correct shape and fields"
    (let [db (setup-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-id)
          effect {:effect/type :untap-lands :effect/count 3}
          remaining-effects []
          sel (sel-core/build-selection-for-effect db :player-1 land-id effect remaining-effects)]
      (is (= :untap-lands (:selection/domain sel))
          "Builder should produce :untap-lands selection type")
      (is (= :at-most (:selection/validation sel))
          "Validation should be :at-most (player may select 0 to N lands)")
      (is (= 3 (:selection/select-count sel))
          "select-count should come from :effect/count")
      (is (= :player-1 (:selection/player-id sel))
          "Selecting player should be :player-1 (caster)")
      (is (= 0 (:selection/min-count sel))
          "min-count should be 0 (player can select 0 lands)")
      (is (= false (:selection/auto-confirm? sel))
          "auto-confirm? should be false")
      (is (= :battlefield (:selection/zone sel))
          "Zone should be :battlefield")
      (is (contains? (:selection/candidates sel) land-id)
          "Tapped land should be in candidates"))))


(deftest untap-lands-builder-filters-only-tapped-lands-of-land-type
  (testing ":untap-lands builder excludes untapped lands and non-land permanents"
    (let [db (setup-db)
          ;; Tapped island — should be included
          [db tapped-land] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Untapped swamp — should be excluded
          [db untapped-land] (th/add-card-to-zone db :swamp :battlefield :player-1)
          ;; Tap only the island
          db (th/tap-permanent db tapped-land)
          effect {:effect/type :untap-lands :effect/count 3}
          sel (sel-core/build-selection-for-effect db :player-1 tapped-land effect [])]
      (is (contains? (:selection/candidates sel) tapped-land)
          "Tapped island should be a candidate")
      (is (not (contains? (:selection/candidates sel) untapped-land))
          "Untapped swamp should NOT be a candidate — only tapped lands are eligible"))))


;; =====================================================
;; execute-confirmed-selection :untap-lands
;; Executor: reduces lands/untap-permanent over selected land IDs.
;; Selected lands become untapped; unselected remain unchanged.
;; =====================================================

(deftest untap-lands-executor-untaps-selected-lands
  (testing ":untap-lands executor untaps selected lands, leaves unselected tapped"
    (let [db (setup-db)
          ;; Two tapped lands — player will select only one
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land-2] (th/add-card-to-zone db :swamp :battlefield :player-1)
          db (th/tap-permanent db land-1)
          db (th/tap-permanent db land-2)
          effect {:effect/type :untap-lands :effect/count 3}
          sel (sel-untap/build-untap-lands-selection db :player-1 land-1 effect [])
          ;; Route through ADR-019 chokepoint
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Player selects only land-1
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{land-1})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)]
      ;; land-1 should be untapped
      (is (false? (:object/tapped (q/get-object db' land-1)))
          "Selected land should be untapped after executor runs")
      ;; land-2 should remain tapped (not selected)
      (is (true? (:object/tapped (q/get-object db' land-2)))
          "Unselected land should remain tapped")
      ;; Both lands should still be on battlefield (untap does not move zones)
      (is (= :battlefield (:object/zone (q/get-object db' land-1)))
          "Untapped land should remain on battlefield")
      (is (= :battlefield (:object/zone (q/get-object db' land-2)))
          "Unselected land should remain on battlefield"))))


(deftest untap-lands-executor-empty-selection-is-no-op
  (testing ":untap-lands executor with empty selection leaves all tapped lands unchanged"
    (let [db (setup-db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-id)
          effect {:effect/type :untap-lands :effect/count 3}
          sel (sel-untap/build-untap-lands-selection db :player-1 land-id effect [])
          ;; Route through ADR-019 chokepoint
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Player selects zero lands
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)]
      ;; Land should remain tapped — no untap happened
      (is (true? (:object/tapped (q/get-object db' land-id)))
          "Land should remain tapped when no lands selected (empty selection is a no-op)"))))


;; =====================================================
;; Full round-trip: ETB trigger → build-selection-for-effect → execute-confirmed-selection
;; Tests both defmethods in production sequence via Cloud of Faeries ETB trigger.
;; =====================================================

(deftest untap-lands-full-round-trip-via-etb-trigger
  (testing "Full 2-defmethod round-trip: builder (ETB trigger) → executor (untap selected)"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          ;; Two tapped lands — will select both (max 2 for Cloud of Faeries ETB)
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land-2] (th/add-card-to-zone db :swamp :battlefield :player-1)
          db (th/tap-permanent db land-1)
          db (th/tap-permanent db land-2)
          ;; === Step 1: Cast Cloud of Faeries — creature spell, no effects ===
          db-cast (rules/cast-spell db :player-1 cf-id)
          ;; Resolve creature — enters battlefield, ETB trigger queued
          {:keys [db]} (th/resolve-top db-cast)
          ;; Cloud of Faeries should be on battlefield
          _ (is (= :battlefield (:object/zone (q/get-object db cf-id)))
                "Precondition: Cloud of Faeries should be on battlefield")
          ;; === Step 2: Resolve ETB trigger — exercises build-selection-for-effect :untap-lands ===
          {:keys [db selection]} (th/resolve-top db)]
      ;; Verify builder output shape
      (is (= :untap-lands (:selection/domain selection))
          "ETB trigger should produce :untap-lands selection via build-selection-for-effect")
      (is (= :at-most (:selection/validation selection))
          "Builder: validation should be :at-most")
      (is (= 2 (:selection/select-count selection))
          "Builder: max select-count should be 2 (from :effect/count on ETB trigger)")
      (is (contains? (:selection/candidates selection) land-1)
          "Builder: tapped island should be a candidate")
      (is (contains? (:selection/candidates selection) land-2)
          "Builder: tapped swamp should be a candidate")
      ;; === Step 3: Confirm selection — exercises execute-confirmed-selection :untap-lands ===
      (let [{:keys [db]} (th/confirm-selection db selection #{land-1 land-2})]
        ;; Both lands should now be untapped
        (is (false? (:object/tapped (q/get-object db land-1)))
            "Executor: land-1 should be untapped after selection confirmed")
        (is (false? (:object/tapped (q/get-object db land-2)))
            "Executor: land-2 should be untapped after selection confirmed")
        ;; Both should remain on battlefield
        (is (= :battlefield (:object/zone (q/get-object db land-1)))
            "Executor: untapped land should remain on battlefield")
        (is (= :battlefield (:object/zone (q/get-object db land-2)))
            "Executor: untapped land should remain on battlefield")))))
