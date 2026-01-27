(ns fizzle.engine.zones-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [datascript.core :as d]
            [fizzle.db.init :refer [init-game-state]]
            [fizzle.db.queries :as q]
            [fizzle.engine.zones :as zones]))

;; === move-to-zone tests ===

(deftest move-to-zone-hand-to-stack-test
  (testing "move-to-zone moves object from hand to stack"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :stack)]
      (is (= :stack (:object/zone (q/get-object db' object-id))))
      (is (empty? (q/get-hand db' :player-1)))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))))))

(deftest move-to-zone-stack-to-graveyard-test
  (testing "move-to-zone moves object from stack to graveyard"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (-> db
                  (zones/move-to-zone object-id :stack)
                  (zones/move-to-zone object-id :graveyard))]
      (is (= :graveyard (:object/zone (q/get-object db' object-id))))
      (is (empty? (q/get-objects-in-zone db' :player-1 :stack)))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :graveyard)))))))

(deftest move-to-zone-preserves-other-attributes-test
  (testing "move-to-zone only changes zone, not owner/controller/card"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          original-obj (q/get-object db object-id)
          db' (zones/move-to-zone db object-id :stack)
          moved-obj (q/get-object db' object-id)]
      (is (= (:object/owner original-obj) (:object/owner moved-obj)))
      (is (= (:object/controller original-obj) (:object/controller moved-obj)))
      (is (= (:object/card original-obj) (:object/card moved-obj))))))

(deftest move-to-zone-same-zone-no-op-test
  (testing "move-to-zone to same zone is a no-op"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :hand)]
      (is (= :hand (:object/zone (q/get-object db' object-id))))
      (is (= 1 (count (q/get-hand db' :player-1)))))))

(deftest move-to-zone-battlefield-test
  (testing "move-to-zone can move to battlefield"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :battlefield)]
      (is (= :battlefield (:object/zone (q/get-object db' object-id))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :battlefield)))))))

(deftest move-to-zone-exile-test
  (testing "move-to-zone can move to exile"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :exile)]
      (is (= :exile (:object/zone (q/get-object db' object-id))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :exile)))))))
