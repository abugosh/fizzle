(ns fizzle.cards.green.nimble-mongoose-test
  "Tests for Nimble Mongoose — G, 1/1 Mongoose with Shroud and Threshold +2/+2."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.green.nimble-mongoose :as nimble-mongoose]
    [fizzle.db.queries :as q]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; === A. Card definition ===

(deftest nimble-mongoose-card-definition-test
  (testing "Nimble Mongoose card data is correct"
    (let [card nimble-mongoose/card]
      (is (= :nimble-mongoose (:card/id card)))
      (is (= "Nimble Mongoose" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:green 1} (:card/mana-cost card)))
      (is (= #{:green} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:mongoose} (:card/subtypes card)))
      (is (= 1 (:card/power card)))
      (is (= 1 (:card/toughness card)))
      (is (= #{:shroud} (:card/keywords card)))
      (is (= "Shroud\nThreshold — Nimble Mongoose gets +2/+2." (:card/text card)))
      ;; Static abilities
      (is (= 1 (count (:card/static-abilities card))))
      (let [sa (first (:card/static-abilities card))]
        (is (= :pt-modifier (:static/type sa)))
        (is (= 2 (:modifier/power sa)))
        (is (= 2 (:modifier/toughness sa)))
        (is (= {:condition/type :threshold} (:modifier/condition sa)))
        (is (= :self (:modifier/applies-to sa)))))))


(deftest nimble-mongoose-passes-card-spec-test
  (testing "Nimble Mongoose passes card spec validation"
    (is (true? (card-spec/valid-card? nimble-mongoose/card))
        (str "Card spec failure: "
             (card-spec/explain-card nimble-mongoose/card)))))


;; === B. Cast-resolve happy path ===

(deftest nimble-mongoose-enters-bf-1-1-test
  (testing "Nimble Mongoose enters battlefield as 1/1 with summoning sickness"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj))
          "Should be on the battlefield")
      (is (= 1 (:object/power obj))
          "Base power should be 1")
      (is (= 1 (:object/toughness obj))
          "Base toughness should be 1")
      (is (true? (:object/summoning-sick obj))
          "Should have summoning sickness")
      (is (= 0 (:object/damage-marked obj))
          "Damage should be 0"))))


;; === C. Cannot-cast guards ===

(deftest nimble-mongoose-cannot-cast-without-mana-test
  (testing "Cannot cast Nimble Mongoose without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest nimble-mongoose-cannot-cast-wrong-color-test
  (testing "Cannot cast Nimble Mongoose with wrong color mana"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with blue mana"))))


(deftest nimble-mongoose-cannot-cast-from-graveyard-test
  (testing "Cannot cast Nimble Mongoose from graveyard"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm count ===

(deftest nimble-mongoose-increments-storm-test
  (testing "Casting Nimble Mongoose increments storm count"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1"))))


;; === E. Threshold P/T ===

(deftest nimble-mongoose-threshold-3-3-test
  (testing "Nimble Mongoose is 3/3 with threshold"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db _] (th/add-cards-to-graveyard db (vec (repeat 7 :dark-ritual)) :player-1)
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 3 (creatures/effective-power db obj-id))
          "Should be 3 power with threshold")
      (is (= 3 (creatures/effective-toughness db obj-id))
          "Should be 3 toughness with threshold"))))


(deftest nimble-mongoose-threshold-dynamic-test
  (testing "Threshold gained mid-game updates effective P/T"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          ;; Verify 1/1 without threshold
          _ (is (= 1 (creatures/effective-power db obj-id)))
          _ (is (= 1 (creatures/effective-toughness db obj-id)))
          ;; Add 7 cards for threshold
          [db _] (th/add-cards-to-graveyard db (vec (repeat 7 :dark-ritual)) :player-1)]
      (is (= 3 (creatures/effective-power db obj-id))
          "Power should update to 3 with threshold")
      (is (= 3 (creatures/effective-toughness db obj-id))
          "Toughness should update to 3 with threshold"))))


(deftest nimble-mongoose-lose-threshold-test
  (testing "Losing threshold drops P/T back to 1/1"
    (let [db (th/create-test-db {:mana {:green 1}})
          ;; Start with threshold
          [db gy-ids] (th/add-cards-to-graveyard db (vec (repeat 7 :dark-ritual)) :player-1)
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          ;; Verify 3/3 with threshold
          _ (is (= 3 (creatures/effective-power db obj-id)))
          ;; Remove a card from graveyard to break threshold
          db (zones/move-to-zone db (first gy-ids) :exile)]
      (is (= 1 (creatures/effective-power db obj-id))
          "Power should drop to 1 without threshold")
      (is (= 1 (creatures/effective-toughness db obj-id))
          "Toughness should drop to 1 without threshold"))))


;; === F. Shroud ===

(deftest nimble-mongoose-shroud-blocks-targeting-test
  (testing "Nimble Mongoose is excluded from valid targets due to shroud"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          target-req {:target/type :object
                      :target/zone :battlefield
                      :target/controller :self
                      :target/criteria {}}
          valid (targeting/find-valid-targets db :player-1 target-req)]
      (is (true? (creatures/has-keyword? db obj-id :shroud))
          "Should have shroud keyword")
      (is (not (contains? (set valid) obj-id))
          "Mongoose should not appear in valid targets"))))


;; === G. Edge cases ===

(deftest nimble-mongoose-summoning-sick-on-entry-test
  (testing "Cannot attack the turn it enters"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (true? (creatures/summoning-sick? db obj-id))
          "Should be summoning sick")
      (is (false? (creatures/can-attack? db obj-id))
          "Should not be able to attack"))))


(deftest nimble-mongoose-attacks-after-untap-test
  (testing "Can attack after your untap step clears summoning sickness"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          ;; Clear summoning sickness as untap step would
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])]
      (is (false? (creatures/summoning-sick? db obj-id))
          "Should not be summoning sick after untap")
      (is (true? (creatures/can-attack? db obj-id))
          "Should be able to attack"))))
