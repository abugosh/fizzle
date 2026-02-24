(ns fizzle.cards.blue.annul-test
  "Tests for Annul card.

   Annul: U - Instant
   Counter target artifact or enchantment spell.

   Key behaviors:
   - Only targets artifact or enchantment spells on the stack
   - Cannot target instants, sorceries, or other spell types
   - Same counter behavior as Counterspell (graveyard/exile/remove)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.annul :as annul]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.game :as game]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest annul-card-definition-test
  (testing "card has correct oracle properties"
    (let [card annul/card]
      (is (= :annul (:card/id card))
          "Card ID should be :annul")
      (is (= "Annul" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:blue 1} (:card/mana-cost card))
          "Mana cost should be {U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Counter target artifact or enchantment spell." (:card/text card))
          "Oracle text should match")))

  (testing "card has correct targeting with type criteria"
    (let [targeting (:card/targeting annul/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-spell (:target/id req))
            "Target ID should be :target-spell")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :stack (:target/zone req))
            "Target zone should be :stack")
        (is (= :any (:target/controller req))
            "Should be able to target any controller's spell")
        (is (= {:match/types #{:artifact :enchantment}} (:target/criteria req))
            "Should only target artifacts or enchantments")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "card has one counter-spell effect"
    (let [effects (:card/effects annul/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :counter-spell (:effect/type effect))
            "Effect should be counter-spell")
        (is (= :target-spell (:effect/target-ref effect))
            "Effect should reference target spell")))))


;; === B. Cast-Resolve Happy Path ===

(deftest annul-counters-artifact-spell-test
  (testing "Annul counters an artifact spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put Lotus Petal on stack (artifact)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (rules/cast-spell db :player-2 petal-id)
          ;; Verify Lotus Petal is on stack
          _ (is (= :stack (:object/zone (q/get-object db petal-id)))
                "Lotus Petal should be on stack")
          ;; Add Annul with U mana
          [db annul-id] (th/add-card-to-zone db :annul :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Cast Annul targeting Lotus Petal
          target-req (first (:card/targeting annul/card))
          modes (rules/get-casting-modes db :player-1 annul-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id annul-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Lotus Petal should be countered -> graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved petal-id)))
          "Countered artifact should be in graveyard")
      ;; Annul should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved annul-id)))
          "Annul should be in graveyard after resolving"))))


(deftest annul-counters-enchantment-spell-test
  (testing "Annul counters an enchantment spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put Seal of Cleansing on stack (enchantment)
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :hand :player-2)
          db (mana/add-mana db :player-2 {:white 2})
          db (rules/cast-spell db :player-2 seal-id)
          ;; Add Annul
          [db annul-id] (th/add-card-to-zone db :annul :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          target-req (first (:card/targeting annul/card))
          modes (rules/get-casting-modes db :player-1 annul-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id annul-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{seal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved seal-id)))
          "Countered enchantment should be in graveyard"))))


;; === C. Cannot-Cast Guards ===

(deftest annul-cannot-cast-without-mana-test
  (testing "Cannot cast Annul without blue mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put artifact on stack
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (rules/cast-spell db :player-2 petal-id)
          ;; Add Annul but no mana
          [db annul-id] (th/add-card-to-zone db :annul :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 annul-id))
          "Should not be castable without mana"))))


(deftest annul-cannot-target-instant-on-stack-test
  (testing "Cannot target an instant spell with Annul"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put an instant (Dark Ritual) on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          target-req (first (:card/targeting annul/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find instant as valid target for Annul"))))


(deftest annul-cannot-target-sorcery-on-stack-test
  (testing "Cannot target a sorcery spell with Annul"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a sorcery (Duress) on stack
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 duress-id)
          target-req (first (:card/targeting annul/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find sorcery as valid target for Annul"))))


;; === D. Storm Count ===

(deftest annul-increments-storm-count-test
  (testing "Casting Annul increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put artifact on stack
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (rules/cast-spell db :player-2 petal-id)
          ;; Cast Annul
          [db annul-id] (th/add-card-to-zone db :annul :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          storm-before (q/get-storm-count db :player-1)
          target-req (first (:card/targeting annul/card))
          modes (rules/get-casting-modes db :player-1 annul-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id annul-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === G. Edge Cases ===

(deftest annul-targets-only-artifact-enchantment-test
  (testing "Annul only targets artifact and enchantment spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put artifact on stack
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (rules/cast-spell db :player-2 petal-id)
          ;; Put instant on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          target-req (first (:card/targeting annul/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      ;; Only the artifact should be a valid target
      (is (= 1 (count targets))
          "Should find only one valid target (artifact)")
      (is (= petal-id (first targets))
          "Valid target should be Lotus Petal (artifact)"))))


(deftest annul-fizzles-when-target-leaves-stack-test
  (testing "Annul fizzles when target spell resolves before it"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put artifact on stack
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          db (rules/cast-spell db :player-2 petal-id)
          ;; Cast Annul targeting petal
          [db annul-id] (th/add-card-to-zone db :annul :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          target-req (first (:card/targeting annul/card))
          modes (rules/get-casting-modes db :player-1 annul-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id annul-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{petal-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          ;; Simulate petal resolving first: move to battlefield (permanent)
          db-petal-resolved (zones/move-to-zone db-cast petal-id :battlefield)
          ;; Resolve Annul — target left stack, should fizzle
          result (game/resolve-one-item db-petal-resolved)
          db-resolved (:db result)]
      ;; Annul should still end up in graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved annul-id)))
          "Annul should be in graveyard after fizzling")
      ;; Lotus Petal should still be on battlefield (it resolved before counter)
      (is (= :battlefield (:object/zone (q/get-object db-resolved petal-id)))
          "Lotus Petal should be on battlefield (it resolved)"))))


(deftest annul-cannot-cast-with-only-nonartifact-nonenchantment-on-stack-test
  (testing "Annul is not castable when only instants/sorceries are on stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put an instant on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Add Annul with mana
          [db annul-id] (th/add-card-to-zone db :annul :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (false? (rules/can-cast? db :player-1 annul-id))
          "Should not be castable when no artifact/enchantment on stack"))))
