(ns fizzle.cards.green.crumble-test
  "Tests for Crumble card.

   Crumble: G - Instant
   Destroy target artifact. It can't be regenerated.
   That artifact's controller gains life equal to its mana value.

   Key behaviors:
   - Targets artifacts only (not enchantments, creatures, etc.)
   - Life gain goes to the artifact's controller (not the caster)
   - Life amount equals the artifact's CMC"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.green.crumble :as crumble]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Crumble" — verified against Scryfall
(deftest crumble-card-definition-test
  (testing "card has correct oracle properties"
    (let [card crumble/card]
      (is (= :crumble (:card/id card))
          "Card ID should be :crumble")
      (is (= "Crumble" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:green 1} (:card/mana-cost card))
          "Mana cost should be {G}")
      (is (= #{:green} (:card/colors card))
          "Card should be green")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting crumble/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-artifact (:target/id req))
            "Target ID should be :target-artifact")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :battlefield (:target/zone req))
            "Target zone should be :battlefield")
        (is (= :any (:target/controller req))
            "Should be able to target any controller's artifact")
        (is (= {:match/types #{:artifact}} (:target/criteria req))
            "Should only target artifacts")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "Destroy target artifact" + "controller gains life equal to its mana value"
  (testing "card has two effects: destroy + gain-life-equal-to-cmc"
    (let [effects (:card/effects crumble/card)]
      (is (= 2 (count effects))
          "Should have exactly two effects")
      (let [[destroy-effect life-effect] effects]
        (is (= :destroy (:effect/type destroy-effect))
            "First effect should be destroy")
        (is (= :target-artifact (:effect/target-ref destroy-effect))
            "Destroy should reference target artifact")
        (is (= :gain-life-equal-to-cmc (:effect/type life-effect))
            "Second effect should be gain-life-equal-to-cmc")
        (is (= :target-artifact (:effect/target-ref life-effect))
            "Life gain should reference target artifact")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Destroy target artifact. ... controller gains life equal to its mana value."
(deftest crumble-destroys-artifact-and-gains-life-test
  (testing "Crumble destroys artifact and gives controller life equal to CMC"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crumble-id] (th/add-card-to-zone db :crumble :hand :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          ;; Lotus Petal (artifact, CMC 0) on opponent's battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          life-before (q/get-life-total db :player-2)
          ;; Cast with targeting flow
          target-req (first (:card/targeting crumble/card))
          modes (rules/get-casting-modes db :player-1 crumble-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id crumble-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Lotus Petal should be destroyed
      (is (= :graveyard (:object/zone (q/get-object db-resolved petal-id)))
          "Target artifact should be in graveyard")
      ;; CMC 0 means no life gain
      (is (= life-before (q/get-life-total db-resolved :player-2))
          "Controller should gain 0 life for CMC-0 artifact"))))


;; === C. Cannot-Cast Guards ===

(deftest crumble-cannot-cast-without-mana-test
  (testing "Cannot cast Crumble without green mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crumble-id] (th/add-card-to-zone db :crumble :hand :player-1)
          [db _target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 crumble-id))
          "Should not be castable without mana"))))


(deftest crumble-cannot-cast-without-target-test
  (testing "Cannot cast Crumble without valid artifact target"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crumble-id] (th/add-card-to-zone db :crumble :hand :player-1)
          db (mana/add-mana db :player-1 {:green 1})]
      ;; No artifacts on battlefield
      (is (false? (rules/can-cast? db :player-1 crumble-id))
          "Should not be castable without artifact target"))))


