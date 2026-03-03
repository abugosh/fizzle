(ns fizzle.cards.red.lightning-bolt-test
  "Tests for Lightning Bolt card.

   Lightning Bolt: R - Instant
   Lightning Bolt deals 3 damage to any target.

   This tests:
   - Card definition (type, cost, targeting, effects)
   - Cast-resolve happy path (deal 3 damage via stored targets)
   - Cannot-cast guards (no mana, wrong zone)
   - Storm count (casting increments storm)
   - Targeting (valid targets, both players)
   - Edge cases (life boundaries, game-end detection)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest lightning-bolt-card-definition-test
  (testing "card has correct oracle properties"
    (let [card lightning-bolt/card]
      (is (= :lightning-bolt (:card/id card))
          "Card ID should be :lightning-bolt")
      (is (= "Lightning Bolt" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:red 1} (:card/mana-cost card))
          "Mana cost should be {R}")
      (is (= #{:red} (:card/colors card))
          "Card should be red")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Lightning Bolt deals 3 damage to any target." (:card/text card))
          "Card text should match oracle")))

  (testing "card uses correct targeting format"
    (let [targeting (:card/targeting lightning-bolt/card)]
      (is (vector? targeting)
          "Targeting must be a vector for targeting system")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target (:target/id req))
            "Target ID should be :target")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (contains? (:target/options req) :any-player)
            "Should allow targeting any player")
        (is (:target/required req)
            "Target should be required"))))

  (testing "effects deal 3 damage via target-ref"
    (let [effects (:card/effects lightning-bolt/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :deal-damage (:effect/type effect))
            "Effect type should be :deal-damage")
        (is (= 3 (:effect/amount effect))
            "Damage amount should be 3")
        (is (= :target (:effect/target-ref effect))
            "Effect should reference :target for stored-targets resolution")))))


;; === B. Cast-Resolve Happy Path Tests ===

(deftest cast-bolt-targeting-opponent-deals-3-damage-test
  (testing "casting and resolving Lightning Bolt deals 3 damage to opponent"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)]
      ;; Spell should be on stack
      (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
          "Lightning Bolt should be on stack after casting")
      ;; Resolve via production path
      (let [result (game/resolve-one-item db-cast)
            db-resolved (:db result)]
        ;; Spell should be in graveyard after resolution
        (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
            "Lightning Bolt should be in graveyard after resolution")
        ;; Opponent should have taken 3 damage (20 -> 17)
        (is (= 17 (q/get-life-total db-resolved :player-2))
            "Opponent life should be 17 after taking 3 damage")
        ;; Caster life should be unchanged
        (is (= 20 (q/get-life-total db-resolved :player-1))
            "Caster life should remain 20")))))


(deftest cast-bolt-targeting-self-deals-3-damage-test
  (testing "casting and resolving Lightning Bolt targeting self deals 3 damage to self"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-1)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Lightning Bolt should be in graveyard after resolution")
      (is (= 17 (q/get-life-total db-resolved :player-1))
          "Self life should be 17 after targeting self")
      (is (= 20 (q/get-life-total db-resolved :player-2))
          "Opponent life should remain 20"))))


;; === C. Cannot-Cast Guards ===

