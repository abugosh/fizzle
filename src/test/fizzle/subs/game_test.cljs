(ns fizzle.subs.game-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- sub-value
  "Get subscription value by resetting app-db and deref'ing the subscription."
  [db sub-vec]
  (reset! rf-db/app-db db)
  @(rf/subscribe sub-vec))


(defn- make-game-db
  "Create a minimal game-db with two players and a game entity.
   Returns a Datascript db value."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 3
                        :player/land-plays-left 1}
                       {:player/id :opponent
                        :player/name "Opponent"
                        :player/life 15
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 0}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 5
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn- set-winner
  "Set :game/winner and :game/loss-condition on a game-db.
   losing-player-id is :player-1 or :opponent."
  [game-db condition losing-player-id]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
        winner-eid (d/q '[:find ?e .
                          :in $ ?loser-pid
                          :where [?e :player/id ?pid]
                          [(not= ?pid ?loser-pid)]]
                        game-db losing-player-id)]
    (d/db-with game-db [[:db/add game-eid :game/loss-condition condition]
                        [:db/add game-eid :game/winner winner-eid]])))


(defn- add-library-cards
  "Add N cards to a player's library. Returns new game-db."
  [game-db player-id n]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db player-id)]
    (d/transact! conn (vec (for [i (range n)]
                             {:object/id (random-uuid)
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position i})))
    @conn))


;; === ::game-over? subscription tests ===

(deftest test-game-over-false-when-no-winner
  (testing "::game-over? returns false when no winner set"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/game-over?])]
      (is (false? result)))))


(deftest test-game-over-true-when-winner-set
  (testing "::game-over? returns true when winner exists"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db} [::subs/game-over?])]
      (is (true? result)))))


(deftest test-game-over-false-when-game-db-nil
  (testing "::game-over? returns false when game-db is nil (no NPE)"
    (let [result (sub-value {} [::subs/game-over?])]
      (is (false? result)))))


;; === ::game-result subscription tests ===

(deftest test-game-result-player-wins
  (testing "::game-result returns :win outcome when player-1 wins"
    (let [game-db (-> (make-game-db)
                      (add-library-cards :opponent 10)
                      (set-winner :life-zero :opponent))
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (= :win (:outcome result)))
      (is (= :life-zero (:condition result)))
      (is (= 5 (:turn result)))
      (is (= 3 (:storm-count result)))
      (is (= 15 (:opponent-life result)))
      (is (= 10 (:opponent-library-size result))))))


(deftest test-game-result-player-loses
  (testing "::game-result returns :loss outcome when player-1 loses"
    (let [game-db (-> (make-game-db)
                      (set-winner :empty-library :player-1))
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (= :loss (:outcome result)))
      (is (= :empty-library (:condition result))))))


(deftest test-game-result-stats-values
  (testing "::game-result returns correct stats values"
    (let [game-db (-> (make-game-db)
                      (add-library-cards :opponent 7)
                      (set-winner :empty-library :opponent))
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (= 5 (:turn result)) "turn number from game state")
      (is (= 3 (:storm-count result)) "storm count from player-1")
      (is (= 15 (:opponent-life result)) "opponent life total")
      (is (= 7 (:opponent-library-size result)) "opponent library card count"))))


(deftest test-game-result-nil-when-no-winner
  (testing "::game-result returns nil when no winner"
    (let [game-db (make-game-db)
          result (sub-value {:game/db game-db} [::subs/game-result])]
      (is (nil? result)))))


;; === ::show-game-over-modal? subscription tests ===

(deftest test-show-modal-true-when-game-over-not-dismissed
  (testing "::show-game-over-modal? returns true when game over and not dismissed"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db
                             :active-screen :game}
                            [::subs/show-game-over-modal?])]
      (is (true? result)))))


(deftest test-show-modal-false-after-dismiss
  (testing "::show-game-over-modal? returns false after dismiss"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db
                             :active-screen :game
                             :game/game-over-dismissed true}
                            [::subs/show-game-over-modal?])]
      (is (false? result)))))


(deftest test-show-modal-false-on-non-game-screen
  (testing "::show-game-over-modal? returns false on setup screen"
    (let [game-db (set-winner (make-game-db) :life-zero :opponent)
          result (sub-value {:game/db game-db
                             :active-screen :setup}
                            [::subs/show-game-over-modal?])]
      (is (false? result)))))
