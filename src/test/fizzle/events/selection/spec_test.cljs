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

(deftest test-missing-lifecycle-fails
  (testing "selection without :selection/lifecycle fails"
    (let [sel (-> (get sel-spec/minimal-valid-selections :discard)
                  (dissoc :selection/lifecycle))]
      (is (not (s/valid? ::sel-spec/selection sel))))))


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


(deftest test-unknown-type-fails
  (testing "selection with :selection/type not in any defmethod fails"
    (is (not (s/valid? ::sel-spec/selection
                       {:selection/type :completely-unknown-type
                        :selection/lifecycle :standard
                        :selection/selected #{}
                        :selection/validation :exact
                        :selection/player-id :player-1})))))


;; ============================================================
;; C. Type-specific required key enforcement
;; ============================================================

;; Zone-pick: :graveyard-return requires :selection/candidate-ids
(deftest test-graveyard-return-missing-candidate-ids-fails
  (testing ":graveyard-return without :selection/candidate-ids fails"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :graveyard-return)
                      :selection/candidate-ids)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


;; Accumulator: :mana-allocation requires :selection/generic-remaining
(deftest test-mana-allocation-missing-generic-remaining-fails
  (testing ":mana-allocation without :selection/generic-remaining fails"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :mana-allocation)
                      :selection/generic-remaining)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


;; Storm-split: :storm-split requires :selection/copy-count
(deftest test-storm-split-missing-copy-count-fails
  (testing ":storm-split without :selection/copy-count fails"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :storm-split)
                      :selection/copy-count)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


;; Tutor: :tutor requires :selection/target-zone
(deftest test-tutor-missing-target-zone-fails
  (testing ":tutor without :selection/target-zone fails"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :tutor)
                      :selection/target-zone)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


;; Combat: :select-attackers requires :selection/stack-item-eid
(deftest test-select-attackers-missing-stack-item-eid-fails
  (testing ":select-attackers without :selection/stack-item-eid fails"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :select-attackers)
                      :selection/stack-item-eid)]
      (is (not (s/valid? ::sel-spec/selection sel))))))


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
;; F. :selection/select-count :req migration (epic fizzle-75qq, task fizzle-3hym)
;;
;; These 7 types have builders that ALWAYS set :selection/select-count.
;; After migration from :opt to :req, dissoc'ing the key must fail spec.
;;
;; 3 zone-pick types (:discard, :graveyard-return, :shuffle-from-graveyard-to-library)
;; are SKIPPED: their zone-pick builder uses (or :effect/count :effect/select-count)
;; which CAN be nil. Migrating would cause false-positive spec failures for legitimate
;; effects without a count. Documented in task fizzle-3hym close-note.
;; ============================================================

(deftest test-select-count-req-migration-tutor
  (testing ":tutor without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :tutor)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":tutor must require :selection/select-count after :opt -> :req migration"))))


(deftest test-select-count-req-migration-peek-and-select
  (testing ":peek-and-select without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :peek-and-select)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":peek-and-select must require :selection/select-count after :opt -> :req migration"))))


(deftest test-select-count-req-migration-pile-choice
  (testing ":pile-choice without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :pile-choice)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":pile-choice must require :selection/select-count after :opt -> :req migration"))))


(deftest test-select-count-req-migration-chain-bounce
  (testing ":chain-bounce without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :chain-bounce)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":chain-bounce must require :selection/select-count after :opt -> :req migration"))))


(deftest test-select-count-req-migration-chain-bounce-target
  (testing ":chain-bounce-target without :selection/select-count fails spec after :req migration"
    (let [sel (dissoc (get sel-spec/minimal-valid-selections :chain-bounce-target)
                      :selection/select-count)]
      (is (not (s/valid? ::sel-spec/selection sel))
          ":chain-bounce-target must require :selection/select-count after :opt -> :req migration"))))


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