(deftest cannot-cast-without-red-mana-test
  (testing "cannot cast Lightning Bolt without red mana"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be able to cast without red mana"))))


(deftest cannot-cast-from-graveyard-test
  (testing "cannot cast Lightning Bolt from graveyard"
    (let [db (th/create-test-db {:mana {:red 1}})
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be able to cast from graveyard"))))


(deftest cannot-cast-from-battlefield-test
  (testing "cannot cast Lightning Bolt from battlefield"
    (let [db (th/create-test-db {:mana {:red 1}})
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be able to cast from battlefield"))))


;; === D. Storm Count ===

(deftest casting-bolt-increments-storm-count-test
  (testing "casting Lightning Bolt increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          _ (is (= 0 (q/get-storm-count db-with-mana :player-1))
                "Precondition: storm count starts at 0")
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Lightning Bolt"))))


;; === E. Targeting Tests ===

(deftest no-valid-targets-without-opponent-test
  (testing "has valid targets even without opponent (can target self)"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db @conn]
      (is (targeting/has-valid-targets? db :player-1 lightning-bolt/card)
          "Should have valid targets (can target self even in goldfish)"))))


(deftest find-valid-targets-returns-both-players-test
  (testing "find-valid-targets returns both players with :any-player"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db (th/add-opponent @conn)
          req (first (:card/targeting lightning-bolt/card))
          targets (targeting/find-valid-targets db :player-1 req)]
      (is (= 2 (count targets))
          "Should find 2 valid targets (self and opponent)")
      (is (contains? (set targets) :player-1)
          "Should include self as valid target")
      (is (contains? (set targets) :player-2)
          "Should include opponent as valid target"))))


;; === F. Edge Cases ===

(defn- set-player-life
  "Set a player's life total directly."
  [db player-id life]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (d/transact! conn [[:db/add player-eid :player/life life]])
    @conn))


(deftest bolt-kills-at-exactly-3-life-test
  (testing "bolt kills opponent at exactly 3 life"
    (let [db (th/create-test-db)
          db (set-player-life (th/add-opponent db) :player-2 3)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)
          result (game/resolve-one-item db-cast)
          db-resolved (sba/check-and-execute-sbas (:db result))]
      (is (= 0 (q/get-life-total db-resolved :player-2))
          "Opponent life should be 0")
      (is (= :life-zero (:game/loss-condition (q/get-game-state db-resolved)))
          "Should set loss condition to :life-zero"))))


(deftest bolt-overkills-below-zero-test
  (testing "bolt overkills below zero (no clamping)"
    (let [db (th/create-test-db)
          db (set-player-life (th/add-opponent db) :player-2 1)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)
          result (game/resolve-one-item db-cast)
          db-resolved (sba/check-and-execute-sbas (:db result))]
      (is (= -2 (q/get-life-total db-resolved :player-2))
          "Opponent life should be -2 (no clamping)")
      (is (= :life-zero (:game/loss-condition (q/get-game-state db-resolved)))
          "Should set loss condition to :life-zero"))))


(deftest bolt-on-already-dead-opponent-test
  (testing "bolt on already-dead opponent still deals damage"
    (let [db (th/create-test-db)
          db (set-player-life (th/add-opponent db) :player-2 0)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          db-cast (th/cast-with-target db-with-mana :player-1 obj-id :player-2)
          result (game/resolve-one-item db-cast)
          db-resolved (sba/check-and-execute-sbas (:db result))]
      (is (= -3 (q/get-life-total db-resolved :player-2))
          "Opponent life should be -3 (still deals damage)")
      (is (= :life-zero (:game/loss-condition (q/get-game-state db-resolved)))
          "Should set loss condition to :life-zero"))))


;; === Stack-Item Target Storage ===

(deftest confirm-cast-time-target-stores-on-stack-item-test
  (testing "cast-time targeting stores targets on stack-item"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:red 1})
          db-after (th/cast-with-target db-with-mana :player-1 obj-id :player-2)]
      ;; Spell should be on stack
      (is (= :stack (:object/zone (q/get-object db-after obj-id)))
          "Lightning Bolt should be on stack after casting")
      ;; Stack-item should have targets with :target key (matching :target/id)
      (let [obj-eid (d/q '[:find ?e . :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-after obj-id)
            stack-item (stack/get-stack-item-by-object-ref db-after obj-eid)]
        (is (some? stack-item)
            "Stack-item should exist for the spell")
        (is (= {:target :player-2} (:stack-item/targets stack-item))
            "Stack-item should have targets with :target key mapped to :player-2"))
      ;; Object should NOT have targets
      (let [obj (q/get-object db-after obj-id)]
        (is (nil? (:object/targets obj))
            "Object should NOT have :object/targets")))))
