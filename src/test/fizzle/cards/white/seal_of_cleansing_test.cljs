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
    [datascript.core :as d]
    [fizzle.cards.white.ray-of-revelation :as ray]
    [fizzle.cards.white.seal-of-cleansing :as seal]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn add-card-to-zone
  "Add a card definition and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition if not already present
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    ;; Create object in zone
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)
          base-obj {:object/id obj-id
                    :object/card card-eid
                    :object/zone zone
                    :object/owner player-eid
                    :object/controller player-eid
                    :object/tapped false}
          obj (if (= zone :library)
                (assoc base-obj :object/position 0)
                base-obj)]
      (d/transact! conn [obj])
      [obj-id @conn])))


;; === Test Cards ===

(def test-enchantment
  "A simple test enchantment for targeting."
  {:card/id :test-enchantment
   :card/name "Test Enchantment"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :white 1}
   :card/colors #{:white}
   :card/types #{:enchantment}
   :card/text "Test enchantment."
   :card/effects []})


(def test-artifact
  "A test artifact for targeting."
  {:card/id :test-artifact
   :card/name "Test Artifact"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Test artifact."
   :card/effects []})


(def test-creature
  "A creature that should NOT be targetable by Seal."
  {:card/id :test-creature
   :card/name "Test Creature"
   :card/cmc 2
   :card/mana-cost {:green 2}
   :card/colors #{:green}
   :card/types #{:creature}
   :card/text "2/2"
   :card/effects []})


(def test-artifact-creature
  "An artifact creature that SHOULD be targetable by Seal."
  {:card/id :test-artifact-creature
   :card/name "Test Artifact Creature"
   :card/cmc 3
   :card/mana-cost {:colorless 3}
   :card/colors #{}
   :card/types #{:artifact :creature}
   :card/text "2/2"
   :card/effects []})


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


;; === Targeting Tests ===

;; Oracle: "Destroy target artifact or enchantment."
(deftest seal-can-target-artifact-test
  (testing "Seal can target artifact on battlefield"
    (let [db (init-game-state)
          [artifact-id db] (add-card-to-zone db :player-1 test-artifact :battlefield)
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
    (let [db (init-game-state)
          [enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find exactly one valid target")
      (is (= enchant-id (first targets))
          "Should find the enchantment"))))


;; Oracle: "target artifact or enchantment" - implies NOT creature-only
(deftest seal-cannot-target-creature-test
  (testing "Seal cannot target creature (non-artifact, non-enchantment)"
    (let [db (init-game-state)
          [_creature-id db] (add-card-to-zone db :player-1 test-creature :battlefield)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find any valid targets when only creature on battlefield"))))


;; Oracle: "artifact or enchantment" - artifact creatures qualify as artifacts
(deftest seal-can-target-artifact-creature-test
  (testing "Seal can target artifact creature (has artifact type)"
    (let [db (init-game-state)
          [artifact-creature-id db] (add-card-to-zone db :player-1 test-artifact-creature :battlefield)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find exactly one valid target")
      (is (= artifact-creature-id (first targets))
          "Should find the artifact creature"))))


;; Edge case: No valid targets
(deftest seal-not-activatable-without-targets-test
  (testing "Seal ability requires valid target"
    (let [db (init-game-state)
          ;; Only creature on battlefield, no artifacts/enchantments
          [_creature-id db] (add-card-to-zone db :player-1 test-creature :battlefield)
          ability (first (:card/abilities seal/card))
          target-req (first (:ability/targeting ability))]
      (is (empty? (targeting/find-valid-targets db :player-1 target-req))
          "Should have no valid targets when no artifacts/enchantments"))))


;; Oracle: "target artifact or enchantment" - either type qualifies
(deftest seal-can-target-multiple-types-test
  (testing "Seal can target either artifact or enchantment when both present"
    (let [db (init-game-state)
          [artifact-id db] (add-card-to-zone db :player-1 test-artifact :battlefield)
          [enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
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
    (let [db (init-game-state)
          ;; Put Seal of Cleansing on battlefield
          [seal-id db] (add-card-to-zone db :player-1 seal/card :battlefield)]
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
    (let [db (init-game-state)
          ;; Add Seal of Cleansing to battlefield
          [seal-id db] (add-card-to-zone db :player-1 seal/card :battlefield)
          ;; Add target enchantment to battlefield
          [target-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
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
      ;; Confirm target selection
      (let [selection-with-target (assoc sel :selection/selected #{target-id})
            final-result (ability-events/confirm-ability-target (:db result) selection-with-target)
            db-after-confirm (:db final-result)]
        ;; Seal should be sacrificed (cost paid)
        (is (= :graveyard (:object/zone (q/get-object db-after-confirm seal-id)))
            "Seal should be in graveyard after sacrifice")
        ;; Should have stack-item for the ability
        (let [top-item (stack/get-top-stack-item db-after-confirm)]
          (is (= :activated-ability (:stack-item/type top-item))
              "Stack item should be activated ability type")
          ;; Resolve the ability from the stack
          (let [db-resolved (:db (game/resolve-one-item db-after-confirm))]
            ;; Target enchantment should be destroyed (moved to graveyard)
            (is (= :graveyard (:object/zone (q/get-object db-resolved target-id)))
                "Target enchantment should be in graveyard after ability resolves")
            ;; Stack should be empty
            (is (nil? (stack/get-top-stack-item db-resolved))
                "Stack should be empty after resolution")))))))
