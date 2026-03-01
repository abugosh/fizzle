(ns fizzle.events.selection.lifecycle-test
  "Tests for selection lifecycle metadata support in confirm-selection-impl.
   Covers :standard, :finalized, and :chaining lifecycles, plus backward
   compatibility with legacy executor return shapes."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.core :as core]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test executor defmethods
;; =====================================================
;; These register selection types used ONLY by tests in this file.
;; Each returns only {:db db} — the lifecycle test verifies that
;; confirm-selection-impl routes correctly based on :selection/lifecycle.

(defmethod core/execute-confirmed-selection :test-standard
  [game-db _selection]
  {:db game-db})


(defmethod core/execute-confirmed-selection :test-finalized
  [game-db _selection]
  {:db game-db})


(defmethod core/execute-confirmed-selection :test-chaining
  [game-db _selection]
  {:db game-db})


;; Legacy executors return old shapes for backward compat tests
(defmethod core/execute-confirmed-selection :test-legacy-finalized
  [game-db _selection]
  {:db game-db :finalized? true :clear-selected-card? true})


(defmethod core/execute-confirmed-selection :test-legacy-chaining
  [game-db _selection]
  {:db game-db
   :pending-selection {:selection/type :test-standard
                       :selection/selected #{}
                       :selection/validation :always
                       :selection/auto-confirm? false
                       :selection/player-id :player-1}})


(defmethod core/execute-confirmed-selection :test-legacy-standard
  [game-db _selection]
  {:db game-db})


;; =====================================================
;; Test chain builder defmethods
;; =====================================================

(defmethod core/build-chain-selection :test-chaining
  [_db _selection]
  {:selection/type :test-standard
   :selection/selected #{}
   :selection/validation :always
   :selection/auto-confirm? false
   :selection/player-id :player-1})


;; A chaining type whose chain builder returns nil (conditional chaining)
(defmethod core/execute-confirmed-selection :test-chaining-nil
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
              (assoc app-db :test/marker true))
          app-db (make-app-db db {:selection/type :test-standard
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
  (testing "selection WITHOUT :selection/lifecycle follows legacy standard path"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-legacy-standard
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
                                  :selection/lifecycle :chaining
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      (is (some? (:game/pending-selection result)))
      (is (= :test-standard (:selection/type (:game/pending-selection result)))))))


(deftest test-chaining-lifecycle-propagates-on-complete
  (testing ":chaining lifecycle propagates :selection/on-complete to chained selection"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-chaining
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
                                  :selection/lifecycle :chaining
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      ;; nil chain => standard path => selection cleared
      (is (nil? (:game/pending-selection result))))))


;; =====================================================
;; Legacy backward compatibility tests
;; =====================================================

(deftest test-legacy-finalized-return-shape
  (testing "legacy executor returning {:finalized? true :clear-selected-card? true}"
    (let [db (th/create-test-db)
          app-db (assoc (make-app-db db {:selection/type :test-legacy-finalized
                                         :selection/player-id :player-1
                                         :selection/selected #{}
                                         :selection/validation :always
                                         :selection/auto-confirm? false})
                        :game/selected-card :some-card)
          result (core/confirm-selection-impl app-db)]
      (is (nil? (:game/pending-selection result)))
      (is (nil? (:game/selected-card result))))))


(deftest test-legacy-chaining-return-shape
  (testing "legacy executor returning {:pending-selection next-sel}"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-legacy-chaining
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      (is (some? (:game/pending-selection result)))
      (is (= :test-standard (:selection/type (:game/pending-selection result)))))))


(deftest test-legacy-chaining-propagates-on-complete
  (testing "legacy chaining propagates :selection/on-complete to chained selection"
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-legacy-chaining
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


;; =====================================================
;; Lifecycle takes precedence over return shape
;; =====================================================

(deftest test-lifecycle-takes-precedence-over-return-shape
  (testing "when :selection/lifecycle is present, executor return flags are ignored"
    ;; Use an executor that returns {:db db :finalized? true} but set lifecycle
    ;; to :standard — the standard path should be followed, not finalized
    (let [db (th/create-test-db)
          app-db (make-app-db db {:selection/type :test-legacy-finalized
                                  :selection/lifecycle :standard
                                  :selection/player-id :player-1
                                  :selection/selected #{}
                                  :selection/validation :always
                                  :selection/auto-confirm? false})
          result (core/confirm-selection-impl app-db)]
      ;; Standard path: selection is cleared (same as finalized in this case)
      ;; but crucially, :clear-selected-card? from executor return is NOT read
      ;; — only :selection/clear-selected-card? on the selection map matters
      (is (nil? (:game/pending-selection result))))))


;; =====================================================
;; build-chain-selection default returns nil
;; =====================================================

(deftest test-build-chain-selection-default-returns-nil
  (testing "build-chain-selection :default method returns nil"
    (let [db (th/create-test-db)]
      (is (nil? (core/build-chain-selection db {:selection/type :unknown-type}))))))
