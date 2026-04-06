(ns fizzle.cards.red.goblin-welder-test
  "Tests for Goblin Welder card and dual-zone targeting infrastructure.

   Goblin Welder: {R} - Creature — Goblin Artificer 1/1
   {T}: Choose target artifact a player controls and target artifact card in that
   player's graveyard. Simultaneously sacrifice the first artifact and return the
   second artifact from that player's graveyard to the battlefield.

   Key behaviors:
   - Two sequential targets: battlefield artifact (any controller), then graveyard artifact
     (same controller as first target)
   - Both targets must be legal at resolution — graceful no-op if either is gone
   - Simultaneous: sacrifice + return happen in the same db update
   - Can target opponent's artifacts (same-controller constraint applies to target pair,
     not to Welder's controller)
   - welder-swap effect is generic (driven by effect data, not card logic)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.goblin-welder :as goblin-welder]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest goblin-welder-card-definition-test
  (testing "card has correct oracle properties"
    (let [card goblin-welder/card]
      (is (= :goblin-welder (:card/id card))
          "Card ID should be :goblin-welder")
      (is (= "Goblin Welder" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:red 1} (:card/mana-cost card))
          "Mana cost should be {R}")
      (is (= #{:red} (:card/colors card))
          "Card should be red")
      (is (= #{:creature} (:card/types card))
          "Card should be a creature")
      (is (= #{:goblin :artificer} (:card/subtypes card))
          "Card should be Goblin Artificer")
      (is (= 1 (:card/power card))
          "Base power should be 1")
      (is (= 1 (:card/toughness card))
          "Base toughness should be 1")))

  (testing "card has tap-cost activated ability with dual targeting"
    (let [ability-list (:card/abilities goblin-welder/card)]
      (is (= 1 (count ability-list))
          "Should have exactly one ability")
      (let [ability (first ability-list)]
        (is (= :activated (:ability/type ability))
            "Ability should be :activated")
        (is (true? (:tap (:ability/cost ability)))
            "Ability cost should include tap"))))

  (testing "ability has two targeting requirements"
    (let [ability (first (:card/abilities goblin-welder/card))
          reqs (:ability/targeting ability)]
      (is (= 2 (count reqs))
          "Should have exactly two targeting requirements")
      (let [[bf-req gy-req] reqs]
        ;; First requirement: battlefield artifact (any controller)
        (is (= :welder-bf (:target/id bf-req))
            "First target ID should be :welder-bf")
        (is (= :object (:target/type bf-req))
            "First target type should be :object")
        (is (= :battlefield (:target/zone bf-req))
            "First target zone should be :battlefield")
        (is (= :any (:target/controller bf-req))
            "First target controller should be :any")
        (is (= #{:artifact} (get-in bf-req [:target/criteria :match/types]))
            "First target criteria should match artifacts")
        (is (true? (:target/required bf-req))
            "First target should be required")
        ;; Second requirement: graveyard artifact (same controller as first)
        (is (= :welder-gy (:target/id gy-req))
            "Second target ID should be :welder-gy")
        (is (= :object (:target/type gy-req))
            "Second target type should be :object")
        (is (= :graveyard (:target/zone gy-req))
            "Second target zone should be :graveyard")
        (is (= :welder-bf (:target/same-controller-as gy-req))
            "Second target should have same-controller-as :welder-bf")
        (is (= #{:artifact} (get-in gy-req [:target/criteria :match/types]))
            "Second target criteria should match artifacts")
        (is (true? (:target/required gy-req))
            "Second target should be required"))))

  (testing "ability has welder-swap effect"
    (let [ability (first (:card/abilities goblin-welder/card))
          effs (:ability/effects ability)]
      (is (= 1 (count effs))
          "Should have exactly one effect")
      (let [effect (first effs)]
        (is (= :welder-swap (:effect/type effect))
            "Effect type should be :welder-swap")
        (is (= :welder-bf (:effect/target-ref effect))
            "Effect should reference :welder-bf target")
        (is (= :welder-gy (:effect/graveyard-ref effect))
            "Effect should reference :welder-gy graveyard target")))))


;; === B. Cast-Resolve Happy Path ===

(deftest goblin-welder-cast-to-battlefield-test
  (testing "Goblin Welder enters the battlefield when cast"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 welder-id))
                "Should be castable with {R} mana")
          db-resolved (th/cast-and-resolve db :player-1 welder-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved welder-id)))
          "Goblin Welder should be on battlefield after resolution"))))


