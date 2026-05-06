(ns fizzle.events.scenario-library-top-test
  "Tests for library-top ordering: add-to-library-top, remove-from-library-top, reorder-library-top
   and the unordered-pool subscription (library-top pool)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.scenario :as scenario]
    [fizzle.subs.scenario :as scenario-subs]))


;; === Fixtures ===

(def ^:private db-with-deck
  {:scenario/editing
   {:scenario/player
    {:deck  [{:card/id :dark-ritual :count 4}
             {:card/id :swamp :count 2}
             {:card/id :lotus-petal :count 3}]
     :zones {:hand [] :graveyard [] :battlefield []}
     :library-top []}}})


(def ^:private db-opponent
  {:scenario/editing
   {:scenario/opponent
    {:deck  [{:card/id :mountain :count 4}
             {:card/id :forest :count 2}]
     :zones {:hand [] :graveyard [] :battlefield []}
     :library-top []}}})


;; === add-to-library-top ===

(deftest test-add-to-library-top-appends-card
  (testing "add-to-library-top appends a card-id to the end of library-top"
    (let [result (scenario/add-to-library-top-handler
                   db-with-deck
                   [nil {:side :player :card-id :dark-ritual}])]
      (is (= [:dark-ritual]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should contain the added card"))))


(deftest test-add-to-library-top-appends-multiple
  (testing "add-to-library-top builds up a sequence"
    (let [result (-> db-with-deck
                     (scenario/add-to-library-top-handler
                       [nil {:side :player :card-id :dark-ritual}])
                     (scenario/add-to-library-top-handler
                       [nil {:side :player :card-id :swamp}])
                     (scenario/add-to-library-top-handler
                       [nil {:side :player :card-id :lotus-petal}]))]
      (is (= [:dark-ritual :swamp :lotus-petal]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should contain all added cards in order"))))


(deftest test-add-to-library-top-respects-pool-availability
  (testing "add-to-library-top allows adding more copies if pool has them"
    (let [result (-> db-with-deck
                     (scenario/add-to-library-top-handler
                       [nil {:side :player :card-id :dark-ritual}])
                     (scenario/add-to-library-top-handler
                       [nil {:side :player :card-id :dark-ritual}])
                     (scenario/add-to-library-top-handler
                       [nil {:side :player :card-id :dark-ritual}]))]
      (is (= 3 (count (get-in result [:scenario/editing :scenario/player :library-top])))
          "library-top should contain 3 dark-rituals"))))


(deftest test-add-to-library-top-guards-against-overcounts
  (testing "add-to-library-top clamps when too many copies are requested"
    (let [db (-> db-with-deck
                 (scenario/add-to-library-top-handler
                   [nil {:side :player :card-id :dark-ritual}])
                 (scenario/add-to-library-top-handler
                   [nil {:side :player :card-id :dark-ritual}])
                 (scenario/add-to-library-top-handler
                   [nil {:side :player :card-id :dark-ritual}])
                 (scenario/add-to-library-top-handler
                   [nil {:side :player :card-id :dark-ritual}])
                 (scenario/add-to-library-top-handler
                   [nil {:side :player :card-id :dark-ritual}]))
          lib-top (get-in db [:scenario/editing :scenario/player :library-top])]
      (is (<= (count (filter #(= :dark-ritual %) lib-top)) 4)
          "library-top should not have more dark-rituals than exist in deck"))))


(deftest test-add-to-library-top-card-not-in-pool-is-noop
  (testing "add-to-library-top ignores cards not in pool"
    (let [result (scenario/add-to-library-top-handler
                   db-with-deck
                   [nil {:side :player :card-id :lotus-bloom}])]
      (is (= []
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should remain empty when card is not in pool"))))


(deftest test-add-to-library-top-opponent-side
  (testing "add-to-library-top works for opponent side"
    (let [result (scenario/add-to-library-top-handler
                   db-opponent
                   [nil {:side :opponent :card-id :mountain}])]
      (is (= [:mountain]
             (get-in result [:scenario/editing :scenario/opponent :library-top]))
          "opponent library-top should contain the added card"))))


;; === remove-from-library-top ===

(defn- db-with-library-top
  "Build a db where the given cards are in library-top."
  [cards side]
  (reduce
    (fn [db card-id]
      (scenario/add-to-library-top-handler
        db
        [nil {:side side :card-id card-id}]))
    (if (= side :player) db-with-deck db-opponent)
    cards))


(deftest test-remove-from-library-top-by-index
  (testing "remove-from-library-top removes card at given index"
    (let [db (db-with-library-top [:dark-ritual :swamp :lotus-petal] :player)
          result (scenario/remove-from-library-top-handler
                   db
                   [nil {:side :player :index 1}])]
      (is (= [:dark-ritual :lotus-petal]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "swamp at index 1 should be removed"))))


(deftest test-remove-from-library-top-first-element
  (testing "remove-from-library-top works for index 0"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          result (scenario/remove-from-library-top-handler
                   db
                   [nil {:side :player :index 0}])]
      (is (= [:swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "dark-ritual at index 0 should be removed"))))


(deftest test-remove-from-library-top-last-element
  (testing "remove-from-library-top works for last index"
    (let [db (db-with-library-top [:dark-ritual :swamp :lotus-petal] :player)
          result (scenario/remove-from-library-top-handler
                   db
                   [nil {:side :player :index 2}])]
      (is (= [:dark-ritual :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "lotus-petal at index 2 should be removed"))))


(deftest test-remove-from-library-top-out-of-bounds-is-noop
  (testing "remove-from-library-top is a no-op for invalid index"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          result (scenario/remove-from-library-top-handler
                   db
                   [nil {:side :player :index 5}])]
      (is (= [:dark-ritual :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should be unchanged on invalid index"))))


(deftest test-remove-from-library-top-negative-index-is-noop
  (testing "remove-from-library-top ignores negative indices"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          result (scenario/remove-from-library-top-handler
                   db
                   [nil {:side :player :index -1}])]
      (is (= [:dark-ritual :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should be unchanged on negative index"))))


(deftest test-remove-from-library-top-opponent-side
  (testing "remove-from-library-top works for opponent side"
    (let [db (db-with-library-top [:mountain :forest] :opponent)
          result (scenario/remove-from-library-top-handler
                   db
                   [nil {:side :opponent :index 0}])]
      (is (= [:forest]
             (get-in result [:scenario/editing :scenario/opponent :library-top]))
          "opponent library-top should have mountain removed"))))


;; === reorder-library-top ===

(deftest test-reorder-library-top-move-forward
  (testing "reorder-library-top moves card earlier in sequence"
    (let [db (db-with-library-top [:dark-ritual :swamp :lotus-petal] :player)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :player :from-index 2 :to-index 0}])]
      (is (= [:lotus-petal :dark-ritual :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "lotus-petal should move from index 2 to 0"))))


(deftest test-reorder-library-top-move-backward
  (testing "reorder-library-top moves card later in sequence"
    (let [db (db-with-library-top [:dark-ritual :swamp :lotus-petal] :player)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :player :from-index 0 :to-index 2}])]
      (is (= [:swamp :lotus-petal :dark-ritual]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "dark-ritual should move from index 0 to 2"))))


(deftest test-reorder-library-top-adjacent-swap
  (testing "reorder-library-top can swap adjacent elements"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :player :from-index 0 :to-index 1}])]
      (is (= [:swamp :dark-ritual]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "dark-ritual and swamp should swap positions"))))


(deftest test-reorder-library-top-no-change
  (testing "reorder-library-top is a no-op when indices are equal"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :player :from-index 0 :to-index 0}])]
      (is (= [:dark-ritual :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should be unchanged"))))


(deftest test-reorder-library-top-invalid-from-index
  (testing "reorder-library-top is a no-op for invalid from-index"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :player :from-index 5 :to-index 0}])]
      (is (= [:dark-ritual :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "library-top should be unchanged on invalid from-index"))))


(deftest test-reorder-library-top-invalid-to-index
  (testing "reorder-library-top clamps invalid to-index: moves card to last position"
    (let [db (db-with-library-top [:dark-ritual :swamp :lotus-petal] :player)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :player :from-index 1 :to-index 10}])]
      (is (= [:dark-ritual :lotus-petal :swamp]
             (get-in result [:scenario/editing :scenario/player :library-top]))
          "swamp at from-index 1 should be clamped to last position (index 2)"))))


(deftest test-reorder-library-top-opponent-side
  (testing "reorder-library-top works for opponent side"
    (let [db (db-with-library-top [:mountain :forest] :opponent)
          result (scenario/reorder-library-top-handler
                   db
                   [nil {:side :opponent :from-index 0 :to-index 1}])]
      (is (= [:forest :mountain]
             (get-in result [:scenario/editing :scenario/opponent :library-top]))
          "opponent cards should be reordered"))))


;; === unordered-pool subscription ===

(deftest test-unordered-pool-excludes-library-top-cards
  (testing "unordered-pool returns pool minus library-top cards"
    (let [player-cfg {:deck  [{:card/id :dark-ritual :count 4}]
                      :zones {:hand [] :graveyard [] :battlefield []}
                      :library-top [:dark-ritual :dark-ritual]}
          zone-pool (scenario-subs/compute-zone-pool player-cfg)
          unord-pool (scenario-subs/unordered-pool player-cfg)
          ritual-zone-count (some #(when (= :dark-ritual (:card/id %)) (:count %)) zone-pool)
          ritual-unord-count (some #(when (= :dark-ritual (:card/id %)) (:count %)) unord-pool)]
      (is (and ritual-zone-count ritual-unord-count)
          "both pools should have dark-ritual")
      (is (< ritual-unord-count ritual-zone-count)
          "unordered-pool should have fewer dark-rituals than zone-pool"))))


(deftest test-unordered-pool-accounts-for-zone-assignments
  (testing "unordered-pool subtracts both zones and library-top"
    (let [player-cfg {:deck  [{:card/id :dark-ritual :count 4}]
                      :zones {:hand [:dark-ritual] :graveyard [] :battlefield []}
                      :library-top [:dark-ritual]}
          zone-pool (scenario-subs/compute-zone-pool player-cfg)
          unord-pool (scenario-subs/unordered-pool player-cfg)
          ritual-zone-count (some #(when (= :dark-ritual (:card/id %)) (:count %)) zone-pool)
          ritual-unord-count (some #(when (= :dark-ritual (:card/id %)) (:count %)) unord-pool)]
      (is (= 3 ritual-zone-count)
          "zone-pool should have 3 dark-rituals (4 - 1 in hand, library-top not considered)")
      (is (= 2 ritual-unord-count)
          "unordered-pool should have 2 dark-rituals (4 - 1 in hand - 1 in library-top)"))))


(deftest test-unordered-pool-multiple-copies
  (testing "unordered-pool tracks multiple copies in library-top"
    (let [player-cfg {:deck  [{:card/id :dark-ritual :count 4}]
                      :zones {:hand [] :graveyard [] :battlefield []}
                      :library-top [:dark-ritual :dark-ritual]}
          unord-pool (scenario-subs/unordered-pool player-cfg)
          ritual-in-unord (some #(when (= :dark-ritual (:card/id %)) (:count %))
                                unord-pool)]
      (is (= 2 ritual-in-unord)
          "unordered-pool should have 2 dark-rituals (4 - 2 in library-top)"))))


(deftest test-player-unordered-pool-subscription
  (testing "player-unordered-pool subscription works"
    (let [db (db-with-library-top [:dark-ritual :swamp] :player)
          player-cfg (get-in db [:scenario/editing :scenario/player])
          pool (scenario-subs/unordered-pool player-cfg)]
      (is (< (count (filter #(= :dark-ritual (:card/id %)) pool)) 4)
          "dark-ritual should be reduced in unordered-pool"))))


(deftest test-opponent-unordered-pool-subscription
  (testing "opponent-unordered-pool subscription works"
    (let [db (db-with-library-top [:mountain :forest] :opponent)
          opp-cfg (get-in db [:scenario/editing :scenario/opponent])
          pool (scenario-subs/unordered-pool opp-cfg)]
      (is (< (count (filter #(= :mountain (:card/id %)) pool)) 4)
          "mountain should be reduced in unordered-pool"))))
