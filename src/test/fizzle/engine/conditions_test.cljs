(ns fizzle.engine.conditions-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.conditions :as conditions]))


;; === Test helper ===

(defn add-cards-to-graveyard
  "Add n cards to a player's graveyard.
   Cards are dummy objects for counting purposes."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [_ (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :graveyard
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}]))
    @conn))


(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])
    @conn))


;; === threshold? tests ===

(deftest test-threshold-false-with-zero-cards
  (testing "threshold? returns false with empty graveyard"
    (let [db (init-game-state)]
      (is (false? (conditions/threshold? db :player-1))))))


(deftest test-threshold-false-with-six-cards
  (testing "threshold? returns false with 6 cards in graveyard (off-by-one check)"
    (let [db (-> (init-game-state)
                 (add-cards-to-graveyard :player-1 6))]
      (is (false? (conditions/threshold? db :player-1))))))


(deftest test-threshold-true-with-seven-cards
  (testing "threshold? returns true with exactly 7 cards in graveyard"
    (let [db (-> (init-game-state)
                 (add-cards-to-graveyard :player-1 7))]
      (is (true? (conditions/threshold? db :player-1))))))


(deftest test-threshold-true-with-many-cards
  (testing "threshold? returns true with 10+ cards in graveyard"
    (let [db (-> (init-game-state)
                 (add-cards-to-graveyard :player-1 12))]
      (is (true? (conditions/threshold? db :player-1))))))


(deftest test-threshold-only-counts-owner-graveyard
  (testing "threshold? only counts player's own graveyard, not opponent's"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 ;; Opponent has 10 cards in graveyard
                 (add-cards-to-graveyard :player-2 10)
                 ;; Player 1 has only 3 cards
                 (add-cards-to-graveyard :player-1 3))]
      ;; Player 1 should NOT have threshold despite opponent's full graveyard
      (is (false? (conditions/threshold? db :player-1)))
      ;; Opponent should have threshold
      (is (true? (conditions/threshold? db :player-2))))))
