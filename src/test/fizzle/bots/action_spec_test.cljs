(ns fizzle.bots.action-spec-test
  "Tests for bots/action-spec.cljs and chokepoint in bots/decisions.cljs.

   All spec validation tests use (binding [spec-util/*throw-on-spec-failure* true] ...)
   to convert console.error into throws for test assertions.
   Do NOT test console.error path directly — the throw path exercises the same logic."
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [fizzle.bots.action-spec :as action-spec]
    [fizzle.bots.decisions :as decisions]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Spec Completeness
;; =====================================================

(def all-action-types [:pass :cast-spell :play-land])


(deftest test-all-action-types-have-defmethod
  (testing "All 3 action types have a defmethod (minimal-valid-action passes s/valid?)"
    (doseq [t all-action-types]
      (let [minimal (action-spec/minimal-valid-action t)]
        (is (s/valid? ::action-spec/bot-action minimal)
            (str "Failed for type " t ": "
                 (s/explain-str ::action-spec/bot-action minimal)))))))


(deftest test-all-types-in-minimal-valid-map
  (testing "minimal-valid-actions map has exactly 3 entries covering all types"
    (is (= 3 (count action-spec/minimal-valid-actions))
        "Should have exactly 3 action types")
    (doseq [t all-action-types]
      (is (contains? action-spec/minimal-valid-actions t)
          (str "Missing type in minimal-valid-actions: " t)))))


;; =====================================================
;; B. Per-Type Required Field Validation
;; =====================================================

(deftest test-pass-minimal
  (testing "{:action :pass} passes validation"
    (is (s/valid? ::action-spec/bot-action {:action :pass})
        "Minimal :pass action should pass")))


(deftest test-cast-spell-requires-object-id
  (testing "cast-spell without :object-id fails"
    (let [action (dissoc (action-spec/minimal-valid-action :cast-spell) :object-id)]
      (is (not (s/valid? ::action-spec/bot-action action))
          "cast-spell without :object-id should fail"))))


(deftest test-cast-spell-requires-player-id
  (testing "cast-spell without :player-id fails"
    (let [action (dissoc (action-spec/minimal-valid-action :cast-spell) :player-id)]
      (is (not (s/valid? ::action-spec/bot-action action))
          "cast-spell without :player-id should fail"))))


(deftest test-cast-spell-requires-tap-sequence
  (testing "cast-spell without :tap-sequence fails"
    (let [action (dissoc (action-spec/minimal-valid-action :cast-spell) :tap-sequence)]
      (is (not (s/valid? ::action-spec/bot-action action))
          "cast-spell without :tap-sequence should fail"))))


;; =====================================================
;; C. Nested Tap-Sequence Validation
;; =====================================================

(deftest test-tap-entry-valid
  (testing "valid tap entry with :object-id (uuid) and :mana-color passes"
    (let [entry {:object-id (random-uuid) :mana-color :black}]
      (is (s/valid? ::action-spec/tap-entry entry)
          "Valid tap entry should pass"))))


(deftest test-tap-entry-missing-object-id
  (testing "tap entry without :object-id fails"
    (let [entry {:mana-color :red}]
      (is (not (s/valid? ::action-spec/tap-entry entry))
          "Tap entry without :object-id should fail"))))


(deftest test-tap-entry-missing-mana-color
  (testing "tap entry without :mana-color fails"
    (let [entry {:object-id (random-uuid)}]
      (is (not (s/valid? ::action-spec/tap-entry entry))
          "Tap entry without :mana-color should fail"))))


;; =====================================================
;; D. Edge Cases
;; =====================================================

(deftest test-unknown-action-fails
  (testing "{:action :nonexistent} fails validation"
    (is (not (s/valid? ::action-spec/bot-action {:action :nonexistent}))
        "Unknown action type should fail")))


(deftest test-cast-spell-optional-target
  (testing "cast-spell with :target passes, and without :target also passes"
    (let [base (action-spec/minimal-valid-action :cast-spell)
          with-target (assoc base :target :player-1)
          without-target (dissoc base :target)]
      (is (s/valid? ::action-spec/bot-action with-target)
          "cast-spell with :target should pass")
      (is (s/valid? ::action-spec/bot-action without-target)
          "cast-spell without :target should also pass"))))


;; =====================================================
;; E. Chokepoint Integration Tests
;; =====================================================

(deftest test-bot-decide-action-valid-no-throw
  (testing "bot-decide-action with no-bot game-db returns :pass without throwing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (th/create-test-db {:stops #{:main1}})
            result (decisions/bot-decide-action db)]
        (is (= :pass (:action result))
            "Should return :pass action")))))


(deftest test-validate-bot-action-invalid-throws
  (testing "validate-bot-action! throws when *throw-on-spec-failure* true and data is invalid"
    (binding [spec-util/*throw-on-spec-failure* true]
      (is (thrown? js/Error
            (action-spec/validate-bot-action! {:action :cast-spell}))
          "cast-spell missing required fields should throw"))))
