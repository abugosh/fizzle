(ns fizzle.engine.phase-mechanic-test
  "Tests for phase out/in mechanic.

   Covers:
   - phase-out/phase-in pure functions (zones.cljs)
   - :phase-out effect type (effects/zones.cljs)
   - Phase-in during untap step (triggers.cljs)
   - Battlefield query exclusion for phased-out objects"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.effects-registry]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helpers
;; =====================================================

(defn add-artifact-to-battlefield
  "Add a Lotus Petal (artifact) to the battlefield for a player.
   Returns [db object-id]."
  [db owner]
  (th/add-card-to-zone db :lotus-petal :battlefield owner))


(defn add-tapped-artifact
  "Add a tapped Lotus Petal to the battlefield for a player.
   Returns [db object-id]."
  [db owner]
  (let [[db obj-id] (add-artifact-to-battlefield db owner)
        obj-eid (q/get-object-eid db obj-id)
        db (d/db-with db [[:db/add obj-eid :object/tapped true]])]
    [db obj-id]))


;; =====================================================
;; Tests: phase-out function
;; =====================================================

(deftest phase-out-moves-to-phased-out-zone-test
  (testing "phase-out moves object from :battlefield to :phased-out zone"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db obj-id)))
                "Precondition: object starts on battlefield")
          db' (zones/phase-out db obj-id)]
      (is (= :phased-out (:object/zone (q/get-object db' obj-id)))
          "phase-out should move object to :phased-out zone"))))


