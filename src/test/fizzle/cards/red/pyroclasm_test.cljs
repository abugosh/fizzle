(ns fizzle.cards.red.pyroclasm-test
  "Tests for Pyroclasm - {1}{R} Sorcery, deals 2 damage to each creature."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.red.pyroclasm :as pyroclasm]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.test-helpers :as th]))


;; === A. Card definition ===

;; Oracle: "Pyroclasm deals 2 damage to each creature."
(deftest pyroclasm-card-definition-test
  (testing "Pyroclasm card data matches Scryfall"
    (let [card pyroclasm/card]
      (is (= :pyroclasm (:card/id card)))
      (is (= "Pyroclasm" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :red 1} (:card/mana-cost card)))
      (is (= #{:sorcery} (:card/types card)))
      (is (= #{:red} (:card/colors card)))
      (is (= "Pyroclasm deals 2 damage to each creature." (:card/text card)))
      (is (= 1 (count (:card/effects card))))
      (let [effect (first (:card/effects card))]
        (is (= :deal-damage-each-creature (:effect/type effect)))
        (is (= 2 (:effect/amount effect)))))))


;; === B. Cast-resolve happy path ===

;; Oracle: "Pyroclasm deals 2 damage to each creature."
(deftest pyroclasm-deals-2-damage-to-each-creature-test
  (testing "Pyroclasm marks 2 damage on every creature on the battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          db (th/add-opponent db)
          [db creature-1] (th/add-test-creature db :player-1 3 3)
          [db creature-2] (th/add-test-creature db :player-2 2 4)
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          c1 (q/get-object db-resolved creature-1)
          c2 (q/get-object db-resolved creature-2)]
      (is (= 2 (:object/damage-marked c1))
          "Player 1's creature should have 2 damage marked")
      (is (= 2 (:object/damage-marked c2))
          "Player 2's creature should have 2 damage marked"))))


;; Oracle: "Pyroclasm deals 2 damage to each creature."
(deftest pyroclasm-kills-creatures-with-toughness-lte-2-test
  (testing "Creatures with toughness <= 2 die to Pyroclasm (via SBA)"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db creature-1] (th/add-test-creature db :player-1 1 1)
          [db creature-2] (th/add-test-creature db :player-1 2 2)
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)
          db-resolved (sba/check-and-execute-sbas (th/cast-and-resolve db :player-1 obj-id))]
      (is (= :graveyard (:object/zone (q/get-object db-resolved creature-1)))
          "1/1 creature should be in graveyard")
      (is (= :graveyard (:object/zone (q/get-object db-resolved creature-2)))
          "2/2 creature should be in graveyard"))))


;; Oracle: "Pyroclasm deals 2 damage to each creature."
(deftest pyroclasm-does-not-kill-high-toughness-creatures-test
  (testing "Creatures with toughness > 2 survive Pyroclasm"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db creature-id] (th/add-test-creature db :player-1 4 3)
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved creature-id)))
          "4/3 creature should survive on battlefield")
      (is (= 2 (:object/damage-marked (q/get-object db-resolved creature-id)))
          "4/3 creature should have 2 damage marked"))))


;; === C. Cannot-cast guards ===

(deftest pyroclasm-cannot-cast-without-mana-test
  (testing "Cannot cast Pyroclasm without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest pyroclasm-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Pyroclasm with only 1 red (needs {1}{R})"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest pyroclasm-cannot-cast-from-graveyard-test
  (testing "Cannot cast Pyroclasm from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :pyroclasm :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; === D. Storm count ===

(deftest pyroclasm-increments-storm-count-test
  (testing "Casting Pyroclasm increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))))))


;; === G. Edge cases ===

;; Oracle: "Pyroclasm deals 2 damage to each creature."
(deftest pyroclasm-no-creatures-on-battlefield-test
  (testing "Pyroclasm resolves cleanly with no creatures on battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Pyroclasm should be in graveyard after resolution"))))


;; Oracle: "Pyroclasm deals 2 damage to each creature."
(deftest pyroclasm-does-not-damage-non-creature-permanents-test
  (testing "Pyroclasm does not damage non-creature permanents (e.g. lands)"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :pyroclasm :hand :player-1)
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved land-id)))
          "Island should remain on battlefield")
      (is (nil? (:object/damage-marked (q/get-object db-resolved land-id)))
          "Island should have no damage marked"))))
