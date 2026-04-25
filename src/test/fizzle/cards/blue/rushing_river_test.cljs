(ns fizzle.cards.blue.rushing-river-test
  "Tests for Rushing River card.

   Rushing River: {2}{U} - Instant
   Kicker—Sacrifice a land.
   Return target nonland permanent to its owner's hand.
   If this spell was kicked, return another target nonland permanent
   to its owner's hand.

   Key behaviors:
   - Primary mode: single nonland permanent bounce
   - Kicked mode: sacrifice a land + bounce 2 distinct nonland permanents
   - Kicker is non-mana (sacrifice cost only)
   - Both kicked targets collected in single 2-slot modal"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.rushing-river :as rushing-river]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.casting :as casting]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helper: multi-step kicked cast flow
;; =====================================================

(defn- cast-kicked
  "Cast Rushing River in kicked mode, sacrificing land-id and targeting two permanents.
   Flow:
   1. cast-spell-handler → mode selector (primary + kicked)
   2. confirm kicked mode → sacrifice selection (chaining lifecycle)
   3. confirm sacrifice of land-id → 2-slot target selection (finalized lifecycle)
   4. confirm [target-a-id target-b-id] → spell on stack (mana paid greedily by confirm-cast-time-target)
   Returns db after casting."
  [db rr-id land-id target-a-id target-b-id]
  (let [;; Step 1: cast-spell-handler → spell-mode selection pending
        app-db (casting/cast-spell-handler {:game/db db :game/selected-card rr-id})
        mode-sel (:game/pending-selection app-db)
        ;; Find the kicked mode from candidates
        kicked-mode (first (filter #(= :kicked (:mode/id %))
                                   (:selection/candidates mode-sel)))
        ;; Step 2: confirm kicked mode → sacrifice selection pending
        {:keys [db selection]} (th/confirm-selection (:game/db app-db) mode-sel #{kicked-mode})
        _ (assert (= :sacrifice-cost (:selection/domain selection))
                  (str "Expected sacrifice-cost selection, got: " (:selection/domain selection)))
        ;; Step 3: confirm sacrifice of land → 2-slot targeting selection
        {:keys [db selection]} (th/confirm-selection db selection #{land-id})
        _ (assert (= :cast-time-targeting (:selection/domain selection))
                  (str "Expected cast-time-targeting selection, got: " (:selection/domain selection)))
        _ (assert (= 2 (:selection/select-count selection))
                  (str "Expected 2-slot target selection, got select-count: "
                       (:selection/select-count selection)))
        ;; Step 4: confirm 2 targets → spell on stack
        {:keys [db]} (th/confirm-selection db selection [target-a-id target-b-id])]
    db))


;; =====================================================
;; Helper Functions
;; =====================================================

(defn- cast-primary
  "Cast Rushing River in primary mode (non-kicked) targeting a single permanent.
   Returns db after casting (spell on stack, mana spent, storm incremented)."
  [db rr-id target-id]
  (th/cast-with-target db :player-1 rr-id target-id))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

(deftest rushing-river-card-definition-test
  (testing "card has correct oracle properties"
    (let [card rushing-river/card]
      (is (= :rushing-river (:card/id card))
          "Card ID should be :rushing-river")
      (is (= "Rushing River" (:card/name card))
          "Card name should match oracle")
      (is (= 3 (:card/cmc card))
          "CMC should be 3")
      (is (= {:colorless 2 :blue 1} (:card/mana-cost card))
          "Mana cost should be {2}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "primary targeting has correct shape"
    (let [targeting (:card/targeting rushing-river/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Primary mode should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :primary (:target/id req))
            "Primary target ID should be :primary")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :battlefield (:target/zone req))
            "Target zone should be :battlefield")
        (is (= :any (:target/controller req))
            "Should target any controller's permanent")
        (is (= {:match/not-types #{:land}} (:target/criteria req))
            "Should target nonland permanents via :match/not-types")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "primary effects bounce primary target"
    (let [effects (:card/effects rushing-river/card)]
      (is (= 1 (count effects))
          "Primary mode should have exactly one effect")
      (let [eff (first effects)]
        (is (= :bounce (:effect/type eff))
            "Effect should be :bounce")
        (is (= :primary (:effect/target-ref eff))
            "Bounce should reference :primary target"))))

  (testing "alternate-costs contains kicker entry"
    (let [alts (:card/alternate-costs rushing-river/card)]
      (is (= 1 (count alts))
          "Should have exactly one alternate cost (kicker)")
      (let [kicked (first alts)]
        (is (= :kicked (:alternate/id kicked))
            "Alternate ID should be :kicked")
        (is (= :kicker (:alternate/kind kicked))
            "Alternate kind should be :kicker")
        (is (= :hand (:alternate/zone kicked))
            "Kicker zone should be :hand")
        (is (= {:colorless 2 :blue 1} (:alternate/mana-cost kicked))
            "Kicked mana cost is same as primary (kicker is non-mana)")
        (is (= :graveyard (:alternate/on-resolve kicked))
            "Should go to graveyard on resolution"))))

  (testing "kicked additional-costs require sacrificing a land"
    (let [kicked (first (:card/alternate-costs rushing-river/card))
          add-costs (:alternate/additional-costs kicked)]
      (is (= 1 (count add-costs))
          "Kicked mode should have exactly one additional cost")
      (let [sac-cost (first add-costs)]
        (is (= :sacrifice-permanent (:cost/type sac-cost))
            "Additional cost type should be :sacrifice-permanent")
        (is (= {:match/types #{:land}} (:cost/criteria sac-cost))
            "Sacrifice criteria should require a land"))))

  (testing "kicked targeting has two nonland-permanent slots"
    (let [kicked (first (:card/alternate-costs rushing-river/card))
          reqs (:alternate/targeting kicked)]
      (is (= 2 (count reqs))
          "Kicked mode should have exactly two target requirements")
      (let [[req-a req-b] reqs]
        (is (= :slot-a (:target/id req-a)) "First slot ID should be :slot-a")
        (is (= :slot-b (:target/id req-b)) "Second slot ID should be :slot-b")
        (doseq [req reqs]
          (is (= :object (:target/type req)) "Target type should be :object")
          (is (= :battlefield (:target/zone req)) "Zone should be :battlefield")
          (is (= :any (:target/controller req)) "Controller should be :any")
          (is (= {:match/not-types #{:land}} (:target/criteria req))
              "Should target nonland permanents")
          (is (true? (:target/required req)) "Both targets are required")))))

  (testing "kicked effects reference slot-a and slot-b"
    (let [kicked (first (:card/alternate-costs rushing-river/card))
          effects (:alternate/effects kicked)]
      (is (= 2 (count effects))
          "Kicked mode should have exactly two effects")
      (let [[eff-a eff-b] effects]
        (is (= :bounce (:effect/type eff-a)) "First effect should be :bounce")
        (is (= :slot-a (:effect/target-ref eff-a)) "First bounce refs :slot-a")
        (is (= :bounce (:effect/type eff-b)) "Second effect should be :bounce")
        (is (= :slot-b (:effect/target-ref eff-b)) "Second bounce refs :slot-b")))))


;; =====================================================
;; B. Cast-Resolve Happy Path — Primary Mode
;; =====================================================

(deftest rushing-river-primary-bounces-opponent-permanent-test
  (testing "Primary mode bounces a single nonland permanent to owner's hand"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          db-cast (cast-primary db rr-id petal-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :hand (:object/zone (q/get-object db petal-id)))
          "Target permanent should be returned to owner's hand"))))


(deftest rushing-river-primary-bounces-own-permanent-test
  (testing "Primary mode can bounce own nonland permanent"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-cast (cast-primary db rr-id petal-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :hand (:object/zone (q/get-object db petal-id)))
          "Own nonland permanent should be returned to hand"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest rushing-river-cannot-cast-without-mana-test
  (testing "Cannot cast Rushing River without {2}{U}"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 rr-id))
          "Should not be castable without mana"))))


(deftest rushing-river-cannot-cast-from-graveyard-test
  (testing "Cannot cast Rushing River from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :graveyard :player-1)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)]
      (is (false? (rules/can-cast? db :player-1 rr-id))
          "Should not be castable from graveyard"))))


(deftest rushing-river-cannot-cast-without-valid-target-test
  (testing "Cannot cast Rushing River without a nonland permanent to target"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)]
      ;; No permanents on battlefield
      (is (false? (rules/can-cast? db :player-1 rr-id))
          "Should not be castable without valid targets"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest rushing-river-storm-increments-primary-mode-test
  (testing "Casting Rushing River in primary mode increments storm by 1"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          storm-before (q/get-storm-count db :player-1)
          db-cast (cast-primary db rr-id petal-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1 when cast in primary mode"))))


;; =====================================================
;; F. Targeting Tests
;; =====================================================

(deftest rushing-river-lands-are-not-valid-targets-test
  (testing "Lands are not valid targets for primary mode"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _island-id] (th/add-card-to-zone db :island :battlefield :player-2)
          target-req (first (:card/targeting rushing-river/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find lands as valid targets"))))


(deftest rushing-river-artifacts-are-valid-targets-test
  (testing "Artifacts (nonland permanents) are valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting rushing-river/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= #{petal-id} (set targets))
          "Artifact should be a valid target"))))


(deftest rushing-river-opponent-permanents-are-valid-targets-test
  (testing "Can target opponent's nonland permanents"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db _rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db _creature-id] (th/add-test-creature db :player-2 2 2)
          target-req (first (:card/targeting rushing-river/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (pos? (count targets))
          "Opponent's creatures should be valid targets"))))


;; =====================================================
;; B. Cast-Resolve Happy Path — Kicked Mode
;; =====================================================

(deftest rushing-river-kicked-bounces-two-permanents-test
  (testing "Kicked mode sacrifices a land and bounces two nonland permanents"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          ;; Land to sacrifice (player-1's)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Two nonland permanents on battlefield (both belong to player-2)
          [db creature-a-id] (th/add-test-creature db :player-2 2 2)
          [db creature-b-id] (th/add-test-creature db :player-2 3 3)
          ;; Execute full kicked flow
          db-cast (cast-kicked db rr-id land-id creature-a-id creature-b-id)
          ;; Verify land was sacrificed
          _ (is (= :graveyard (:object/zone (q/get-object db-cast land-id)))
                "Sacrificed land should be in graveyard")
          ;; Resolve — both permanents should bounce to hand
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :hand (:object/zone (q/get-object db creature-a-id)))
          "First target should be bounced to owner's hand")
      (is (= :hand (:object/zone (q/get-object db creature-b-id)))
          "Second target should be bounced to owner's hand"))))


(deftest rushing-river-kicked-bounces-mixed-controllers-test
  (testing "Kicked mode can target permanents from both players"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; One permanent each player
          [db creature-p1-id] (th/add-test-creature db :player-1 2 2)
          [db creature-p2-id] (th/add-test-creature db :player-2 3 3)
          db-cast (cast-kicked db rr-id land-id creature-p1-id creature-p2-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :hand (:object/zone (q/get-object db creature-p1-id)))
          "Player-1 permanent should be bounced to hand")
      (is (= :hand (:object/zone (q/get-object db creature-p2-id)))
          "Player-2 permanent should be bounced to hand"))))


;; =====================================================
;; C. Kicker-Mode Guards
;; =====================================================

(deftest rushing-river-kicker-disabled-without-land-test
  (testing "Kicked mode is infeasible when no land is available to sacrifice"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          ;; Two nonland permanents but no lands
          [db _creature-a] (th/add-test-creature db :player-2 2 2)
          [db _creature-b] (th/add-test-creature db :player-2 3 3)
          modes (rules/get-casting-modes db :player-1 rr-id)
          castable-modes (filterv #(rules/can-cast-mode? db :player-1 rr-id %) modes)]
      ;; Only primary mode should be castable
      (is (= 1 (count castable-modes))
          "Only primary mode should be castable when no land to sacrifice")
      (is (= :primary (:mode/id (first castable-modes)))
          "The castable mode should be the primary mode"))))


(deftest rushing-river-kicker-disabled-with-fewer-than-two-targets-test
  (testing "Kicked mode is infeasible when fewer than 2 valid targets exist"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          ;; Land available to sacrifice but only 1 nonland permanent
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db _creature-id] (th/add-test-creature db :player-2 2 2)
          modes (rules/get-casting-modes db :player-1 rr-id)
          castable-modes (filterv #(rules/can-cast-mode? db :player-1 rr-id %) modes)]
      ;; Only primary mode should be castable (kicked needs 2+ unique targets)
      (is (= 1 (count castable-modes))
          "Only primary mode should be castable with only 1 valid target")
      (is (= :primary (:mode/id (first castable-modes)))
          "The castable mode should be primary"))))


;; =====================================================
;; D. Storm Count — Kicked Mode
;; =====================================================

(deftest rushing-river-storm-increments-once-kicked-test
  (testing "Casting Rushing River in kicked mode increments storm by 1 (not 2)"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db creature-a-id] (th/add-test-creature db :player-2 2 2)
          [db creature-b-id] (th/add-test-creature db :player-2 3 3)
          storm-before (q/get-storm-count db :player-1)
          db-cast (cast-kicked db rr-id land-id creature-a-id creature-b-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by exactly 1 even when kicked"))))


;; =====================================================
;; F. Kicked Targeting: Distinctness + Nonland-only
;; =====================================================

(deftest rushing-river-kicked-enforces-distinctness-test
  (testing "Kicked mode selection enforces distinctness (cannot target same permanent twice)"
    ;; Both targeting slots share identical criteria — this triggers distinctness enforcement.
    (let [kicked (first (:card/alternate-costs rushing-river/card))
          [req-a req-b] (:alternate/targeting kicked)
          criteria-keys [:target/type :target/options :target/criteria]]
      (is (= (select-keys req-a criteria-keys) (select-keys req-b criteria-keys))
          "Both slots must have identical criteria for distinctness to be enforced"))
    ;; With only 1 valid target, kicked mode requires 2 distinct targets → infeasible.
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db _land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Only 1 nonland permanent — kicked mode infeasible
          [db _creature-id] (th/add-test-creature db :player-2 2 2)
          modes (rules/get-casting-modes db :player-1 rr-id)
          kicked-mode (first (filter #(= :kicked (:mode/id %)) modes))]
      (is (false? (rules/can-cast-mode? db :player-1 rr-id kicked-mode))
          "Kicked mode infeasible with only 1 valid target (distinctness requires 2)"))))


(deftest rushing-river-kicked-targets-nonland-only-test
  (testing "Kicked mode cannot target lands — both slots require nonland criteria"
    (let [kicked (first (:card/alternate-costs rushing-river/card))]
      (doseq [req (:alternate/targeting kicked)]
        (is (= {:match/not-types #{:land}} (:target/criteria req))
            (str "Slot " (:target/id req) " should exclude lands via :match/not-types"))))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest rushing-river-kicked-exactly-two-valid-targets-test
  (testing "Kicked mode is feasible with exactly 2 valid targets"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db rr-id] (th/add-card-to-zone db :rushing-river :hand :player-1)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Exactly 2 nonland permanents
          [db creature-a-id] (th/add-test-creature db :player-2 2 2)
          [db creature-b-id] (th/add-test-creature db :player-2 3 3)
          modes (rules/get-casting-modes db :player-1 rr-id)
          kicked-mode (first (filter #(= :kicked (:mode/id %)) modes))]
      (is (rules/can-cast-mode? db :player-1 rr-id kicked-mode)
          "Kicked mode should be feasible with exactly 2 valid targets")
      ;; Also verify the full cast works
      (let [db-cast (cast-kicked db rr-id land-id creature-a-id creature-b-id)
            {:keys [db]} (th/resolve-top db-cast)]
        (is (= :hand (:object/zone (q/get-object db creature-a-id)))
            "First target bounced to hand")
        (is (= :hand (:object/zone (q/get-object db creature-b-id)))
            "Second target bounced to hand")))))


(deftest rushing-river-sacrificed-land-excluded-from-targets-test
  (testing "The sacrificed land cannot be chosen as a bounce target (it's a land, not a nonland permanent)"
    ;; Lands are excluded by :match/not-types #{:land} in targeting criteria
    ;; This test verifies that explicitly: a land on the battlefield is not in valid targets
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          slot-a-req (first (:alternate/targeting (first (:card/alternate-costs rushing-river/card))))
          targets (targeting/find-valid-targets db :player-1 slot-a-req)]
      (is (not (some #{land-id} targets))
          "The land should not appear in valid targets for bounce slots"))))
