(ns fizzle.cards.blue.hurkyls-recall-test
  "Tests for Hurkyl's Recall card.

   Hurkyl's Recall ({1}{U} Instant):
   Return all artifacts target player controls to their hand.

   Key behaviors:
   - Targets any player (caster or opponent)
   - Bounces ALL artifacts the target player controls to their hand
   - Non-artifacts on target player's battlefield are unaffected"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.hurkyls-recall :as hurkyls-recall]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Hurkyl's Recall" {1}{U} — Instant
(deftest hurkyls-recall-card-definition-test
  (testing "card has correct oracle properties"
    (let [card hurkyls-recall/card]
      (is (= :hurkyls-recall (:card/id card))
          "Card ID should be :hurkyls-recall")
      (is (= "Hurkyl's Recall" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card))
          "Mana cost should be {1}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "card has player targeting"
    (let [targeting (:card/targeting hurkyls-recall/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-player (:target/id req))
            "Target ID should be :target-player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (= #{:any-player} (:target/options req))
            "Target options should be #{:any-player} to target any player")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "Return all artifacts target player controls to their hand."
  (testing "card has bounce-all effect targeting the player"
    (let [effects (:card/effects hurkyls-recall/card)]
      (is (vector? effects)
          "Effects must be a vector")
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :bounce-all (:effect/type effect))
            "Effect type should be :bounce-all")
        (is (= :target-player (:effect/target-ref effect))
            "Effect should reference target-player")
        (is (= {:match/types #{:artifact}} (:effect/criteria effect))
            "Effect criteria should match artifacts only")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Return all artifacts target player controls to their hand."
(deftest hurkyls-recall-bounces-all-opponent-artifacts-test
  (testing "All 3 artifacts opponent controls return to their hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Add 3 artifacts to opponent's battlefield
          [db petal-id-1] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db petal-id-2] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db petal-id-3] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          ;; Cast targeting opponent, then resolve
          db-cast (th/cast-with-target db :player-1 hr-id :player-2)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id-1)))
          "First artifact should be in opponent's hand")
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id-2)))
          "Second artifact should be in opponent's hand")
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id-3)))
          "Third artifact should be in opponent's hand"))))


;; === C. Cannot-Cast Guards ===

;; Oracle: mana cost {1}{U} — cannot cast with only {U}
(deftest hurkyls-recall-cannot-cast-missing-colorless-test
  (testing "Cannot cast Hurkyl's Recall with only {U} (missing the {1} colorless)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (false? (rules/can-cast? db :player-1 hr-id))
          "Should not be castable with only {U} (missing 1 colorless)"))))


;; Oracle: mana cost {1}{U} — cannot cast with wrong color
(deftest hurkyls-recall-cannot-cast-wrong-color-test
  (testing "Cannot cast Hurkyl's Recall with only black mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:black 2})]
      (is (false? (rules/can-cast? db :player-1 hr-id))
          "Should not be castable with only black mana (no blue)"))))


;; === D. Storm Count ===

(deftest hurkyls-recall-increments-storm-count-test
  (testing "Casting Hurkyl's Recall increments storm count by 1"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          storm-before (q/get-storm-count db :player-1)
          db-cast (th/cast-with-target db :player-1 hr-id :player-2)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1 after casting"))))


;; === G. Edge Cases ===

;; G1: Target player has no artifacts — spell resolves cleanly, no crash
(deftest hurkyls-recall-no-artifacts-resolves-cleanly-test
  (testing "Resolves cleanly when target player has no artifacts on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Add a land to opponent's battlefield (not an artifact)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          db-cast (th/cast-with-target db :player-1 hr-id :player-2)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (nil? (:pending-selection result))
          "Should have no pending selection — effect is non-interactive")
      (is (= :battlefield (:object/zone (q/get-object db-resolved land-id)))
          "Land should remain on battlefield (not affected by bounce-all artifacts)"))))


;; G2: Mixed battlefield — only artifacts bounce, non-artifacts stay
(deftest hurkyls-recall-only-bounces-artifacts-mixed-battlefield-test
  (testing "Only artifacts are bounced; lands and creatures stay on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Add artifact, land to opponent's battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          db-cast (th/cast-with-target db :player-1 hr-id :player-2)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :hand (:object/zone (q/get-object db-resolved petal-id)))
          "Artifact (Lotus Petal) should be in hand after bounce-all")
      (is (= :battlefield (:object/zone (q/get-object db-resolved land-id)))
          "Land (Island) should remain on battlefield after bounce-all"))))


;; G3: Caster targets themselves — own artifacts return to caster's hand
(deftest hurkyls-recall-caster-targets-themselves-test
  (testing "Caster can target themselves — own artifacts return to their own hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hr-id] (th/add-card-to-zone db :hurkyls-recall :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Caster has an artifact on their own battlefield
          [db own-petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-cast (th/cast-with-target db :player-1 hr-id :player-1)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :hand (:object/zone (q/get-object db-resolved own-petal-id)))
          "Caster's own artifact should be returned to their hand"))))
