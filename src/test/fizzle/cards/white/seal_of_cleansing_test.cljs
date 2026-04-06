(ns fizzle.cards.white.seal-of-cleansing-test
  "Tests for Seal of Cleansing card.

   Seal of Cleansing: 1W - Enchantment
   Sacrifice this enchantment: Destroy target artifact or enchantment.

   This tests:
   - Card definition (type, cost)
   - Activated ability structure
   - Type-based targeting (artifacts OR enchantments on battlefield)
   - Sacrifice cost"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.white.ray-of-revelation :as ray]
    [fizzle.cards.white.seal-of-cleansing :as seal]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

;; Scryfall: {1}{W}
(deftest seal-of-cleansing-card-definition-test
  (testing "Seal of Cleansing type, cost, and color"
    (is (= {:colorless 1 :white 1} (:card/mana-cost seal/card))
        "Seal of Cleansing should cost {1}{W}")
    (is (= #{:enchantment} (:card/types seal/card))
        "Seal of Cleansing should be an enchantment")
    (is (= 2 (:card/cmc seal/card))
        "Seal of Cleansing should have CMC 2")
    (is (= #{:white} (:card/colors seal/card))
        "Seal of Cleansing should be white"))

  (testing "Seal of Cleansing has activated ability with sacrifice cost"
    (let [abilities (:card/abilities seal/card)
          ability (first abilities)]
      (is (= 1 (count abilities))
          "Seal should have 1 ability")
      (is (= :activated (:ability/type ability))
          "Ability should be :activated")
      (is (true? (:sacrifice-self (:ability/cost ability)))
          "Ability cost should include :sacrifice-self true")))

  (testing "Ability has destroy effect"
    (let [ability (first (:card/abilities seal/card))
          effect (first (:ability/effects ability))]
      (is (= :destroy (:effect/type effect))
          "Ability effect should be :destroy")
      (is (= :target-artifact-or-enchantment (:effect/target-ref effect))
          "Effect should reference :target-artifact-or-enchantment"))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: Seal of Cleansing is a permanent — it enters the battlefield on resolve.
(deftest seal-of-cleansing-enters-battlefield-on-resolve-test
  (testing "Casting and resolving Seal of Cleansing puts it on the battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db obj-id] (th/add-card-to-zone db :seal-of-cleansing :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj))
          "Seal of Cleansing should be on battlefield after resolving")
      (is (false? (:object/tapped obj))
          "Seal should enter untapped"))))


;; === C. Cannot-Cast Guards ===

(deftest seal-of-cleansing-cannot-cast-without-mana-test
  (testing "Cannot cast Seal of Cleansing without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :seal-of-cleansing :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest seal-of-cleansing-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Seal of Cleansing with only 1 white (needs {1}{W})"
    (let [db (th/create-test-db {:mana {:white 1}})
          [db obj-id] (th/add-card-to-zone db :seal-of-cleansing :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 1 white"))))


(deftest seal-of-cleansing-cannot-cast-from-graveyard-test
  (testing "Cannot cast Seal of Cleansing from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db obj-id] (th/add-card-to-zone db :seal-of-cleansing :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest seal-of-cleansing-cast-increments-storm-count-test
  (testing "Casting Seal of Cleansing increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db obj-id] (th/add-card-to-zone db :seal-of-cleansing :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting Seal of Cleansing"))))


;; === Targeting Tests ===

;; Oracle: "Destroy target artifact or enchantment."
(deftest seal-can-target-artifact-test
  (testing "Seal can target artifact on battlefield"
    (let [db (th/create-test-db)
          [db artifact-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find exactly one valid target")
      (is (= artifact-id (first targets))
          "Should find the artifact"))))


;; Oracle: "Destroy target artifact or enchantment."
(deftest seal-can-target-enchantment-test
  (testing "Seal can target enchantment on battlefield"
    (let [db (th/create-test-db)
          [db enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find exactly one valid target")
      (is (= enchant-id (first targets))
          "Should find the enchantment"))))


;; Oracle: "target artifact or enchantment" - implies NOT land-only
(deftest seal-cannot-target-non-artifact-non-enchantment-test
  (testing "Seal cannot target land (non-artifact, non-enchantment)"
    (let [db (th/create-test-db)
          [db _land-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find any valid targets when only land on battlefield"))))


;; Edge case: No valid targets
(deftest seal-not-activatable-without-targets-test
  (testing "Seal ability requires valid target"
    (let [db (th/create-test-db)
          ;; Only land on battlefield, no artifacts/enchantments
          [db _land-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))]
      (is (empty? (targeting/find-valid-targets db :player-1 target-req))
          "Should have no valid targets when no artifacts/enchantments"))))


;; Oracle: "target artifact or enchantment" - either type qualifies
(deftest seal-can-target-multiple-types-test
  (testing "Seal can target either artifact or enchantment when both present"
    (let [db (th/create-test-db)
          [db artifact-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should find both artifact and enchantment as valid targets")
      (is (contains? (set targets) artifact-id)
          "Should include the artifact")
      (is (contains? (set targets) enchant-id)
          "Should include the enchantment"))))


;; === Integration Test: Ray of Revelation can target Seal of Cleansing ===

;; Ray Oracle: "Destroy target enchantment."
;; Seal is an enchantment on battlefield, so Ray should be able to target and destroy it
(deftest ray-destroys-seal-integration-test
  (testing "Ray of Revelation can target Seal of Cleansing (it's an enchantment)"
    (let [db (th/create-test-db)
          ;; Put Seal of Cleansing on battlefield
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-1)]
      ;; Ray should be able to find Seal as a valid target
      (is (targeting/has-valid-targets? db :player-1 ray/card)
          "Ray should have valid targets when Seal is on battlefield")
      (let [target-req (first (:card/targeting ray/card))
            targets (targeting/find-valid-targets db :player-1 target-req)]
        (is (= 1 (count targets))
            "Should find exactly one valid target")
        (is (= seal-id (first targets))
            "Should find Seal of Cleansing as valid target")))))


;; === Activation Resolution Flow Test ===

(deftest seal-of-cleansing-activation-resolution-flow-test
  ;; Bug caught: ability resolution broken
  (testing "Activate ability targeting enchantment, resolve from stack, verify destroyed"
    (let [db (th/create-test-db)
          ;; Add Seal of Cleansing to battlefield
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-1)
          ;; Add target enchantment to battlefield
          [db target-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db seal-id)))
                "Precondition: Seal on battlefield")
          _ (is (= :battlefield (:object/zone (q/get-object db target-id)))
                "Precondition: Target enchantment on battlefield")
          ;; Activate Seal's ability (index 0 = only ability)
          ;; This should return pending selection for targeting
          result (ability-events/activate-ability db :player-1 seal-id 0)
          sel (:pending-selection result)]
      ;; Should have pending selection for target
      (is (= :ability-targeting (:selection/type sel))
          "Selection type should be :ability-targeting")
      ;; Confirm target selection via production path
      (let [{db-after-confirm :db} (th/confirm-selection (:db result) sel #{target-id})]
        ;; Seal should be sacrificed (cost paid)
        (is (= :graveyard (:object/zone (q/get-object db-after-confirm seal-id)))
            "Seal should be in graveyard after sacrifice")
        ;; Should have stack-item for the ability
        (let [top-item (stack/get-top-stack-item db-after-confirm)]
          (is (= :activated-ability (:stack-item/type top-item))
              "Stack item should be activated ability type")
          ;; Resolve the ability from the stack
          (let [db-resolved (:db (resolution/resolve-one-item db-after-confirm))]
            ;; Target enchantment should be destroyed (moved to graveyard)
            (is (= :graveyard (:object/zone (q/get-object db-resolved target-id)))
                "Target enchantment should be in graveyard after ability resolves")
            ;; Stack should be empty
            (is (nil? (stack/get-top-stack-item db-resolved))
                "Stack should be empty after resolution")))))))
