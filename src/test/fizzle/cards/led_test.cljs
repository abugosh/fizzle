(ns fizzle.cards.led-test
  "Tests for Lion's Eye Diamond sacrifice + discard for mana."
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


(defn count-objects-in-zone
  "Count the number of objects in a zone for a player."
  [db player-id zone]
  (let [player-eid (q/get-player-eid db player-id)]
    (count (d/q '[:find [?e ...]
                  :in $ ?owner ?zone
                  :where [?e :object/owner ?owner]
                  [?e :object/zone ?zone]]
                db player-eid zone))))


;; === LED sacrifice + discard for mana tests ===

(deftest test-led-sacrifice-and-discard-for-black-mana
  (testing "LED sacrifices and discards hand for 3 black mana"
    (let [db (create-test-db)
          [db' led-id] (add-artifact-to-battlefield db :lions-eye-diamond :player-1)
          ;; Add 3 cards to hand
          [db'' _] (add-card-to-zone db' :dark-ritual :hand :player-1)
          [db''' _] (add-card-to-zone db'' :dark-ritual :hand :player-1)
          [db'''' _] (add-card-to-zone db''' :dark-ritual :hand :player-1)
          _ (is (= :battlefield (get-object-zone db'''' led-id))
                "Precondition: LED starts on battlefield")
          _ (is (= 3 (count-objects-in-zone db'''' :player-1 :hand))
                "Precondition: 3 cards in hand")
          initial-pool (q/get-mana-pool db'''' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (game/activate-mana-ability db'''' :player-1 led-id :black)]
      (is (= :graveyard (get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:black (q/get-mana-pool db-after :player-1)))
          "Black mana should be 3")
      (is (= 0 (count-objects-in-zone db-after :player-1 :hand))
          "Hand should be empty after discard")
      (is (= 4 (count-objects-in-zone db-after :player-1 :graveyard))
          "Graveyard should have 4 objects (3 hand cards + LED)"))))


(deftest test-led-sacrifice-and-discard-for-blue-mana
  (testing "LED sacrifices and discards hand for 3 blue mana"
    (let [db (create-test-db)
          [db' led-id] (add-artifact-to-battlefield db :lions-eye-diamond :player-1)
          [db'' _] (add-card-to-zone db' :dark-ritual :hand :player-1)
          initial-pool (q/get-mana-pool db'' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          db-after (game/activate-mana-ability db'' :player-1 led-id :blue)]
      (is (= :graveyard (get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:blue (q/get-mana-pool db-after :player-1)))
          "Blue mana should be 3"))))


(deftest test-led-sacrifice-and-discard-for-white-mana
  (testing "LED sacrifices and discards hand for 3 white mana"
    (let [db (create-test-db)
          [db' led-id] (add-artifact-to-battlefield db :lions-eye-diamond :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:white initial-pool)) "Precondition: white mana is 0")
          db-after (game/activate-mana-ability db' :player-1 led-id :white)]
      (is (= :graveyard (get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:white (q/get-mana-pool db-after :player-1)))
          "White mana should be 3"))))


(deftest test-led-with-empty-hand
  (testing "LED works with empty hand"
    (let [db (create-test-db)
          [db' led-id] (add-artifact-to-battlefield db :lions-eye-diamond :player-1)
          _ (is (= :battlefield (get-object-zone db' led-id))
                "Precondition: LED starts on battlefield")
          _ (is (= 0 (count-objects-in-zone db' :player-1 :hand))
                "Precondition: hand is empty")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:red initial-pool)) "Precondition: red mana is 0")
          db-after (game/activate-mana-ability db' :player-1 led-id :red)]
      (is (= :graveyard (get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:red (q/get-mana-pool db-after :player-1)))
          "Red mana should be 3 (empty hand doesn't prevent activation)"))))


(deftest test-led-cannot-activate-from-graveyard
  (testing "LED in graveyard cannot activate mana ability"
    (let [db (create-test-db)
          [db' led-id] (add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          _ (is (= :graveyard (get-object-zone db' led-id))
                "Precondition: LED is in graveyard")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (game/activate-mana-ability db' :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (get-object-zone db-after led-id))
          "LED should remain in graveyard"))))


(deftest test-led-cannot-activate-from-hand
  (testing "LED in hand cannot activate mana ability"
    (let [db (create-test-db)
          [db' led-id] (add-card-to-zone db :lions-eye-diamond :hand :player-1)
          _ (is (= :hand (get-object-zone db' led-id))
                "Precondition: LED is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (game/activate-mana-ability db' :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (get-object-zone db-after led-id))
          "LED should remain in hand"))))


(deftest test-led-card-definition
  (testing "Lion's Eye Diamond card definition is complete and correct"
    (let [card cards/lions-eye-diamond]
      ;; Card must exist
      (is (some? card)
          "LED card definition should exist")
      ;; Core attributes
      (is (= :lions-eye-diamond (:card/id card))
          "Card ID should be :lions-eye-diamond")
      (is (= "Lion's Eye Diamond" (:card/name card))
          "Card name should be 'Lion's Eye Diamond'")
      (is (= 0 (:card/cmc card))
          "LED should have CMC 0")
      (is (= {} (:card/mana-cost card))
          "LED should have no mana cost")
      ;; Types - verify exact set, not just contains
      (is (= #{:artifact} (:card/types card))
          "LED should be exactly an artifact (no other types)")
      ;; Colors
      (is (= #{} (:card/colors card))
          "LED should be colorless")
      ;; Abilities
      (is (= 1 (count (:card/abilities card)))
          "LED should have exactly 1 ability")
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability))
            "Ability should be a mana ability")
        (is (true? (get-in ability [:ability/cost :discard-hand]))
            "LED should require discarding hand as part of cost")))))
