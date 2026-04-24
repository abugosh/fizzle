(ns fizzle.events.selection.core-test
  "Tests for the selection mechanism in events/selection/core.cljs.
   Covers validate-selection (5 validation types + nil-reject + candidate membership)
   and auto-confirm behavior (data-driven via :selection/auto-confirm? flag).
   Also covers confirm-selection-impl validation absorption and deferred entry processing.
   Also covers ADR-030 mechanism dispatch integration tests (task 4, fizzle-xx4u)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.deep-analysis]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.engine.validation :as validation]
    [fizzle.events.casting :as casting]
    [fizzle.events.priority-flow]
    [fizzle.events.selection.core :as selection-core]
    ;; Side-effect: registers :cast-time-targeting and :targeting-to-mana-allocation defmethods
    [fizzle.events.selection.targeting]
    ;; Side-effect: registers execute-confirmed-selection defmethods for zone-ops domains
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; validate-selection tests
;; =====================================================

;; --- :exact validation ---

(deftest test-exact-validation-passes-when-count-equals-select-count
  (is (true? (validation/validate-selection
               {:selection/selected #{:a :b}
                :selection/select-count 2
                :selection/validation :exact}))))


(deftest test-exact-validation-fails-when-count-differs
  (is (false? (validation/validate-selection
                {:selection/selected #{:a}
                 :selection/select-count 2
                 :selection/validation :exact}))))


;; --- :at-most validation ---

(deftest test-at-most-validation-passes-when-count-under-limit
  (is (true? (validation/validate-selection
               {:selection/selected #{:a}
                :selection/select-count 3
                :selection/validation :at-most}))))


(deftest test-at-most-validation-passes-at-exact-limit
  (is (true? (validation/validate-selection
               {:selection/selected #{:a :b :c}
                :selection/select-count 3
                :selection/validation :at-most}))))


(deftest test-at-most-validation-fails-when-count-exceeds
  (is (false? (validation/validate-selection
                {:selection/selected #{:a :b :c :d}
                 :selection/select-count 3
                 :selection/validation :at-most}))))


;; --- :at-least-one validation ---

(deftest test-at-least-one-validation-passes-with-one
  (is (true? (validation/validate-selection
               {:selection/selected #{:a}
                :selection/validation :at-least-one}))))


(deftest test-at-least-one-validation-passes-with-multiple
  (is (true? (validation/validate-selection
               {:selection/selected #{:a :b :c}
                :selection/validation :at-least-one}))))


(deftest test-at-least-one-validation-fails-when-empty
  (is (false? (validation/validate-selection
                {:selection/selected #{}
                 :selection/validation :at-least-one}))))


;; --- :always validation ---

(deftest test-always-validation-passes-with-empty
  (is (true? (validation/validate-selection
               {:selection/selected #{}
                :selection/validation :always}))))


(deftest test-always-validation-passes-with-items
  (is (true? (validation/validate-selection
               {:selection/selected #{:a :b}
                :selection/validation :always}))))


;; --- :exact-or-zero validation ---

(deftest test-exact-or-zero-passes-with-zero
  (is (true? (validation/validate-selection
               {:selection/selected #{}
                :selection/select-count 2
                :selection/validation :exact-or-zero}))))


(deftest test-exact-or-zero-passes-with-exact-count
  (is (true? (validation/validate-selection
               {:selection/selected #{:a :b}
                :selection/select-count 2
                :selection/validation :exact-or-zero}))))


(deftest test-exact-or-zero-fails-with-partial
  (is (false? (validation/validate-selection
                {:selection/selected #{:a}
                 :selection/select-count 2
                 :selection/validation :exact-or-zero}))))


;; --- nil validation (safety default) ---

