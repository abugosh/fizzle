(ns fizzle.engine.effects-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [fizzle.db.init :refer [init-game-state]]
            [fizzle.db.queries :as q]
            [fizzle.engine.effects :as fx]))

;; === execute-effect :add-mana tests ===

(deftest execute-effect-add-mana-test
  (testing "execute-effect with :add-mana adds mana to player's pool"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 3}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))

(deftest execute-effect-add-mana-multiple-colors-test
  (testing "execute-effect with :add-mana handles multiple colors"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 2 :red 1}}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 2 (:black (q/get-mana-pool db' :player-1))))
      (is (= 1 (:red (q/get-mana-pool db' :player-1)))))))

(deftest execute-effect-add-mana-accumulates-test
  (testing "execute-effect with :add-mana accumulates with existing mana"
    (let [db (init-game-state)
          effect {:effect/type :add-mana
                  :effect/mana {:black 3}}
          db' (-> db
                  (fx/execute-effect :player-1 effect)
                  (fx/execute-effect :player-1 effect))]
      (is (= 6 (:black (q/get-mana-pool db' :player-1)))))))

(deftest execute-effect-unknown-type-test
  (testing "execute-effect with unknown type returns db unchanged"
    (let [db (init-game-state)
          effect {:effect/type :unknown-effect}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= (q/get-mana-pool db :player-1)
             (q/get-mana-pool db' :player-1))))))

(deftest execute-effect-nil-type-test
  (testing "execute-effect with nil type (missing key) returns db unchanged"
    (let [db (init-game-state)
          effect {}  ;; no :effect/type key
          db' (fx/execute-effect db :player-1 effect)]
      (is (= (q/get-mana-pool db :player-1)
             (q/get-mana-pool db' :player-1))))))
