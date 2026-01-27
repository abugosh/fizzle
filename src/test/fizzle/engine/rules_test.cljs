(ns fizzle.engine.rules-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [fizzle.db.init :refer [init-game-state]]
            [fizzle.db.queries :as q]
            [fizzle.engine.mana :as mana]
            [fizzle.engine.zones :as zones]
            [fizzle.engine.rules :as rules]))

;; === can-cast? tests ===

(deftest can-cast-returns-false-without-mana-test
  (testing "can-cast? returns false when player lacks mana"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      (is (false? (rules/can-cast? db :player-1 (:object/id ritual)))))))

(deftest can-cast-returns-true-with-mana-test
  (testing "can-cast? returns true when player has sufficient mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      (is (true? (rules/can-cast? db :player-1 (:object/id ritual)))))))

(deftest can-cast-returns-false-wrong-zone-test
  (testing "can-cast? returns false when card not in hand (on stack)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))  ;; Extra mana for test
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          ;; Move card to stack (simulating cast without using cast-spell)
          db' (zones/move-to-zone db obj-id :stack)]
      ;; Card is on stack, not in hand - should return false
      (is (false? (rules/can-cast? db' :player-1 obj-id))))))

;; === cast-spell tests ===

(deftest cast-spell-moves-to-stack-test
  (testing "cast-spell moves card from hand to stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 0 (count (q/get-hand db' :player-1))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))))))

(deftest cast-spell-pays-mana-test
  (testing "cast-spell deducts mana cost from pool"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))))))

(deftest cast-spell-increments-storm-test
  (testing "cast-spell increments storm count"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 1 (q/get-storm-count db' :player-1))))))

;; === resolve-spell tests ===

(deftest resolve-spell-executes-effects-test
  (testing "resolve-spell executes card effects"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (-> db
                  (rules/cast-spell :player-1 (:object/id ritual))
                  (rules/resolve-spell :player-1 (:object/id ritual)))]
      ;; Dark Ritual adds BBB
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))

(deftest resolve-spell-moves-to-graveyard-test
  (testing "resolve-spell moves card from stack to graveyard"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (-> db
                  (rules/cast-spell :player-1 (:object/id ritual))
                  (rules/resolve-spell :player-1 (:object/id ritual)))]
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :stack))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :graveyard)))))))

;; === Full pipeline test ===

(deftest dark-ritual-full-pipeline-test
  (testing "Dark Ritual: cast with B, resolve to get BBB, storm = 1"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)

          ;; Verify can cast
          _ (is (true? (rules/can-cast? db :player-1 obj-id)))

          ;; Cast it
          db-cast (rules/cast-spell db :player-1 obj-id)
          _ (is (= 0 (:black (q/get-mana-pool db-cast :player-1))))
          _ (is (= 1 (q/get-storm-count db-cast :player-1)))
          _ (is (= 1 (count (q/get-objects-in-zone db-cast :player-1 :stack))))

          ;; Resolve it
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= 3 (:black (q/get-mana-pool db-resolved :player-1))))
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :stack))))
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))))))
