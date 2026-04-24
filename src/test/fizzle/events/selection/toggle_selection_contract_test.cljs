(ns fizzle.events.selection.toggle-selection-contract-test
  "Contract tests for toggle-selection-impl hardening (epic fizzle-75qq, task fizzle-3hym).

   Three-layer hardening per epic requirements:
   - Layer 1a: pos-int? entry guard — throws when select-count is missing/nil/0/negative
   - Layer 1b: explicit at-limit cond arm (UX preserved) + :else contract-violation throw
   - Layer 3: validate-at-chokepoint! at entry catches malformed state

   Reference: fizzle-dx7b was the seed bug — silent no-op when select-count=0 (absent)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.events.selection.core :as selection-core]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helpers
;; =====================================================

(defn- base-selection
  "Minimal valid selection for toggle-selection-impl contract tests.
   Uses :select-count as passed. Does not go through spec validation —
   the entry guard must catch invalid select-count before spec would run."
  [select-count]
  {:selection/mechanism :pick-from-zone
   :selection/domain    :discard
   :selection/lifecycle :standard
   :selection/player-id :player-1
   :selection/selected #{}
   :selection/valid-targets [:card-a :card-b :card-c]
   :selection/validation :exact
   :selection/auto-confirm? false
   :selection/select-count select-count})


(defn- app-db-with
  "Build app-db with the given selection map."
  [selection]
  (let [db (th/create-test-db)]
    {:game/db db :game/pending-selection selection}))


;; =====================================================
;; Layer 1a — Entry Guard: pos-int? on select-count
;; Tests must FAIL before implementation (select-count currently defaults to 0 via get)
;; =====================================================

(deftest test-toggle-impl-throws-when-select-count-missing
  (testing "toggle-selection-impl throws ex-info when :selection/select-count is absent"
    (let [sel (dissoc (base-selection 2) :selection/select-count)
          app-db (app-db-with sel)]
      (is (thrown-with-msg?
            js/Error
            #"select-count must be a positive int"
            (selection-core/toggle-selection-impl app-db :card-a))
          "must throw when :selection/select-count is absent"))))


(deftest test-toggle-impl-throws-when-select-count-nil
  (testing "toggle-selection-impl throws ex-info when :selection/select-count is nil"
    (let [sel (assoc (base-selection 2) :selection/select-count nil)
          app-db (app-db-with sel)]
      (is (thrown-with-msg?
            js/Error
            #"select-count must be a positive int"
            (selection-core/toggle-selection-impl app-db :card-a))
          "must throw when :selection/select-count is nil"))))


(deftest test-toggle-impl-throws-when-select-count-zero
  (testing "toggle-selection-impl throws ex-info when :selection/select-count is 0"
    (let [sel (base-selection 0)
          app-db (app-db-with sel)]
      (is (thrown-with-msg?
            js/Error
            #"select-count must be a positive int"
            (selection-core/toggle-selection-impl app-db :card-a))
          "must throw when :selection/select-count is 0 (the dx7b silent-noop seed)"))))


(deftest test-toggle-impl-throws-when-select-count-negative
  (testing "toggle-selection-impl throws ex-info when :selection/select-count is negative"
    (let [sel (base-selection -1)
          app-db (app-db-with sel)]
      (is (thrown-with-msg?
            js/Error
            #"select-count must be a positive int"
            (selection-core/toggle-selection-impl app-db :card-a))
          "must throw when :selection/select-count is -1"))))


(deftest test-toggle-impl-throws-includes-selection-type-in-ex-data
  (testing "ex-info thrown by entry guard includes :selection-type in ex-data"
    (let [sel (base-selection 0)
          app-db (app-db-with sel)]
      (try
        (selection-core/toggle-selection-impl app-db :card-a)
        (is false "Expected exception was not thrown")
        (catch js/Error e
          (is (= :discard (:selection/domain (ex-data e)))
              "ex-data must include :selection/domain"))))))


;; =====================================================
;; Layer 1b — Explicit at-limit arm (UX regression tests)
;; These must PASS before AND after the refactor (positive-path regression)
;; =====================================================

