(ns fizzle.cards.black.duress-test
  "Tests for Duress card.

   Duress: B - Sorcery
   Target opponent reveals their hand. You choose a noncreature, nonland
   card from it. That player discards that card.

   Key behaviors:
   - Must show full opponent hand (all cards visible)
   - Only noncreature, nonland cards are selectable
   - If no valid cards exist, resolves with no discard
   - Is a sorcery (requires main phase + empty stack)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.black.duress :as duress]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Duress" — verified against Scryfall
(deftest duress-card-definition-test
  (testing "card has correct oracle properties"
    (let [card duress/card]
      (is (= :duress (:card/id card))
          "Card ID should be :duress")
      (is (= "Duress" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:black 1} (:card/mana-cost card))
          "Mana cost should be {B}")
      (is (= #{:black} (:card/colors card))
          "Card should be black")
      (is (= #{:sorcery} (:card/types card))
          "Card should be a sorcery")))

  (testing "card targets opponent only"
    (let [targeting (:card/targeting duress/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req))
            "Target ID should be :player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (= #{:opponent} (:target/options req))
            "Should only target opponents (not self)")
        (is (true? (:target/required req))
            "Target should be required"))))

  ;; Oracle: "You choose a noncreature, nonland card from it. That player discards that card."
  (testing "card has discard-from-revealed-hand effect with not-types criteria"
    (let [effects (:card/effects duress/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :discard-from-revealed-hand (:effect/type effect))
            "Effect type should be :discard-from-revealed-hand")
        (is (= :targeted-player (:effect/target effect))
            "Effect should target the targeted player")
        (is (= {:match/not-types #{:creature :land}} (:effect/criteria effect))
            "Criteria should exclude creatures and lands")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "You choose a noncreature, nonland card from it. That player discards that card."
(deftest duress-cast-resolve-discards-instant-test
  (testing "Duress forces opponent to discard a noncreature/nonland card"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; Add an instant (Dark Ritual) to opponent's hand — selectable
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Cast Duress targeting opponent
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          ;; Resolve — should return pending-selection for hand reveal
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; Should have pending selection for hand reveal
      (is (= :hand-reveal-discard (:selection/type sel))
          "Should enter hand-reveal-discard selection")
      (is (= :player-1 (:selection/player-id sel))
          "Caster should be the one choosing")
      (is (= :player-2 (:selection/target-player sel))
          "Target player should be opponent")
      ;; Valid targets should include the instant
      (is (contains? (set (:selection/valid-targets sel)) ritual-id)
          "Dark Ritual should be a valid target (instant)")
      ;; Select the instant and confirm
      (let [app-db {:game/db (:db result)
                    :game/pending-selection (assoc sel :selection/selected #{ritual-id})}
            app-db-after (sel-core/confirm-selection-impl app-db)]
        ;; Dark Ritual should be discarded
        (is (= :graveyard (th/get-object-zone (:game/db app-db-after) ritual-id))
            "Dark Ritual should be in graveyard after Duress")))))


;; === C. Cannot-Cast Guards ===

(deftest duress-cannot-cast-without-mana-test
  (testing "Cannot cast Duress without black mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 duress-id))
          "Should not be castable without mana"))))


(deftest duress-cannot-cast-from-graveyard-test
  (testing "Cannot cast Duress from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :graveyard :player-1)
          db (mana/add-mana db :player-1 {:black 1})]
      (is (false? (rules/can-cast? db :player-1 duress-id))
          "Should not be castable from graveyard"))))


;; Sorcery requires main phase + empty stack
(deftest duress-cannot-cast-at-instant-speed-test
  (testing "Cannot cast Duress outside main phase (sorcery speed)"
    (let [db (th/create-test-db {:mana {:black 1}})
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          ;; Change phase to upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db-upkeep (d/db-with db [[:db/add game-eid :game/phase :upkeep]])]
      (is (false? (rules/can-cast? db-upkeep :player-1 duress-id))
          "Should not be castable during upkeep"))))


;; === D. Storm Count ===

(deftest duress-increments-storm-count-test
  (testing "Casting Duress increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          storm-before (q/get-storm-count db :player-1)
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Selection / Hand Reveal Tests ===

;; Oracle: "You choose a noncreature, nonland card"
(deftest duress-only-instants-sorceries-selectable-test
  (testing "Only noncreature/nonland cards are selectable"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; Opponent hand: instant + land
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          [db _island-id] (th/add-card-to-zone db :island :hand :player-2)
          ;; Cast and resolve
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; Only instant should be selectable
      (is (= 1 (count (:selection/valid-targets sel)))
          "Only 1 card should be selectable (the instant)")
      (is (contains? (set (:selection/valid-targets sel)) ritual-id)
          "Dark Ritual (instant) should be selectable"))))


;; Oracle: implies lands are NOT selectable
(deftest duress-lands-not-selectable-test
  (testing "Lands are not selectable by Duress"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; Opponent hand: only lands
          [db _island-id] (th/add-card-to-zone db :island :hand :player-2)
          [db _swamp-id] (th/add-card-to-zone db :swamp :hand :player-2)
          ;; Cast and resolve
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; No cards should be selectable (both are lands)
      (is (empty? (:selection/valid-targets sel))
          "No cards should be selectable when hand is all lands")
      ;; Should allow confirming with 0 selected (exact-or-zero)
      (is (= :exact-or-zero (:selection/validation sel))
          "Validation should allow 0 selection"))))


;; === F. Targeting Tests ===

(deftest duress-targets-opponent-only-test
  (testing "Duress can only target opponent"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          target-req (first (:card/targeting duress/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find exactly one valid target")
      (is (= :player-2 (first targets))
          "Should only target opponent"))))


(deftest duress-targets-only-opponent-not-self-test
  (testing "Duress targets only opponent, cannot target self"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          target-req (first (:card/targeting duress/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (not (contains? (set targets) :player-1))
          "Should not be able to target self"))))


;; === G. Edge Cases ===

;; Epic open question: "Handling when Duress target has no valid cards"
(deftest duress-empty-hand-no-discard-test
  (testing "Duress against empty hand resolves with no discard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; Opponent has empty hand
          _ (is (= 0 (th/get-hand-count db :player-2))
                "Precondition: opponent has empty hand")
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      ;; Should still enter selection (reveals empty hand)
      (is (= :hand-reveal-discard (:selection/type sel))
          "Should enter hand-reveal-discard even with empty hand")
      (is (empty? (:selection/valid-targets sel))
          "Should have no valid targets")
      ;; Auto-confirm should be true when no valid targets
      (is (true? (:selection/auto-confirm? sel))
          "Should auto-confirm when no valid targets"))))


;; Sorceries should be selectable (noncreature, nonland)
(deftest duress-sorcery-selectable-test
  (testing "Sorceries are selectable by Duress"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; Add a sorcery (Careful Study) to opponent's hand
          [db study-id] (th/add-card-to-zone db :careful-study :hand :player-2)
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (contains? (set (:selection/valid-targets sel)) study-id)
          "Careful Study (sorcery) should be selectable"))))


;; Artifacts should be selectable (noncreature, nonland)
(deftest duress-artifact-selectable-test
  (testing "Artifacts are selectable by Duress"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; Add Lotus Petal (artifact) to opponent's hand
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-2)
          target-req (first (:card/targeting duress/card))
          modes (rules/get-casting-modes db :player-1 duress-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id duress-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (contains? (set (:selection/valid-targets sel)) petal-id)
          "Lotus Petal (artifact) should be selectable"))))