(deftest goblin-welder-activate-swaps-artifacts-test
  (testing "Activating Goblin Welder swaps battlefield and graveyard artifacts"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          ;; Put Lotus Petal on battlefield (to sacrifice)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Put LED in graveyard (to return)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          ability (first (:card/abilities goblin-welder/card))
          _ (is (true? (abilities/can-activate? db welder-id ability :player-1))
                "Welder should be activatable (untapped with artifacts available)")
          ;; Activate ability — first selection: battlefield artifact
          result1 (ability-events/activate-ability db :player-1 welder-id 0)
          pending1 (:pending-selection result1)
          _ (is (= :ability-targeting (:selection/type pending1))
                "First pending selection should be ability-targeting")
          _ (is (contains? (set (:selection/valid-targets pending1)) petal-id)
                "First selection should offer Lotus Petal as valid target")
          ;; Select Lotus Petal (battlefield artifact)
          result2 (ability-events/confirm-ability-target (:db result1) (assoc pending1 :selection/selected #{petal-id}))
          pending2 (:pending-selection result2)
          _ (is (= :ability-targeting (:selection/type pending2))
                "Second pending selection should also be ability-targeting")
          _ (is (contains? (set (:selection/valid-targets pending2)) led-id)
                "Second selection should offer LED (same controller's graveyard)")
          ;; Select LED (graveyard artifact)
          result3 (ability-events/confirm-ability-target (:db result2) (assoc pending2 :selection/selected #{led-id}))
          _ (is (nil? (:pending-selection result3))
                "No pending selection after both targets chosen")
          db-after-confirm (:db result3)
          ;; Welder should be tapped (cost paid)
          _ (is (true? (:object/tapped (q/get-object db-after-confirm welder-id)))
                "Goblin Welder should be tapped after activation")
          ;; Resolve the ability
          db-resolved (:db (resolution/resolve-one-item db-after-confirm))]
      ;; Lotus Petal should be sacrificed (in graveyard)
      (is (= :graveyard (:object/zone (q/get-object db-resolved petal-id)))
          "Lotus Petal should be sacrificed (in graveyard)")
      ;; LED should be returned to battlefield
      (is (= :battlefield (:object/zone (q/get-object db-resolved led-id)))
          "LED should be returned to battlefield"))))


;; === C. Cannot-Cast Guards ===

(deftest goblin-welder-cannot-cast-without-mana-test
  (testing "Cannot cast Goblin Welder without {R}"
    (let [db (th/create-test-db)
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 welder-id))
          "Should not be castable without mana"))))


(deftest goblin-welder-cannot-cast-from-graveyard-test
  (testing "Cannot cast Goblin Welder from graveyard"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 welder-id))
          "Should not be castable from graveyard"))))


(deftest goblin-welder-cannot-activate-when-tapped-test
  (testing "Cannot activate Goblin Welder when tapped"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db _led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          ;; Manually tap the Welder
          welder-eid (q/get-object-eid db welder-id)
          db-tapped (d/db-with db [[:db/add welder-eid :object/tapped true]])
          ability (first (:card/abilities goblin-welder/card))]
      (is (false? (abilities/can-activate? db-tapped welder-id ability :player-1))
          "Cannot activate tapped Welder"))))


(deftest goblin-welder-cannot-activate-without-targets-test
  (testing "Cannot activate Goblin Welder without a battlefield artifact"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          ;; No artifacts on battlefield or in graveyard
          ability (first (:card/abilities goblin-welder/card))]
      ;; has-valid-targets? should return false when no artifacts exist
      (is (false? (targeting/has-valid-targets? db :player-1 ability))
          "has-valid-targets? should be false with no artifacts"))))


;; === D. Storm Count ===

