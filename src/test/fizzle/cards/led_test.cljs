(ns fizzle.cards.led-test
  "Tests for Lion's Eye Diamond sacrifice + discard for mana."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.events.abilities :as ability-events]
    [fizzle.test-helpers :as th]))


;; === LED sacrifice + discard for mana tests ===

(deftest test-led-sacrifice-and-discard-for-black-mana
  (testing "LED sacrifices and discards hand for 3 black mana"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          ;; Add 3 cards to hand
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db''' _] (th/add-card-to-zone db'' :dark-ritual :hand :player-1)
          [db'''' _] (th/add-card-to-zone db''' :dark-ritual :hand :player-1)
          _ (is (= :battlefield (th/get-object-zone db'''' led-id))
                "Precondition: LED starts on battlefield")
          _ (is (= 3 (th/get-zone-count db'''' :hand :player-1))
                "Precondition: 3 cards in hand")
          initial-pool (q/get-mana-pool db'''' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (ability-events/activate-mana-ability db'''' :player-1 led-id :black)]
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:black (q/get-mana-pool db-after :player-1)))
          "Black mana should be 3")
      (is (= 0 (th/get-zone-count db-after :hand :player-1))
          "Hand should be empty after discard")
      (is (= 4 (th/get-zone-count db-after :graveyard :player-1))
          "Graveyard should have 4 objects (3 hand cards + LED)"))))


(deftest test-led-sacrifice-and-discard-for-blue-mana
  (testing "LED sacrifices and discards hand for 3 blue mana"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          initial-pool (q/get-mana-pool db'' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          db-after (ability-events/activate-mana-ability db'' :player-1 led-id :blue)]
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:blue (q/get-mana-pool db-after :player-1)))
          "Blue mana should be 3"))))


(deftest test-led-sacrifice-and-discard-for-white-mana
  (testing "LED sacrifices and discards hand for 3 white mana"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:white initial-pool)) "Precondition: white mana is 0")
          db-after (ability-events/activate-mana-ability db' :player-1 led-id :white)]
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:white (q/get-mana-pool db-after :player-1)))
          "White mana should be 3"))))


(deftest test-led-with-empty-hand
  (testing "LED works with empty hand"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          _ (is (= :battlefield (th/get-object-zone db' led-id))
                "Precondition: LED starts on battlefield")
          _ (is (= 0 (th/get-zone-count db' :hand :player-1))
                "Precondition: hand is empty")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:red initial-pool)) "Precondition: red mana is 0")
          db-after (ability-events/activate-mana-ability db' :player-1 led-id :red)]
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED should be in graveyard after sacrifice")
      (is (= 3 (:red (q/get-mana-pool db-after :player-1)))
          "Red mana should be 3 (empty hand doesn't prevent activation)"))))


(deftest test-led-cannot-activate-from-graveyard
  (testing "LED in graveyard cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          _ (is (= :graveyard (th/get-object-zone db' led-id))
                "Precondition: LED is in graveyard")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (ability-events/activate-mana-ability db' :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED should remain in graveyard"))))


(deftest test-led-cannot-activate-from-hand
  (testing "LED in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' led-id))
                "Precondition: LED is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (ability-events/activate-mana-ability db' :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (th/get-object-zone db-after led-id))
          "LED should remain in hand"))))


(deftest test-led-card-definition
  (testing "Lion's Eye Diamond card definition is complete and correct"
    (let [card cards/lions-eye-diamond]
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


(deftest test-led-cannot-activate-when-tapped
  ;; Bug caught: LED activatable even when already tapped
  (testing "LED that is already tapped cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          ;; Manually tap the LED
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db' led-id)
          conn (d/conn-from-db db')
          _ (d/transact! conn [[:db/add obj-eid :object/tapped true]])
          db-tapped @conn
          _ (is (true? (:object/tapped (q/get-object db-tapped led-id)))
                "Precondition: LED is tapped")
          initial-pool (q/get-mana-pool db-tapped :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (ability-events/activate-mana-ability db-tapped :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (LED already tapped)")
      (is (= :battlefield (th/get-object-zone db-after led-id))
          "LED should remain on battlefield (not sacrificed)"))))
