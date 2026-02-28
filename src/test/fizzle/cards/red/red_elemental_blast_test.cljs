(ns fizzle.cards.red.red-elemental-blast-test
  "Tests for Red Elemental Blast card.

   Red Elemental Blast: R - Instant
   Choose one —
   * Counter target blue spell.
   * Destroy target blue permanent.

   Key behaviors:
   - Modal spell: player must choose a mode at cast time
   - Mode 1: Counter target blue spell (restricted to blue spells on stack)
   - Mode 2: Destroy target blue permanent (restricted to blue permanents on battlefield)
   - Cannot cast if no mode has valid targets
   - Color filtering restricts targets at cast time (not resolution)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.red.red-elemental-blast :as reb]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest reb-card-definition-test
  (testing "card has correct oracle properties"
    (let [card reb/card]
      (is (= :red-elemental-blast (:card/id card))
          "Card ID should be :red-elemental-blast")
      (is (= "Red Elemental Blast" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:red 1} (:card/mana-cost card))
          "Mana cost should be {R}")
      (is (= #{:red} (:card/colors card))
          "Card should be red")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Choose one —\n• Counter target blue spell.\n• Destroy target blue permanent."
             (:card/text card))
          "Oracle text should match")))

  (testing "card has two modes"
    (let [modes (:card/modes reb/card)]
      (is (vector? modes)
          "Modes must be a vector")
      (is (= 2 (count modes))
          "Should have exactly two modes")))

  (testing "mode 1: counter target blue spell"
    (let [mode (first (:card/modes reb/card))]
      (is (= "Counter target blue spell" (:mode/label mode))
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
          (is (= {:match/colors #{:blue}} (:target/criteria req))
              "Should only target blue spells")
          (is (true? (:target/required req))
              "Target should be required")))
      (let [effects (:mode/effects mode)]
        (is (= 1 (count effects))
            "Mode 1 should have one effect")
        (is (= :counter-spell (:effect/type (first effects)))
            "Effect should be counter-spell")
        (is (= :target-spell (:effect/target-ref (first effects)))
            "Effect should reference target spell"))))

  (testing "mode 2: destroy target blue permanent"
    (let [mode (second (:card/modes reb/card))]
      (is (= "Destroy target blue permanent" (:mode/label mode))
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
          (is (= {:match/colors #{:blue}} (:target/criteria req))
              "Should only target blue permanents")
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

(deftest reb-counters-blue-spell-test
  (testing "REB mode 1 counters a blue spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell (Opt) on opponent's stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add REB to player's hand with R mana
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          ;; Cast REB choosing counter mode, targeting Opt
          counter-mode (first (:card/modes reb/card))
          db-cast (th/cast-mode-with-target db :player-1 reb-id counter-mode opt-id)
          {:keys [db]} (th/resolve-top db-cast)]
      ;; Opt should be countered -> graveyard
      (is (= :graveyard (:object/zone (q/get-object db opt-id)))
          "Countered blue spell should be in graveyard")
      ;; REB should be in graveyard (resolved)
      (is (= :graveyard (:object/zone (q/get-object db reb-id)))
          "REB should be in graveyard after resolving"))))


(deftest reb-destroys-blue-permanent-test
  (testing "REB mode 2 destroys a blue permanent on the battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue permanent on battlefield
          [db perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          ;; Add REB
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          ;; Cast REB choosing destroy mode, targeting the blue permanent
          destroy-mode (second (:card/modes reb/card))
          db-cast (th/cast-mode-with-target db :player-1 reb-id destroy-mode perm-id)
          {:keys [db]} (th/resolve-top db-cast)]
      ;; Blue permanent should be destroyed -> graveyard
      (is (= :graveyard (:object/zone (q/get-object db perm-id)))
          "Destroyed blue permanent should be in graveyard")
      ;; REB should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db reb-id)))
          "REB should be in graveyard after resolving"))))


;; === C. Cannot-Cast Guards ===

(deftest reb-cannot-cast-without-mana-test
  (testing "Cannot cast REB without red mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack for targeting
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add REB but no mana
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 reb-id))
          "Should not be castable without mana"))))


(deftest reb-cannot-cast-with-no-valid-targets-test
  (testing "Cannot cast REB when no blue spells or permanents exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a non-blue spell on stack (Dark Ritual is black)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Add REB with mana
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})]
      (is (false? (rules/can-cast? db :player-1 reb-id))
          "Should not be castable when no blue targets exist"))))


(deftest reb-cannot-cast-from-graveyard-test
  (testing "Cannot cast REB from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :graveyard :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)]
      (is (false? (rules/can-cast? db :player-1 reb-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest reb-increments-storm-count-test
  (testing "Casting REB increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Cast REB
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          storm-before (q/get-storm-count db :player-1)
          counter-mode (first (:card/modes reb/card))
          db-cast (th/cast-mode-with-target db :player-1 reb-id counter-mode opt-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === F. Targeting Tests ===

(deftest reb-counter-mode-only-targets-blue-spells-test
  (testing "Counter mode only finds blue spells as valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Put a black spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Check counter mode targeting
          counter-mode (first (:card/modes reb/card))
          target-req (first (:mode/targeting counter-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find only one valid target (blue spell)")
      (is (= opt-id (first targets))
          "Valid target should be the blue spell"))))


(deftest reb-destroy-mode-only-targets-blue-permanents-test
  (testing "Destroy mode only finds blue permanents as valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue permanent on battlefield
          [db blue-perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          ;; Put a non-blue permanent on battlefield (Lightning Bolt is red)
          [db _red-perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          ;; Check destroy mode targeting
          destroy-mode (second (:card/modes reb/card))
          target-req (first (:mode/targeting destroy-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find only one valid target (blue permanent)")
      (is (= blue-perm-id (first targets))
          "Valid target should be the blue permanent"))))


(deftest reb-cannot-target-non-blue-spell-test
  (testing "Counter mode cannot target non-blue spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a black spell on stack only
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Check counter mode targeting
          counter-mode (first (:card/modes reb/card))
          target-req (first (:mode/targeting counter-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should find no valid targets (no blue spells)"))))


;; === G. Edge Cases ===

(deftest reb-castable-with-only-blue-permanent-test
  (testing "REB is castable when only blue permanents exist (no blue spells)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue permanent on battlefield (no blue spells on stack)
          [db _perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          ;; Add REB with mana
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})]
      (is (true? (rules/can-cast? db :player-1 reb-id))
          "Should be castable with blue permanent on battlefield"))))


(deftest reb-castable-with-only-blue-spell-test
  (testing "REB is castable when only blue spells exist (no blue permanents)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add REB with mana
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})]
      (is (true? (rules/can-cast? db :player-1 reb-id))
          "Should be castable with blue spell on stack"))))


(deftest reb-fizzles-when-target-leaves-test
  (testing "REB fizzles when targeted spell leaves the stack before resolution"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Cast REB targeting Opt
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          counter-mode (first (:card/modes reb/card))
          db-cast (th/cast-mode-with-target db :player-1 reb-id counter-mode opt-id)
          ;; Move Opt off stack (simulate it resolving)
          db-opt-gone (rules/move-spell-off-stack db-cast nil opt-id)
          ;; Now resolve REB — target gone, should fizzle
          {:keys [db]} (th/resolve-top db-opt-gone)]
      ;; REB should still end up in graveyard (fizzled)
      (is (= :graveyard (:object/zone (q/get-object db reb-id)))
          "REB should be in graveyard after fizzling"))))
