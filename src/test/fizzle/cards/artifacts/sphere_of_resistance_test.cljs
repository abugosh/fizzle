(ns fizzle.cards.artifacts.sphere-of-resistance-test
  "Tests for Sphere of Resistance static cost modifier.

   Sphere of Resistance: {2} - Artifact
   Spells cost {1} more to cast."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.sphere-of-resistance :as sphere]
    [fizzle.db.queries :as q]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.events.casting :as casting]
    [fizzle.test-helpers :as th]))


(defn- add-decrease-modifier
  "Add a test permanent with a cost-decrease static ability to the battlefield.
   Returns [db object-id]."
  ([db player-id amount]
   (add-decrease-modifier db player-id amount :all nil))
  ([db player-id amount applies-to]
   (add-decrease-modifier db player-id amount applies-to nil))
  ([db player-id amount applies-to criteria]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         card-id (keyword (str "test-decrease-" (random-uuid)))
         ability (cond-> {:static/type :cost-modifier
                          :modifier/amount amount
                          :modifier/direction :decrease
                          :modifier/applies-to applies-to}
                   criteria (assoc :modifier/criteria criteria))
         _ (d/transact! conn [{:card/id card-id
                               :card/name "Test Decrease Card"
                               :card/cmc 0
                               :card/mana-cost {}
                               :card/colors #{}
                               :card/types #{:artifact}
                               :card/text "Test"
                               :card/static-abilities [ability]}])
         card-eid (d/q '[:find ?e .
                         :in $ ?cid
                         :where [?e :card/id ?cid]]
                       @conn card-id)
         object-id (random-uuid)
         _ (d/transact! conn [{:object/id object-id
                               :object/card card-eid
                               :object/zone :battlefield
                               :object/owner player-eid
                               :object/controller player-eid
                               :object/tapped false}])]
     [@conn object-id])))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

(deftest sphere-of-resistance-card-definition-test
  (testing "Sphere of Resistance card definition is complete and correct"
    (let [card sphere/card]
      (is (= :sphere-of-resistance (:card/id card))
          "Card ID should be :sphere-of-resistance")
      (is (= "Sphere of Resistance" (:card/name card))
          "Card name should be 'Sphere of Resistance'")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 2} (:card/mana-cost card))
          "Mana cost should be {2}")
      (is (= #{} (:card/colors card))
          "Should be colorless")
      (is (= #{:artifact} (:card/types card))
          "Should be exactly an artifact")
      (is (= "Spells cost {1} more to cast." (:card/text card))
          "Card text should match")))

  (testing "Sphere has exactly one static ability"
    (let [abilities (:card/static-abilities sphere/card)]
      (is (= 1 (count abilities))
          "Should have exactly 1 static ability")
      (let [ability (first abilities)]
        (is (= :cost-modifier (:static/type ability))
            "Static type should be :cost-modifier")
        (is (= 1 (:modifier/amount ability))
            "Modifier amount should be 1")
        (is (= :increase (:modifier/direction ability))
            "Modifier direction should be :increase")
        (is (nil? (:modifier/condition ability))
            "Should have no condition (applies to all)")
        (is (nil? (:modifier/criteria ability))
            "Should have no criteria (applies to all spells)")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest sphere-cast-and-resolve-test
  (testing "Sphere enters battlefield as permanent when cast and resolved"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db obj-id))
          "Sphere should be on battlefield after resolution")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))
          "Mana pool should be empty after paying {2}"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest sphere-cannot-cast-without-mana-test
  (testing "Sphere not castable without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest sphere-cannot-cast-with-insufficient-mana-test
  (testing "Sphere not castable with only 1 mana"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 1 colorless"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest sphere-cast-increments-storm-test
  (testing "Casting Sphere increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db :player-1))
          "Storm count should be 1 after casting Sphere"))))


;; =====================================================
;; Engine Tests (static_abilities module)
;; =====================================================

(deftest get-cost-modifiers-empty-battlefield-test
  (testing "No modifiers when battlefield is empty"
    (let [db (th/create-test-db)]
      (is (= [] (static-abilities/get-cost-modifiers db))
          "Should return empty vec with no permanents"))))


(deftest get-cost-modifiers-no-static-abilities-test
  (testing "No modifiers when permanents have no static abilities"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (= [] (static-abilities/get-cost-modifiers db))
          "Lotus Petal has no static abilities, should return empty vec"))))


