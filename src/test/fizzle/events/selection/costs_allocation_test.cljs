(ns fizzle.events.selection.costs-allocation-test
  "Tests for :mana-allocation selection type in events/selection/costs.cljs.
   Covers has-generic-mana-cost?, build-mana-allocation-selection,
   allocate/reset handlers, and execute-confirmed-selection :mana-allocation."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.costs :as costs]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; has-generic-mana-cost? tests
;; =====================================================

(deftest test-has-generic-cost-with-colorless
  (testing "Cost with :colorless > 0 has generic cost"
    (is (true? (costs/has-generic-mana-cost? {:colorless 2 :black 1})))))


(deftest test-has-generic-cost-without-colorless
  (testing "Cost without :colorless has no generic cost"
    (is (false? (costs/has-generic-mana-cost? {:black 2})))))


(deftest test-has-generic-cost-with-zero-colorless
  (testing "Cost with :colorless 0 has no generic cost"
    (is (false? (costs/has-generic-mana-cost? {:colorless 0 :black 1})))))


(deftest test-has-generic-cost-only-colorless
  (testing "Cost with only :colorless has generic cost"
    (is (true? (costs/has-generic-mana-cost? {:colorless 3})))))


;; =====================================================
;; build-mana-allocation-selection tests
;; =====================================================

(deftest test-build-allocation-selection-basic
  (testing "Builder creates selection with correct fields for mixed cost"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 5}))
          obj-id (random-uuid)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 2 :black 1}
                :mode/additional-costs []}
          sel (costs/build-mana-allocation-selection
                db :player-1 obj-id mode {:colorless 2 :black 1})]
      (is (= :mana-allocation (:selection/domain sel)))
      (is (= 2 (:selection/generic-remaining sel)))
      (is (= {} (:selection/allocation sel)))
      (is (= {:black 1} (:selection/colored-cost sel)))
      ;; remaining-pool: pool {:black 5} minus colored {:black 1} = {:black 4, ...}
      (is (= 4 (:black (:selection/remaining-pool sel))))
      (is (= :player-1 (:selection/player-id sel)))
      (is (= obj-id (:selection/spell-id sel)))
      (is (= mode (:selection/mode sel)))
      (is (true? (:selection/auto-confirm? sel))))))


(deftest test-build-allocation-selection-no-generic
  (testing "Builder returns nil when cost has no generic portion"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3}))
          obj-id (random-uuid)
          mode {:mode/id :primary :mode/mana-cost {:black 2}}
          sel (costs/build-mana-allocation-selection
                db :player-1 obj-id mode {:black 2})]
      (is (nil? sel)))))


(deftest test-build-allocation-selection-only-generic
  (testing "Builder handles cost with only generic (no colored)"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 2 :blue 2}))
          obj-id (random-uuid)
          mode {:mode/id :primary :mode/mana-cost {:colorless 3}}
          sel (costs/build-mana-allocation-selection
                db :player-1 obj-id mode {:colorless 3})]
      (is (= 3 (:selection/generic-remaining sel)))
      (is (= {} (:selection/colored-cost sel)))
      ;; remaining-pool should equal current pool (no colored deduction)
      (is (= 2 (:black (:selection/remaining-pool sel))))
      (is (= 2 (:blue (:selection/remaining-pool sel)))))))


(deftest test-build-allocation-selection-multicolor-cost
  (testing "Builder handles multi-colored cost with generic"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3 :blue 2}))
          obj-id (random-uuid)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 1 :black 1 :blue 1}}
          sel (costs/build-mana-allocation-selection
                db :player-1 obj-id mode {:colorless 1 :black 1 :blue 1})]
      (is (= 1 (:selection/generic-remaining sel)))
      (is (= {:black 1 :blue 1} (:selection/colored-cost sel)))
      ;; remaining-pool: {:black 3-1=2, :blue 2-1=1, ...}
      (is (= 2 (:black (:selection/remaining-pool sel))))
      (is (= 1 (:blue (:selection/remaining-pool sel)))))))