(deftest goblin-welder-increments-storm-count-test
  (testing "Casting Goblin Welder increments storm count"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-resolved (th/cast-and-resolve db :player-1 welder-id)]
      (is (= (inc storm-before) (q/get-storm-count db-resolved :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Constraint Tests ===

(deftest goblin-welder-second-target-filtered-by-first-controller-test
  (testing "Second target (graveyard) is filtered to same controller as first target"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          ;; Player-1 has artifact on battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Player-1 has artifact in graveyard (should be valid second target)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          ;; Player-2 has artifact in graveyard (should NOT be valid if selecting p1 bf artifact)
          [db _p2-led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-2)
          ;; Activate and select player-1's battlefield artifact
          result1 (ability-events/activate-ability db :player-1 welder-id 0)
          pending1 (:pending-selection result1)
          result2 (ability-events/confirm-ability-target (:db result1) (assoc pending1 :selection/selected #{petal-id}))
          pending2 (:pending-selection result2)]
      ;; Second selection should only offer player-1's graveyard artifact
      (is (contains? (set (:selection/valid-targets pending2)) led-id)
          "Player-1's graveyard artifact should be a valid second target")
      (is (= 1 (count (:selection/valid-targets pending2)))
          "Only player-1's graveyard artifact should be valid (not player-2's)"))))


(deftest goblin-welder-can-target-opponent-artifacts-test
  (testing "Can target artifacts controlled by opponent"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          ;; Player-2 has artifact on battlefield
          [db p2-petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          ;; Player-2 has artifact in graveyard (needed to make has-valid-targets? true)
          [db _p2-led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-2)
          ability (first (:card/abilities goblin-welder/card))]
      ;; has-valid-targets? should be true (can target opponent's artifacts)
      (is (true? (targeting/has-valid-targets? db :player-1 ability))
          "has-valid-targets? should be true when opponent has matching artifacts")
      ;; First selection should include player-2's battlefield artifact
      (let [result1 (ability-events/activate-ability db :player-1 welder-id 0)
            pending1 (:pending-selection result1)]
        (is (contains? (set (:selection/valid-targets pending1)) p2-petal-id)
            "Opponent's battlefield artifact should be a valid first target")))))


;; === F. Legality at Resolution Tests ===

(deftest goblin-welder-noop-if-battlefield-target-removed-test
  (testing "Welder does nothing if battlefield target is removed in response"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          ;; Activate ability and collect both targets
          result1 (ability-events/activate-ability db :player-1 welder-id 0)
          pending1 (:pending-selection result1)
          result2 (ability-events/confirm-ability-target (:db result1) (assoc pending1 :selection/selected #{petal-id}))
          pending2 (:pending-selection result2)
          result3 (ability-events/confirm-ability-target (:db result2) (assoc pending2 :selection/selected #{led-id}))
          db-with-stack (:db result3)
          ;; Simulate: battlefield target (petal) is removed before resolution
          petal-eid (q/get-object-eid db-with-stack petal-id)
          db-petal-gone (d/db-with db-with-stack [[:db/add petal-eid :object/zone :graveyard]])
          ;; Resolve — welder effect should detect illegal target and do nothing
          db-resolved (:db (resolution/resolve-one-item db-petal-gone))]
      ;; LED should still be in graveyard (no swap happened)
      (is (= :graveyard (:object/zone (q/get-object db-resolved led-id)))
          "LED should remain in graveyard — swap aborted due to illegal target"))))


(deftest goblin-welder-noop-if-graveyard-target-removed-test
  (testing "Welder does nothing if graveyard target is removed in response"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          ;; Activate and collect both targets
          result1 (ability-events/activate-ability db :player-1 welder-id 0)
          pending1 (:pending-selection result1)
          result2 (ability-events/confirm-ability-target (:db result1) (assoc pending1 :selection/selected #{petal-id}))
          pending2 (:pending-selection result2)
          result3 (ability-events/confirm-ability-target (:db result2) (assoc pending2 :selection/selected #{led-id}))
          db-with-stack (:db result3)
          ;; Simulate: graveyard target (LED) is removed before resolution (e.g., exiled)
          led-eid (q/get-object-eid db-with-stack led-id)
          db-led-exiled (d/db-with db-with-stack [[:db/add led-eid :object/zone :exile]])
          ;; Resolve — welder effect should detect illegal target and do nothing
          db-resolved (:db (resolution/resolve-one-item db-led-exiled))]
      ;; Petal should still be on battlefield (not sacrificed)
      (is (= :battlefield (:object/zone (q/get-object db-resolved petal-id)))
          "Lotus Petal should remain on battlefield — swap aborted due to illegal target"))))


;; === G. Edge Cases ===

(deftest goblin-welder-no-valid-targets-when-no-graveyard-artifact-test
  (testing "has-valid-targets? false when battlefield artifact exists but no graveyard artifact"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; No artifacts in graveyard
          ability (first (:card/abilities goblin-welder/card))]
      (is (false? (targeting/has-valid-targets? db :player-1 ability))
          "has-valid-targets? should be false when no graveyard artifact matches"))))


(deftest goblin-welder-no-valid-targets-when-different-controller-test
  (testing "has-valid-targets? false when bf artifact and gy artifact have different controllers"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          ;; Player-1 has artifact on battlefield
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Only player-2 has artifact in graveyard (different controller from p1's petal)
          [db _p2-led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-2)
          ;; No player-1 graveyard artifacts
          ability (first (:card/abilities goblin-welder/card))]
      (is (false? (targeting/has-valid-targets? db :player-1 ability))
          "has-valid-targets? false: no matching controller pair"))))


;; === H. Regression: Selection System Chaining ===

(deftest goblin-welder-chaining-through-selection-system-test
  (testing "Multi-target chaining works through confirm-selection-impl (not just confirm-ability-target)"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db welder-id] (th/add-card-to-zone db :goblin-welder :hand :player-1)
          db (th/cast-and-resolve db :player-1 welder-id)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :graveyard :player-1)
          ;; Activate ability — get first selection
          result1 (ability-events/activate-ability db :player-1 welder-id 0)
          pending1 (:pending-selection result1)
          ;; Confirm first target through the SELECTION SYSTEM (not confirm-ability-target)
          chain-result (th/confirm-selection (:db result1) pending1 #{petal-id})]
      ;; The selection system should chain to the second target selection
      (is (= :ability-targeting (:selection/type (:selection chain-result)))
          "Chained selection should be :ability-targeting type")
      (is (contains? (set (:selection/valid-targets (:selection chain-result))) led-id)
          "Chained selection should include LED as valid graveyard target"))))
