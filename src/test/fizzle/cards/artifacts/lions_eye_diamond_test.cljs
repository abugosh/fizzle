(ns fizzle.cards.artifacts.lions-eye-diamond-test
  "Tests for Lion's Eye Diamond - 0 artifact, tap/sacrifice/discard-hand for 3 mana.

   This file tests:
   - Card definition (exact fields, ability structure, cost fields)
   - Ability activation happy path (produces 3 of chosen color, sacrifices, discards hand)
   - Cannot-activate guards (wrong zone, tapped state)
   - Storm count is NOT applicable (mana abilities don't use the stack)
   - Edge cases (empty hand, all 5 colors)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.lions-eye-diamond :as led]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition ===

(deftest led-card-definition-test
  (testing "Lion's Eye Diamond core attributes are correct"
    (let [card led/card]
      (is (= :lions-eye-diamond (:card/id card))
          "Card ID should be :lions-eye-diamond")
      (is (= "Lion's Eye Diamond" (:card/name card))
          "Card name should match oracle")
      (is (= 0 (:card/cmc card))
          "CMC should be 0")
      (is (= {} (:card/mana-cost card))
          "Mana cost should be empty map (costs 0)")
      (is (= #{} (:card/colors card))
          "LED is colorless — no color identity from mana cost")
      (is (= #{:artifact} (:card/types card))
          "LED should be exactly an artifact (no other types)")
      (is (= "{T}, Sacrifice Lion's Eye Diamond, Discard your hand: Add three mana of any one color. Activate only as an instant."
             (:card/text card))
          "Oracle text should match exactly")))

  (testing "Lion's Eye Diamond has exactly one mana ability with correct cost"
    (let [card led/card]
      (is (= 1 (count (:card/abilities card)))
          "LED should have exactly 1 ability")
      (let [ability (first (:card/abilities card))]
        (is (= :mana (:ability/type ability))
            "Ability type should be :mana")
        (is (true? (get-in ability [:ability/cost :tap]))
            "Cost should include tap")
        (is (true? (get-in ability [:ability/cost :sacrifice-self]))
            "Cost should include sacrifice-self")
        (is (true? (get-in ability [:ability/cost :discard-hand]))
            "Cost should include discard-hand"))))

  (testing "Lion's Eye Diamond ability produces 3 mana of any color"
    (let [ability (first (:card/abilities led/card))
          effects (:ability/effects ability)]
      (is (= 1 (count effects))
          "Ability should have exactly 1 effect")
      (let [effect (first effects)]
        (is (= :add-mana (:effect/type effect))
            "Effect type should be :add-mana")
        (is (= {:any 3} (:effect/mana effect))
            "Should produce 3 mana of any color")))))


;; === B. Ability Activation Happy Path ===

(deftest led-cast-to-battlefield-test
  (testing "LED enters battlefield when cast from hand"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db obj-id))
          "LED should be on battlefield after cast and resolve")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))
          "Mana pool should be empty after casting LED (costs 0)"))))


(def ^:private mana-colors [:black :blue :white :red :green])


(deftest led-activate-produces-3-mana-and-sacrifices-test
  (doseq [color mana-colors]
    (testing (str "LED produces 3 " (name color) " mana and sacrifices itself")
      (let [db (th/create-test-db)
            [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
            initial-pool (q/get-mana-pool db :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (name color) " mana starts at 0"))
            _ (is (= :battlefield (th/get-object-zone db led-id))
                  "Precondition: LED on battlefield")
            db-after (engine-mana/activate-mana-ability db :player-1 led-id color)]
        (is (= 3 (get (q/get-mana-pool db-after :player-1) color))
            (str "Should have 3 " (name color) " mana after activation"))
        (is (= :graveyard (th/get-object-zone db-after led-id))
            (str "LED should be in graveyard after sacrifice for " (name color)))))))


