(ns fizzle.cards.blue.chain-of-vapor-test
  "Tests for Chain of Vapor card.

   Chain of Vapor: U - Instant
   Return target nonland permanent to its owner's hand. Then that
   permanent's controller may sacrifice a land. If the player does,
   they may copy this spell and may choose a new target for the copy.

   Key behaviors:
   - Targets nonland permanents only (artifacts, enchantments, etc.)
   - Bounces target to owner's hand (not controller's)
   - Chain mechanic: target's controller may sacrifice a land to copy"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.chain-of-vapor :as chain-of-vapor]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Chain of Vapor" — verified against Scryfall
(deftest chain-of-vapor-card-definition-test
  (testing "card has correct oracle properties"
    (let [card chain-of-vapor/card]
      (is (= :chain-of-vapor (:card/id card))
          "Card ID should be :chain-of-vapor")
      (is (= "Chain of Vapor" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:blue 1} (:card/mana-cost card))
          "Mana cost should be {U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting chain-of-vapor/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-permanent (:target/id req))
            "Target ID should be :target-permanent")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :battlefield (:target/zone req))
            "Target zone should be :battlefield")
        (is (= :any (:target/controller req))
            "Should be able to target any controller's permanent")
        (is (= {:match/not-types #{:land}} (:target/criteria req))
            "Should target nonland permanents via :match/not-types")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "Return target nonland permanent to its owner's hand."
  (testing "card has bounce effect"
    (let [card-effects (:card/effects chain-of-vapor/card)]
      (is (= 1 (count card-effects))
          "Should have exactly one effect")
      (let [bounce-effect (first card-effects)]
        (is (= :bounce (:effect/type bounce-effect))
            "Effect should be :bounce")
        (is (= :target-permanent (:effect/target-ref bounce-effect))
            "Bounce should reference target permanent")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Return target nonland permanent to its owner's hand."
(deftest chain-of-vapor-bounces-permanent-test
  (testing "Chain of Vapor returns target nonland permanent to owner's hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Lotus Petal (artifact) on opponent's battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          ;; Cast with targeting flow
          target-req (first (:card/targeting chain-of-vapor/card))
          modes (rules/get-casting-modes db :player-1 cov-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cov-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Lotus Petal should be bounced to hand
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id)))
          "Target permanent should be returned to owner's hand"))))


;; Oracle: bounce works on own permanents too
(deftest chain-of-vapor-bounces-own-permanent-test
  (testing "Chain of Vapor can bounce own permanent"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Lotus Petal on player-1's battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          target-req (first (:card/targeting chain-of-vapor/card))
          modes (rules/get-casting-modes db :player-1 cov-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cov-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id)))
          "Own permanent should be returned to hand"))))


;; === C. Cannot-Cast Guards ===

(deftest chain-of-vapor-cannot-cast-without-mana-test
  (testing "Cannot cast Chain of Vapor without blue mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          [db _target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 cov-id))
          "Should not be castable without mana"))))


(deftest chain-of-vapor-cannot-cast-without-target-test
  (testing "Cannot cast Chain of Vapor without valid nonland permanent target"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      ;; No nonland permanents on battlefield
      (is (false? (rules/can-cast? db :player-1 cov-id))
          "Should not be castable without nonland permanent target"))))


(deftest chain-of-vapor-cannot-cast-from-graveyard-test
  (testing "Cannot cast Chain of Vapor from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :graveyard :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db _target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 cov-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest chain-of-vapor-increments-storm-count-test
  (testing "Casting Chain of Vapor increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          storm-before (q/get-storm-count db :player-1)
          target-req (first (:card/targeting chain-of-vapor/card))
          modes (rules/get-casting-modes db :player-1 cov-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cov-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{target-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

;; Oracle: "target nonland permanent" — lands are excluded
(deftest chain-of-vapor-cannot-target-lands-test
  (testing "Cannot target lands"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add a land to the battlefield
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          target-req (first (:card/targeting chain-of-vapor/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find lands as valid targets"))))


;; Oracle: "nonland permanent" — artifacts are valid
(deftest chain-of-vapor-can-target-artifacts-test
  (testing "Can target artifacts"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting chain-of-vapor/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find artifact as valid target")
      (is (= petal-id (first targets))
          "Should find Lotus Petal"))))


;; Oracle: "nonland permanent" — enchantments are valid
(deftest chain-of-vapor-can-target-enchantments-test
  (testing "Can target enchantments"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          target-req (first (:card/targeting chain-of-vapor/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find enchantment as valid target")
      (is (= seal-id (first targets))
          "Should find Seal of Cleansing"))))


(deftest chain-of-vapor-has-no-targets-when-only-lands-test
  (testing "has-valid-targets? returns false when only lands on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)]
      (is (false? (targeting/has-valid-targets? db :player-1 chain-of-vapor/card))
          "Should have no valid targets when only lands exist"))))


(deftest chain-of-vapor-can-target-own-permanents-test
  (testing "Can target own nonland permanents"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          target-req (first (:card/targeting chain-of-vapor/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find own artifact as valid target")
      (is (= petal-id (first targets))
          "Should find own Lotus Petal"))))


;; === F. Bounce Effect Tests ===

;; Oracle: "Return target nonland permanent to its owner's hand."
(deftest bounce-effect-returns-to-hand-test
  (testing "Bounce effect moves target from battlefield to hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          effect {:effect/type :bounce
                  :effect/target petal-id}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= :hand (:object/zone (q/get-object db-after petal-id)))
          "Target should be moved to hand"))))


(deftest bounce-effect-no-target-noop-test
  (testing "Bounce effect with nil target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :bounce
                  :effect/target nil}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged with nil target"))))


(deftest bounce-effect-nonexistent-target-noop-test
  (testing "Bounce effect with nonexistent target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :bounce
                  :effect/target (random-uuid)}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged with nonexistent target"))))


;; === G. Edge Cases ===

;; Lands should be excluded even when mixed with nonland permanents
(deftest chain-of-vapor-only-targets-nonlands-in-mixed-battlefield-test
  (testing "Only nonland permanents are targetable in mixed battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting chain-of-vapor/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should only find the nonland permanent")
      (is (= petal-id (first targets))
          "Should find artifact but not land"))))


;; Cannot cast when only lands exist as permanents
(deftest chain-of-vapor-cannot-cast-with-only-lands-test
  (testing "Cannot cast when only lands on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Only lands on battlefield
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 cov-id))
          "Should not be castable when only lands on battlefield"))))


;; Spell goes to graveyard after resolution (standard instant behavior)
(deftest chain-of-vapor-goes-to-graveyard-after-resolution-test
  (testing "Chain of Vapor goes to graveyard after resolving"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting chain-of-vapor/card))
          modes (rules/get-casting-modes db :player-1 cov-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cov-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved cov-id)))
          "Chain of Vapor should be in graveyard after resolving"))))