;; =====================================================
;; allocate-mana-color handler tests
;; =====================================================

(deftest test-allocate-mana-color-decrements
  (testing "Allocating a color increments allocation and decrements remaining"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool {:black 3 :blue 0 :white 0
                                              :red 0 :green 0 :colorless 0}}}
          result (costs/allocate-mana-color-impl app-db :black)]
      (is (= 1 (get-in result [:game/pending-selection :selection/generic-remaining])))
      (is (= {:black 1} (get-in result [:game/pending-selection :selection/allocation])))
      (is (= 2 (get-in result [:game/pending-selection :selection/remaining-pool :black]))))))


(deftest test-allocate-mana-color-zero-remaining-noop
  (testing "Allocating from color with 0 remaining is a no-op"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool {:black 0 :blue 3 :white 0
                                              :red 0 :green 0 :colorless 0}}}
          result (costs/allocate-mana-color-impl app-db :black)]
      (is (= 2 (get-in result [:game/pending-selection :selection/generic-remaining])))
      (is (= {} (get-in result [:game/pending-selection :selection/allocation]))))))


(deftest test-allocate-mana-color-zero-generic-noop
  (testing "Allocating when generic-remaining is 0 is a no-op"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 0
                   :selection/generic-total 2
                   :selection/allocation {:black 2}
                   :selection/remaining-pool {:black 1 :blue 0 :white 0
                                              :red 0 :green 0 :colorless 0}}}
          result (costs/allocate-mana-color-impl app-db :black)]
      (is (= 0 (get-in result [:game/pending-selection :selection/generic-remaining])))
      (is (= {:black 2} (get-in result [:game/pending-selection :selection/allocation]))))))


;; =====================================================
;; reset-mana-allocation handler tests
;; =====================================================

(deftest test-reset-mana-allocation
  (testing "Reset restores allocation and remaining to initial state"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 0
                   :selection/generic-total 3
                   :selection/allocation {:black 2 :blue 1}
                   :selection/remaining-pool {:black 0 :blue 1 :white 0
                                              :red 0 :green 0 :colorless 0}
                   :selection/original-remaining-pool {:black 2 :blue 2 :white 0
                                                       :red 0 :green 0 :colorless 0}
                   :selection/player-id :player-1
                   :selection/spell-id :some-id
                   :selection/mode {:mode/id :primary}}}
          result (costs/reset-mana-allocation-impl app-db)]
      (is (= {} (get-in result [:game/pending-selection :selection/allocation])))
      (is (= 3 (get-in result [:game/pending-selection :selection/generic-remaining])))
      (is (= {:black 2 :blue 2 :white 0 :red 0 :green 0 :colorless 0}
             (get-in result [:game/pending-selection :selection/remaining-pool]))))))


(deftest test-reset-preserves-other-selection-fields
  (testing "Reset preserves player-id, spell-id, mode"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 1
                   :selection/generic-total 3
                   :selection/allocation {:black 2}
                   :selection/remaining-pool {:black 0 :blue 0 :white 0
                                              :red 0 :green 0 :colorless 0}
                   :selection/original-remaining-pool {:black 2 :blue 0 :white 0
                                                       :red 0 :green 0 :colorless 0}
                   :selection/player-id :player-1
                   :selection/spell-id :some-spell
                   :selection/mode {:mode/id :kicked}}}
          result (costs/reset-mana-allocation-impl app-db)]
      (is (= :player-1 (get-in result [:game/pending-selection :selection/player-id])))
      (is (= :some-spell (get-in result [:game/pending-selection :selection/spell-id])))
      (is (= {:mode/id :kicked} (get-in result [:game/pending-selection :selection/mode]))))))


;; =====================================================
;; execute-confirmed-selection :mana-allocation tests
;; =====================================================

