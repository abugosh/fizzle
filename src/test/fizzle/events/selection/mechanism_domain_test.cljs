(ns fizzle.events.selection.mechanism-domain-test
  "Tests for the :selection/mechanism and :selection/domain compat adapter.
   The adapter is inject-mechanism-domain, called at the top of set-pending-selection.
   Task fizzle-6y8i (epic fizzle-xjuz, ADR-030)."
  (:require
    [cljs.test :refer [deftest is testing]]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.events.selection.spec :as sel-spec]))


;; =====================================================
;; A. inject-mechanism-domain unit tests
;; Test each mechanism representative directly.
;; =====================================================

(deftest test-pick-from-zone-discard
  (testing ":discard maps to :pick-from-zone mechanism with :discard domain"
    (let [sel {:selection/type :discard
               :selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/validation :exact
               :selection/auto-confirm? false}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :pick-from-zone (:selection/mechanism result)))
      (is (= :discard (:selection/domain result))))))


(deftest test-reorder-scry
  (testing ":scry maps to :reorder mechanism with :scry domain"
    (let [sel {:selection/type :scry
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/validation :always
               :selection/auto-confirm? false
               :selection/cards [:obj-1]}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :reorder (:selection/mechanism result)))
      (is (= :scry (:selection/domain result))))))


(deftest test-accumulate-storm-split
  (testing ":storm-split maps to :accumulate mechanism with :storm-split domain"
    (let [sel {:selection/type :storm-split
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/validation :always
               :selection/auto-confirm? false
               :selection/copy-count 3
               :selection/valid-targets [:player-2 :player-1]
               :selection/allocation {:player-2 3 :player-1 0}}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :accumulate (:selection/mechanism result)))
      (is (= :storm-split (:selection/domain result))))))


(deftest test-allocate-resource-mana-allocation
  (testing ":mana-allocation maps to :allocate-resource mechanism with :mana-allocation domain"
    (let [sel {:selection/type :mana-allocation
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/validation :always
               :selection/auto-confirm? true
               :selection/generic-remaining 2
               :selection/generic-total 2
               :selection/allocation {}}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :allocate-resource (:selection/mechanism result)))
      (is (= :mana-allocation (:selection/domain result))))))


(deftest test-n-slot-targeting-cast-time-targeting
  (testing ":cast-time-targeting maps to :n-slot-targeting mechanism"
    (let [sel {:selection/type :cast-time-targeting
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/select-count 1
               :selection/valid-targets [:obj-1]
               :selection/validation :exact
               :selection/auto-confirm? true
               :selection/object-id :spell-id
               :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}
               :selection/target-requirement {:target/id :target :target/type :object}}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :n-slot-targeting (:selection/mechanism result)))
      (is (= :cast-time-targeting (:selection/domain result))))))


(deftest test-pick-mode-spell-mode
  (testing ":spell-mode maps to :pick-mode mechanism (new mechanism)"
    (let [sel {:selection/type :spell-mode
               :selection/lifecycle :finalized
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/select-count 1
               :selection/validation :exact
               :selection/auto-confirm? true
               :selection/object-id :spell-id
               :selection/candidates [{:mode/id :primary}]}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :pick-mode (:selection/mechanism result)))
      (is (= :spell-mode (:selection/domain result))))))


(deftest test-binary-choice-unless-pay
  (testing ":unless-pay maps to :binary-choice mechanism (new mechanism)"
    (let [sel {:selection/type :unless-pay
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/select-count 1
               :selection/valid-targets [:pay :decline]
               :selection/validation :exact
               :selection/auto-confirm? true}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (= :binary-choice (:selection/mechanism result)))
      (is (= :unless-pay (:selection/domain result))))))


(deftest test-unknown-type-passthrough
  (testing "unknown :selection/type passes through unchanged — no nil injection"
    (let [sel {:selection/type :not-a-real-type
               :selection/player-id :player-1
               :selection/selected #{}}
          result (sel-spec/inject-mechanism-domain sel)]
      ;; :selection/mechanism must be absent (not nil)
      (is (not (contains? result :selection/mechanism)))
      ;; :selection/domain must be absent (not nil)
      (is (not (contains? result :selection/domain))))))


(deftest test-missing-type-key-passthrough
  (testing "map without :selection/type key passes through unchanged — no exception, no enrichment"
    (let [sel {:selection/player-id :player-1
               :selection/selected #{}}
          result (sel-spec/inject-mechanism-domain sel)]
      (is (not (contains? result :selection/mechanism)))
      (is (not (contains? result :selection/domain))))))


;; =====================================================
;; B. End-to-end: set-pending-selection enriches selections
;; =====================================================

(deftest test-set-pending-selection-stores-selection-directly
  (testing "set-pending-selection stores selection as-is (builders set mechanism+domain directly)"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [discard-sel {:selection/type      :discard
                         :selection/mechanism :pick-from-zone
                         :selection/domain    :discard
                         :selection/lifecycle :standard
                         :selection/player-id :player-1
                         :selection/selected #{}
                         :selection/validation :exact
                         :selection/auto-confirm? false}
            app-db (sel-spec/set-pending-selection {} discard-sel)
            pending (get app-db :game/pending-selection)]
        (is (= :pick-from-zone (:selection/mechanism pending)))
        (is (= :discard (:selection/domain pending)))))))


(deftest test-set-pending-selection-spell-mode-stored-directly
  (testing "set-pending-selection stores :spell-mode with mechanism+domain (built by casting.cljs)"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [spell-mode-sel {:selection/type      :spell-mode
                            :selection/mechanism :pick-mode
                            :selection/domain    :spell-mode
                            :selection/lifecycle :finalized
                            :selection/player-id :player-1
                            :selection/selected #{}
                            :selection/select-count 1
                            :selection/validation :exact
                            :selection/auto-confirm? true
                            :selection/object-id :spell-id
                            :selection/candidates [{:mode/id :primary}]}
            app-db (sel-spec/set-pending-selection {} spell-mode-sel)
            pending (get app-db :game/pending-selection)]
        (is (= :pick-mode (:selection/mechanism pending)))
        (is (= :spell-mode (:selection/domain pending)))))))
