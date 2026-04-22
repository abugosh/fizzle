(ns fizzle.cards.blue.hoodwink-test
  "Tests for Hoodwink card.

   Hoodwink: 1U - Instant
   Return target artifact, enchantment, or land to its owner's hand.

   This tests:
   - Card definition (type, cost, targeting, effects)
   - Cast-resolve happy path (bounce artifact, enchantment, or land)
   - Cannot-cast guards (no mana, no valid target, wrong zone)
   - Storm count (casting increments storm)
   - Targeting (artifact/enchantment/land valid, creature invalid, both players)
   - Edge cases (own permanent, returns to owner regardless of controller)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.hoodwink :as hoodwink]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Hoodwink" — verified against Scryfall (2026-04-22)
(deftest hoodwink-card-definition-test
  (testing "card has correct oracle properties"
    (let [card hoodwink/card]
      (is (= :hoodwink (:card/id card))
          "Card ID should be :hoodwink")
      (is (= "Hoodwink" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card))
          "Mana cost should be {1}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Return target artifact, enchantment, or land to its owner's hand."
             (:card/text card))
          "Card text should match oracle")))

  (testing "card uses correct targeting format"
    (let [targeting (:card/targeting hoodwink/card)]
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
            "Should be able to target either player's permanent")
        (is (= {:match/types #{:artifact :enchantment :land}}
               (:target/criteria req))
            "Should match artifact, enchantment, or land via :match/types OR-semantics")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "Return target ... to its owner's hand."
  (testing "effects bounce via target-ref"
    (let [effects (:card/effects hoodwink/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :bounce (:effect/type effect))
            "Effect type should be :bounce")
        (is (= :target-permanent (:effect/target-ref effect))
            "Bounce should reference :target-permanent for stored-targets resolution")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Return target artifact ... to its owner's hand."
(deftest hoodwink-bounces-artifact-test
  (testing "Hoodwink returns target artifact to owner's hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db-cast (th/cast-with-target db :player-1 hw-id petal-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved hw-id)))
          "Hoodwink should be in graveyard after resolution")
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id)))
          "Target artifact should be returned to owner's hand"))))


;; Oracle: "Return target ... enchantment ... to its owner's hand."
(deftest hoodwink-bounces-enchantment-test
  (testing "Hoodwink returns target enchantment to owner's hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          db-cast (th/cast-with-target db :player-1 hw-id seal-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :hand (:object/zone (q/get-object db-resolved seal-id)))
          "Target enchantment should be returned to owner's hand"))))


;; Oracle: "Return target ... or land to its owner's hand."
(deftest hoodwink-bounces-land-test
  (testing "Hoodwink returns target land to owner's hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-2)
          db-cast (th/cast-with-target db :player-1 hw-id island-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :hand (:object/zone (q/get-object db-resolved island-id)))
          "Target land should be returned to owner's hand"))))


;; === C. Cannot-Cast Guards ===

(deftest hoodwink-cannot-cast-without-mana-test
  (testing "Cannot cast Hoodwink without {1}{U}"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 hw-id))
          "Should not be castable without mana"))))


(deftest hoodwink-cannot-cast-without-blue-mana-test
  (testing "Cannot cast Hoodwink with only colorless mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 2})
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 hw-id))
          "Colorless substitute cannot satisfy the {U} pip"))))


(deftest hoodwink-cannot-cast-without-valid-target-test
  (testing "Cannot cast Hoodwink with no artifact/enchantment/land in play"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Only a creature on the battlefield — not a valid target
          [db _swarm-id] (th/add-card-to-zone db :xantid-swarm :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 hw-id))
          "Should not be castable without a valid target type"))))


(deftest hoodwink-cannot-cast-from-graveyard-test
  (testing "Cannot cast Hoodwink from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 hw-id))
          "Should not be castable from graveyard"))))


(deftest hoodwink-cannot-cast-from-battlefield-test
  (testing "Cannot cast Hoodwink from battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :battlefield :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 hw-id))
          "Should not be castable from battlefield"))))


;; === D. Storm Count ===

(deftest hoodwink-increments-storm-count-test
  (testing "Casting Hoodwink increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count starts at 0")
          db-cast (th/cast-with-target db :player-1 hw-id petal-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Hoodwink"))))


;; === F. Targeting Tests ===

;; Oracle: "target artifact" — artifacts are valid
(deftest hoodwink-can-target-artifact-test
  (testing "Hoodwink can target an artifact"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting hoodwink/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= [petal-id] targets)
          "Should find the artifact as the only valid target"))))