(deftest led-activate-discards-hand-test
  (testing "LED activation discards all cards in hand as part of cost"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          _ (is (= 3 (th/get-zone-count db :hand :player-1))
                "Precondition: 3 cards in hand")
          db-after (engine-mana/activate-mana-ability db :player-1 led-id :black)]
      (is (= 0 (th/get-zone-count db-after :hand :player-1))
          "Hand should be empty after LED activation")
      (is (= 3 (:black (q/get-mana-pool db-after :player-1)))
          "Should produce 3 black mana")
      ;; 3 hand cards + LED itself in graveyard
      (is (= 4 (th/get-zone-count db-after :graveyard :player-1))
          "Graveyard should contain LED plus the 3 discarded cards"))))


;; === C. Cannot-Activate Guards ===

(deftest led-cannot-cast-from-graveyard-test
  (testing "Cannot cast LED from graveyard"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "LED is not castable from graveyard"))))


(deftest led-cannot-cast-from-battlefield-test
  (testing "Cannot cast LED from battlefield"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "LED is not castable from battlefield (already in play)"))))


(deftest led-cannot-activate-from-graveyard-test
  (testing "LED in graveyard cannot activate mana ability"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          _ (is (= :graveyard (th/get-object-zone db led-id))
                "Precondition: LED is in graveyard")
          db-after (engine-mana/activate-mana-ability db :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "No mana should be added when LED is in graveyard")
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED should remain in graveyard"))))


(deftest led-cannot-activate-from-hand-test
  (testing "LED in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :hand :player-1)
          _ (is (= :hand (th/get-object-zone db led-id))
                "Precondition: LED is in hand")
          db-after (engine-mana/activate-mana-ability db :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "No mana should be added when LED is in hand")
      (is (= :hand (th/get-object-zone db-after led-id))
          "LED should remain in hand"))))


(deftest led-cannot-activate-when-tapped-test
  (testing "LED that is tapped cannot activate mana ability"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          obj-eid (q/get-object-eid db led-id)
          db-tapped (d/db-with db [[:db/add obj-eid :object/tapped true]])
          _ (is (true? (:object/tapped (q/get-object db-tapped led-id)))
                "Precondition: LED is tapped")
          db-after (engine-mana/activate-mana-ability db-tapped :player-1 led-id :black)]
      (is (= 0 (:black (q/get-mana-pool db-after :player-1)))
          "No mana should be added when LED is already tapped")
      (is (= :battlefield (th/get-object-zone db-after led-id))
          "LED should remain on battlefield (not sacrificed)"))))


;; === D. Storm Count ===
;; NOTE: LED is an artifact permanent. Casting it DOES increment storm.
;; However, the mana ability (tap/sacrifice/discard) is a mana ability —
;; mana abilities do NOT use the stack and do NOT increment storm count.

(deftest led-cast-increments-storm-test
  (testing "Casting LED (the spell) increments storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lions-eye-diamond :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting LED"))))


(deftest led-mana-ability-does-not-increment-storm-test
  (testing "Activating LED's mana ability does NOT increment storm count"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-after (engine-mana/activate-mana-ability db :player-1 led-id :black)]
      (is (= 0 (q/get-storm-count db-after :player-1))
          "Mana abilities do not use the stack — storm count must not change"))))


;; === G. Edge Cases ===

(deftest led-activate-with-empty-hand-test
  (testing "LED activates correctly with empty hand"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          _ (is (= 0 (th/get-zone-count db :hand :player-1))
                "Precondition: hand is empty")
          db-after (engine-mana/activate-mana-ability db :player-1 led-id :red)]
      (is (= 3 (:red (q/get-mana-pool db-after :player-1)))
          "Empty hand does not prevent LED activation — still produces 3 mana")
      (is (= :graveyard (th/get-object-zone db-after led-id))
          "LED still sacrifices with empty hand"))))


(deftest led-each-activation-discards-exactly-current-hand-test
  (testing "LED discards exactly the current hand contents on activation"
    (let [db (th/create-test-db)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          [db _] (th/add-card-to-zone db :cabal-ritual :hand :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          _ (is (= 2 (th/get-zone-count db :hand :player-1))
                "Precondition: exactly 2 cards in hand")
          db-after (engine-mana/activate-mana-ability db :player-1 led-id :blue)]
      (is (= 0 (th/get-zone-count db-after :hand :player-1))
          "Hand should be empty after activation")
      (is (= 3 (th/get-zone-count db-after :graveyard :player-1))
          "Graveyard should contain LED plus 2 discarded hand cards"))))
