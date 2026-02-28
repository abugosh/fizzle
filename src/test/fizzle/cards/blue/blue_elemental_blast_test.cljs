(ns fizzle.cards.blue.blue-elemental-blast-test
  "Tests for Blue Elemental Blast card.

   Blue Elemental Blast: U - Instant
   Choose one —
   * Counter target red spell.
   * Destroy target red permanent.

   Key behaviors:
   - Modal spell: player must choose a mode at cast time
   - Mode 1: Counter target red spell (restricted to red spells on stack)
   - Mode 2: Destroy target red permanent (restricted to red permanents on battlefield)
   - Cannot cast if no mode has valid targets
   - Color filtering restricts targets at cast time (not resolution)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.blue-elemental-blast :as beb]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest beb-card-definition-test
  (testing "card has correct oracle properties"
    (let [card beb/card]
      (is (= :blue-elemental-blast (:card/id card))
          "Card ID should be :blue-elemental-blast")
      (is (= "Blue Elemental Blast" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:blue 1} (:card/mana-cost card))
          "Mana cost should be {U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Choose one —\n• Counter target red spell.\n• Destroy target red permanent."
             (:card/text card))
          "Oracle text should match")))

  (testing "card has two modes"
    (let [modes (:card/modes beb/card)]
      (is (vector? modes)
          "Modes must be a vector")
      (is (= 2 (count modes))
          "Should have exactly two modes")))

  (testing "mode 1: counter target red spell"
    (let [mode (first (:card/modes beb/card))]
      (is (= "Counter target red spell" (:mode/label mode))
          "Mode label should match")
      (let [targeting (:mode/targeting mode)]
        (is (= 1 (count targeting))
            "Mode 1 should have one target requirement")
        (let [req (first targeting)]
          (is (= :target-spell (:target/id req))
              "Target ID should be :target-spell")
          (is (= :object (:target/type req))
              "Target type should be :object")
          (is (= :stack (:target/zone req))
              "Target zone should be :stack")
          (is (= :any (:target/controller req))
              "Should target any controller's spell")
          (is (= {:match/colors #{:red}} (:target/criteria req))
              "Should only target red spells")
          (is (true? (:target/required req))
              "Target should be required")))
      (let [effects (:mode/effects mode)]
        (is (= 1 (count effects))
            "Mode 1 should have one effect")
        (is (= :counter-spell (:effect/type (first effects)))
            "Effect should be counter-spell")
        (is (= :target-spell (:effect/target-ref (first effects)))
            "Effect should reference target spell"))))

  (testing "mode 2: destroy target red permanent"
    (let [mode (second (:card/modes beb/card))]
      (is (= "Destroy target red permanent" (:mode/label mode))
          "Mode label should match")
      (let [targeting (:mode/targeting mode)]
        (is (= 1 (count targeting))
            "Mode 2 should have one target requirement")
        (let [req (first targeting)]
          (is (= :target-permanent (:target/id req))
              "Target ID should be :target-permanent")
          (is (= :object (:target/type req))
              "Target type should be :object")
          (is (= :battlefield (:target/zone req))
              "Target zone should be :battlefield")
          (is (= :any (:target/controller req))
              "Should target any controller's permanent")
          (is (= {:match/colors #{:red}} (:target/criteria req))
              "Should only target red permanents")
          (is (true? (:target/required req))
              "Target should be required")))
      (let [effects (:mode/effects mode)]
        (is (= 1 (count effects))
            "Mode 2 should have one effect")
        (is (= :destroy (:effect/type (first effects)))
            "Effect should be destroy")
        (is (= :target-permanent (:effect/target-ref (first effects)))
            "Effect should reference target permanent")))))


;; === B. Cast-Resolve Happy Path ===

(deftest beb-counters-red-spell-test
  (testing "BEB mode 1 counters a red spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red spell (Lightning Bolt) on opponent's stack
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          ;; Add BEB to player's hand with U mana
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Cast BEB choosing counter mode, targeting Lightning Bolt
          counter-mode (first (:card/modes beb/card))
          db-cast (th/cast-mode-with-target db :player-1 beb-id counter-mode bolt-id)
          ;; Resolve BEB
          {:keys [db]} (th/resolve-top db-cast)]
      ;; Lightning Bolt should be countered -> graveyard
      (is (= :graveyard (:object/zone (q/get-object db bolt-id)))
          "Countered red spell should be in graveyard")
      ;; BEB should be in graveyard (resolved)
      (is (= :graveyard (:object/zone (q/get-object db beb-id)))
          "BEB should be in graveyard after resolving"))))


(deftest beb-destroys-red-permanent-test
  (testing "BEB mode 2 destroys a red permanent on the battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red permanent on battlefield
          [db perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          ;; Add BEB
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Cast BEB choosing destroy mode, targeting the red permanent
          destroy-mode (second (:card/modes beb/card))
          db-cast (th/cast-mode-with-target db :player-1 beb-id destroy-mode perm-id)
          {:keys [db]} (th/resolve-top db-cast)]
      ;; Red permanent should be destroyed -> graveyard
      (is (= :graveyard (:object/zone (q/get-object db perm-id)))
          "Destroyed red permanent should be in graveyard")
      ;; BEB should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db beb-id)))
          "BEB should be in graveyard after resolving"))))


