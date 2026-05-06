(ns fizzle.events.scenario-zone-test
  "Tests for zone assignment and removal events (assign-to-zone, remove-from-zone)
   and the pool subscription helper (compute-zone-pool via subs/scenario)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.scenario :as scenario]
    [fizzle.subs.scenario :as scenario-subs]))


;; === Fixtures ===

(def ^:private db-with-deck
  {:scenario/editing
   {:scenario/player
    {:deck  [{:card/id :dark-ritual :count 4}
             {:card/id :swamp :count 2}]
     :zones {:hand [] :graveyard [] :battlefield []}}}})


(def ^:private db-no-zones
  {:scenario/editing
   {:scenario/player
    {:deck [{:card/id :dark-ritual :count 4}]}}})


;; === assign-to-zone ===

(deftest test-assign-to-zone-adds-card-to-hand
  (testing "assign-to-zone adds a card-id to the specified zone"
    (let [result (scenario/assign-to-zone-handler
                   db-with-deck
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])]
      (is (= [:dark-ritual]
             (get-in result [:scenario/editing :scenario/player :zones :hand]))
          "hand should contain the assigned card"))))


(deftest test-assign-to-zone-adds-card-to-graveyard
  (testing "assign-to-zone works for graveyard zone"
    (let [result (scenario/assign-to-zone-handler
                   db-with-deck
                   [nil {:side :player :card-id :swamp :zone :graveyard}])]
      (is (= [:swamp]
             (get-in result [:scenario/editing :scenario/player :zones :graveyard]))
          "graveyard should contain the assigned swamp"))))


(deftest test-assign-to-zone-adds-card-to-battlefield
  (testing "assign-to-zone works for battlefield zone"
    (let [result (scenario/assign-to-zone-handler
                   db-with-deck
                   [nil {:side :player :card-id :dark-ritual :zone :battlefield}])]
      (is (= [:dark-ritual]
             (get-in result [:scenario/editing :scenario/player :zones :battlefield]))
          "battlefield should contain the assigned card"))))


