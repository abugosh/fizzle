(ns fizzle.cards.artifacts.lotus-petal-test
  "Tests for Lotus Petal sacrifice-for-mana."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.artifacts.lotus-petal :as lotus-petal]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === C. Cannot-Cast Guards ===

(deftest lotus-petal-cannot-cast-from-graveyard-test
  (testing "Cannot cast Lotus Petal from graveyard"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


(deftest lotus-petal-cannot-cast-from-battlefield-test
  (testing "Cannot cast Lotus Petal from battlefield"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from battlefield"))))


;; === D. Storm Count ===

(deftest lotus-petal-cast-increments-storm-count-test
  (testing "Casting Lotus Petal increments storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Lotus Petal"))))


;; === Lotus Petal sacrifice for mana tests ===

(def ^:private mana-colors [:black :blue :white :red :green])


(deftest test-lotus-petal-sacrifice-for-any-color
  (doseq [color mana-colors]
    (testing (str "Lotus Petal sacrifices for " (name color) " mana")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
            initial-pool (q/get-mana-pool db' :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (name color) " mana is 0"))
            db'' (engine-mana/activate-mana-ability db' :player-1 obj-id color)]
        (is (= :graveyard (th/get-object-zone db'' obj-id))
            (str "Lotus Petal should be in graveyard after sacrifice for " (name color)))
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color))
            (str (name color) " mana should be added to pool"))))))


;; === Edge cases ===

(deftest test-lotus-petal-cannot-activate-from-graveyard
  (testing "Lotus Petal in graveyard cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :lotus-petal :graveyard :player-1)
          _ (is (= :graveyard (th/get-object-zone db' obj-id))
                "Precondition: Lotus Petal is in graveyard")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          ;; Attempt to activate mana ability from graveyard
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 0 (:black (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "Lotus Petal should remain in graveyard"))))


(deftest test-lotus-petal-cannot-activate-from-hand
  (testing "Lotus Petal in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id))
                "Precondition: Lotus Petal is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          ;; Attempt to activate mana ability from hand
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 0 (:black (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Lotus Petal should remain in hand"))))


(deftest test-lotus-petal-card-definition
  (testing "Lotus Petal card definition is complete and correct"
    (let [card lotus-petal/card]
      ;; Core attributes
      (is (= :lotus-petal (:card/id card))
          "Card ID should be :lotus-petal")
      (is (= "Lotus Petal" (:card/name card))
          "Card name should be 'Lotus Petal'")
      (is (= 0 (:card/cmc card))
          "Lotus Petal should have CMC 0")
      (is (= {} (:card/mana-cost card))
          "Lotus Petal should have no mana cost")
      ;; Types - verify exact set, not just contains
      (is (= #{:artifact} (:card/types card))
          "Lotus Petal should be exactly an artifact (no other types)")
      ;; Colors
      (is (= #{} (:card/colors card))
          "Lotus Petal should be colorless")
      ;; Abilities
      (is (= 1 (count (:card/abilities card)))
          "Lotus Petal should have exactly 1 ability")
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability))
            "Ability should be a mana ability")
        (is (= {:any 1} (:ability/produces ability))
            "Should produce 1 mana of any color")))))
