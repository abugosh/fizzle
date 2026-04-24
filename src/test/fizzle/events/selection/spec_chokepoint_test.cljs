(ns fizzle.events.selection.spec-chokepoint-test
  "Direct throw-path tests for set-pending-selection → validate-at-chokepoint!.
   Each test names the specific bug it catches: if validate-at-chokepoint! were
   replaced with identity or a no-op, the (thrown? ...) assertions would fail."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Throw path — invalid selections cause js/Error
;;    Bug caught: no-op'd validate-at-chokepoint! would not throw.
;; =====================================================

(deftest set-pending-selection-throws-on-invalid-discard-shape
  (testing "missing required discard fields throws when *throw-on-spec-failure* is true"
    ;; Bug caught: if validate-at-chokepoint! were removed or made a no-op,
    ;; this call would succeed silently, letting an invalid selection corrupt game state.
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [app-db {:game/db (th/create-test-db)}
            ;; :selection/mechanism alone is not a valid :discard — missing
            ;; :selection/domain, :selection/lifecycle, :selection/player-id, :selection/selected,
            ;; :selection/validation, :selection/auto-confirm?
            invalid-sel {:selection/mechanism :pick-from-zone}]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection app-db invalid-sel))
            "set-pending-selection with invalid :discard should throw")))))


(deftest set-pending-selection-throws-on-invalid-cast-time-targeting-shape
  (testing "missing :selection/valid-targets from cast-time-targeting throws when binding true"
    ;; Bug caught: a defmethod for :cast-time-targeting that has weaker validation
    ;; than the base spec would let invalid data through — this test catches that.
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [app-db {:game/db (th/create-test-db)}
            ;; Start with a valid cast-time-targeting, remove a required field
            invalid-sel (dissoc (sel-spec/minimal-valid-selection :cast-time-targeting)
                                :selection/valid-targets)]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection app-db invalid-sel))
            "cast-time-targeting without :selection/valid-targets should throw")))))


(deftest set-pending-selection-throws-on-invalid-mana-allocation-shape
  (testing "bare mana-allocation map without required fields throws when binding true"
    ;; Bug caught: defmethod for :mana-allocation could have weaker validation —
    ;; this test verifies the full spec path rejects maps missing required fields.
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [app-db {:game/db (th/create-test-db)}
            ;; :allocate-resource requires domain, lifecycle, player-id, generic-remaining, etc.
            invalid-sel {:selection/mechanism :allocate-resource}]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection app-db invalid-sel))
            "set-pending-selection with bare :mana-allocation map should throw")))))


;; =====================================================
;; B. Valid path — valid selection passes through without throwing
;;    Proves the throw is shape-specific, not unconditional.
;; =====================================================

(deftest set-pending-selection-accepts-valid-discard-when-binding-true
  (testing "valid :discard selection is stored without throwing"
    ;; Proves the throw in the invalid tests above is due to the bad shape,
    ;; not an always-throw chokepoint. If this fails, the chokepoint is broken.
    ;; ADR-030: builders set mechanism+domain directly; stored selection equals input.
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [app-db {:game/db (th/create-test-db)}
            valid-sel (sel-spec/minimal-valid-selection :discard)
            result (sel-spec/set-pending-selection app-db valid-sel)
            pending (:game/pending-selection result)]
        (is (= :pick-from-zone (:selection/mechanism pending))
            "Valid :discard selection should have :pick-from-zone mechanism")
        (is (= :discard (:selection/domain pending))
            "Valid :discard selection should have :discard domain")
        (is (= valid-sel pending)
            "Stored selection should equal input (builders set mechanism+domain)")))))


;; =====================================================
;; C. validate-at-chokepoint! directly
;;    Distinguishes wiring failure from chokepoint-fn failure.
;; =====================================================

(deftest validate-at-chokepoint-throws-directly-on-invalid-spec
  (testing "validate-at-chokepoint! throws when binding true and data does not satisfy spec"
    ;; Bug caught: if the wiring between set-pending-selection and
    ;; validate-at-chokepoint! were severed, test A would fail but this
    ;; one would still pass — separating the two failure modes.
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (thrown? js/Error
            (spec-util/validate-at-chokepoint!
              :fizzle.events.selection.spec/selection
              {:selection/mechanism :pick-from-zone}
              "test-label"))
          "validate-at-chokepoint! should throw on invalid data when binding is true"))))