(deftest test-assign-to-zone-multiple-copies
  (testing "assigning 1 of 4 copies leaves 3 in pool"
    (let [result (scenario/assign-to-zone-handler
                   db-with-deck
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
          zones (get-in result [:scenario/editing :scenario/player :zones])
          deck  (get-in result [:scenario/editing :scenario/player :deck])
          assigned-count (count (filter #(= :dark-ritual %) (apply concat (vals zones))))
          deck-count (some #(when (= :dark-ritual (:card/id %)) (:count %)) deck)
          remaining (- deck-count assigned-count)]
      (is (= 3 remaining)
          "assigning 1 of 4 dark-rituals should leave 3 in pool"))))


(deftest test-assign-to-zone-all-copies
  (testing "assigning all copies blocks further assignment"
    (let [db (-> db-with-deck
                 (scenario/assign-to-zone-handler
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
                 (scenario/assign-to-zone-handler
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
                 (scenario/assign-to-zone-handler
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
                 (scenario/assign-to-zone-handler
                   [nil {:side :player :card-id :dark-ritual :zone :hand}]))
          ;; Try to assign a 5th copy
          result (scenario/assign-to-zone-handler
                   db
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
          hand (get-in result [:scenario/editing :scenario/player :zones :hand])]
      (is (= 4 (count hand))
          "should not be possible to assign more copies than exist in deck"))))


(deftest test-assign-to-zone-card-not-in-deck-is-noop
  (testing "assign-to-zone is a no-op for a card not present in the deck"
    (let [result (scenario/assign-to-zone-handler
                   db-with-deck
                   [nil {:side :player :card-id :lotus-petal :zone :hand}])]
      (is (= [] (get-in result [:scenario/editing :scenario/player :zones :hand]))
          "hand should remain empty when card is not in deck"))))


(deftest test-assign-to-zone-opponent-side
  (testing "assign-to-zone works for opponent side"
    (let [db {:scenario/editing
              {:scenario/opponent
               {:deck  [{:card/id :mountain :count 3}]
                :zones {:hand [] :graveyard [] :battlefield []}}}}
          result (scenario/assign-to-zone-handler
                   db
                   [nil {:side :opponent :card-id :mountain :zone :hand}])]
      (is (= [:mountain]
             (get-in result [:scenario/editing :scenario/opponent :zones :hand]))
          "opponent hand should contain the assigned card"))))


(deftest test-assign-to-zone-initialises-zone-when-absent
  (testing "assign-to-zone creates zone entry when zones map is missing"
    (let [result (scenario/assign-to-zone-handler
                   db-no-zones
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])]
      (is (= [:dark-ritual]
             (get-in result [:scenario/editing :scenario/player :zones :hand]))
          "hand zone should be created with the assigned card"))))


;; === remove-from-zone ===

(defn- db-with-hand
  "Build a db where card-id appears n times in zone for side."
  [card-id n zone side]
  (reduce
    (fn [db _]
      (scenario/assign-to-zone-handler
        db
        [nil {:side side :card-id card-id :zone zone}]))
    db-with-deck
    (range n)))


(deftest test-remove-from-zone-returns-card-to-pool
  (testing "remove-from-zone removes one occurrence from zone"
    (let [db     (db-with-hand :dark-ritual 2 :hand :player)
          result (scenario/remove-from-zone-handler
                   db
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
          hand   (get-in result [:scenario/editing :scenario/player :zones :hand])]
      (is (= 1 (count hand))
          "one copy should remain after removing one"))))


(deftest test-remove-from-zone-empty-zone-is-noop
  (testing "remove-from-zone on empty zone is a no-op"
    (let [result (scenario/remove-from-zone-handler
                   db-with-deck
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])]
      (is (= db-with-deck result)
          "db should be unchanged when zone is empty"))))


(deftest test-remove-from-zone-wrong-card-is-noop
  (testing "remove-from-zone is a no-op when card is not in that zone"
    (let [db     (db-with-hand :swamp 1 :hand :player)
          result (scenario/remove-from-zone-handler
                   db
                   [nil {:side :player :card-id :dark-ritual :zone :hand}])
          hand   (get-in result [:scenario/editing :scenario/player :zones :hand])]
      (is (= [:swamp] hand)
          "swamp should remain in hand after failed removal of dark-ritual"))))


(deftest test-remove-from-zone-increases-pool
  (testing "removing a card from a zone increases pool count"
    (let [db-after-assign (scenario/assign-to-zone-handler
                            db-with-deck
                            [nil {:side :player :card-id :dark-ritual :zone :hand}])
          pool-after-assign (scenario-subs/compute-zone-pool
                              (get-in db-after-assign [:scenario/editing :scenario/player]))
          ritual-pool-after-assign (some #(when (= :dark-ritual (:card/id %)) (:count %))
                                         pool-after-assign)
          db-after-remove (scenario/remove-from-zone-handler
                            db-after-assign
                            [nil {:side :player :card-id :dark-ritual :zone :hand}])
          pool-after-remove (scenario-subs/compute-zone-pool
                              (get-in db-after-remove [:scenario/editing :scenario/player]))
          ritual-pool-after-remove (some #(when (= :dark-ritual (:card/id %)) (:count %))
                                         pool-after-remove)]
      (is (= 3 ritual-pool-after-assign)
          "pool should have 3 after assigning 1 of 4")
      (is (= 4 ritual-pool-after-remove)
          "pool should return to 4 after removing the assigned copy"))))


;; === compute-zone-pool (pure helper exposed for testing) ===

(deftest test-compute-zone-pool-full-deck-no-zones
  (testing "pool equals deck when no cards are zone-assigned"
    (let [side-config {:deck  [{:card/id :dark-ritual :count 4}
                               {:card/id :swamp :count 2}]
                       :zones {:hand [] :graveyard [] :battlefield []}}
          pool (scenario-subs/compute-zone-pool side-config)]
      (is (= 2 (count pool))
          "pool should have 2 distinct card entries")
      (is (= 4 (some #(when (= :dark-ritual (:card/id %)) (:count %)) pool))
          "dark-ritual should have count 4 in pool")
      (is (= 2 (some #(when (= :swamp (:card/id %)) (:count %)) pool))
          "swamp should have count 2 in pool"))))


(deftest test-compute-zone-pool-reduces-by-zone-assignment
  (testing "pool count decreases by number of copies assigned to zones"
    (let [side-config {:deck  [{:card/id :dark-ritual :count 4}]
                       :zones {:hand [:dark-ritual :dark-ritual]
                               :graveyard [:dark-ritual]
                               :battlefield []}}
          pool (scenario-subs/compute-zone-pool side-config)]
      (is (= 1 (some #(when (= :dark-ritual (:card/id %)) (:count %)) pool))
          "pool should have 1 copy left after assigning 3 of 4"))))


(deftest test-compute-zone-pool-fully-assigned-card-absent
  (testing "card fully assigned to zones does not appear in pool"
    (let [side-config {:deck  [{:card/id :lotus-petal :count 2}]
                       :zones {:hand [:lotus-petal :lotus-petal]
                               :graveyard []
                               :battlefield []}}
          pool (scenario-subs/compute-zone-pool side-config)]
      (is (nil? (some #(= :lotus-petal (:card/id %)) pool))
          "lotus-petal should not appear in pool when all copies are assigned"))))


(deftest test-compute-zone-pool-empty-deck
  (testing "pool is empty when deck is empty"
    (let [side-config {:deck  []
                       :zones {:hand [] :graveyard [] :battlefield []}}
          pool (scenario-subs/compute-zone-pool side-config)]
      (is (empty? pool)
          "pool should be empty when deck is empty"))))


(deftest test-compute-zone-pool-nil-zones
  (testing "pool equals deck when :zones is absent"
    (let [side-config {:deck [{:card/id :dark-ritual :count 3}]}
          pool (scenario-subs/compute-zone-pool side-config)]
      (is (= 3 (some #(when (= :dark-ritual (:card/id %)) (:count %)) pool))
          "all cards should be in pool when zones key is absent"))))


(deftest test-assign-then-return-all-pool-equals-full-deck
  (testing "assigning then returning all cards restores pool to full deck"
    (let [db1 (-> db-with-deck
                  (scenario/assign-to-zone-handler
                    [nil {:side :player :card-id :dark-ritual :zone :hand}])
                  (scenario/assign-to-zone-handler
                    [nil {:side :player :card-id :dark-ritual :zone :graveyard}])
                  (scenario/assign-to-zone-handler
                    [nil {:side :player :card-id :swamp :zone :battlefield}]))
          db2 (-> db1
                  (scenario/remove-from-zone-handler
                    [nil {:side :player :card-id :dark-ritual :zone :hand}])
                  (scenario/remove-from-zone-handler
                    [nil {:side :player :card-id :dark-ritual :zone :graveyard}])
                  (scenario/remove-from-zone-handler
                    [nil {:side :player :card-id :swamp :zone :battlefield}]))
          pool (scenario-subs/compute-zone-pool
                 (get-in db2 [:scenario/editing :scenario/player]))]
      (is (= 4 (some #(when (= :dark-ritual (:card/id %)) (:count %)) pool))
          "dark-ritual pool should be back to 4")
      (is (= 2 (some #(when (= :swamp (:card/id %)) (:count %)) pool))
          "swamp pool should be back to 2"))))