(deftest get-cost-modifiers-sphere-on-battlefield-test
  (testing "Sphere on battlefield returns its modifier"
    (let [db (th/create-test-db)
          [db _sphere-id] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          modifiers (static-abilities/get-cost-modifiers db)]
      (is (= 1 (count modifiers))
          "Should find exactly 1 modifier")
      (is (= :cost-modifier (:static/type (:static-ability (first modifiers))))
          "Should be a cost-modifier type"))))


(deftest apply-cost-modifiers-no-modifiers-test
  (testing "No modifiers returns base cost unchanged"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      (is (= {:blue 2} result)
          "Counterspell base cost should be unchanged"))))


(deftest apply-cost-modifiers-sphere-adds-generic-test
  (testing "Sphere adds {1} to spell with colored-only cost"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      (is (= {:blue 2 :colorless 1} result)
          "Counterspell should cost {U}{U}{1} with Sphere"))))


(deftest apply-cost-modifiers-sphere-on-generic-cost-test
  (testing "Sphere adds {1} to spell that already has generic"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 2})]
      (is (= {:colorless 3} result)
          "Sphere itself should cost {3} with another Sphere in play"))))


(deftest apply-cost-modifiers-dark-ritual-gains-generic-test
  (testing "Sphere adds generic to spell with no base generic"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      (is (= {:black 1 :colorless 1} result)
          "Dark Ritual should cost {B}{1} with Sphere"))))


;; =====================================================
;; Integration: can-cast? with Sphere
;; =====================================================

(deftest can-cast-with-sphere-requires-extra-mana-test
  (testing "Dark Ritual needs extra mana with Sphere in play"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Dark Ritual should NOT be castable with only {B} when Sphere is in play"))))


(deftest can-cast-with-sphere-sufficient-mana-test
  (testing "Dark Ritual castable with {B} + {1} when Sphere is in play"
    (let [db (th/create-test-db {:mana {:black 2}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Dark Ritual should be castable with 2 black (1 for cost, 1 for Sphere generic)"))))


(deftest can-cast-mode-blue-spell-with-sphere-test
  (testing "Blue spell with colored-only cost needs extra for Sphere"
    (let [db (th/create-test-db {:mana {:blue 2}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))]
      (is (false? (rules/can-cast-mode? db :player-1 obj-id primary))
          "Counterspell should NOT be payable with only 2 blue when Sphere is in play"))
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))]
      (is (rules/can-cast-mode? db :player-1 obj-id primary)
          "Counterspell should be payable with 3 blue when Sphere is in play"))))


;; =====================================================
;; Integration: Full cast flow with Sphere
;; =====================================================

(deftest cast-dark-ritual-with-sphere-pays-extra-test
  (testing "Casting Dark Ritual with Sphere pays {B} + {1}"
    (let [db (th/create-test-db {:mana {:black 2}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; rules/cast-spell required — test verifies mana state on stack before resolution
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= :stack (th/get-object-zone db-cast obj-id))
          "Dark Ritual should be on stack")
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db-cast :player-1))
          "Should have used all 2 black mana (1 for cost + 1 for Sphere generic)"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest multiple-spheres-stack-test
  (testing "Two Spheres add {2} to spell costs"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      (is (= {:blue 2 :colorless 2} result)
          "Two Spheres should add {2} to Counterspell's cost"))))


(deftest multiple-spheres-can-cast-mode-test
  (testing "Two Spheres require {2} extra for can-cast-mode?"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))]
      (is (false? (rules/can-cast-mode? db :player-1 obj-id primary))
          "3 blue not enough for Counterspell + 2 Spheres (needs 4 total)"))
    (let [db (th/create-test-db {:mana {:blue 4}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))]
      (is (rules/can-cast-mode? db :player-1 obj-id primary)
          "4 blue enough for Counterspell + 2 Spheres"))))


