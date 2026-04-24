(ns fizzle.events.selection.lifecycle-test
  "Tests for selection lifecycle metadata support in confirm-selection-impl.
   Covers :standard, :finalized, and :chaining lifecycles."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.core :as core]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test executor / domain defmethods (ADR-030)
;; =====================================================
;; These register apply-domain-policy entries for :selection/domain values used
;; ONLY by tests in this file. Each returns {:db db} — the lifecycle test verifies
;; that confirm-selection-impl routes correctly based on :selection/lifecycle.
;; execute-confirmed-selection now dispatches on :selection/mechanism; the domain
;; methods are invoked by the mechanism defmethod in core.cljs.

(defmethod core/apply-domain-policy :test-standard
  [game-db _selection]
  {:db game-db})


(defmethod core/apply-domain-policy :test-finalized
  [game-db _selection]
  {:db game-db})


(defmethod core/apply-domain-policy :test-chaining
  [game-db _selection]
  {:db game-db})


;; No-lifecycle executor: used to test that missing lifecycle defaults to :standard
(defmethod core/apply-domain-policy :test-no-lifecycle
  [game-db _selection]
  {:db game-db})


;; =====================================================
;; Test chain builder defmethods
;; =====================================================

(defmethod core/build-chain-selection :test-chaining
  [_db _selection]
  {:selection/type :test-standard
   :selection/mechanism :pick-from-zone
   :selection/domain :test-standard
   :selection/selected #{}
   :selection/validation :always
   :selection/auto-confirm? false
   :selection/player-id :player-1})


;; A chaining type whose chain builder returns nil (conditional chaining)
(defmethod core/apply-domain-policy :test-chaining-nil
  [game-db _selection]
  {:db game-db})


(defmethod core/build-chain-selection :test-chaining-nil
  [_db _selection]
  nil)


;; =====================================================
;; Helper: build minimal app-db for confirm-selection-impl
;; =====================================================

(defn- make-app-db
  "Build minimal app-db with game-db and a pending selection."
  [game-db selection]
  {:game/db game-db
   :game/pending-selection selection})


;; =====================================================
;; Standard lifecycle tests
;; =====================================================

(deftest test-standard-lifecycle-clears-selection
  (testing ":selection/lifecycle :standard dissocs pending-selection"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-standard
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-standard
                                  :selection/lifecycle :standard
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result))))))


(deftest test-standard-lifecycle-applies-continuation
  (testing ":standard lifecycle applies :selection/on-complete continuation"
    (let [db (th/create-test-db)
          ;; Register a test continuation that sets a marker on app-db
          _ (defmethod core/apply-continuation :test-marker
              [_continuation app-db]
              {:app-db (assoc app-db :test/marker true)})
          app-db (make-app-db db {:selection/type :test-standard
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-standard
                                  :selection/lifecycle :standard
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false
                                  :selection/on-complete {:continuation/type :test-marker}})
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result)))
      (is (true? (:test/marker result))))))


(deftest test-no-lifecycle-defaults-to-standard
  (testing "selection WITHOUT :selection/lifecycle defaults to :standard behavior"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-no-lifecycle
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-no-lifecycle
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result))))))


;; =====================================================
;; Finalized lifecycle tests
;; =====================================================

(deftest test-finalized-lifecycle-clears-selection
  (testing ":selection/lifecycle :finalized dissocs pending-selection"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-finalized
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-finalized
                                  :selection/lifecycle :finalized
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result))))))


(deftest test-finalized-lifecycle-clears-selected-card
  (testing ":finalized with :selection/clear-selected-card? true dissocs :game/selected-card"
    (let [db (th/create-test-db)
          app-db (assoc (make-app-db db {:selection/type :test-finalized
                                         :selection/mechanism :pick-from-zone
                                         :selection/domain :test-finalized
                                         :selection/lifecycle :finalized
                                         :selection/clear-selected-card? true
                                         :selection/player-id :player-1
                                         :selection/selected #{}
                                         :selection/validation :always
                                         :selection/auto-confirm? false})
                        :game/selected-card :some-card)
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result)))
      (is (nil? (:game/selected-card result))))))


(deftest test-finalized-lifecycle-without-clear-selected-card
  (testing ":finalized without :selection/clear-selected-card? preserves :game/selected-card"
    (let [db (th/create-test-db)
          app-db (assoc (make-app-db db {:selection/type :test-finalized
                                         :selection/mechanism :pick-from-zone
                                         :selection/domain :test-finalized
                                         :selection/lifecycle :finalized
                                         :selection/player-id :player-1
                                         :selection/selected #{}
                                         :selection/validation :always
                                         :selection/auto-confirm? false})
                        :game/selected-card :some-card)
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result)))
      (is (= :some-card (:game/selected-card result))))))


(deftest test-finalized-lifecycle-applies-continuation
  (testing ":finalized lifecycle applies :selection/on-complete continuation"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-finalized
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-finalized
                                  :selection/lifecycle :finalized
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false
                                  :selection/on-complete {:continuation/type :test-marker}})
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result)))
      (is (true? (:test/marker result))))))


;; =====================================================
;; Chaining lifecycle tests
;; =====================================================

(deftest test-chaining-lifecycle-chains-to-next-selection
  (testing ":selection/lifecycle :chaining sets next selection from build-chain-selection"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-chaining
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-chaining
                                  :selection/lifecycle :chaining
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      (is (some? (:game/pending-selection result)))
      (is (= :test-standard (:selection/domain (:game/pending-selection result)))))))


