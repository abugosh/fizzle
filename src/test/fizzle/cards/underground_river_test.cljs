(ns fizzle.cards.underground-river-test
  "Tests for Underground River - pain land with 3 mana abilities.
   {T}: Add {C}. {T}: Add {U} or {B}. Underground River deals 1 damage to you."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.events.abilities :as ability-events]
    [fizzle.test-helpers :as th]))


;; === Test helpers ===

(defn get-object-tapped
  "Get the tapped state of an object by its ID."
  [db object-id]
  (:object/tapped (q/get-object db object-id)))


;; === Card definition test ===

(deftest underground-river-card-definition-test
  (testing "Underground River has 3 mana abilities with correct produces and effects"
    (let [card cards/underground-river
          abilities (:card/abilities card)]
      (is (= :underground-river (:card/id card)))
      (is (= "Underground River" (:card/name card)))
      (is (= #{:land} (:card/types card)))
      (is (= 3 (count abilities))
          "Should have 3 mana abilities")
      ;; First ability: colorless, no damage
      (let [colorless-ability (first abilities)]
        (is (= {:colorless 1} (:ability/produces colorless-ability)))
        (is (nil? (:ability/effects colorless-ability))
            "Colorless ability should have no pain effect"))
      ;; Second ability: blue, deals damage
      (let [blue-ability (second abilities)]
        (is (= {:blue 1} (:ability/produces blue-ability)))
        (is (= 1 (count (:ability/effects blue-ability)))
            "Blue ability should have 1 damage effect")
        (is (= :deal-damage (:effect/type (first (:ability/effects blue-ability))))))
      ;; Third ability: black, deals damage
      (let [black-ability (nth abilities 2)]
        (is (= {:black 1} (:ability/produces black-ability)))
        (is (= :deal-damage (:effect/type (first (:ability/effects black-ability)))))))))


;; === Mana ability integration tests ===

(deftest underground-river-colorless-no-damage-test
  (testing "Tapping for colorless mana does not deal damage"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :underground-river :battlefield :player-1)
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :colorless)]
      (is (= 1 (:colorless (q/get-mana-pool db'' :player-1)))
          "Should produce 1 colorless mana")
      (is (= 20 (q/get-life-total db'' :player-1))
          "No damage from colorless ability")
      (is (true? (get-object-tapped db'' obj-id))
          "Land should be tapped"))))


(deftest underground-river-blue-deals-1-damage-test
  (testing "Tapping for blue mana deals 1 damage to controller"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :underground-river :battlefield :player-1)
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Should produce 1 blue mana")
      (is (= 19 (q/get-life-total db'' :player-1))
          "Should take 1 damage from pain land")
      (is (true? (get-object-tapped db'' obj-id))
          "Land should be tapped"))))


(deftest underground-river-black-deals-1-damage-test
  (testing "Tapping for black mana deals 1 damage to controller"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :underground-river :battlefield :player-1)
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Should produce 1 black mana")
      (is (= 19 (q/get-life-total db'' :player-1))
          "Should take 1 damage from pain land")
      (is (true? (get-object-tapped db'' obj-id))
          "Land should be tapped"))))


(deftest underground-river-cannot-activate-when-tapped-test
  (testing "Cannot activate mana ability when already tapped"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :underground-river :battlefield :player-1)
          ;; First activation succeeds
          db-first (ability-events/activate-mana-ability db' :player-1 obj-id :colorless)
          _ (is (true? (get-object-tapped db-first obj-id))
                "Precondition: land is tapped after first activation")
          ;; Second activation should fail (return unchanged db)
          db-second (ability-events/activate-mana-ability db-first :player-1 obj-id :blue)
          pool (q/get-mana-pool db-second :player-1)]
      (is (= 1 (:colorless pool))
          "Colorless mana unchanged (first activation)")
      (is (= 0 (:blue pool))
          "No blue mana added (second activation failed)")
      (is (= 20 (q/get-life-total db-second :player-1))
          "No damage from failed second activation"))))