;; Oracle: "enchantment" — enchantments are valid
(deftest hoodwink-can-target-enchantment-test
  (testing "Hoodwink can target an enchantment"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db seal-id] (th/add-card-to-zone db :seal-of-cleansing :battlefield :player-2)
          target-req (first (:card/targeting hoodwink/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= [seal-id] targets)
          "Should find the enchantment as the only valid target"))))


;; Oracle: "or land" — lands are valid (distinguishes Hoodwink from Chain of Vapor)
(deftest hoodwink-can-target-land-test
  (testing "Hoodwink can target a land"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-2)
          target-req (first (:card/targeting hoodwink/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= [island-id] targets)
          "Should find the land as the only valid target"))))


;; Oracle: "target artifact, enchantment, or land" — creatures are NOT listed
(deftest hoodwink-cannot-target-creature-test
  (testing "Hoodwink cannot target a creature"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _swarm-id] (th/add-card-to-zone db :xantid-swarm :battlefield :player-2)
          target-req (first (:card/targeting hoodwink/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find a creature as a valid target"))))


;; Oracle: "target artifact, enchantment, or land" — owner-agnostic
(deftest hoodwink-can-target-either-player-test
  (testing "Hoodwink can target permanents on either side of the battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db own-petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db opp-petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting hoodwink/card))
          targets (set (targeting/find-valid-targets db :player-1 target-req))]
      (is (= #{own-petal-id opp-petal-id} targets)
          "Should find both players' artifacts as valid targets"))))


(deftest hoodwink-has-valid-targets-returns-false-when-only-creatures-test
  (testing "has-valid-targets? returns false when only creatures on the battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _swarm-id] (th/add-card-to-zone db :xantid-swarm :battlefield :player-2)]
      (is (false? (targeting/has-valid-targets? db :player-1 hoodwink/card))
          "Should have no valid targets when only creatures exist"))))


;; === G. Edge Cases ===

;; Oracle: "its owner's hand" — the target returns to the owner, not the caster's hand.
(deftest hoodwink-returns-to-opponents-hand-not-casters-test
  (testing "Bouncing opponent's artifact returns it to opponent's hand, not caster's"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          hand1-before (count (q/get-objects-in-zone db :player-1 :hand))
          hand2-before (count (q/get-objects-in-zone db :player-2 :hand))
          db-cast (th/cast-with-target db :player-1 hw-id petal-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)
          bounced (q/get-object db-resolved petal-id)]
      (is (= :hand (:object/zone bounced))
          "Bounced permanent should be in the hand zone")
      ;; Owner ref on object points to player-2 (opponent)
      (let [owner-ref (:object/owner bounced)
            owner-eid (if (map? owner-ref) (:db/id owner-ref) owner-ref)
            owner-player-id (q/get-player-id db-resolved owner-eid)]
        (is (= :player-2 owner-player-id)
            "Bounced permanent's owner should be :player-2"))
      ;; Opponent gained 1 card in hand (the bounced petal), caster lost 1 (Hoodwink cast)
      (is (= (dec hand1-before) (count (q/get-objects-in-zone db-resolved :player-1 :hand)))
          "Caster's hand should be down 1 (Hoodwink moved to graveyard)")
      (is (= (inc hand2-before) (count (q/get-objects-in-zone db-resolved :player-2 :hand)))
          "Opponent's hand should be up 1 (the bounced petal)"))))


;; Oracle: can bounce own permanent — useful to save from destruction
(deftest hoodwink-can-bounce-own-permanent-test
  (testing "Hoodwink can return caster's own permanent to their hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hw-id] (th/add-card-to-zone db :hoodwink :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db own-petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-cast (th/cast-with-target db :player-1 hw-id own-petal-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)
          bounced (q/get-object db-resolved own-petal-id)]
      (is (= :hand (:object/zone bounced))
          "Own permanent should be returned to hand")
      (let [owner-ref (:object/owner bounced)
            owner-eid (if (map? owner-ref) (:db/id owner-ref) owner-ref)
            owner-player-id (q/get-player-id db-resolved owner-eid)]
        (is (= :player-1 owner-player-id)
            "Own permanent's owner should remain :player-1")))))