(deftest test-confirm-mana-allocation-pays-correctly
  (testing "Confirm handler deducts colored + allocated mana from pool"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3 :blue 2}))
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 2 :blue 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          selection (-> (costs/build-mana-allocation-selection
                          db' :player-1 obj-id mode {:colorless 2 :blue 1})
                        (assoc :selection/generic-remaining 0
                               :selection/allocation {:black 2}))
          result (core/execute-confirmed-selection db' selection)
          pool (q/get-mana-pool (:db result) :player-1)]
      (is (some? (:db result)))
      ;; Pool was {:black 3 :blue 2}. Colored {:blue 1} + allocation {:black 2}
      (is (= 1 (:black pool)))
      (is (= 1 (:blue pool))))))


(deftest test-confirm-mana-allocation-moves-to-stack
  (testing "After confirm, spell is on the stack"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3}))
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 1 :black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          selection (-> (costs/build-mana-allocation-selection
                          db' :player-1 obj-id mode {:colorless 1 :black 1})
                        (assoc :selection/generic-remaining 0
                               :selection/allocation {:black 1}))
          result (core/execute-confirmed-selection db' selection)
          obj (q/get-object (:db result) obj-id)]
      (is (= :stack (:object/zone obj))))))


(deftest test-confirm-mana-allocation-increments-storm
  (testing "After confirm, storm count is incremented"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3}))
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          initial-storm (q/get-storm-count db' :player-1)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 1 :black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          selection (-> (costs/build-mana-allocation-selection
                          db' :player-1 obj-id mode {:colorless 1 :black 1})
                        (assoc :selection/generic-remaining 0
                               :selection/allocation {:black 1}))
          result (core/execute-confirmed-selection db' selection)]
      (is (= (inc initial-storm) (q/get-storm-count (:db result) :player-1))))))


;; =====================================================
;; Contract guard tests (fizzle-hxbe)
;; =====================================================

(deftest test-allocate-mana-color-throws-on-missing-generic-remaining
  (testing "Missing :selection/generic-remaining key throws ex-info"
    (let [app-db {:game/pending-selection
                  {:selection/generic-total 2
                   ;; :selection/generic-remaining deliberately absent
                   :selection/allocation {}
                   :selection/remaining-pool {:black 3 :blue 0 :white 0
                                              :red 0 :green 0 :colorless 0}}}]
      (is (thrown-with-msg? js/Error
                            #"generic-remaining missing"
            (costs/allocate-mana-color-impl app-db :black))))))


(deftest test-allocate-mana-color-throws-on-nil-generic-remaining
  (testing "Nil :selection/generic-remaining key throws ex-info"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining nil
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool {:black 3 :blue 0 :white 0
                                              :red 0 :green 0 :colorless 0}}}]
      (is (thrown-with-msg? js/Error
                            #"generic-remaining missing"
            (costs/allocate-mana-color-impl app-db :black))))))


(deftest test-allocate-mana-color-throws-on-missing-remaining-pool
  (testing "Missing :selection/remaining-pool key throws ex-info"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   ;; :selection/remaining-pool deliberately absent
                   }}]
      (is (thrown-with-msg? js/Error
                            #"remaining-pool missing"
            (costs/allocate-mana-color-impl app-db :black))))))


(deftest test-allocate-mana-color-throws-on-nil-remaining-pool
  (testing "Nil :selection/remaining-pool key throws ex-info"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool nil}}]
      (is (thrown-with-msg? js/Error
                            #"remaining-pool missing"
            (costs/allocate-mana-color-impl app-db :black))))))


(deftest test-allocate-mana-color-zero-generic-remaining-is-noop
  (testing "generic-remaining=0 (at-limit) is a legitimate no-op — does NOT throw"
    ;; This tests that the contains? guard passes for key-present-with-value-0.
    ;; The (zero? remaining) arm handles the no-op itself.
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 0
                   :selection/generic-total 2
                   :selection/allocation {:black 2}
                   :selection/remaining-pool {:black 0 :blue 1 :white 0
                                              :red 0 :green 0 :colorless 0}}}
          result (costs/allocate-mana-color-impl app-db :blue)]
      (is (= app-db result)))))


