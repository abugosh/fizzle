(ns fizzle.cards.lands.wasteland-test
  "Tests for Wasteland.

   Wasteland: Land
   {T}: Add {C}.
   {T}, Sacrifice this land: Destroy target nonbasic land.

   What this file tests:
   - Card definition (fields, two abilities, nonbasic land targeting)
   - Mana ability (tap for 1 colorless)
   - Sacrifice-destroy ability (cost, stack-item, resolution)
   - Nonbasic-only targeting (basic lands NOT valid, nonbasic lands ARE valid)
   - Cannot-activate guards (wrong zone, already tapped)
   - Edge cases (targets own lands, no nonbasic lands on battlefield)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lands.wasteland :as wasteland]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.lands :as lands]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Wasteland" — verified against Scryfall
(deftest wasteland-card-definition-test
  (testing "card has correct oracle properties"
    (let [card wasteland/card]
      (is (= :wasteland (:card/id card))
          "Card ID should be :wasteland")
      (is (= "Wasteland" (:card/name card))
          "Card name should match oracle")
      (is (= 0 (:card/cmc card))
          "CMC should be 0")
      (is (= {} (:card/mana-cost card))
          "Mana cost should be empty")
      (is (= #{} (:card/colors card))
          "Card should be colorless")
      (is (= #{:land} (:card/types card))
          "Card should be exactly a land")
      (is (= "{T}: Add {C}.\n{T}, Sacrifice this land: Destroy target nonbasic land."
             (:card/text card))
          "Text should match Scryfall oracle_text")))

  ;; Oracle: "{T}: Add {C}." + "{T}, Sacrifice this land: Destroy target nonbasic land."
  (testing "card has exactly two abilities"
    (is (= 2 (count (:card/abilities wasteland/card)))
        "Should have two abilities"))

  ;; Oracle: "{T}: Add {C}."
  (testing "Ability 0 is a mana ability producing 1 colorless"
    (let [ability (nth (:card/abilities wasteland/card) 0)]
      (is (= :mana (:ability/type ability))
          "Ability 0 type should be :mana")
      (is (= {:tap true} (:ability/cost ability))
          "Ability 0 cost should be tap only")
      (is (= {:colorless 1} (:ability/produces ability))
          "Ability 0 should produce 1 colorless")))

  ;; Oracle: "{T}, Sacrifice this land: Destroy target nonbasic land."
  (testing "Ability 1 is an activated ability with tap+sacrifice cost"
    (let [ability (nth (:card/abilities wasteland/card) 1)]
      (is (= :activated (:ability/type ability))
          "Ability 1 type should be :activated")
      (is (true? (get-in ability [:ability/cost :tap]))
          "Ability 1 cost should include tap")
      (is (true? (get-in ability [:ability/cost :sacrifice-self]))
          "Ability 1 cost should include sacrifice-self")
      (is (= "Destroy target nonbasic land" (:ability/description ability))
          "Ability description should match oracle")))

  ;; Oracle: "target nonbasic land"
  (testing "Ability 1 targets land but excludes basic supertype"
    (let [ability (nth (:card/abilities wasteland/card) 1)
          targeting (:ability/targeting ability)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-land (:target/id req))
            "Target ID should be :target-land")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :battlefield (:target/zone req))
            "Target zone should be :battlefield")
        (is (= :any (:target/controller req))
            "Should be able to target any controller's land")
        (is (= #{:land} (get-in req [:target/criteria :match/types]))
            "Criteria should require :land type")
        (is (= #{:basic} (get-in req [:target/criteria :match/not-supertypes]))
            "Criteria should exclude :basic supertype")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "Destroy target nonbasic land."
  (testing "Ability 1 has destroy effect referencing target-land"
    (let [ability (nth (:card/abilities wasteland/card) 1)
          effects (:ability/effects ability)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :destroy (:effect/type effect))
            "Effect type should be :destroy")
        (is (= :target-land (:effect/target-ref effect))
            "Effect should reference :target-land")))))


;; === B. Mana-ability happy path ===

;; Oracle: "{T}: Add {C}."
(deftest wasteland-tap-for-colorless-test
  (testing "Wasteland taps for 1 colorless mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :wasteland :hand :player-1)
          db-played (lands/play-land db' :player-1 obj-id)
          _ (is (= :battlefield (th/get-object-zone db-played obj-id))
                "Precondition: Wasteland on battlefield")
          _ (is (= 0 (:colorless (q/get-mana-pool db-played :player-1)))
                "Precondition: colorless pool is 0")
          db-tapped (engine-mana/activate-mana-ability db-played :player-1 obj-id :colorless 0)]
      (is (= 1 (:colorless (q/get-mana-pool db-tapped :player-1)))
          "Colorless mana should be added to pool"))))


