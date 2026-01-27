(ns fizzle.engine.mana-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [datascript.core :as d]
            [fizzle.db.schema :refer [schema]]
            [fizzle.db.init :refer [init-game-state empty-mana-pool]]
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