(deftest test-allocate-mana-color-missing-color-in-pool-is-noop
  (testing "Color absent from pool (per-color missing) is a legitimate no-op — does NOT throw"
    ;; pool has only :black; clicking :red uses (get pool :red 0) = 0 → no-op
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool {:black 2}}}
          result (costs/allocate-mana-color-impl app-db :red)]
      (is (= app-db result)))))


(deftest test-allocate-mana-color-zero-color-in-pool-is-noop
  (testing "Color with value 0 in pool is a legitimate no-op — does NOT throw"
    ;; pool has {:black 0 :blue 1}; clicking :black uses (get pool :black 0) = 0 → no-op
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool {:black 0 :blue 1}}}
          result (costs/allocate-mana-color-impl app-db :black)]
      (is (= app-db result)))))


(deftest test-allocate-mana-color-ex-data-includes-effect-type
  (testing "Thrown ex-info has :effect-type :mana-allocation in ex-data"
    (let [app-db {:game/pending-selection
                  {:selection/allocation {}}}]
      ;; missing both keys to trigger first guard
      (try
        (costs/allocate-mana-color-impl app-db :black)
        (is false "Expected exception was not thrown")
        (catch js/Error e
          (is (= :mana-allocation (:effect-type (ex-data e)))))))))


;; =====================================================
;; Edge case tests
;; =====================================================

(deftest test-allocate-colorless-mana-from-pool
  (testing "Can allocate actual colorless mana from pool toward generic"
    (let [app-db {:game/pending-selection
                  {:selection/generic-remaining 2
                   :selection/generic-total 2
                   :selection/allocation {}
                   :selection/remaining-pool {:black 1 :colorless 2 :white 0
                                              :blue 0 :red 0 :green 0}}}
          result (costs/allocate-mana-color-impl app-db :colorless)]
      (is (= 1 (get-in result [:game/pending-selection :selection/generic-remaining])))
      (is (= {:colorless 1} (get-in result [:game/pending-selection :selection/allocation])))
      (is (= 1 (get-in result [:game/pending-selection :selection/remaining-pool :colorless]))))))


(deftest test-build-allocation-with-zero-pool-colors
  (testing "Builder preserves zero-pool colors for view dimming"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:blue 3}))
          obj-id (random-uuid)
          mode {:mode/id :primary :mode/mana-cost {:colorless 2}}
          sel (costs/build-mana-allocation-selection
                db :player-1 obj-id mode {:colorless 2})]
      ;; :black should be 0 in remaining-pool (not absent)
      (is (= 0 (:black (:selection/remaining-pool sel))))
      (is (= 3 (:blue (:selection/remaining-pool sel)))))))


;; =====================================================
;; Auto-confirm integration test
;; =====================================================

(deftest test-allocate-auto-confirms-when-generic-zero
  (testing "Allocating last mana triggers auto-confirm, casting the spell"
    (let [db (-> (th/create-test-db)
                 (mana/add-mana :player-1 {:black 3}))
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 1 :black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          sel (costs/build-mana-allocation-selection
                db' :player-1 obj-id mode {:colorless 1 :black 1})
          app-db {:game/db db'
                  :game/pending-selection sel}
          ;; Allocate :black for the 1 generic — should auto-confirm
          result (costs/allocate-mana-color-impl app-db :black)]
      ;; After auto-confirm, pending-selection should be cleared
      (is (nil? (:game/pending-selection result)))
      ;; Spell should be on stack
      (let [obj (q/get-object (:game/db result) obj-id)]
        (is (= :stack (:object/zone obj))))
      ;; Storm count should be incremented
      (is (= 1 (q/get-storm-count (:game/db result) :player-1))))))