(deftest sphere-in-graveyard-no-effect-test
  (testing "Sphere in graveyard does not modify costs"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :graveyard :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Dark Ritual castable with {B} when Sphere is in graveyard"))))


(deftest sphere-in-hand-no-effect-test
  (testing "Sphere in hand does not modify costs"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Dark Ritual castable with {B} when Sphere is in hand"))))


(deftest sphere-on-stack-no-effect-test
  (testing "Sphere being cast (on stack) does not modify costs"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db sphere-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast Sphere first — rules/cast-spell required, Sphere must stay on stack to test no-effect
          db (rules/cast-spell db :player-1 sphere-id)
          _ (is (= :stack (th/get-object-zone db sphere-id))
                "Sphere should be on stack")
          ;; Now check if Dark Ritual is castable for just {B}
          db (mana/add-mana db :player-1 {:black 1})]
      (is (rules/can-cast? db :player-1 dr-id)
          "Dark Ritual castable with {B} when Sphere is on stack (not yet resolved)"))))


(deftest zero-cost-artifact-with-sphere-test
  (testing "Zero-cost artifact (Lotus Petal) costs {1} with Sphere"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Lotus Petal should NOT be free with Sphere in play"))
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Lotus Petal should be castable with {1} when Sphere is in play"))))


(deftest sphere-does-not-affect-activated-abilities-test
  (testing "Lotus Petal mana ability is unaffected by Sphere"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          initial-pool (q/get-mana-pool db :player-1)
          _ (is (= 0 (get initial-pool :black))
                "Precondition: no black mana")
          db (engine-mana/activate-mana-ability db :player-1 petal-id :black)]
      (is (= 1 (get (q/get-mana-pool db :player-1) :black))
          "Lotus Petal should still produce 1 mana despite Sphere in play")
      (is (= :graveyard (th/get-object-zone db petal-id))
          "Lotus Petal should be sacrificed as normal"))))


(deftest sphere-affects-opponent-spells-test
  (testing "Sphere affects both players' spells (symmetric)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Sphere on player-1's battlefield
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          ;; Give opponent a spell
          [db opp-dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Opponent has only {B} - not enough with Sphere
          db (mana/add-mana db :player-2 {:black 1})]
      (is (false? (rules/can-cast? db :player-2 opp-dr-id))
          "Opponent's Dark Ritual should need extra mana with Sphere"))))


(deftest sphere-opponent-battlefield-affects-player-test
  (testing "Sphere on opponent's battlefield also affects player's spells"
    (let [db (th/create-test-db {:mana {:black 1}})
          db (th/add-opponent db)
          ;; Sphere on opponent's battlefield
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-2)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Player's Dark Ritual should need extra mana with opponent's Sphere"))))


(deftest flashback-with-sphere-test
  (testing "Flashback cost is also modified by Sphere"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          ;; Flashback cost is {1}{U}, with Sphere should be {2}{U}
          ;; With only {1}{U} it should fail
          db-insufficient (mana/add-mana db :player-1 {:colorless 1 :blue 1})]
      (is (false? (rules/can-cast? db-insufficient :player-1 obj-id))
          "Flashback should need extra mana with Sphere ({1}{U} not enough, needs {2}{U})"))
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          ;; With {2}{U} it should succeed
          db-sufficient (mana/add-mana db :player-1 {:colorless 2 :blue 1})]
      (is (rules/can-cast? db-sufficient :player-1 obj-id)
          "Flashback should be castable with {2}{U} when Sphere adds {1}"))))


(deftest sphere-does-not-affect-own-casting-test
  (testing "Casting Sphere with no other cost modifiers costs exactly {2}"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Sphere should be castable for {2} with no other Sphere in play"))))


(deftest sphere-resolve-then-subsequent-spells-cost-more-test
  (testing "After resolving Sphere, subsequent spells cost more"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db sphere-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast and resolve Sphere
          db (th/cast-and-resolve db :player-1 sphere-id)
          _ (is (= :battlefield (th/get-object-zone db sphere-id))
                "Sphere should be on battlefield")]
      ;; Dark Ritual needs {B} + {1} now, but only {B} left after paying {2} for Sphere
      ;; (started with 2 colorless + 1 black, paid 2 colorless for Sphere, black remains)
      (is (false? (rules/can-cast? db :player-1 dr-id))
          "Dark Ritual should NOT be castable with only {B} after Sphere resolves"))))


;; =====================================================
;; Decrease Mechanics (cost reduction engine)
;; =====================================================

(deftest decrease-reduces-generic-mana-test
  (testing "Decrease modifier reduces :colorless portion of cost"
    (let [db (th/create-test-db)
          [db _] (add-decrease-modifier db :player-1 1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          ;; Counterspell base cost {:blue 2}, but let's test with a cost that has :colorless
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 2 :blue 1})]
      (is (= {:colorless 1 :blue 1} result)
          "Decrease by 1 should reduce :colorless from 2 to 1"))))