(deftest phase-out-preserves-tapped-state-test
  (testing "phase-out preserves :object/tapped true — tapped artifact stays tapped"
    (let [db (th/create-test-db)
          [db obj-id] (add-tapped-artifact db :player-1)
          _ (is (true? (:object/tapped (q/get-object db obj-id)))
                "Precondition: object is tapped")
          db' (zones/phase-out db obj-id)]
      (is (true? (:object/tapped (q/get-object db' obj-id)))
          "phase-out must NOT reset tapped state (direct zone change bypasses move-to-zone)"))))


(deftest phase-out-preserves-creature-fields-test
  (testing "phase-out preserves :object/power, :object/toughness, :object/summoning-sick"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-test-creature db :player-1 3 4)
          obj-before (q/get-object db obj-id)
          _ (is (= 3 (:object/power obj-before)) "Precondition: power is 3")
          _ (is (= 4 (:object/toughness obj-before)) "Precondition: toughness is 4")
          _ (is (true? (:object/summoning-sick obj-before)) "Precondition: has summoning sickness")
          db' (zones/phase-out db obj-id)
          obj-after (q/get-object db' obj-id)]
      (is (= 3 (:object/power obj-after))
          "phase-out must preserve :object/power")
      (is (= 4 (:object/toughness obj-after))
          "phase-out must preserve :object/toughness")
      (is (true? (:object/summoning-sick obj-after))
          "phase-out must preserve :object/summoning-sick"))))


(deftest phase-out-preserves-grants-test
  (testing "phase-out preserves :object/grants — temporary abilities survive phasing"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          grant {:grant/id (random-uuid)
                 :grant/type :ability
                 :grant/data {:ability/type :mana}}
          db-with-grant (grants/add-grant db obj-id grant)
          _ (is (seq (q/get-grants db-with-grant obj-id))
                "Precondition: object has a grant")
          db' (zones/phase-out db-with-grant obj-id)]
      (is (seq (q/get-grants db' obj-id))
          "phase-out must preserve grants — direct zone change does not retract them"))))


;; =====================================================
;; Tests: phase-in function
;; =====================================================

(deftest phase-in-moves-to-battlefield-test
  (testing "phase-in moves object from :phased-out to :battlefield"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          db-phased (zones/phase-out db obj-id)
          _ (is (= :phased-out (:object/zone (q/get-object db-phased obj-id)))
                "Precondition: object is phased out")
          db' (zones/phase-in db-phased obj-id)]
      (is (= :battlefield (:object/zone (q/get-object db' obj-id)))
          "phase-in should move object back to :battlefield"))))


(deftest phase-in-preserves-tapped-state-test
  (testing "phase-in preserves tapped state — tapped when phased out remains tapped"
    (let [db (th/create-test-db)
          [db obj-id] (add-tapped-artifact db :player-1)
          _ (is (true? (:object/tapped (q/get-object db obj-id)))
                "Precondition: object is tapped")
          db-phased (zones/phase-out db obj-id)
          db' (zones/phase-in db-phased obj-id)]
      (is (true? (:object/tapped (q/get-object db' obj-id)))
          "phase-in must NOT reset tapped state (direct zone change bypasses move-to-zone)"))))


(deftest phase-in-does-not-reset-summoning-sickness-test
  (testing "phase-in does NOT reset summoning sickness — creature phased out sick stays sick"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          _ (is (true? (:object/summoning-sick (q/get-object db obj-id)))
                "Precondition: creature has summoning sickness")
          db-phased (zones/phase-out db obj-id)
          db' (zones/phase-in db-phased obj-id)]
      (is (true? (:object/summoning-sick (q/get-object db' obj-id)))
          "phase-in must NOT clear summoning sickness (unlike move-to-zone which would)"))))


(deftest phase-in-nonexistent-object-noop-test
  (testing "phase-in on nonexistent object returns db unchanged"
    (let [db (th/create-test-db)
          fake-id (random-uuid)
          db' (zones/phase-in db fake-id)]
      (is (= db db')
          "phase-in on nonexistent object must be a no-op"))))


;; =====================================================
;; Tests: :phase-out effect type
;; =====================================================

(deftest phase-out-effect-moves-target-test
  (testing ":phase-out effect moves target to :phased-out zone"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db obj-id)))
                "Precondition: object is on battlefield")
          effect {:effect/type :phase-out
                  :effect/target obj-id}
          db' (effects/execute-effect db :player-1 effect nil)]
      (is (= :phased-out (:object/zone (q/get-object db' obj-id)))
          ":phase-out effect must move target to :phased-out zone"))))


(deftest phase-out-effect-nil-target-noop-test
  (testing ":phase-out effect with nil target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :phase-out
                  :effect/target nil}
          db' (effects/execute-effect db :player-1 effect nil)]
      (is (= db db')
          ":phase-out with nil target must be a no-op"))))


;; =====================================================
;; Tests: Phase-in during untap step
;; =====================================================

(deftest untap-step-phases-in-owner-objects-test
  (testing "Phased-out object phases in during its owner's untap step"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          db-phased (zones/phase-out db obj-id)
          _ (is (= :phased-out (:object/zone (q/get-object db-phased obj-id)))
                "Precondition: object is phased out")
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db-phased trigger)]
      (is (= :battlefield (:object/zone (q/get-object db' obj-id)))
          "Phased-out object must phase in during owner's untap step"))))


(deftest untap-step-does-not-phase-in-opponent-objects-test
  (testing "Phased-out object does NOT phase in during opponent's untap step"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          db-phased (zones/phase-out db obj-id)
          _ (is (= :phased-out (:object/zone (q/get-object db-phased obj-id)))
                "Precondition: object owned by player-1 is phased out")
          ;; Player-2's untap step fires
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-2}
          db' (triggers/resolve-trigger db-phased trigger)]
      (is (= :phased-out (:object/zone (q/get-object db' obj-id)))
          "Object owned by player-1 must NOT phase in during player-2's untap step"))))


(deftest untap-step-phases-in-multiple-objects-test
  (testing "Multiple phased-out objects all phase in simultaneously during owner's untap step"
    (let [db (th/create-test-db)
          [db obj-id-1] (add-artifact-to-battlefield db :player-1)
          [db obj-id-2] (add-artifact-to-battlefield db :player-1)
          [db obj-id-3] (add-artifact-to-battlefield db :player-1)
          db-phased (-> db
                        (zones/phase-out obj-id-1)
                        (zones/phase-out obj-id-2)
                        (zones/phase-out obj-id-3))
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db-phased trigger)]
      (is (= :battlefield (:object/zone (q/get-object db' obj-id-1)))
          "First phased-out object must phase in")
      (is (= :battlefield (:object/zone (q/get-object db' obj-id-2)))
          "Second phased-out object must phase in")
      (is (= :battlefield (:object/zone (q/get-object db' obj-id-3)))
          "Third phased-out object must phase in"))))


(deftest phase-in-does-not-trigger-etb-test
  (testing "Phase-in during untap does NOT trigger ETB effects"
    ;; ETB check: no :permanent-entered event dispatched.
    ;; We verify this by checking that a creature phased in does NOT
    ;; get its summoning sickness cleared (ETB sets sick=true; direct
    ;; zone change leaves it as-is).
    ;; The real test: move-to-zone sets summoning-sick=true on ETB,
    ;; but phase-in preserves whatever state the creature had.
    (let [db (th/create-test-db)
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          ;; Manually clear summoning sickness (simulate creature that survived a turn)
          obj-eid (q/get-object-eid db obj-id)
          db-no-sick (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])
          _ (is (nil? (:object/summoning-sick (q/get-object db-no-sick obj-id)))
                "Precondition: creature has no summoning sickness")
          db-phased (zones/phase-out db-no-sick obj-id)
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db-phased trigger)]
      (is (nil? (:object/summoning-sick (q/get-object db' obj-id)))
          "Phase-in via untap must NOT add summoning sickness (no ETB path)"))))


;; =====================================================
;; Tests: Battlefield query exclusion
;; =====================================================

(deftest phased-out-excluded-from-battlefield-query-test
  (testing "Phased-out objects NOT returned by get-objects-in-zone for :battlefield"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          _ (is (= 1 (count (q/get-objects-in-zone db :player-1 :battlefield)))
                "Precondition: 1 object on battlefield")
          db' (zones/phase-out db obj-id)]
      (is (empty? (q/get-objects-in-zone db' :player-1 :battlefield))
          "Phased-out objects must not appear in battlefield queries"))))


(deftest phased-out-visible-in-phased-out-zone-query-test
  (testing "Phased-out objects ARE returned by get-objects-in-zone for :phased-out"
    (let [db (th/create-test-db)
          [db obj-id] (add-artifact-to-battlefield db :player-1)
          db' (zones/phase-out db obj-id)]
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :phased-out)))
          "Phased-out objects must appear in :phased-out zone queries")
      (is (= obj-id (:object/id (first (q/get-objects-in-zone db' :player-1 :phased-out))))
          "The phased-out object must be the correct one"))))
