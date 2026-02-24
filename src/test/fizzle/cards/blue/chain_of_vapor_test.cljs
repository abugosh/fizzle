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
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === Helper: cast Chain of Vapor targeting a permanent ===

(defn- cast-chain-of-vapor
  "Cast Chain of Vapor targeting a permanent. Returns db after casting
   (spell on stack, mana spent, storm incremented)."
  [db cov-id target-id]
  (let [target-req (first (:card/targeting chain-of-vapor/card))
        modes (rules/get-casting-modes db :player-1 cov-id)
        mode (first modes)
        selection {:selection/type :cast-time-targeting
                   :selection/player-id :player-1
                   :selection/object-id cov-id
                   :selection/mode mode
                   :selection/target-requirement target-req
                   :selection/selected #{target-id}}]
    (sel-targeting/confirm-cast-time-target db selection)))


(defn- resolve-and-decline-chain
  "Resolve Chain of Vapor and decline the chain (no sacrifice).
   Returns the final db after full resolution."
  [db]
  (let [result (game/resolve-one-item db)]
    (if (:pending-selection result)
      ;; Chain-bounce selection pending — decline by confirming with 0 selected
      (let [app-db {:game/db (:db result)
                    :game/pending-selection (:pending-selection result)}
            app-db-after (sel-core/confirm-selection-impl app-db)]
        (:game/db app-db-after))
      (:db result))))


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

  ;; Oracle: "Return target nonland permanent to its owner's hand.
  ;;          Then that permanent's controller may sacrifice a land..."
  (testing "card has bounce and chain-bounce effects"
    (let [card-effects (:card/effects chain-of-vapor/card)]
      (is (= 2 (count card-effects))
          "Should have two effects: bounce + chain-bounce")
      (let [[bounce-effect chain-effect] card-effects]
        (is (= :bounce (:effect/type bounce-effect))
            "First effect should be :bounce")
        (is (= :target-permanent (:effect/target-ref bounce-effect))
            "Bounce should reference target permanent")
        (is (= :chain-bounce (:effect/type chain-effect))
            "Second effect should be :chain-bounce")
        (is (= :target-permanent (:effect/target-ref chain-effect))
            "Chain-bounce should reference target permanent")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Return target nonland permanent to its owner's hand."
(deftest chain-of-vapor-bounces-permanent-test
  (testing "Chain of Vapor returns target nonland permanent to owner's hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Bounce happens as first effect even though chain-bounce pauses resolution
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id)))
          "Target permanent should be returned to owner's hand"))))


;; Oracle: bounce works on own permanents too
(deftest chain-of-vapor-bounces-own-permanent-test
  (testing "Chain of Vapor can bounce own permanent"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
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
          db-cast (cast-chain-of-vapor db cov-id target-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

;; Oracle: "target nonland permanent" — lands are excluded
(deftest chain-of-vapor-cannot-target-lands-test
  (testing "Cannot target lands"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
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


;; === G. Chain Mechanic Tests ===

;; Oracle: "Then that permanent's controller may sacrifice a land."
(deftest chain-of-vapor-offers-chain-choice-test
  (testing "After bounce, chain-bounce offers sacrifice-land selection"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          ;; Opponent has a land to sacrifice
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          result (game/resolve-one-item db-cast)]
      ;; Should have a pending selection for chain choice
      (is (some? (:pending-selection result))
          "Should have pending chain-bounce selection")
      (is (= :chain-bounce (:selection/type (:pending-selection result)))
          "Selection type should be :chain-bounce")
      (is (= :player-2 (:selection/player-id (:pending-selection result)))
          "Chain choice should be for the bounced permanent's controller"))))


;; Oracle: controller MAY sacrifice — declining ends the chain
(deftest chain-of-vapor-decline-chain-test
  (testing "Declining the chain (no sacrifice) ends resolution normally"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          db-resolved (resolve-and-decline-chain db-cast)]
      ;; Target was bounced
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id)))
          "Target should be in hand after declining chain")
      ;; Chain of Vapor goes to graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved cov-id)))
          "Chain of Vapor should be in graveyard after declining")
      ;; No extra copies on the stack
      (is (nil? (stack/get-top-stack-item db-resolved))
          "Stack should be empty after declining chain"))))