(deftest test-toggle-impl-at-limit-returns-unchanged-app-db
  (testing "at-limit click (selecting beyond select-count) returns [app-db false] — NOT a throw"
    (let [sel {:selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{:card-a :card-b}
               :selection/valid-targets [:card-a :card-b :card-c]
               :selection/select-count 2
               :selection/validation :exact
               :selection/auto-confirm? false}
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :card-c)]
      (is (= app-db (:app-db result))
          "app-db must be unchanged at limit — at-limit is a legitimate no-op UX, not a throw")
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false at limit"))))


(deftest test-toggle-impl-deselect-branch-preserved
  (testing "deselecting an already-selected item works correctly with pos-int? guard in place"
    (let [sel {:selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{:card-a :card-b}
               :selection/valid-targets [:card-a :card-b :card-c]
               :selection/select-count 2
               :selection/validation :exact
               :selection/auto-confirm? false}
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :card-a)]
      (is (= #{:card-b} (get-in result [:app-db :game/pending-selection :selection/selected]))
          "deselect must remove item from selected set")
      (is (false? (:auto-confirm? result))
          "deselect must not auto-confirm"))))


(deftest test-toggle-impl-single-select-replace-preserved
  (testing "single-select (select-count=1) replaces current selection with new id"
    (let [sel {:selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{:card-a}
               :selection/valid-targets [:card-a :card-b]
               :selection/select-count 1
               :selection/validation :exact
               :selection/auto-confirm? false}
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :card-b)]
      (is (= #{:card-b} (get-in result [:app-db :game/pending-selection :selection/selected]))
          "single-select must replace existing selection with the new id"))))


(deftest test-toggle-impl-unlimited-exact-false-preserved
  (testing "exact?=false (unlimited select) always adds new id beyond select-count"
    (let [sel {:selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{:card-a :card-b :card-c}
               :selection/valid-targets [:card-a :card-b :card-c :card-d]
               :selection/select-count 1
               :selection/exact? false
               :selection/validation :exact
               :selection/auto-confirm? false}
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :card-d)]
      (is (contains? (get-in result [:app-db :game/pending-selection :selection/selected]) :card-d)
          "unlimited (exact?=false) mode must add card-d even though already above select-count"))))


;; =====================================================
;; Layer 1b — Terminal :else throw (exhaustiveness safety net)
;; After refactor, :else is unreachable with valid inputs.
;; We test it by forcing a state where all explicit arms are bypassed:
;;   - valid-targets check: passes (no valid-targets set, so not rejected)
;;   - deselect: passes (id is NOT in selected)
;;   - single-select: passes (select-count != 1)
;;   - unlimited: passes (exact? is not false)
;;   - multi-select under limit: passes (count selected >= select-count → NOT under limit)
;;   - at-limit: MUST be caught by explicit at-limit arm (count selected >= select-count)
;; So :else truly cannot be reached with correct at-limit arm in place.
;; Test documents this via structural observation: with at-limit arm present,
;; calling toggle at exact limit hits the at-limit arm (verified above),
;; and calling with count > select-count also hits it (overshoot case).
;; =====================================================

(deftest test-toggle-impl-at-limit-arm-catches-overshoot
  (testing "explicit at-limit arm catches overshoot case (selected count > select-count)"
    ;; This state should not occur in practice but tests the at-limit arm handles
    ;; the (>= count select-count) condition generally — not just ==.
    (let [sel {:selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{:card-a :card-b :card-c}  ; 3 selected
               :selection/select-count 2  ; limit is 2
               :selection/validation :exact
               :selection/auto-confirm? false}
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :card-d)]
      (is (= app-db (:app-db result))
          "overshoot (count > select-count) must hit at-limit arm, not :else throw"))))


;; =====================================================
;; Layer 3 — validate-at-chokepoint! at toggle entry
;; Uses *throw-on-spec-failure* true to assert malformed state is caught
;; =====================================================

(deftest test-toggle-impl-chokepoint-catches-malformed-selection
  (testing "validate-at-chokepoint! at toggle entry catches malformed state"
    ;; :discard selection missing :selection/lifecycle — malformed per spec
    ;; After Layer 2 migration adds :selection/select-count to :req, this also
    ;; triggers spec failure on missing select-count. Here we verify the chokepoint
    ;; fires for lifecycle absence (a pre-existing :req field).
    (let [db (th/create-test-db)
          ;; Malformed: missing :selection/lifecycle (required for :discard)
          malformed-sel {:selection/player-id :player-1
                         :selection/selected #{}
                         :selection/select-count 1
                         :selection/validation :exact
                         :selection/auto-confirm? false}
          app-db {:game/db db :game/pending-selection malformed-sel}]
      (binding [spec-util/*throw-on-spec-failure* true]
        (is (thrown?
              js/Error
              (selection-core/toggle-selection-impl app-db :card-a))
            "validate-at-chokepoint! must fire on malformed selection and throw under *throw-on-spec-failure*")))))


;; =====================================================
;; Vector semantics — N-slot targeting (fizzle-l77d)
;; :selection/selected is a vector [] for multi-slot cast-time targeting.
;; toggle-selection-impl must handle vector correctly (not use set ops).
;; =====================================================

(defn- n-slot-selection
  "Minimal valid n-slot selection with vector :selection/selected."
  [selected]
  {:selection/mechanism :n-slot-targeting
   :selection/domain    :cast-time-targeting
   :selection/lifecycle :standard
   :selection/player-id :player-1
   :selection/selected selected
   :selection/valid-targets [:obj-a :obj-b :obj-c]
   :selection/validation :exact
   :selection/auto-confirm? false
   :selection/select-count 2})


(deftest test-toggle-impl-vector-add-item
  (testing "toggle-selection-impl adds item to vector :selection/selected"
    (let [sel (n-slot-selection [])
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :obj-a)
          new-sel (get-in result [:app-db :game/pending-selection :selection/selected])]
      (is (= 1 (count new-sel))
          "adding to empty vector should yield count=1")
      (is (some #{:obj-a} new-sel)
          "added item must be in the result"))))


(deftest test-toggle-impl-vector-deselect-item
  (testing "toggle-selection-impl removes item from vector :selection/selected"
    (let [sel (n-slot-selection [:obj-a :obj-b])
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :obj-a)
          new-sel (get-in result [:app-db :game/pending-selection :selection/selected])]
      (is (= 1 (count new-sel))
          "removing one item from 2-item vector should yield count=1")
      (is (not (some #{:obj-a} new-sel))
          "removed item must not be in result")
      (is (some #{:obj-b} new-sel)
          "remaining item must still be in result"))))


(deftest test-toggle-impl-vector-at-limit-noop
  (testing "toggle-selection-impl is a no-op when vector is at select-count limit"
    (let [sel (n-slot-selection [:obj-a :obj-b])
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :obj-c)]
      (is (= app-db (:app-db result))
          "at-limit click on new item must return unchanged app-db")
      (is (false? (:auto-confirm? result))
          "at-limit must not auto-confirm"))))


(deftest test-toggle-impl-vector-no-auto-confirm-at-2
  (testing "toggle-selection-impl does not auto-confirm when select-count=2 even when filled"
    (let [sel (n-slot-selection [:obj-a])
          app-db (app-db-with sel)
          result (selection-core/toggle-selection-impl app-db :obj-b)]
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when select-count=2 (not 1)"))))