(deftest test-chaining-lifecycle-propagates-on-complete
  (testing ":chaining lifecycle propagates :selection/on-complete to chained selection"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-chaining
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-chaining
                                  :selection/lifecycle :chaining
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false
                                  :selection/on-complete {:continuation/type :test-marker}})
          result (core/confirm-selection-impl app-db)
          chained-sel (:game/pending-selection result)]
      (is (some? chained-sel))
      (is (= {:continuation/type :test-marker}
             (:selection/on-complete chained-sel))))))


(deftest test-chaining-lifecycle-nil-falls-through-to-standard
  (testing ":chaining with nil from build-chain-selection falls through to standard"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-chaining-nil
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-chaining-nil
                                  :selection/lifecycle :chaining
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      ;; nil chain => standard path => selection cleared
      (is (nil? (:game/pending-selection result))))))


;; =====================================================
;; build-chain-selection default returns nil
;; =====================================================

(deftest test-build-chain-selection-default-returns-nil
  (testing "build-chain-selection :default method returns nil"
    (let [db (th/create-test-db)]
      (is (nil? (core/build-chain-selection db {:selection/type :unknown-type}))))))


;; =====================================================
;; apply-continuation return shape tests (ADR-020)
;; =====================================================

(deftest test-apply-continuation-default-returns-map-shape
  (testing "apply-continuation :default returns {:app-db app-db} not raw app-db"
    (let [db (th/create-test-db)
          app-db {:game/db db}
          result (core/apply-continuation {:continuation/type :unknown-continuation-type} app-db)]
      (is (map? result))
      (is (contains? result :app-db))
      (is (= app-db (:app-db result)))
      (is (not (contains? result :then))))))


(deftest test-continuation-chain-drains-via-then
  (testing "confirm-selection-impl drains :then chain from apply-continuation"
    (let [db (th/create-test-db)
          ;; Register a chained continuation: :test-chain-a -> :test-chain-b -> done
          _ (defmethod core/apply-continuation :test-chain-a
              [_cont app-db]
              {:app-db (assoc app-db :test/chain-a-ran true)
               :then {:continuation/type :test-chain-b}})
          _ (defmethod core/apply-continuation :test-chain-b
              [_cont app-db]
              {:app-db (assoc app-db :test/chain-b-ran true)})
          app-db (make-app-db db {:selection/type :test-finalized
                                  :selection/mechanism :pick-from-zone
                                  :selection/domain :test-finalized
                                  :selection/lifecycle :finalized
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false
                                  :selection/on-complete {:continuation/type :test-chain-a}})
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result)))
      ;; Both continuations in the chain must have run
      (is (true? (:test/chain-a-ran result)))
      (is (true? (:test/chain-b-ran result))))))


;; =====================================================
;; HIGH corner case: chain selection on-complete propagates through 2+ steps
;; =====================================================
;; :chaining lifecycle copies :selection/on-complete to the chained selection.
;; When the chained selection is confirmed (with :standard lifecycle), the
;; continuation must execute. This verifies 2-step propagation:
;;   step 1: :test-chaining (lifecycle :chaining) → propagates on-complete → step 2
;;   step 2: :test-standard (lifecycle :standard) → applies on-complete continuation
;;
;; Bug caught: if chaining-path forgets to copy on-complete to the chained
;; selection, the continuation would be silently dropped after step 1,
;; and confirming step 2 would not fire it.

(deftest test-chaining-on-complete-propagates-through-2-steps
  (testing ":chaining lifecycle propagates on-complete through 2 confirm steps"
    ;; Bug caught: if :chaining path drops :selection/on-complete, the continuation
    ;; registered in step 1 would silently disappear. Step 2's confirm would return
    ;; without running :test-marker, and (:test/marker result) would be nil.
    (let [db (th/create-test-db)
          ;; Step 1: chaining selection with on-complete continuation
          app-db-step1 (make-app-db db {:selection/type :test-chaining
                                        :selection/mechanism :pick-from-zone
                                        :selection/domain :test-chaining
                                        :selection/lifecycle :chaining
                                        :selection/player-id :player-1
                                        :selection/selected #{}
                                        :selection/validation :always
                                        :selection/auto-confirm? false
                                        :selection/on-complete {:continuation/type :test-marker}})
          ;; Confirm step 1 — transitions to step 2 (:test-standard with on-complete)
          result-step1 (core/confirm-selection-impl app-db-step1)
          chained-sel (:game/pending-selection result-step1)
          ;; Step 2: confirm the chained selection (standard lifecycle applies continuation)
          app-db-step2 (assoc result-step1 :game/pending-selection chained-sel)
          result-step2 (core/confirm-selection-impl app-db-step2)]
      ;; After step 1: chained selection must exist and have on-complete
      (is (some? chained-sel)
          "Step 1: chaining must produce a new pending-selection")
      (is (= {:continuation/type :test-marker} (:selection/on-complete chained-sel))
          "Step 1: on-complete must be propagated to the chained selection")
      ;; After step 2: no pending-selection, continuation must have fired
      (is (nil? (:game/pending-selection result-step2))
          "Step 2: after confirming chained selection, pending-selection must be nil")
      (is (true? (:test/marker result-step2))
          "Step 2: :test-marker continuation must have fired after 2 confirm steps"))))