(deftest decrease-floors-colorless-at-zero-test
  (testing "Decrease cannot make :colorless negative — floors at 0"
    (let [db (th/create-test-db)
          [db _] (add-decrease-modifier db :player-1 2)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 1})]
      (is (= {:colorless 0} result)
          "Decrease by 2 on {:colorless 1} should floor at 0, not go negative"))))


(deftest decrease-on-pure-colored-cost-is-noop-test
  (testing "Decrease on spell with only colored mana has no practical effect"
    (let [db (th/create-test-db)
          [db _] (add-decrease-modifier db :player-1 1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      (is (= {:blue 2 :colorless 0} result)
          "Decrease on pure-colored cost results in {:colorless 0} — functionally no change"))))


(deftest multiple-decreases-stack-test
  (testing "Multiple decrease modifiers stack additively"
    (let [db (th/create-test-db)
          [db _] (add-decrease-modifier db :player-1 1)
          [db _] (add-decrease-modifier db :player-1 1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 3 :blue 1})]
      (is (= {:colorless 1 :blue 1} result)
          "Two decrease-1 modifiers should reduce :colorless by 2 total"))))


;; =====================================================
;; Increase-then-Decrease Ordering
;; =====================================================

(deftest increase-and-decrease-cancel-test
  (testing "Sphere increase + decrease cancel out (net 0)"
    (let [db (th/create-test-db)
          ;; Sphere adds 1, decrease modifier subtracts 1
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (add-decrease-modifier db :player-1 1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      (is (= {:blue 2 :colorless 0} result)
          "Sphere +1 and decrease -1 should cancel: {:blue 2 :colorless 0}"))))


(deftest increase-before-decrease-matters-for-floor-test
  (testing "Increases applied before decreases — order matters for floor"
    ;; Sphere adds 1 to colorless (0 → 1), then decrease-2 subtracts 2 (1 → floor 0)
    ;; If decrease applied first: colorless stays 0 (floor), then increase adds 1 → colorless=1
    ;; Correct MTG behavior: increase first, then decrease → colorless=0
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db _] (add-decrease-modifier db :player-1 2)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      (is (= {:black 1 :colorless 0} result)
          "Increase +1 then decrease -2: colorless goes 0→1→0 (floor), not 0→0→1"))))


;; =====================================================
;; :applies-to Ownership Filtering
;; =====================================================

(deftest applies-to-controller-blocks-opponent-test
  (testing ":applies-to :controller does NOT apply when caster != source controller"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Modifier entry with :applies-to :controller on player-1's side
          modifier-entry {:static-ability {:static/type :cost-modifier
                                           :modifier/amount 1
                                           :modifier/direction :decrease
                                           :modifier/applies-to :controller}
                          :source/controller :player-1}
          ;; Player-2 is casting — should NOT apply
          spell-card {:card/colors #{:blue}}]
      (is (false? (static-abilities/modifier-applies? db :player-2 spell-card modifier-entry))
          ":controller modifier should not apply to opponent's spells"))))


(deftest applies-to-controller-allows-own-spells-test
  (testing ":applies-to :controller applies when caster == source controller"
    (let [db (th/create-test-db)
          modifier-entry {:static-ability {:static/type :cost-modifier
                                           :modifier/amount 1
                                           :modifier/direction :decrease
                                           :modifier/applies-to :controller}
                          :source/controller :player-1}
          spell-card {:card/colors #{:blue}}]
      (is (static-abilities/modifier-applies? db :player-1 spell-card modifier-entry)
          ":controller modifier should apply to own spells"))))


(deftest applies-to-all-applies-to-both-players-test
  (testing ":applies-to :all applies to both players (like Sphere)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          modifier-entry {:static-ability {:static/type :cost-modifier
                                           :modifier/amount 1
                                           :modifier/direction :increase
                                           :modifier/applies-to :all}
                          :source/controller :player-1}
          spell-card {:card/colors #{:blue}}]
      (is (static-abilities/modifier-applies? db :player-1 spell-card modifier-entry)
          ":all modifier should apply to controller's spells")
      (is (static-abilities/modifier-applies? db :player-2 spell-card modifier-entry)
          ":all modifier should apply to opponent's spells"))))


(deftest applies-to-absent-defaults-to-all-test
  (testing "Absent :applies-to defaults to :all — backward compat with existing Sphere"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Sphere-style modifier — no :modifier/applies-to field
          modifier-entry {:static-ability {:static/type :cost-modifier
                                           :modifier/amount 1
                                           :modifier/direction :increase}
                          :source/controller :player-1}
          spell-card {:card/colors #{:blue}}]
      (is (static-abilities/modifier-applies? db :player-1 spell-card modifier-entry)
          "Absent :applies-to should apply to controller")
      (is (static-abilities/modifier-applies? db :player-2 spell-card modifier-entry)
          "Absent :applies-to should apply to opponent (backward compat)"))))


;; =====================================================
;; Mixed Criteria + :applies-to
;; =====================================================

(deftest controller-plus-color-criteria-test
  (testing ":applies-to :controller + :spell-color criteria must both match"
    (let [db (th/create-test-db)
          ;; Medallion-like: controller's blue spells only
          modifier-entry {:static-ability {:static/type :cost-modifier
                                           :modifier/amount 1
                                           :modifier/direction :decrease
                                           :modifier/applies-to :controller
                                           :modifier/criteria {:criteria/type :spell-color
                                                               :criteria/colors #{:blue}}}
                          :source/controller :player-1}]
      ;; Controller casting blue spell → applies
      (is (static-abilities/modifier-applies? db :player-1 {:card/colors #{:blue}} modifier-entry)
          "Controller's blue spell should match")
      ;; Controller casting red spell → does NOT apply (color mismatch)
      (is (false? (static-abilities/modifier-applies? db :player-1 {:card/colors #{:red}} modifier-entry))
          "Controller's red spell should not match (wrong color)")
      ;; Controller casting colorless spell → does NOT apply
      (is (false? (static-abilities/modifier-applies? db :player-1 {:card/colors #{}} modifier-entry))
          "Controller's colorless spell should not match"))))


;; =====================================================
;; Spec Validation
;; =====================================================

(deftest spec-decrease-direction-valid-test
  (testing "Card with :modifier/direction :decrease passes spec validation"
    (let [test-card {:card/id :test-decrease
                     :card/name "Test Decrease"
                     :card/cmc 2
                     :card/mana-cost {:colorless 2}
                     :card/colors #{}
                     :card/types #{:artifact}
                     :card/text "Test"
                     :card/static-abilities [{:static/type :cost-modifier
                                              :modifier/amount 1
                                              :modifier/direction :decrease}]}]
      (is (card-spec/valid-card? test-card)
          ":decrease direction should be valid"))))


(deftest spec-applies-to-controller-valid-test
  (testing "Card with :modifier/applies-to :controller passes spec validation"
    (let [test-card {:card/id :test-applies-to
                     :card/name "Test Applies To"
                     :card/cmc 2
                     :card/mana-cost {:colorless 2}
                     :card/colors #{}
                     :card/types #{:artifact}
                     :card/text "Test"
                     :card/static-abilities [{:static/type :cost-modifier
                                              :modifier/amount 1
                                              :modifier/direction :decrease
                                              :modifier/applies-to :controller}]}]
      (is (card-spec/valid-card? test-card)
          ":applies-to :controller should be valid"))))


(deftest spec-existing-cards-still-valid-test
  (testing "Existing cards without :applies-to still pass validation"
    (is (card-spec/valid-card? sphere/card)
        "Sphere of Resistance should still be valid without :applies-to")))


;; =====================================================
;; Event-Layer: Mana Allocation Triggered by Effective Cost
;; =====================================================

(deftest sphere-triggers-mana-allocation-for-no-base-generic-test
  (testing "Dark Ritual (no base generic) triggers mana allocation when Sphere adds generic"
    (let [db (th/create-test-db)
          ;; Sphere on battlefield
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          ;; Dark Ritual in hand — base cost {B}, effective cost {B}{1} with Sphere
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:black 2})
          app-db {:game/db db}
          result (casting/cast-spell-handler app-db {:object-id dr-id})
          sel (:game/pending-selection result)]
      (is (= :mana-allocation (:selection/domain sel))
          "Selection type should be :mana-allocation")))

  (testing "Dark Ritual without Sphere casts immediately (no allocation needed)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          app-db {:game/db db}
          result (casting/cast-spell-handler app-db {:object-id dr-id})]
      (is (nil? (:game/pending-selection result))
          "Should NOT have pending selection (no generic in base cost)")
      (is (= :stack (:object/zone (q/get-object (:game/db result) dr-id)))
          "Dark Ritual should be on stack (cast immediately)"))))
