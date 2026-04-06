(ns fizzle.cards.blue.chill-test
  "Tests for Chill static cost modifier.

   Chill: {1}{U} - Enchantment
   Red spells cost {2} more to cast."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.chill :as chill]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

(deftest chill-card-definition-test
  (testing "Chill card definition is complete and correct"
    (let [card chill/card]
      (is (= :chill (:card/id card))
          "Card ID should be :chill")
      (is (= "Chill" (:card/name card))
          "Card name should be 'Chill'")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card))
          "Mana cost should be {1}{U}")
      (is (= #{:blue} (:card/colors card))
          "Should be blue")
      (is (= #{:enchantment} (:card/types card))
          "Should be exactly an enchantment")
      (is (= "Red spells cost {2} more to cast." (:card/text card))
          "Card text should match")))

  (testing "Chill has exactly one static ability with color criteria"
    (let [abilities (:card/static-abilities chill/card)]
      (is (= 1 (count abilities))
          "Should have exactly 1 static ability")
      (let [ability (first abilities)]
        (is (= :cost-modifier (:static/type ability))
            "Static type should be :cost-modifier")
        (is (= 2 (:modifier/amount ability))
            "Modifier amount should be 2")
        (is (= :increase (:modifier/direction ability))
            "Modifier direction should be :increase")
        (is (nil? (:modifier/condition ability))
            "Should have no condition (always applies)")
        (is (= {:criteria/type :spell-color
                :criteria/colors #{:red}}
               (:modifier/criteria ability))
            "Should have spell-color criteria for red")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest chill-cast-and-resolve-test
  (testing "Chill enters battlefield as permanent when cast and resolved"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :chill :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db obj-id))
          "Chill should be on battlefield after resolution")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))
          "Mana pool should be empty after paying {1}{U}"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest chill-cannot-cast-without-mana-test
  (testing "Chill not castable without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :chill :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest chill-cannot-cast-with-insufficient-mana-test
  (testing "Chill not castable without blue mana"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :chill :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only colorless (needs blue)"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest chill-cast-increments-storm-test
  (testing "Casting Chill increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :chill :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db :player-1))
          "Storm count should be 1 after casting Chill"))))


;; =====================================================
;; Engine Tests: modifier-applies? with color criteria
;; =====================================================

(deftest chill-modifier-applies-to-red-spell-test
  (testing "Modifier applies to red spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          spell-card (:object/card (q/get-object db bolt-id))
          modifiers (static-abilities/get-cost-modifiers db)
          modifier (first modifiers)]
      (is (static-abilities/modifier-applies? db :player-1 spell-card modifier)
          "Modifier should apply to Lightning Bolt (red spell)"))))


(deftest chill-modifier-does-not-apply-to-non-red-spell-test
  (testing "Modifier does NOT apply to non-red spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db cs-id))
          modifiers (static-abilities/get-cost-modifiers db)
          modifier (first modifiers)]
      (is (false? (static-abilities/modifier-applies? db :player-1 spell-card modifier))
          "Modifier should NOT apply to Counterspell (blue spell)")))

  (testing "Modifier does NOT apply to colorless spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          spell-card (:object/card (q/get-object db petal-id))
          modifiers (static-abilities/get-cost-modifiers db)
          modifier (first modifiers)]
      (is (false? (static-abilities/modifier-applies? db :player-1 spell-card modifier))
          "Modifier should NOT apply to Lotus Petal (colorless)")))

  (testing "Modifier does NOT apply to black spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db dr-id))
          modifiers (static-abilities/get-cost-modifiers db)
          modifier (first modifiers)]
      (is (false? (static-abilities/modifier-applies? db :player-1 spell-card modifier))
          "Modifier should NOT apply to Dark Ritual (black spell)"))))


;; =====================================================
;; Integration: can-cast? with Chill
;; =====================================================