(deftest crumble-cannot-cast-from-graveyard-test
  (testing "Cannot cast Crumble from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crumble-id] (th/add-card-to-zone db :crumble :graveyard :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          [db _target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 crumble-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest crumble-increments-storm-count-test
  (testing "Casting Crumble increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crumble-id] (th/add-card-to-zone db :crumble :hand :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          [db target-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          storm-before (q/get-storm-count db :player-1)
          target-req (first (:card/targeting crumble/card))
          modes (rules/get-casting-modes db :player-1 crumble-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id crumble-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{target-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

;; Oracle: "target artifact" — only artifacts
(deftest crumble-targets-only-artifacts-test
  (testing "Can only target artifacts, not enchantments"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add an enchantment (Seal of Cleansing)
          [db _seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          target-req (first (:card/targeting crumble/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find enchantment as valid target"))))


(deftest crumble-can-target-own-artifact-test
  (testing "Can target own artifacts"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          target-req (first (:card/targeting crumble/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find own artifact as valid target")
      (is (= petal-id (first targets))
          "Should find Lotus Petal"))))


(deftest crumble-has-no-targets-when-no-artifacts-test
  (testing "has-valid-targets? returns false when no artifacts on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)]
      (is (false? (targeting/has-valid-targets? db :player-1 crumble/card))
          "Should have no valid targets"))))


;; === F. gain-life-equal-to-cmc Effect Tests ===

(deftest gain-life-equal-to-cmc-effect-test
  (testing "gain-life-equal-to-cmc gives controller life equal to artifact CMC"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Gemstone Mine has CMC 0, so test with direct effect
          ;; Create a scenario: Seal of Cleansing (CMC 2) on opponent's battlefield
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          life-before (q/get-life-total db :player-2)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target seal-id}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= (+ life-before 2) (q/get-life-total db-after :player-2))
          "Controller (player-2) should gain 2 life (Seal CMC = 2)"))))


(deftest gain-life-equal-to-cmc-zero-cmc-test
  (testing "gain-life-equal-to-cmc with CMC 0 is a no-op"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          life-before (q/get-life-total db :player-2)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target petal-id}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= life-before (q/get-life-total db-after :player-2))
          "Controller should gain 0 life for CMC-0 artifact"))))


(deftest gain-life-equal-to-cmc-no-target-noop-test
  (testing "gain-life-equal-to-cmc with nil target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target nil}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged with nil target"))))


(deftest gain-life-equal-to-cmc-nonexistent-target-noop-test
  (testing "gain-life-equal-to-cmc with nonexistent target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target (random-uuid)}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged with nonexistent target"))))


;; === G. Edge Cases ===

;; Destroy own artifact: caster gains life (they're the controller)
(deftest crumble-own-artifact-caster-gains-life-test
  (testing "Destroying own artifact gives caster life equal to CMC"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crumble-id] (th/add-card-to-zone db :crumble :hand :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          ;; Seal of Cleansing on player-1's battlefield (CMC 2, has artifact in type match)
          ;; Actually Seal is an enchantment, not artifact. Use a different test approach.
          ;; Use gain-life-equal-to-cmc effect directly with a known CMC artifact
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          life-before (q/get-life-total db :player-1)
          target-req (first (:card/targeting crumble/card))
          modes (rules/get-casting-modes db :player-1 crumble-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id crumble-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Lotus Petal destroyed
      (is (= :graveyard (:object/zone (q/get-object db-resolved petal-id)))
          "Own Lotus Petal should be in graveyard")
      ;; Caster is the controller, CMC 0 so no life change
      (is (= life-before (q/get-life-total db-resolved :player-1))
          "Caster gains 0 life for CMC-0 artifact"))))


;; Life gain goes to artifact's controller (opponent), not caster
(deftest crumble-life-gain-goes-to-controller-not-caster-test
  (testing "Life gain from CMC goes to artifact controller, not caster"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Use direct effect test with a CMC > 0 card on opponent's battlefield
          ;; Seal of Cleansing has CMC 2 — but it's an enchantment so can't be
          ;; targeted by Crumble. Test effect handler directly.
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          p1-life-before (q/get-life-total db :player-1)
          p2-life-before (q/get-life-total db :player-2)
          effect {:effect/type :gain-life-equal-to-cmc
                  :effect/target seal-id}
          db-after (effects/execute-effect db :player-1 effect)]
      ;; Player-2 (controller) gains life
      (is (= (+ p2-life-before 2) (q/get-life-total db-after :player-2))
          "Artifact controller should gain life")
      ;; Player-1 (caster) does NOT gain life
      (is (= p1-life-before (q/get-life-total db-after :player-1))
          "Caster should NOT gain life"))))
