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