;; Oracle: controller has no lands — chain ends automatically
(deftest chain-of-vapor-no-lands-chain-ends-test
  (testing "Chain ends automatically when controller has no lands to sacrifice"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          ;; Lotus Petal on opponent's battlefield, no lands for opponent
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          result (game/resolve-one-item db-cast)]
      ;; Chain-bounce should have pending-selection but with auto-confirm
      ;; (no lands = empty valid-targets)
      (is (some? (:pending-selection result))
          "Should have pending selection (chain-bounce)")
      (let [sel (:pending-selection result)]
        (is (empty? (:selection/valid-targets sel))
            "Valid targets should be empty (no lands)")
        (is (true? (:selection/auto-confirm? sel))
            "Should auto-confirm when no lands available")))))


;; Oracle: "If the player does, they may copy this spell and may choose
;;          a new target for that copy."
(deftest chain-of-vapor-sacrifice-land-creates-copy-test
  (testing "Sacrificing a land creates a copy and offers new target selection"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          ;; A second nonland permanent to target with the copy
          [db _seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-1)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          result (game/resolve-one-item db-cast)
          ;; Simulate selecting the land to sacrifice
          chain-sel (assoc (:pending-selection result)
                           :selection/selected #{land-id})
          app-db {:game/db (:db result)
                  :game/pending-selection chain-sel}
          app-db-after (sel-core/confirm-selection-impl app-db)]
      ;; Land should be sacrificed (in graveyard)
      (is (= :graveyard (:object/zone (q/get-object (:game/db app-db-after) land-id)))
          "Sacrificed land should be in graveyard")
      ;; Should now have a target selection for the copy
      (is (some? (:game/pending-selection app-db-after))
          "Should have pending target selection for the copy")
      (is (= :chain-bounce-target (:selection/type (:game/pending-selection app-db-after)))
          "Selection type should be :chain-bounce-target"))))


;; === H. Edge Cases ===

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
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 cov-id))
          "Should not be castable when only lands on battlefield"))))


;; Spell goes to graveyard after declining chain (standard instant behavior)
(deftest chain-of-vapor-goes-to-graveyard-after-resolution-test
  (testing "Chain of Vapor goes to graveyard after resolving (chain declined)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          db-resolved (resolve-and-decline-chain db-cast)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved cov-id)))
          "Chain of Vapor should be in graveyard after resolving"))))


;; MTG Rule 707.2: Copies cease to exist when they leave the stack
(deftest chain-of-vapor-copy-ceases-to-exist-after-resolving-test
  (testing "Copy ceases to exist after resolving (not moved to graveyard)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cov-id] (th/add-card-to-zone db :chain-of-vapor :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          ;; A second nonland permanent to target with the copy
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-1)
          ;; Cast and resolve Chain of Vapor targeting petal
          db-cast (cast-chain-of-vapor db cov-id petal-id)
          result (game/resolve-one-item db-cast)]
      ;; Chain-bounce selection should be pending
      (is (some? (:pending-selection result)))
      (let [;; Sacrifice the land to create a copy
            chain-sel (assoc (:pending-selection result)
                             :selection/selected #{land-id})
            app-db {:game/db (:db result)
                    :game/pending-selection chain-sel}
            app-after-sac (sel-core/confirm-selection-impl app-db)]
        ;; Should now have chain-bounce-target selection for the copy
        (is (= :chain-bounce-target
               (:selection/type (:game/pending-selection app-after-sac))))
        (let [;; Select the seal as the copy's target
              target-sel (assoc (:game/pending-selection app-after-sac)
                                :selection/selected #{seal-id})
              app-with-target (assoc app-after-sac :game/pending-selection target-sel)
              app-after-target (sel-core/confirm-selection-impl app-with-target)
              ;; Now resolve the copy (it's on the stack)
              copy-result (game/resolve-one-item (:game/db app-after-target))]
          ;; Copy should trigger chain-bounce selection (same card effects)
          (is (some? (:pending-selection copy-result))
              "Copy should create chain-bounce selection when it resolves")
          (let [;; Decline the chain for the copy (select nothing)
                copy-chain-sel (:pending-selection copy-result)
                app-copy {:game/db (:db copy-result)
                          :game/pending-selection copy-chain-sel}
                app-final (sel-core/confirm-selection-impl app-copy)
                db-final (:game/db app-final)
                ;; Check graveyard — copy should NOT be there
                p1-gy (q/get-objects-in-zone db-final :player-1 :graveyard)
                p2-gy (q/get-objects-in-zone db-final :player-2 :graveyard)
                all-gy (concat p1-gy p2-gy)
                gy-copies (filter :object/is-copy all-gy)]
            ;; Copy should cease to exist — not in any zone
            (is (empty? gy-copies)
                "Copy should cease to exist after resolving, not remain in graveyard")
            ;; Seal should have been bounced by the copy
            (is (= :hand (:object/zone (q/get-object db-final seal-id)))
                "Copy's target should have been bounced to hand")))))))
