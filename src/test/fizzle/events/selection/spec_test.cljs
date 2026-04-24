(ns fizzle.events.selection.spec-test
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [fizzle.events.selection.spec :as sel-spec]))


;; ============================================================
;; A. All 32 minimal-valid-selections pass ::selection
;; ============================================================

(deftest test-exercise-generates-valid-selections
  (testing "spec can generate valid selection maps for all 32 types (s/exercise substitute)"
    ;; cljs.spec.gen.alpha requires test.check which is not in this project's deps.
    ;; minimal-valid-selections covers the same guarantee with full type coverage:
    ;; one known-valid instance per type, all 32 conforming to ::selection.
    (doseq [[sel-type minimal] sel-spec/minimal-valid-selections]
      (is (s/valid? ::sel-spec/selection minimal)
          (str "Failed for type " sel-type ": "
               (s/explain-str ::sel-spec/selection minimal))))))


(deftest test-minimal-selections-count
  (testing "exactly 32 selection types covered"
    (is (= 32 (count sel-spec/minimal-valid-selections)))))


;; ============================================================
;; B. Base key constraints
;; ============================================================

(deftest test-missing-lifecycle-is-valid-at-mechanism-level
  (testing ":selection/lifecycle is :opt at mechanism level (ADR-030 phase 4); domain executors enforce it"
    ;; After phase 4, spec dispatches on :selection/mechanism (7 methods).
    ;; Domain-specific fields like :lifecycle are :opt at mechanism level —
    ;; enforcement moved from spec to apply-domain-policy executors.
    (let [sel (-> (get sel-spec/minimal-valid-selections :discard)
                  (dissoc :selection/lifecycle))]
      (is (s/valid? ::sel-spec/selection sel)
          ":discard without :lifecycle is spec-valid (enforcement is executor-level)"))))