(deftest test-nil-validation-rejects
  (is (false? (validation/validate-selection
                {:selection/selected #{:a}}))))


;; --- candidate membership ---

(deftest test-candidate-membership-rejects-non-candidate
  (testing ":selection/candidates present - selected item not in candidates"
    (is (false? (validation/validate-selection
                  {:selection/selected #{:a :b}
                   :selection/select-count 2
                   :selection/validation :exact
                   :selection/candidates #{:a :c}})))))


(deftest test-candidate-membership-passes-when-all-in-candidates
  (testing ":selection/candidates present - all selected items are candidates"
    (is (true? (validation/validate-selection
                 {:selection/selected #{:a :c}
                  :selection/select-count 2
                  :selection/validation :exact
                  :selection/candidates #{:a :b :c}})))))


(deftest test-candidate-ids-membership-rejects-non-candidate
  (testing ":selection/candidate-ids present - selected item not in candidate-ids"
    (is (false? (validation/validate-selection
                  {:selection/selected #{:a :b}
                   :selection/select-count 2
                   :selection/validation :exact
                   :selection/candidate-ids #{:a :c}})))))


(deftest test-candidate-membership-skipped-when-no-candidates
  (testing "No candidates or candidate-ids - skip membership check"
    (is (true? (validation/validate-selection
                 {:selection/selected #{:a :b}
                  :selection/select-count 2
                  :selection/validation :exact})))))


;; =====================================================
;; confirm-selection-impl validation absorption tests
;; (ADR-019: validation moves from handler into impl)
;; =====================================================

;; Test domain policy: returns db unchanged — purely for routing tests.
;; The lifecycle tests in lifecycle_test.cljs cover routing; these
;; tests focus on the validation gate added to confirm-selection-impl.
(defmethod selection-core/apply-domain-policy :test-impl-validation
  [game-db _selection]
  {:db game-db})


;; Test chaining domain policy + chain builder: used to verify deferred entry
;; is NOT processed when chaining continues (chain not complete).
(defmethod selection-core/apply-domain-policy :test-chaining-for-deferred
  [game-db _selection]
  {:db game-db})


(defmethod selection-core/build-chain-selection :test-chaining-for-deferred
  [_db _selection]
  {:selection/type :test-impl-validation
   :selection/mechanism :pick-from-zone
   :selection/domain :test-impl-validation
   :selection/selected #{}
   :selection/validation :always
   :selection/auto-confirm? false
   :selection/player-id :player-1})


(deftest test-confirm-impl-returns-unchanged-on-invalid-count
  (testing "confirm-selection-impl returns app-db unchanged when count is invalid"
    (let [db (th/create-test-db)
          ;; Two items selected but select-count is 1 — exact validation fails
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{:a :b}
               :selection/select-count 1
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/confirm-selection-impl app-db)]
      (is (= app-db result)
          "confirm-selection-impl must return app-db unchanged on invalid count"))))


(deftest test-confirm-impl-returns-unchanged-on-non-candidate-selected
  (testing "confirm-selection-impl returns app-db unchanged when selected item not in candidates"
    (let [db (th/create-test-db)
          ;; :mountain not in candidates [:island :forest]
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{:mountain}
               :selection/candidates [:island :forest]
               :selection/select-count 1
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/confirm-selection-impl app-db)]
      (is (= app-db result)
          "confirm-selection-impl must return app-db unchanged when selected item not in candidates"))))


(deftest test-confirm-impl-regression-vector-candidates-value-membership
  (testing "regression: vector candidates use set coercion for membership, not contains?-on-vector"
    (let [db (th/create-test-db)
          ;; Use mode maps as candidates (vectors, like build-spell-mode-selection does)
          ;; contains? on a vector checks indices, not values — this is the shipped bug
          mode-a {:mode/id :mode-a :mode/name "Mode A"}
          mode-b {:mode/id :mode-b :mode/name "Mode B"}
          candidates [mode-a mode-b]
          ;; Select mode-a which IS in the vector — must pass membership check
          sel {:selection/type :test-impl-validation
               :selection/mechanism :pick-from-zone
               :selection/domain :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{mode-a}
               :selection/candidates candidates
               :selection/select-count 1
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/confirm-selection-impl app-db)]
      ;; Valid selection: should be processed (app-db CHANGED — pending-selection cleared)
      (is (nil? (:game/pending-selection result))
          "Valid selection with vector candidates must be processed — membership check must coerce to set"))))


(deftest test-confirm-impl-processes-deferred-entry-when-chain-complete
  (testing "confirm-selection-impl processes deferred entry when selection chain is complete"
    (let [db (th/create-test-db)
          ;; Valid finalized selection — no chaining, chain will be complete after confirm
          sel {:selection/type :test-impl-validation
               :selection/mechanism :pick-from-zone
               :selection/domain :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/validation :always}
          ;; Deferred entry of type :cast-spell (describe-cast-spell handles nil object-id gracefully)
          deferred {:type :cast-spell
                    :object-id nil
                    :event-type :fizzle.events.casting/cast-spell
                    :principal :player-1}
          app-db {:game/db db
                  :game/pending-selection sel
                  :history/deferred-entry deferred}
          result (selection-core/confirm-selection-impl app-db)]
      (is (nil? (:history/deferred-entry result))
          "Deferred entry must be consumed when chain is complete")
      (is (some? (:history/pending-entry result))
          "Pending entry must be produced from deferred entry"))))


(deftest test-confirm-impl-skips-deferred-entry-when-chaining-continues
  (testing "confirm-selection-impl does NOT process deferred entry when chaining continues"
    (let [db (th/create-test-db)
          ;; Chaining selection — confirm-selection-impl will set a new pending-selection
          sel {:selection/type :test-chaining-for-deferred
               :selection/mechanism :pick-from-zone
               :selection/domain :test-chaining-for-deferred
               :selection/lifecycle :chaining
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/validation :always}
          deferred {:type :cast-spell
                    :object-id nil
                    :event-type :fizzle.events.casting/cast-spell
                    :principal :player-1}
          app-db {:game/db db
                  :game/pending-selection sel
                  :history/deferred-entry deferred}
          result (selection-core/confirm-selection-impl app-db)]
      ;; Chain continues: new pending-selection was set
      (is (some? (:game/pending-selection result))
          "Chaining must produce a new pending-selection")
      ;; Deferred entry must NOT be processed yet — chain not complete
      (is (some? (:history/deferred-entry result))
          "Deferred entry must be preserved when chaining continues"))))


(deftest test-confirm-impl-valid-finalized-selection-works
  (testing "confirm-selection-impl processes valid finalized selection correctly"
    (let [db (th/create-test-db)
          sel {:selection/type :test-impl-validation
               :selection/mechanism :pick-from-zone
               :selection/domain :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{:a}
               :selection/select-count 1
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result))
          "Valid finalized selection must clear pending-selection"))))


;; =====================================================
;; toggle-selection-impl return type tests
;; (ADR-019: toggle-selection-impl returns {:app-db ... :auto-confirm? bool})
;; =====================================================

(deftest test-toggle-impl-auto-confirm-true-on-single-select
  (testing "toggle with select-count 1 and auto-confirm? true signals auto-confirm"
    (let [db (th/create-test-db)
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/candidates #{:a :b}
               :selection/select-count 1
               :selection/auto-confirm? true
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/toggle-selection-impl app-db :a)]
      (is (true? (:auto-confirm? result))
          "auto-confirm? must be true for single-select with auto-confirm? flag")
      (is (contains? (get-in (:app-db result) [:game/pending-selection :selection/selected]) :a)
          ":a must be in :selection/selected on returned app-db"))))


(deftest test-toggle-impl-auto-confirm-false-on-deselect
  (testing "deselecting an already-selected item does not signal auto-confirm"
    (let [db (th/create-test-db)
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{:a}
               :selection/candidates #{:a :b}
               :selection/select-count 1
               :selection/auto-confirm? true
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/toggle-selection-impl app-db :a)]
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when deselecting"))))


(deftest test-toggle-impl-auto-confirm-false-on-multi-select
  (testing "toggle with select-count 3 does not signal auto-confirm even after adding item"
    (let [db (th/create-test-db)
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/candidates #{:a :b :c}
               :selection/select-count 3
               :selection/auto-confirm? false
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/toggle-selection-impl app-db :a)]
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false for multi-select"))))


(deftest test-toggle-impl-auto-confirm-false-when-at-limit-rejected
  (testing "toggling a new item when at limit is rejected and does not signal auto-confirm"
    (let [db (th/create-test-db)
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{:a :b}
               :selection/candidates #{:a :b :c}
               :selection/select-count 2
               :selection/auto-confirm? false
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/toggle-selection-impl app-db :c)]
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when toggle is rejected at limit")
      (is (= app-db (:app-db result))
          "app-db must be unchanged when toggle is rejected"))))


(deftest test-toggle-impl-rejects-invalid-target
  (testing "toggling an id not in valid-targets does not change app-db and returns false auto-confirm"
    (let [db (th/create-test-db)
          sel {:selection/type :test-impl-validation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/valid-targets [:a :b]
               :selection/select-count 1
               :selection/auto-confirm? true
               :selection/validation :exact}
          app-db {:game/db db :game/pending-selection sel}
          result (selection-core/toggle-selection-impl app-db :c)]
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when target is rejected")
      (is (= app-db (:app-db result))
          "app-db must be unchanged when target is invalid"))))


;; =====================================================
;; :resolve-one-and-stop continuation deferred-entry ownership
;; (fizzle-f4gc: deferred-entry owned by confirm-selection-impl, not continuation)
;; =====================================================

(deftest test-resolve-one-and-stop-continuation-does-not-consume-deferred-entry
  (testing ":resolve-one-and-stop continuation does not consume :history/deferred-entry
           — deferred-entry processing belongs to confirm-selection-impl's terminal step"
    ;; Stack-empty case: resolve-one-and-stop is a no-op when stack is empty.
    ;; The continuation should leave :history/deferred-entry intact so that
    ;; confirm-selection-impl can process it as the single owner.
    (let [db (th/create-test-db)
          deferred {:type :cast-and-yield
                    :object-id nil
                    :pre-game-db db
                    :event-type :fizzle.events.priority-flow/cast-and-yield
                    :principal :player-1}
          app-db {:game/db db
                  :history/deferred-entry deferred}
          continuation {:continuation/type :resolve-one-and-stop}
          result (selection-core/apply-continuation continuation app-db)]
      (is (some? (get-in result [:app-db :history/deferred-entry]))
          ":resolve-one-and-stop must NOT consume :history/deferred-entry — that is confirm-selection-impl's responsibility"))))


;; =====================================================
;; apply-domain-policy multimethod scaffold (fizzle-snnw, ADR-030)
;; =====================================================

(deftest test-apply-domain-policy-is-a-multimethod
  (testing "apply-domain-policy is bound as a multimethod (MultiFn)"
    (is (instance? cljs.core/MultiFn selection-core/apply-domain-policy)
        "apply-domain-policy must be a defmulti (MultiFn instance)")))


(deftest test-apply-domain-policy-unknown-domain-throws-with-ex-info
  (testing ":default method throws ex-info for unknown :selection/domain"
    ;; Use a domain keyword that is genuinely unregistered (not in any defmethod)
    (let [sel {:selection/domain :nonexistent-domain-addzz9912
               :selection/type :discard
               :selection/selected #{}}]
      (is (thrown? js/Error
            (selection-core/apply-domain-policy nil sel))
          "apply-domain-policy must throw for unregistered domain")
      (let [caught (try
                     (selection-core/apply-domain-policy nil sel)
                     nil
                     (catch :default e e))]
        (is (= :nonexistent-domain-addzz9912 (:selection/domain (ex-data caught)))
            ":selection/domain must be present in ex-data")))))


(deftest test-apply-domain-policy-nil-domain-throws-with-ex-info
  (testing ":default method throws ex-info when :selection/domain is nil and type has no mapping"
    ;; A selection with no :selection/domain AND a :selection/type not in mechanism-domain
    ;; must hit :default and throw. Using a clearly fabricated type to avoid ambiguity.
    (let [sel {:selection/type :nonexistent-type-for-nil-domain-test
               :selection/selected #{}}]
      (is (thrown? js/Error
            (selection-core/apply-domain-policy nil sel))
          "apply-domain-policy must throw when :selection/domain is absent and type has no mapping"))))


;; =====================================================
;; chaining-path: nil :selection/source-type propagation guard (fizzle-jaz4)
;; =====================================================
;; Bug: when the parent selection has no :selection/source-type (e.g.
;; :cast-time-targeting which does not set it), chaining-path propagates
;; (get parent :selection/source-type) → nil, then assoc-s nil into the child.
;; The :mana-allocation spec declares :selection/source-type as :opt keyword? —
;; nil fails keyword?, so set-pending-selection logs a spec error.
;; Under *throw-on-spec-failure* true the assoc-nil site becomes assertable.
;;
;; Production path used:
;;   cast-spell-handler → :cast-time-targeting selection (chaining lifecycle)
;;   confirm-selection-impl → chaining-path → :mana-allocation selection
;;   set-pending-selection validates chained selection
;;
;; Real card: Deep Analysis ({3}{U} — targeting required + colorless generic cost)

(deftest test-chaining-path-does-not-propagate-nil-source-type
  (testing "chaining-path must not write nil :selection/source-type into child selection"
    ;; Deep Analysis has targeting (produces :cast-time-targeting) + colorless generic
    ;; mana ({3}{U}). After target selection, chains to :mana-allocation.
    ;; :cast-time-targeting does not set :selection/source-type.
    ;; Bug: chaining-path would assoc nil for source-type into the mana-allocation child.
    (let [db (th/add-opponent (th/create-test-db {:mana {:colorless 3 :blue 1}}))
          [db spell-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          ;; cast-spell-handler produces the :cast-time-targeting selection (chaining lifecycle)
          app-db-with-sel (casting/cast-spell-handler {:game/db db}
                                                      {:player-id :player-1
                                                       :object-id spell-id})
          targeting-sel (:game/pending-selection app-db-with-sel)]
      ;; Precondition: targeting sel was produced with :chaining lifecycle and no source-type
      (is (= :cast-time-targeting (:selection/type targeting-sel))
          "Precondition: cast-spell-handler must produce :cast-time-targeting selection")
      (is (= :chaining (:selection/lifecycle targeting-sel))
          "Precondition: lifecycle must be :chaining (Deep Analysis has generic cost)")
      (is (nil? (:selection/source-type targeting-sel))
          "Precondition: :cast-time-targeting must NOT have :selection/source-type set")
      ;; Select player-2 as target, then confirm — this triggers chaining-path
      ;; Under *throw-on-spec-failure* true, the buggy nil propagation throws here
      (binding [spec-util/*throw-on-spec-failure* true]
        (let [app-db' (update app-db-with-sel :game/pending-selection
                              assoc :selection/selected #{:player-2})
              after-confirm (selection-core/confirm-selection-impl app-db')
              mana-sel (:game/pending-selection after-confirm)]
          ;; Should have chained to :mana-allocation without spec error
          (is (= :mana-allocation (:selection/type mana-sel))
              "Must chain to :mana-allocation selection after targeting confirm")
          ;; Key assertion: source-type must be absent (not nil) in the chained selection
          (is (not (contains? mana-sel :selection/source-type))
              "chaining-path must NOT propagate nil :selection/source-type into child — key must be absent"))))))


;; =====================================================
;; ADR-030 Mechanism Dispatch Integration Tests (task 4, fizzle-xx4u)
;; =====================================================

(deftest test-mechanism-dispatch-routes-by-selection-mechanism
  (testing "execute-confirmed-selection dispatches on :selection/mechanism, not :selection/type (ADR-030)"
    (let [db (th/create-test-db)
          [db id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Selection has :selection/mechanism :pick-from-zone but a BOGUS :selection/type
          ;; (not in the mechanism-domain map). After task 4, dispatch uses :mechanism, so
          ;; it should still route to :pick-from-zone and call apply-domain-policy :discard.
          selection {:selection/mechanism :pick-from-zone
                     :selection/domain :discard
                     :selection/type :bogus-type-that-has-no-defmethod
                     :selection/selected #{id1}
                     :selection/target-zone :graveyard}
          result (selection-core/execute-confirmed-selection db selection)]
      (is (map? result) "execute-confirmed-selection must return a map")
      (is (contains? result :db) "result must contain :db key"))))


(deftest test-unknown-mechanism-hits-default
  (testing "Unknown :selection/mechanism hits :default on execute-confirmed-selection"
    (let [db (th/create-test-db)]
      (is (thrown? js/Error
            (selection-core/execute-confirmed-selection
              db
              {:selection/mechanism :nonexistent-mechanism-12345
               :selection/domain :discard
               :selection/selected #{}}))
          "Unknown mechanism must throw via :default"))))


(deftest test-discard-domain-policy-moves-to-graveyard
  (testing ":discard apply-domain-policy moves selected cards to graveyard (ADR-030 dispatch)"
    (let [db (th/create-test-db)
          [db id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Drive through execute-confirmed-selection with explicit mechanism+domain
          selection {:selection/mechanism :pick-from-zone
                     :selection/domain :discard
                     :selection/type :discard
                     :selection/selected #{id1}
                     :selection/target-zone :graveyard}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)]
      (is (= :graveyard (th/get-object-zone db-after id1))
          ":discard domain policy must move card to graveyard"))))
