(ns fizzle.cards.lands.ancient-tomb-test
  "Tests for Ancient Tomb — land that taps for 2 colorless and deals 2 damage.
   {T}: Add {C}{C}. Ancient Tomb deals 2 damage to you.

   What this file tests:
   - Card definition (fields, ability structure with produces + effects)
   - Mana ability activation (produces 2 colorless)
   - Damage as part of mana ability (immediate, not triggered)
   - Cannot-activate guards (wrong zone, already tapped)
   - Edge cases (cumulative damage, no stack involvement)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lands.ancient-tomb :as ancient-tomb]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.events.lands :as lands]
    [fizzle.test-helpers :as th]))


;; === Card definition tests ===

;; Oracle: "{T}: Add {C}{C}. Ancient Tomb deals 2 damage to you."
(deftest ancient-tomb-card-definition-test
  (testing "Ancient Tomb base card data is correct"
    (let [card ancient-tomb/card]
      (is (= :ancient-tomb (:card/id card)))
      (is (= "Ancient Tomb" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))))

  (testing "Mana ability structure — tap for 2 colorless with damage"
    (let [abilities (:card/abilities ancient-tomb/card)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :mana (:ability/type ability)))
        (is (true? (get-in ability [:ability/cost :tap]))
            "Should require tap")
        (is (= {:colorless 2} (:ability/produces ability))
            "Should produce 2 colorless mana")
        (is (= 1 (count (:ability/effects ability)))
            "Should have one effect (damage)")
        (let [effect (first (:ability/effects ability))]
          (is (= :deal-damage (:effect/type effect)))
          (is (= 2 (:effect/amount effect)))
          (is (= :controller (:effect/target effect))))))))


;; === Mana production tests ===

;; Oracle: "Add {C}{C}"
(deftest ancient-tomb-produces-two-colorless-test
  (testing "Ancient Tomb taps for 2 colorless mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          db-played (lands/play-land db' :player-1 obj-id)
          _ (is (= :battlefield (th/get-object-zone db-played obj-id))
                "Precondition: Ancient Tomb on battlefield")
          _ (is (= 0 (get (q/get-mana-pool db-played :player-1) :colorless 0))
                "Precondition: colorless mana is 0")
          db-tapped (engine-mana/activate-mana-ability db-played :player-1 obj-id :colorless)]
      (is (= 2 (get (q/get-mana-pool db-tapped :player-1) :colorless 0))
          "Should have 2 colorless mana in pool"))))


;; === Damage tests ===

;; Oracle: "Ancient Tomb deals 2 damage to you."
(deftest ancient-tomb-deals-damage-on-activation-test
  (testing "Activating Ancient Tomb deals 2 damage to controller immediately"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          db-played (lands/play-land db' :player-1 obj-id)
          _ (is (= 20 (q/get-life-total db-played :player-1))
                "Precondition: player starts at 20 life")
          db-tapped (engine-mana/activate-mana-ability db-played :player-1 obj-id :colorless)]
      (is (= 18 (q/get-life-total db-tapped :player-1))
          "Player should lose 2 life from damage"))))


;; Oracle: "{T}: Add {C}{C}. Ancient Tomb deals 2 damage to you."
;; Damage is part of the mana ability, not a triggered ability — no stack involvement.
(deftest ancient-tomb-damage-is-not-triggered-test
  (testing "Damage from Ancient Tomb does not go on the stack (mana ability, not trigger)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          db-played (lands/play-land db' :player-1 obj-id)
          db-tapped (engine-mana/activate-mana-ability db-played :player-1 obj-id :colorless)]
      (is (= 0 (count (q/get-all-stack-items db-tapped)))
          "No trigger should be on the stack — damage is part of the mana ability")
      (is (= 18 (q/get-life-total db-tapped :player-1))
          "Damage applied immediately, no resolution needed"))))


;; === Cannot-activate guard tests ===

;; Oracle: "{T}" (tap cost requires battlefield)
(deftest ancient-tomb-cannot-activate-from-hand-test
  (testing "Ancient Tomb in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (get initial-pool :colorless 0)) "Precondition: colorless mana is 0")
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)]
      (is (= 0 (get (q/get-mana-pool db'' :player-1) :colorless 0))
          "Mana should NOT be added (card in hand)")
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Ancient Tomb should remain in hand"))))


(deftest ancient-tomb-cannot-activate-from-graveyard-test
  (testing "Ancient Tomb in graveyard cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :graveyard :player-1)
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)]
      (is (= 0 (get (q/get-mana-pool db'' :player-1) :colorless 0))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "Ancient Tomb should remain in graveyard"))))


(deftest ancient-tomb-cannot-activate-when-tapped-test
  (testing "Ancient Tomb already tapped cannot activate again"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          db-played (lands/play-land db' :player-1 obj-id)
          db-tap1 (engine-mana/activate-mana-ability db-played :player-1 obj-id :colorless)
          _ (is (= 2 (get (q/get-mana-pool db-tap1 :player-1) :colorless 0))
                "Precondition: first tap adds mana")
          db-tap2 (engine-mana/activate-mana-ability db-tap1 :player-1 obj-id :colorless)]
      (is (= 2 (get (q/get-mana-pool db-tap2 :player-1) :colorless 0))
          "Colorless mana unchanged after failed second tap")
      (is (= 18 (q/get-life-total db-tap2 :player-1))
          "Life unchanged after failed second tap (still 18 from first tap)"))))


;; === Edge case tests ===

;; Oracle: "Ancient Tomb deals 2 damage to you." — each activation deals 2
(deftest ancient-tomb-cumulative-damage-test
  (testing "Tapping Ancient Tomb twice deals 4 cumulative damage"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          db-played (lands/play-land db' :player-1 obj-id)
          ;; First tap
          db-tap1 (engine-mana/activate-mana-ability db-played :player-1 obj-id :colorless)
          _ (is (= 18 (q/get-life-total db-tap1 :player-1))
                "18 life after first activation")
          ;; Manually untap for second activation
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db-tap1 obj-id)
          db-untapped (d/db-with db-tap1 [[:db/add obj-eid :object/tapped false]])
          ;; Second tap
          db-tap2 (engine-mana/activate-mana-ability db-untapped :player-1 obj-id :colorless)]
      (is (= 16 (q/get-life-total db-tap2 :player-1))
          "Player at 16 life after both activations")
      (is (= 4 (get (q/get-mana-pool db-tap2 :player-1) :colorless 0))
          "Should have 4 colorless mana total"))))


(deftest ancient-tomb-play-from-hand-test
  (testing "Playing Ancient Tomb from hand puts it on battlefield"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ancient-tomb :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id))
                "Precondition: card in hand")
          db-played (lands/play-land db' :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db-played obj-id))
          "Ancient Tomb should be on battlefield after playing"))))