;; === B. Activated-ability happy path (destroy nonbasic land) ===

;; Oracle: "{T}, Sacrifice this land: Destroy target nonbasic land."
(deftest wasteland-activate-destroys-nonbasic-land-test
  (testing "Activating ability 1 sacrifices Wasteland and destroys target nonbasic land"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          ;; Opponent has a nonbasic land — City of Brass
          [db target-land-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-2)
          ability (nth (:card/abilities wasteland/card) 1)
          _ (is (true? (abilities/can-activate? db wasteland-id ability :player-1))
                "Ability should be activatable")
          ;; Activate ability index 1 (index 0 is the mana ability)
          result (ability-events/activate-ability db :player-1 wasteland-id 1)
          sel (:pending-selection result)]
      (is (= :ability-targeting (:selection/type sel))
          "Should enter targeting selection")
      (is (contains? (set (:selection/valid-targets sel)) target-land-id)
          "City of Brass should be a valid target")
      ;; Confirm the target
      (let [selection-with-target (assoc sel :selection/selected #{target-land-id})
            confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
            db-after-confirm (:db confirm-result)]
        ;; Wasteland sacrificed (cost paid)
        (is (= :graveyard (th/get-object-zone db-after-confirm wasteland-id))
            "Wasteland should be in graveyard after sacrifice cost")
        ;; Stack has the activated ability
        (let [top-item (stack/get-top-stack-item db-after-confirm)]
          (is (= :activated-ability (:stack-item/type top-item))
              "Stack item should be activated ability")
          ;; Resolve the ability
          (let [db-resolved (:db (resolution/resolve-one-item db-after-confirm))]
            ;; Target nonbasic land destroyed
            (is (= :graveyard (th/get-object-zone db-resolved target-land-id))
                "Target nonbasic land should be in graveyard after resolution")))))))


;; === E. Targeting tests ===

;; Oracle: "target nonbasic land" — basic lands are NOT legal targets
(deftest wasteland-cannot-target-basic-land-test
  (testing "Basic lands are NOT valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Opponent controls only basic lands
          [db _island-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db _mountain-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          ability (nth (:card/abilities wasteland/card) 1)
          target-req (first (:ability/targeting ability))
          valid-targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? valid-targets)
          "No valid targets: all basics are excluded"))))


;; Oracle: "target nonbasic land" — nonbasic lands ARE legal targets
(deftest wasteland-can-target-nonbasic-land-test
  (testing "Nonbasic lands (no :basic supertype) are valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cob-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-2)
          [db cv-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-2)
          ability (nth (:card/abilities wasteland/card) 1)
          target-req (first (:ability/targeting ability))
          valid-targets (set (targeting/find-valid-targets db :player-1 target-req))]
      (is (= 2 (count valid-targets))
          "Should find both nonbasic lands")
      (is (contains? valid-targets cob-id)
          "City of Brass should be a valid target")
      (is (contains? valid-targets cv-id)
          "Crystal Vein should be a valid target"))))


;; Oracle: non-land permanents are NOT legal targets (criteria requires :land type)
(deftest wasteland-cannot-target-non-land-permanents-test
  (testing "Non-land permanents are NOT valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Opponent controls an artifact and an enchantment — neither is a land
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db _seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          ability (nth (:card/abilities wasteland/card) 1)
          target-req (first (:ability/targeting ability))
          valid-targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? valid-targets)
          "No valid targets: non-land permanents don't match :land criteria"))))