;; === C. Cannot-Cast Guards ===

(deftest beb-cannot-cast-without-mana-test
  (testing "Cannot cast BEB without blue mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red spell on stack
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          ;; Add BEB but no mana
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 beb-id))
          "Should not be castable without mana"))))


(deftest beb-cannot-cast-with-no-red-targets-test
  (testing "Cannot cast BEB when no red spells or permanents exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack (not red)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add BEB with mana
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (false? (rules/can-cast? db :player-1 beb-id))
          "Should not be castable when no red targets exist"))))


(deftest beb-cannot-cast-from-graveyard-test
  (testing "Cannot cast BEB from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :graveyard :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Put a red spell on stack
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)]
      (is (false? (rules/can-cast? db :player-1 beb-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest beb-increments-storm-count-test
  (testing "Casting BEB increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red spell on stack
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          ;; Cast BEB
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          storm-before (q/get-storm-count db :player-1)
          counter-mode (first (:card/modes beb/card))
          db-cast (th/cast-mode-with-target db :player-1 beb-id counter-mode bolt-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === F. Targeting Tests ===

(deftest beb-counter-mode-only-targets-red-spells-test
  (testing "Counter mode only finds red spells as valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red spell on stack
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Check counter mode targeting
          counter-mode (first (:card/modes beb/card))
          target-req (first (:mode/targeting counter-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find only one valid target (red spell)")
      (is (= bolt-id (first targets))
          "Valid target should be the red spell"))))


(deftest beb-destroy-mode-only-targets-red-permanents-test
  (testing "Destroy mode only finds red permanents as valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red permanent on battlefield
          [db red-perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          ;; Put a blue permanent on battlefield
          [db _blue-perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          ;; Check destroy mode targeting
          destroy-mode (second (:card/modes beb/card))
          target-req (first (:mode/targeting destroy-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find only one valid target (red permanent)")
      (is (= red-perm-id (first targets))
          "Valid target should be the red permanent"))))


;; === G. Edge Cases ===

(deftest beb-castable-with-only-red-permanent-test
  (testing "BEB is castable when only red permanents exist (no red spells)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (true? (rules/can-cast? db :player-1 beb-id))
          "Should be castable with red permanent on battlefield"))))


(deftest beb-castable-with-only-red-spell-test
  (testing "BEB is castable when only red spells exist (no red permanents)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (true? (rules/can-cast? db :player-1 beb-id))
          "Should be castable with red spell on stack"))))


(deftest beb-fizzles-when-target-leaves-test
  (testing "BEB fizzles when targeted spell leaves the stack before resolution"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db beb-id] (th/add-card-to-zone db :blue-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          counter-mode (first (:card/modes beb/card))
          db-cast (th/cast-mode-with-target db :player-1 beb-id counter-mode bolt-id)
          db-bolt-gone (rules/move-spell-off-stack db-cast nil bolt-id)
          {:keys [db]} (th/resolve-top db-bolt-gone)]
      (is (= :graveyard (:object/zone (q/get-object db beb-id)))
          "BEB should be in graveyard after fizzling"))))
