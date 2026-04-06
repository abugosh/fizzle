(ns fizzle.cards.artifacts.led-test
  "Tests for Lion's Eye Diamond sacrifice + discard for mana."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.lions-eye-diamond :as lions-eye-diamond]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === B. Cast-Resolve Happy Path ===

(deftest led-cast-and-resolve-to-battlefield-test
  (testing "LED enters battlefield when cast from hand"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db obj-id))
          "LED should be on battlefield after cast and resolve")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))
          "Mana pool should be empty (LED costs 0)"))))


;; === C. Cannot-Cast Guards ===

(deftest led-cannot-cast-from-graveyard-test
  (testing "Cannot cast LED from graveyard"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


(deftest led-cannot-cast-from-battlefield-test
  (testing "Cannot cast LED from battlefield"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from battlefield"))))


;; === D. Storm Count ===

(deftest led-cast-increments-storm-count-test
  (testing "Casting LED increments storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting LED"))))


;; === LED sacrifice + discard for mana tests ===

(def ^:private mana-colors [:black :blue :white :red :green])


(deftest test-led-sacrifice-and-discard-for-any-color
  (doseq [color mana-colors]
    (testing (str "LED sacrifices and discards hand for 3 " (name color) " mana")
      (let [db (th/create-test-db)
            [db' led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
            ;; Add 3 cards to hand
            [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
            [db''' _] (th/add-card-to-zone db'' :dark-ritual :hand :player-1)
            [db'''' _] (th/add-card-to-zone db''' :dark-ritual :hand :player-1)
            _ (is (= :battlefield (th/get-object-zone db'''' led-id))
                  (str "Precondition: LED starts on battlefield"))
            _ (is (= 3 (th/get-zone-count db'''' :hand :player-1))
                  (str "Precondition: 3 cards in hand"))
            initial-pool (q/get-mana-pool db'''' :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (name color) " mana is 0"))
            db-after (engine-mana/activate-mana-ability db'''' :player-1 led-id color)]
        (is (= :graveyard (th/get-object-zone db-after led-id))
            (str "LED should be in graveyard after sacrifice for " (name color)))
        (is (= 3 (get (q/get-mana-pool db-after :player-1) color))
            (str (name color) " mana should be 3"))
        (is (= 0 (th/get-zone-count db-after :hand :player-1))
            (str "Hand should be empty after discard for " (name color)))
        (is (= 4 (th/get-zone-count db-after :graveyard :player-1))
            (str "Graveyard should have 4 objects for " (name color)))))))


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
          db-after (engine-mana/activate-mana-ability db' :player-1 led-id :red)]
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
          db-after (engine-mana/activate-mana-ability db' :player-1 led-id :black)]
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
          db-after (engine-mana/activate-mana-ability db' :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (th/get-object-zone db-after led-id))
          "LED should remain in hand"))))


(deftest test-led-card-definition
  (testing "Lion's Eye Diamond card definition is complete and correct"
    (let [card lions-eye-diamond/card]
      ;; Core attributes
      (is (= :lions-eye-diamond (:card/id card))
          "Card ID should be :lions-eye-diamond")
      (is (= "Lion's Eye Diamond" (:card/name card))
          "Card name should be 'Lion's Eye Diamond'")
      (is (= 0 (:card/cmc card))
          "LED should have CMC 0")
      (is (= {} (:card/mana-cost card))
          "LED should have no mana cost")
      (is (= "{T}, Sacrifice Lion's Eye Diamond, Discard your hand: Add three mana of any one color. Activate only as an instant."
             (:card/text card))
          "Oracle text should match exactly")
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
          ;; Manually tap the LED using d/db-with (immutable, consistent with rest of tests)
          obj-eid (q/get-object-eid db' led-id)
          db-tapped (d/db-with db' [[:db/add obj-eid :object/tapped true]])
          _ (is (true? (:object/tapped (q/get-object db-tapped led-id)))
                "Precondition: LED is tapped")
          initial-pool (q/get-mana-pool db-tapped :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db-after (engine-mana/activate-mana-ability db-tapped :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "Mana should NOT be added (LED already tapped)")
      (is (= :battlefield (th/get-object-zone db-after led-id))
          "LED should remain on battlefield (not sacrificed)"))))