;; Oracle: "target nonbasic land" — can target own nonbasic land
(deftest wasteland-can-target-own-nonbasic-land-test
  (testing "Can target your own nonbasic land"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db own-land-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ability (nth (:card/abilities wasteland/card) 1)
          target-req (first (:ability/targeting ability))
          valid-targets (set (targeting/find-valid-targets db :player-1 target-req))]
      (is (contains? valid-targets own-land-id)
          "Own nonbasic land should be a valid target (controller :any)"))))


;; === C. Cannot-activate guards ===

(deftest wasteland-cannot-activate-when-tapped-test
  (testing "Cannot activate ability 1 when already tapped"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          [db _target-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-2)
          obj-eid (q/get-object-eid db wasteland-id)
          db-tapped (d/db-with db [[:db/add obj-eid :object/tapped true]])
          result (ability-events/activate-ability db-tapped :player-1 wasteland-id 1)]
      (is (nil? (:pending-selection result))
          "Should not enter targeting selection when tapped")
      (is (= :battlefield (th/get-object-zone (:db result) wasteland-id))
          "Wasteland should NOT be sacrificed (ability did not activate)"))))


(deftest wasteland-cannot-activate-from-graveyard-test
  (testing "Cannot activate ability 1 from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :graveyard :player-1)
          [db _target-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-2)
          result (ability-events/activate-ability db :player-1 wasteland-id 1)]
      (is (= :graveyard (th/get-object-zone (:db result) wasteland-id))
          "Wasteland should remain in graveyard")
      (is (true? (q/stack-empty? (:db result)))
          "Stack should be empty (ability did not activate)"))))


;; === G. Edge cases ===

;; Oracle: "target nonbasic land" — destruction sends target to graveyard, not Wasteland's controller
(deftest wasteland-destroys-opponents-land-stays-in-opponents-graveyard-test
  (testing "Destroyed land goes to its owner's graveyard (not caster's)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          [db target-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-2)
          gy-before-p2 (th/get-zone-count db :graveyard :player-2)
          gy-before-p1 (th/get-zone-count db :graveyard :player-1)
          result (ability-events/activate-ability db :player-1 wasteland-id 1)
          sel (:pending-selection result)
          selection-with-target (assoc sel :selection/selected #{target-id})
          confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          db-resolved (:db (resolution/resolve-one-item (:db confirm-result)))]
      ;; Opponent's graveyard gained their destroyed land
      (is (= (inc gy-before-p2) (th/get-zone-count db-resolved :graveyard :player-2))
          "Opponent's graveyard should have gained the destroyed land")
      ;; Player-1's graveyard gained the sacrificed Wasteland (1 card)
      (is (= (inc gy-before-p1) (th/get-zone-count db-resolved :graveyard :player-1))
          "Controller's graveyard should have the sacrificed Wasteland")
      (is (= :graveyard (th/get-object-zone db-resolved wasteland-id))
          "Wasteland in owner's graveyard")
      (is (= :graveyard (th/get-object-zone db-resolved target-id))
          "Target land in owner's graveyard"))))


;; Oracle: "target nonbasic land" — :target/required true means activation requires a valid target
(deftest wasteland-cannot-activate-without-nonbasic-target-test
  (testing "Cannot activate ability 1 when no nonbasic land exists"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          ;; Only basic lands exist — no valid target
          [db _island-id] (th/add-card-to-zone db :island :battlefield :player-2)
          result (ability-events/activate-ability db :player-1 wasteland-id 1)]
      (is (= :battlefield (th/get-object-zone (:db result) wasteland-id))
          "Wasteland should NOT be sacrificed (no valid target, activation aborted)")
      (is (true? (q/stack-empty? (:db result)))
          "Stack should be empty"))))


;; MTG rules 601.2c: self-targeting is legal. The ability goes on the stack,
;; Wasteland is sacrificed as cost, and on resolution the target is illegal
;; (not on the battlefield) so the ability fizzles with no effect.
(deftest wasteland-can-target-itself-test
  (testing "Wasteland is a legal target for its own destroy ability (will fizzle on resolve)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          ability (nth (:card/abilities wasteland/card) 1)
          target-req (first (:ability/targeting ability))
          valid-targets (set (targeting/find-valid-targets db :player-1 target-req))]
      (is (contains? valid-targets wasteland-id)
          "Wasteland itself is a valid target (nonbasic land on battlefield)"))))