(deftest test-wrong-lifecycle-fails
  (testing "selection with invalid :selection/lifecycle fails"
    (let [sel (assoc (get sel-spec/minimal-valid-selections :discard)
                     :selection/lifecycle :bogus)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


(deftest test-missing-player-id-fails
  (testing "selection without :selection/player-id fails"
    (let [sel (-> (get sel-spec/minimal-valid-selections :discard)
                  (dissoc :selection/player-id))]
      (is (not (s/valid? ::sel-spec/selection sel))))))


(deftest test-missing-validation-fails
  (testing "selection without :selection/validation fails"
    (let [sel (-> (get sel-spec/minimal-valid-selections :discard)
                  (dissoc :selection/validation))]
      (is (not (s/valid? ::sel-spec/selection sel))))))


(deftest test-wrong-validation-fails
  (testing "selection with invalid :selection/validation value fails"
    (let [sel (assoc (get sel-spec/minimal-valid-selections :discard)
                     :selection/validation :nonsense)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


(deftest test-unknown-mechanism-fails
  (testing "selection with unknown :selection/mechanism not in any defmethod fails"
    (is (not (s/valid? ::sel-spec/selection
                       {:selection/mechanism :completely-unknown-mechanism
                        :selection/domain :discard
                        :selection/lifecycle :standard
                        :selection/selected #{}
                        :selection/validation :exact
                        :selection/player-id :player-1})))))


;; ============================================================
;; C. Type-specific required key enforcement
;; ============================================================

;; Zone-pick: :candidate-ids is :opt at mechanism level (ADR-030 phase 4)
(deftest test-graveyard-return-missing-candidate-ids-is-valid-at-mechanism-level
  (testing ":graveyard-return without :candidate-ids is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :graveyard-return)
                      :selection/candidate-ids)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :candidate-ids is spec-valid; executor enforces it"))))


;; Accumulator: :mana-allocation requires :selection/generic-remaining
(deftest test-mana-allocation-missing-generic-remaining-fails
  (testing ":mana-allocation without :selection/generic-remaining fails"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :mana-allocation)
                      :selection/generic-remaining)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


;; Accumulate: :copy-count is :opt at mechanism level (ADR-030 phase 4)
(deftest test-storm-split-missing-copy-count-is-valid-at-mechanism-level
  (testing ":storm-split without :copy-count is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :storm-split)
                      :selection/copy-count)]
      (is (s/valid? ::sel-spec/selection sel)
          ":accumulate without :copy-count is spec-valid; executor enforces it"))))


;; Tutor: :target-zone is :opt at mechanism level (ADR-030 phase 4)
(deftest test-tutor-missing-target-zone-is-valid-at-mechanism-level
  (testing ":tutor without :selection/target-zone is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :tutor)
                      :selection/target-zone)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :target-zone is spec-valid; executor enforces it"))))


;; Combat: :stack-item-eid is :opt at mechanism level (ADR-030 phase 4)
(deftest test-select-attackers-missing-stack-item-eid-is-valid-at-mechanism-level
  (testing ":select-attackers without :selection/stack-item-eid is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :select-attackers)
                      :selection/stack-item-eid)]
      (is (s/valid? ::sel-spec/selection sel)
          ":n-slot-targeting without :stack-item-eid is spec-valid; executor enforces it"))))


;; ============================================================
;; D. set-pending-selection helper
;; ============================================================

(deftest test-set-pending-selection-returns-app-db-with-selection
  (testing "set-pending-selection assocs :game/pending-selection onto app-db"
    (let [app-db {:game/db nil :other-key :val}
          sel (get sel-spec/minimal-valid-selections :discard)
          result (sel-spec/set-pending-selection app-db sel)
          pending (:game/pending-selection result)]
      ;; ADR-030: builders set mechanism+domain directly; stored selection equals input
      (is (= sel pending))
      (is (= :val (:other-key result))))))


(deftest test-set-pending-selection-is-pure
  (testing "set-pending-selection returns a new map without mutating input"
    (let [app-db {:game/db nil}
          sel (get sel-spec/minimal-valid-selections :tutor)
          result (sel-spec/set-pending-selection app-db sel)]
      (is (nil? (:game/pending-selection app-db)) "original should be unmodified")
      ;; ADR-030: stored selection equals input (builders set mechanism+domain directly)
      (is (= sel (:game/pending-selection result))))))


;; ============================================================
;; E. minimal-valid-selection accessor
;; ============================================================

(deftest test-minimal-valid-selection-returns-valid-map
  (testing "minimal-valid-selection returns a valid selection for each type"
    (doseq [[sel-type _] sel-spec/minimal-valid-selections]
      (let [minimal (sel-spec/minimal-valid-selection sel-type)]
        (is (s/valid? ::sel-spec/selection minimal)
            (str "Invalid minimal for type: " sel-type))))))


(deftest test-minimal-valid-selection-returns-nil-for-unknown
  (testing "minimal-valid-selection returns nil for unknown type"
    (is (nil? (sel-spec/minimal-valid-selection :no-such-type)))))


;; ============================================================
;; F. :selection/select-count enforcement (ADR-030 phase 4)
;;
;; After phase 4, :selection/select-count is :opt in :pick-from-zone mechanism.
;; Enforcement for zone-pick types (tutor, peek-and-select, pile-choice,
;; chain-bounce, chain-bounce-target) moved from spec to apply-domain-policy.
;;
;; :n-slot-targeting mechanism keeps :select-count in :req — tests
;; for select-attackers and assign-blockers are below unchanged.
;;
;; Zone-pick types: dissoc'ing :select-count must be spec-valid.
;; ============================================================

(deftest test-select-count-opt-at-mechanism-level-tutor
  (testing ":tutor without :selection/select-count is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :tutor)
                      :selection/select-count)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :select-count is spec-valid; executor enforces it"))))


(deftest test-select-count-opt-at-mechanism-level-peek-and-select
  (testing ":peek-and-select without :selection/select-count is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :peek-and-select)
                      :selection/select-count)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :select-count is spec-valid; executor enforces it"))))


(deftest test-select-count-opt-at-mechanism-level-pile-choice
  (testing ":pile-choice without :selection/select-count is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :pile-choice)
                      :selection/select-count)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :select-count is spec-valid; executor enforces it"))))


(deftest test-select-count-opt-at-mechanism-level-chain-bounce
  (testing ":chain-bounce without :selection/select-count is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :chain-bounce)
                      :selection/select-count)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :select-count is spec-valid; executor enforces it"))))


(deftest test-select-count-opt-at-mechanism-level-chain-bounce-target
  (testing ":chain-bounce-target without :selection/select-count is spec-valid at mechanism level (enforcement moved to executor)"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :chain-bounce-target)
                      :selection/select-count)]
      (is (s/valid? ::sel-spec/selection sel)
          ":pick-from-zone without :select-count is spec-valid; executor enforces it"))))


(deftest test-select-count-req-migration-select-attackers
  (testing ":select-attackers without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :select-attackers)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":select-attackers must require :selection/select-count after :opt -> :req migration"))))


(deftest test-select-count-req-migration-assign-blockers
  (testing ":assign-blockers without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :assign-blockers)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":assign-blockers must require :selection/select-count after :opt -> :req migration"))))
