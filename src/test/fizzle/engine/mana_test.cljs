(ns fizzle.engine.mana-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]))


;; === add-mana tests ===

(deftest add-mana-adds-black-mana-test
  (testing "add-mana adds black mana to empty pool"
    (let [db (init-game-state)
          db' (mana/add-mana db :player-1 {:black 3})]
      (is (= {:white 0 :blue 0 :black 3 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db' :player-1))))))


(deftest add-mana-accumulates-test
  (testing "add-mana accumulates with existing mana"
    (let [db (init-game-state)
          db' (-> db
                  (mana/add-mana :player-1 {:black 2})
                  (mana/add-mana :player-1 {:black 1}))]
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))


(deftest add-mana-multiple-colors-test
  (testing "add-mana handles multiple colors at once"
    (let [db (init-game-state)
          db' (mana/add-mana db :player-1 {:black 2 :blue 1})]
      (is (= 2 (:black (q/get-mana-pool db' :player-1))))
      (is (= 1 (:blue (q/get-mana-pool db' :player-1)))))))


(deftest add-mana-empty-map-test
  (testing "add-mana with empty map returns unchanged db"
    (let [db (init-game-state)
          db' (mana/add-mana db :player-1 {})]
      (is (= (q/get-mana-pool db :player-1)
             (q/get-mana-pool db' :player-1))))))


;; === can-pay? tests ===

(deftest can-pay-empty-pool-test
  (testing "can-pay? returns false with empty pool"
    (let [db (init-game-state)]
      (is (false? (mana/can-pay? db :player-1 {:black 1}))))))


(deftest can-pay-exact-mana-test
  (testing "can-pay? returns true with exact mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))]
      (is (true? (mana/can-pay? db :player-1 {:black 1}))))))


(deftest can-pay-excess-mana-test
  (testing "can-pay? returns true with excess mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))]
      (is (true? (mana/can-pay? db :player-1 {:black 1}))))))


(deftest can-pay-wrong-color-test
  (testing "can-pay? returns false with wrong color mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:blue 5}))]
      (is (false? (mana/can-pay? db :player-1 {:black 1}))))))


(deftest can-pay-empty-cost-test
  (testing "can-pay? returns true for empty cost (free spell)"
    (let [db (init-game-state)]
      (is (true? (mana/can-pay? db :player-1 {}))))))


(deftest can-pay-zero-amount-test
  (testing "can-pay? returns true for zero amount cost"
    (let [db (init-game-state)]
      (is (true? (mana/can-pay? db :player-1 {:black 0}))))))


;; === can-pay? generic mana tests ===

(deftest can-pay-generic-with-colored-mana-test
  (testing "can-pay? with colorless cost payable by colored mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))]
      (is (true? (mana/can-pay? db :player-1 {:colorless 2}))))))


(deftest can-pay-generic-plus-colored-test
  (testing "can-pay? with mixed generic and colored cost"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))]
      (is (true? (mana/can-pay? db :player-1 {:colorless 1 :black 1}))))))


(deftest can-pay-generic-insufficient-total-test
  (testing "can-pay? returns false when total mana < colored + generic"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))]
      (is (false? (mana/can-pay? db :player-1 {:colorless 1 :black 1}))))))


(deftest can-pay-generic-enough-total-but-wrong-color-test
  (testing "can-pay? returns false when colored requirement not met even with generic surplus"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:blue 5}))]
      (is (false? (mana/can-pay? db :player-1 {:colorless 1 :black 1}))))))


(deftest can-pay-generic-multicolor-pool-test
  (testing "can-pay? generic cost uses total across all colors"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1 :blue 1}))]
      (is (true? (mana/can-pay? db :player-1 {:colorless 1 :black 1}))))))


;; === pay-mana tests ===

(deftest pay-mana-removes-mana-test
  (testing "pay-mana removes mana from pool"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))
          db' (mana/pay-mana db :player-1 {:black 1})]
      (is (= 2 (:black (q/get-mana-pool db' :player-1)))))))


(deftest pay-mana-exact-payment-test
  (testing "pay-mana handles exact payment (pool goes to zero)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          db' (mana/pay-mana db :player-1 {:black 1})]
      (is (= 0 (:black (q/get-mana-pool db' :player-1)))))))


(deftest pay-mana-empty-cost-test
  (testing "pay-mana with empty cost returns unchanged pool"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))
          db' (mana/pay-mana db :player-1 {})]
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))


;; === pay-mana generic mana tests ===

(deftest pay-mana-generic-from-single-color-test
  (testing "pay-mana deducts generic cost from available colored mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))
          db' (mana/pay-mana db :player-1 {:colorless 2})]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))))))


(deftest pay-mana-generic-from-largest-pool-test
  (testing "pay-mana deducts generic cost from largest pool first"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 4 :blue 1}))
          db' (mana/pay-mana db :player-1 {:colorless 1})]
      ;; Should take from black (larger pool)
      (is (= 3 (:black (q/get-mana-pool db' :player-1))))
      (is (= 1 (:blue (q/get-mana-pool db' :player-1)))))))


(deftest pay-mana-mixed-generic-and-colored-test
  (testing "pay-mana handles colored + generic correctly"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 4 :blue 2}))
          ;; Cabal Ritual cost: {1}{B}
          db' (mana/pay-mana db :player-1 {:colorless 1 :black 1})]
      ;; 1B from colored, 1 generic from black (largest remaining)
      (is (= 2 (:black (q/get-mana-pool db' :player-1))))
      (is (= 2 (:blue (q/get-mana-pool db' :player-1)))))))


(deftest pay-mana-generic-spans-multiple-colors-test
  (testing "pay-mana generic cost can span multiple color pools"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1 :blue 1}))
          db' (mana/pay-mana db :player-1 {:colorless 2})]
      (is (= 0 (:black (q/get-mana-pool db' :player-1))))
      (is (= 0 (:blue (q/get-mana-pool db' :player-1)))))))


;; === Corner case tests ===

(deftest test-mana-pool-large-values
  (testing "mana pool handles large values without overflow"
    ;; Corner case: very large mana pool values.
    ;; Bug it catches: integer overflow in ClojureScript (JS numbers are 64-bit floats,
    ;; but large integers can lose precision above 2^53).
    ;; 1,000,000 is well within safe range. Tests that basic operations work.
    (let [db (init-game-state)
          db' (mana/add-mana db :player-1 {:black 1000000})]
      (is (= 1000000 (:black (q/get-mana-pool db' :player-1)))
          "Large mana values should be stored correctly"))))


(deftest test-pay-mana-without-can-pay
  (testing "pay-mana when pool is insufficient: documents caller-responsibility contract"
    ;; Corner case: calling pay-mana without checking can-pay? first.
    ;; Current behavior: pool goes negative (no validation in pay-mana).
    ;; This is documented in the docstring as caller responsibility.
    ;; Bug it catches: documents behavior so callers know to use can-pay?.
    (let [db (init-game-state)
          initial-pool (q/get-mana-pool db :player-1)]
      ;; Precondition: pool starts at 0 for all colors
      (is (= 0 (:black initial-pool))
          "Precondition: black mana should be 0")
      (is (false? (mana/can-pay? db :player-1 {:black 3}))
          "can-pay? correctly returns false for insufficient mana")
      ;; Call pay-mana without having the mana (violates contract)
      (let [db' (mana/pay-mana db :player-1 {:black 3})
            final-pool (q/get-mana-pool db' :player-1)]
        ;; Document current behavior: pool goes negative
        (is (= -3 (:black final-pool))
            "Current behavior: pay-mana allows negative pool (no internal validation)")
        ;; Other colors should be unaffected
        (is (= (:blue initial-pool) (:blue final-pool))
            "Other colors should be unchanged by pay-mana contract violation")))))


;; === X cost tests ===

(deftest can-pay-x-cost-always-true-test
  ;; X can always be 0, so any X cost is payable (for the X portion)
  ;; Bug it catches: X costs rejected when they should always be valid
  (testing "can-pay? returns true for X cost even with empty pool"
    (let [db (init-game-state)]
      ;; {:x 1 :blue 1} requires 1 blue, but X portion is always payable
      ;; With empty pool, should return false (can't pay blue)
      (is (false? (mana/can-pay? db :player-1 {:x 1 :blue 1}))
          "Still need to pay colored portion")))
  (testing "can-pay? returns true when colored portion is met"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:blue 1}))]
      ;; Has 1 blue, X can be 0, so this is payable
      (is (true? (mana/can-pay? db :player-1 {:x 1 :blue 1}))
          "X portion ignored, colored portion met"))))


(deftest pay-mana-x-value-zero-test
  ;; When X=0, only pay the non-X portion
  ;; Bug it catches: X=0 not handled correctly, pays wrong amount
  (testing "pay-mana with x-value=0 pays only fixed costs"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:blue 2}))
          ;; Cost: X + 1 colorless + 1 blue, with X=0 -> just 1 colorless + 1 blue
          db' (mana/pay-mana db :player-1 {:x 1 :colorless 1 :blue 1} 0)]
      ;; Should pay 1 colorless (from blue) + 1 blue = 2 blue total
      (is (= 0 (:blue (q/get-mana-pool db' :player-1)))
          "Paid 2 mana (1 colorless from blue, 1 blue)"))))


(deftest pay-mana-x-value-converts-to-colorless-test
  ;; {:x 1 :colorless 1 :blue 1} with x-value=3 -> {:colorless 4 :blue 1}
  ;; Bug it catches: X not converted to colorless correctly
  (testing "pay-mana resolves X to colorless mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 5 :blue 1}))
          ;; Cost: X(3) + 1 colorless + 1 blue = 4 colorless + 1 blue
          db' (mana/pay-mana db :player-1 {:x 1 :colorless 1 :blue 1} 3)]
      ;; Should pay 4 generic from black, 1 from blue
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))
          "4 black spent on generic (X=3 + 1 colorless)")
      (is (= 0 (:blue (q/get-mana-pool db' :player-1)))
          "1 blue spent on blue requirement"))))


(deftest pay-mana-x-value-nil-defaults-zero-test
  ;; Backwards compatibility: omitting x-value treats X as 0
  ;; Bug it catches: nil x-value causes error or wrong behavior
  (testing "pay-mana without x-value treats X as 0"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:blue 2}))
          ;; No x-value arg provided - should default to 0
          db' (mana/pay-mana db :player-1 {:x 1 :blue 1})]
      ;; X=0, so just pay 1 blue
      (is (= 1 (:blue (q/get-mana-pool db' :player-1)))
          "Only paid 1 blue (X defaulted to 0)"))))


(deftest pay-mana-no-x-key-unchanged-test
  ;; Backwards compatibility: costs without :x work exactly as before
  ;; Bug it catches: adding X support breaks existing non-X costs
  (testing "pay-mana without :x key works unchanged (backwards compat)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3 :blue 1}))
          ;; Standard cost with no X - should work exactly as before
          db' (mana/pay-mana db :player-1 {:colorless 1 :black 1})]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))
          "1 black for colored, 1 black for generic")
      (is (= 1 (:blue (q/get-mana-pool db' :player-1)))
          "Blue untouched"))))


(deftest pay-mana-multiple-x-multiplies-test
  ;; Future-proofing: {:x 2} means "2X" (e.g., XX cost like Blaze)
  ;; Bug it catches: multiple X not multiplied correctly
  (testing "pay-mana with {:x 2} multiplies x-value by 2"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 6}))
          ;; XX cost where X=3 -> 6 colorless total
          db' (mana/pay-mana db :player-1 {:x 2} 3)]
      (is (= 0 (:black (q/get-mana-pool db' :player-1)))
          "Paid 6 mana (2 * 3 from XX)"))))


;; === pay-mana-with-allocation tests ===

(deftest pay-allocated-single-color-test
  (testing "pay-mana-with-allocation deducts colored + allocates generic from one color"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 5}))
          ;; Cost: 2 generic + 1 black. Allocate generic from black.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 2 :black 1}
                                             {:black 2})]
      (is (= 2 (:black (q/get-mana-pool db' :player-1)))
          "1 colored + 2 allocated = 3 deducted from 5"))))


(deftest pay-allocated-multi-color-test
  (testing "pay-mana-with-allocation splits generic allocation across colors"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3 :blue 2}))
          ;; Cost: 2 generic + 1 black. Allocate 1 from black, 1 from blue.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 2 :black 1}
                                             {:black 1 :blue 1})]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))
          "1 colored + 1 allocated = 2 from black")
      (is (= 1 (:blue (q/get-mana-pool db' :player-1)))
          "1 allocated from blue"))))


(deftest pay-allocated-preserves-unused-colors-test
  (testing "pay-mana-with-allocation leaves untouched colors unchanged"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3 :blue 2 :red 1}))
          ;; Cost: 2 generic. Allocate from black only.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 2}
                                             {:black 2})]
      (is (= 1 (:black (q/get-mana-pool db' :player-1))))
      (is (= 2 (:blue (q/get-mana-pool db' :player-1)))
          "Blue untouched")
      (is (= 1 (:red (q/get-mana-pool db' :player-1)))
          "Red untouched"))))


(deftest pay-allocated-zero-generic-test
  (testing "pay-mana-with-allocation with no generic just deducts colored"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))
          ;; Cost: just 2 black, no generic. Empty allocation.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:black 2}
                                             {})]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))
          "Only colored cost deducted"))))


(deftest pay-allocated-only-generic-test
  (testing "pay-mana-with-allocation with only generic cost"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3 :blue 2}))
          ;; Cost: 3 generic, no colored. Allocate from black and blue.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 3}
                                             {:black 2 :blue 1})]
      (is (= 1 (:black (q/get-mana-pool db' :player-1))))
      (is (= 1 (:blue (q/get-mana-pool db' :player-1)))))))


(deftest pay-allocated-colorless-mana-in-allocation-test
  (testing "pay-mana-with-allocation uses colorless mana from pool toward generic"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:colorless 2 :black 1}))
          ;; Cost: 2 generic. Allocate 1 from colorless pool, 1 from black.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 2}
                                             {:colorless 1 :black 1})]
      (is (= 1 (:colorless (q/get-mana-pool db' :player-1)))
          "1 colorless allocated from pool")
      (is (= 0 (:black (q/get-mana-pool db' :player-1)))
          "1 black allocated from pool"))))


(deftest pay-allocated-all-from-one-color-test
  (testing "pay-mana-with-allocation drains a single color completely"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 5}))
          ;; Cost: 3 generic + 2 black. Allocate all generic from black.
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 3 :black 2}
                                             {:black 3})]
      (is (= 0 (:black (q/get-mana-pool db' :player-1)))
          "2 colored + 3 allocated = 5, pool drained"))))


(deftest pay-allocated-empty-cost-empty-allocation-test
  (testing "pay-mana-with-allocation with empty cost is a no-op"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 3}))
          db' (mana/pay-mana-with-allocation db :player-1 {} {})]
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))
          "Pool unchanged"))))


(deftest pay-allocated-large-pool-values-test
  (testing "pay-mana-with-allocation handles large values without precision issues"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 100}))
          db' (mana/pay-mana-with-allocation db :player-1
                                             {:colorless 50}
                                             {:black 50})]
      (is (= 50 (:black (q/get-mana-pool db' :player-1)))))))


(deftest pay-allocated-without-can-pay-goes-negative-test
  (testing "pay-mana-with-allocation without can-pay? allows negative pool"
    (let [db (init-game-state)
          initial-pool (q/get-mana-pool db :player-1)]
      ;; Precondition: pool starts at 0 for all colors
      (is (= 0 (:black initial-pool))
          "Precondition: black mana should be 0")
      (is (false? (mana/can-pay? db :player-1 {:colorless 2}))
          "can-pay? correctly returns false for insufficient mana")
      ;; Violating contract by not checking can-pay?
      (let [db' (mana/pay-mana-with-allocation db :player-1
                                               {:colorless 2}
                                               {:black 2})
            final-pool (q/get-mana-pool db' :player-1)]
        (is (= -2 (:black final-pool))
            "Documents caller-responsibility contract: no internal validation")
        (is (= (:blue initial-pool) (:blue final-pool))
            "Other colors should be unchanged by contract violation")))))


;; === Property-based invariant tests ===
;;
;; These tests verify mana system invariants across a range of inputs,
;; using manually generated test cases (no test.check dependency).

(def ^:private mana-colors [:white :blue :black :red :green :colorless])


(def ^:private property-test-pools
  "Diverse mana pool configurations for invariant testing."
  [{:black 3}
   {:blue 1 :red 1}
   {:black 2 :blue 1 :green 1}
   {:white 1 :blue 1 :black 1 :red 1 :green 1}
   {:colorless 3}
   {:colorless 2 :black 1}
   {:black 5 :blue 3 :red 1}
   {:green 10}
   {:white 2 :blue 2 :black 2 :red 2 :green 2 :colorless 2}])


(def ^:private property-test-costs
  "Costs that can be paid from various pool configurations."
  [{:black 1}
   {:blue 1 :red 1}
   {:colorless 2}
   {:colorless 1 :black 1}
   {:black 2 :blue 1}
   {}
   {:black 0}
   {:colorless 3}
   {:white 1 :blue 1 :black 1 :red 1 :green 1}])


(deftest add-pay-roundtrip-invariant-test
  (testing "add-mana then pay-mana returns pool to original state (from empty pool)"
    ;; Property: For any mana map M, starting from an empty pool,
    ;; add-mana(M) then pay-mana(M) should return to all zeros.
    ;; Note: This holds from empty pools because pay-mana's generic
    ;; heuristic will select the same pools that add-mana filled.
    (doseq [mana-map property-test-pools]
      (testing (str "roundtrip with " mana-map)
        (let [db (init-game-state)
              initial-pool (q/get-mana-pool db :player-1)
              db-added (mana/add-mana db :player-1 mana-map)
              db-paid (mana/pay-mana db-added :player-1 mana-map)]
          (is (= initial-pool (q/get-mana-pool db-paid :player-1))
              (str "Pool should return to initial state after add then pay " mana-map)))))))


(deftest can-pay-pay-mana-consistency-invariant-test
  (testing "If can-pay? returns true, pay-mana produces non-negative pool"
    ;; Property: For any pool P and cost C, if can-pay?(P, C) is true,
    ;; then pay-mana(P, C) should produce a pool where all values >= 0.
    (doseq [pool-mana property-test-pools
            cost property-test-costs]
      (let [db (-> (init-game-state)
                   (mana/add-mana :player-1 pool-mana))
            payable? (mana/can-pay? db :player-1 cost)]
        (when payable?
          (testing (str "pool=" pool-mana " cost=" cost)
            (let [db-paid (mana/pay-mana db :player-1 cost)
                  final-pool (q/get-mana-pool db-paid :player-1)]
              (doseq [color mana-colors]
                (is (>= (get final-pool color 0) 0)
                    (str color " should be non-negative after paying " cost
                         " from pool " pool-mana))))))))))


(deftest add-mana-no-negative-invariant-test
  (testing "add-mana never produces negative pool values"
    ;; Property: For any non-negative mana map M and any initial state,
    ;; add-mana(M) should never produce negative values.
    (doseq [mana-map property-test-pools]
      (testing (str "adding " mana-map " to empty pool")
        (let [db (init-game-state)
              db' (mana/add-mana db :player-1 mana-map)
              pool (q/get-mana-pool db' :player-1)]
          (doseq [color mana-colors]
            (is (>= (get pool color 0) 0)
                (str color " should be non-negative after adding " mana-map)))))))

  (testing "add-mana accumulation never produces negative values"
    ;; Multiple add-mana calls should never produce negative values.
    (let [db (init-game-state)
          db-accumulated (reduce (fn [d mana-map]
                                   (mana/add-mana d :player-1 mana-map))
                                 db
                                 property-test-pools)
          pool (q/get-mana-pool db-accumulated :player-1)]
      (doseq [color mana-colors]
        (is (>= (get pool color 0) 0)
            (str color " should be non-negative after accumulating all test pools"))))))