(deftest chill-taxes-red-spell-test
  (testing "Red spell needs extra mana with Chill in play"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Lightning Bolt should NOT be castable with only {R} when Chill is in play")))

  (testing "Red spell castable with enough mana under Chill"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 2}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Lightning Bolt should be castable with {R}{2} when Chill adds {2}"))))


(deftest chill-does-not-tax-non-red-spell-test
  (testing "Blue spell is unaffected by Chill"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :mental-note :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Mental Note should be castable for {U} despite Chill")))

  (testing "Black spell is unaffected by Chill"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Dark Ritual should be castable for {B} despite Chill")))

  (testing "Colorless spell is unaffected by Chill"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Lotus Petal should be free despite Chill"))))


;; =====================================================
;; Integration: Full cast flow with Chill
;; =====================================================

(deftest cast-red-spell-with-chill-pays-extra-test
  (testing "Casting red spell with Chill pays {2} extra"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 2}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          ;; rules/cast-spell required — test verifies mana state on stack before resolution
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= :stack (th/get-object-zone db-cast obj-id))
          "Lightning Bolt should be on stack")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db-cast :player-1))
          "Should have used {R}{2} for Lightning Bolt under Chill"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest chill-in-graveyard-no-effect-test
  (testing "Chill in graveyard does not modify costs"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db _] (th/add-card-to-zone db :chill :graveyard :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Lightning Bolt castable with {R} when Chill is in graveyard"))))


(deftest chill-in-hand-no-effect-test
  (testing "Chill in hand does not modify costs"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db _] (th/add-card-to-zone db :chill :hand :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Lightning Bolt castable with {R} when Chill is in hand"))))


(deftest multiple-chills-stack-test
  (testing "Two Chills add {4} to red spell costs"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:red 1})]
      (is (= {:red 1 :colorless 4} result)
          "Two Chills should add {4} to Lightning Bolt's cost"))))


(deftest chill-stacks-with-sphere-test
  (testing "Chill + Sphere of Resistance stack for red spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:red 1})]
      ;; Chill adds {2}, Sphere adds {1} = {3} total
      (is (= {:red 1 :colorless 3} result)
          "Chill ({2}) + Sphere ({1}) should add {3} for red spells")))

  (testing "Non-red spell only gets Sphere tax, not Chill"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      ;; Only Sphere adds {1}, Chill does not apply to black spells
      (is (= {:black 1 :colorless 1} result)
          "Only Sphere ({1}) should apply to Dark Ritual, not Chill"))))


(deftest chill-affects-both-players-test
  (testing "Chill affects opponent's red spells too"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})]
      (is (false? (rules/can-cast? db :player-2 obj-id))
          "Opponent's Lightning Bolt should need extra mana with Chill"))))


(deftest chill-opponent-battlefield-affects-player-test
  (testing "Chill on opponent's battlefield affects player's red spells"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-2)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Player's Lightning Bolt should need extra mana with opponent's Chill"))))


(deftest chill-does-not-tax-chill-itself-test
  (testing "Chill is blue, not red, so it does not tax itself"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :chill :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Casting a second Chill should cost normal {1}{U} (Chill is blue, not red)"))))


(deftest chill-flashback-red-spell-cost-test
  (testing "Flashback cost of a red spell is modified by Chill"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :recoup :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          ;; Recoup flashback cost is {3}{R}. With Chill, should be {5}{R}
          result (static-abilities/apply-cost-modifiers
                   db :player-1 spell-card {:colorless 3 :red 1})]
      (is (= {:colorless 5 :red 1} result)
          "Recoup flashback cost {3}{R} should become {5}{R} with Chill adding {2}"))))


(deftest chill-flashback-non-red-spell-not-taxed-test
  (testing "Flashback of a non-red spell is NOT taxed by Chill"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Deep Analysis flashback should cost normal {1}{U} despite Chill (it's blue)"))))


;; =====================================================
;; Cross-Card Stacking Tests
;; =====================================================

(deftest chill-stacks-with-defense-grid-test
  (testing "Chill + Defense Grid stack for off-turn red spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:red 1})]
      ;; Chill adds {2} (red spell), Defense Grid adds {3} (off-turn) = {5} total
      (is (= {:red 1 :colorless 5} result)
          "Chill ({2}) + Defense Grid ({3}) should add {5} for off-turn red spells")))

  (testing "On own turn, only Chill applies to red spells (not Defense Grid)"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:red 1})]
      ;; Only Chill adds {2}, Defense Grid does not apply on own turn
      (is (= {:red 1 :colorless 2} result)
          "Only Chill ({2}) should apply on own turn, not Defense Grid")))

  (testing "Off-turn non-red spell only gets Defense Grid tax"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:black 1})]
      ;; Defense Grid adds {3} (off-turn), Chill does not apply to black spells
      (is (= {:black 1 :colorless 3} result)
          "Only Defense Grid ({3}) should apply to off-turn Dark Ritual, not Chill"))))


(deftest all-three-modifiers-stack-test
  (testing "Sphere + Chill + Defense Grid all stack for off-turn red spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:red 1})]
      ;; Sphere {1} + Chill {2} + Defense Grid {3} = {6} total
      (is (= {:red 1 :colorless 6} result)
          "All three: Sphere ({1}) + Chill ({2}) + Defense Grid ({3}) = {6} for off-turn red spells")))

  (testing "On own turn, only Sphere + Chill apply to red spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:red 1})]
      ;; Sphere {1} + Chill {2} = {3}, Defense Grid does not apply on own turn
      (is (= {:red 1 :colorless 3} result)
          "On own turn: Sphere ({1}) + Chill ({2}) = {3} for red spells (no Defense Grid)")))

  (testing "On own turn, non-red spell only gets Sphere tax"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _] (th/add-card-to-zone db :defense-grid :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      ;; Only Sphere {1}: Chill doesn't apply (not red), Defense Grid doesn't apply (own turn)
      (is (= {:black 1 :colorless 1} result)
          "On own turn, non-red: only Sphere ({1}) applies"))))
