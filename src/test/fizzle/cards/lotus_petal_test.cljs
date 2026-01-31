(ns fizzle.cards.lotus-petal-test
  "Tests for Lotus Petal sacrifice-for-mana."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-artifact-to-battlefield
  "Add an artifact card to the battlefield for a player.
   Returns [db object-id] tuple."
  [db card-id player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [db object-id] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


;; === Lotus Petal sacrifice for mana tests ===

(deftest test-lotus-petal-sacrifice-for-black-mana
  (testing "Lotus Petal sacrifices for black mana"
    (let [db (create-test-db)
          [db' obj-id] (add-artifact-to-battlefield db :lotus-petal :player-1)
          _ (is (= :battlefield (get-object-zone db' obj-id))
                "Precondition: Lotus Petal starts on battlefield")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db'' (game/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Lotus Petal should be in graveyard after sacrifice")
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Black mana should be added to pool"))))


(deftest test-lotus-petal-sacrifice-for-blue-mana
  (testing "Lotus Petal sacrifices for blue mana"
    (let [db (create-test-db)
          [db' obj-id] (add-artifact-to-battlefield db :lotus-petal :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          db'' (game/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Lotus Petal should be in graveyard after sacrifice")
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added to pool"))))


(deftest test-lotus-petal-sacrifice-for-white-mana
  (testing "Lotus Petal sacrifices for white mana"
    (let [db (create-test-db)
          [db' obj-id] (add-artifact-to-battlefield db :lotus-petal :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:white initial-pool)) "Precondition: white mana is 0")
          db'' (game/activate-mana-ability db' :player-1 obj-id :white)]
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Lotus Petal should be in graveyard after sacrifice")
      (is (= 1 (:white (q/get-mana-pool db'' :player-1)))
          "White mana should be added to pool"))))


(deftest test-lotus-petal-sacrifice-for-red-mana
  (testing "Lotus Petal sacrifices for red mana"
    (let [db (create-test-db)
          [db' obj-id] (add-artifact-to-battlefield db :lotus-petal :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:red initial-pool)) "Precondition: red mana is 0")
          db'' (game/activate-mana-ability db' :player-1 obj-id :red)]
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Lotus Petal should be in graveyard after sacrifice")
      (is (= 1 (:red (q/get-mana-pool db'' :player-1)))
          "Red mana should be added to pool"))))


(deftest test-lotus-petal-sacrifice-for-green-mana
  (testing "Lotus Petal sacrifices for green mana"
    (let [db (create-test-db)
          [db' obj-id] (add-artifact-to-battlefield db :lotus-petal :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:green initial-pool)) "Precondition: green mana is 0")
          db'' (game/activate-mana-ability db' :player-1 obj-id :green)]
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Lotus Petal should be in graveyard after sacrifice")
      (is (= 1 (:green (q/get-mana-pool db'' :player-1)))
          "Green mana should be added to pool"))))


;; === Edge cases ===

(deftest test-lotus-petal-cannot-activate-from-graveyard
  (testing "Lotus Petal in graveyard cannot activate mana ability"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :lotus-petal :graveyard :player-1)
          _ (is (= :graveyard (get-object-zone db' obj-id))
                "Precondition: Lotus Petal is in graveyard")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          ;; Attempt to activate mana ability from graveyard
          db'' (game/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 0 (:black (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Lotus Petal should remain in graveyard"))))


(deftest test-lotus-petal-cannot-activate-from-hand
  (testing "Lotus Petal in hand cannot activate mana ability"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :lotus-petal :hand :player-1)
          _ (is (= :hand (get-object-zone db' obj-id))
                "Precondition: Lotus Petal is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          ;; Attempt to activate mana ability from hand
          db'' (game/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 0 (:black (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (get-object-zone db'' obj-id))
          "Lotus Petal should remain in hand"))))


(deftest test-lotus-petal-is-artifact-type
  (testing "Lotus Petal card definition has artifact type"
    (is (contains? (:card/types cards/lotus-petal) :artifact)
        "Lotus Petal should be an artifact")))
