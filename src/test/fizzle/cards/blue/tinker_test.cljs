(ns fizzle.cards.blue.tinker-test
  "Tests for Tinker card and sacrifice-permanent cost infrastructure.

   Tinker: {2}{U} - Sorcery
   As an additional cost to cast this spell, sacrifice an artifact.
   Search your library for an artifact card, put that card onto the
   battlefield, then shuffle.

   Key behaviors:
   - Additional cost: sacrifice an artifact (required, not optional)
   - Pre-cast pipeline: sacrifice-permanent-cost selection fires before mana-allocation
   - Tutor resolves to :battlefield (not :hand)
   - sacrifice-permanent cost is generic (criteria-driven, works for any type)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.tinker :as tinker]
    [fizzle.db.queries :as q]
    [fizzle.engine.costs :as costs]
    [fizzle.engine.rules :as rules]
    [fizzle.events.casting :as casting]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest tinker-card-definition-test
  (testing "card has correct oracle properties"
    (let [card tinker/card]
      (is (= :tinker (:card/id card))
          "Card ID should be :tinker")
      (is (= "Tinker" (:card/name card))
          "Card name should match oracle")
      (is (= 3 (:card/cmc card))
          "CMC should be 3")
      (is (= {:colorless 2 :blue 1} (:card/mana-cost card))
          "Mana cost should be {2}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:sorcery} (:card/types card))
          "Card should be a sorcery")
      (is (= "As an additional cost to cast this spell, sacrifice an artifact.\nSearch your library for an artifact card, put that card onto the battlefield, then shuffle."
             (:card/text card))
          "Card text should match oracle")))

  (testing "card has sacrifice-permanent additional cost"
    (let [costs (:card/additional-costs tinker/card)]
      (is (= 1 (count costs))
          "Should have exactly one additional cost")
      (let [cost (first costs)]
        (is (= :sacrifice-permanent (:cost/type cost))
            "Cost type should be :sacrifice-permanent")
        (is (= #{:artifact} (get-in cost [:cost/criteria :match/types]))
            "Should require sacrificing an artifact"))))

  (testing "card has tutor-to-battlefield effect"
    (let [effects (:card/effects tinker/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :tutor (:effect/type effect))
            "Effect type should be :tutor")
        (is (= #{:artifact} (get-in effect [:effect/criteria :match/types]))
            "Should search for artifacts")
        (is (= :battlefield (:effect/target-zone effect))
            "Should put card onto battlefield")
        (is (true? (:effect/shuffle? effect))
            "Should shuffle library after")))))


;; === B. Cast-Resolve Happy Path ===

(deftest tinker-happy-path-tutor-artifact-to-battlefield-test
  (testing "Cast Tinker: sacrifice Lotus Petal, tutor Lions Eye Diamond to battlefield"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          ;; Add Lotus Petal to battlefield (to sacrifice)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Add Lions Eye Diamond to library (to tutor)
          [db [led-id]] (th/add-cards-to-library db [:lions-eye-diamond] :player-1)
          ;; Add Tinker to hand
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          ;; Cast Tinker — pre-cast pipeline fires sacrifice-permanent-cost selection
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card tinker-id})
          pending-sel (:game/pending-selection app-db)
          ;; Selection should be sacrifice-permanent-cost
          _ (is (= :sacrifice-cost (:selection/domain pending-sel))
                "Should show sacrifice-permanent-cost selection")
          _ (is (= #{petal-id} (set (:selection/valid-targets pending-sel)))
                "Should offer Lotus Petal as sacrifice candidate")
          ;; Confirm sacrifice of Lotus Petal — lifecycle is :finalized (no targeting on Tinker)
          ;; Executor calls cast-spell-mode, which puts Tinker on stack
          {:keys [db]} (th/confirm-selection (:game/db app-db) pending-sel #{petal-id})
          ;; Tinker should now be on stack
          _ (is (= :stack (:object/zone (q/get-object db tinker-id)))
                "Tinker should be on stack after sacrifice cost confirmed")
          ;; Lotus Petal should be in graveyard (sacrificed)
          _ (is (= :graveyard (:object/zone (q/get-object db petal-id)))
                "Sacrificed artifact should be in graveyard")
          ;; Now resolve Tinker — triggers tutor selection
          {:keys [db selection]} (th/resolve-top db)
          ;; Should produce tutor selection for artifacts
          _ (is (= :tutor (:selection/domain selection))
                "Resolving Tinker should show tutor selection")
          _ (is (= #{led-id} (:selection/candidates selection))
                "Should offer LED as tutor candidate")
          ;; Confirm tutor — LED goes to battlefield
          {:keys [db]} (th/confirm-selection db selection #{led-id})]
      ;; LED should be on battlefield
      (is (= :battlefield (:object/zone (q/get-object db led-id)))
          "Tutored artifact should be put onto battlefield"))))


;; === C. Cannot-Cast Guards ===

(deftest tinker-cannot-cast-without-artifacts-test
  (testing "Cannot cast Tinker if controller has no artifacts on battlefield"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 tinker-id))
          "Should not be castable without an artifact to sacrifice"))))


(deftest tinker-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Tinker without {2}{U}"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 tinker-id))
          "Should not be castable with only {U} mana"))))


(deftest tinker-cannot-cast-from-graveyard-test
  (testing "Cannot cast Tinker from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 tinker-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest tinker-increments-storm-count-test
  (testing "Casting Tinker increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card tinker-id})
          pending-sel (:game/pending-selection app-db)
          ;; Confirm sacrifice — executor casts Tinker (finalized lifecycle)
          {:keys [db]} (th/confirm-selection (:game/db app-db) pending-sel #{petal-id})]
      (is (= (inc storm-before) (q/get-storm-count db :player-1))
          "Storm count should increment after casting Tinker"))))


;; === E. Sacrifice Selection Tests ===

(deftest tinker-sacrifice-selection-shows-only-artifacts-test
  (testing "Sacrifice selection only includes artifacts (not creatures or lands)"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          ;; Artifact on battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Non-artifact on battlefield (creature)
          [db _mongoose-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card tinker-id})
          pending-sel (:game/pending-selection app-db)]
      (is (= :sacrifice-cost (:selection/domain pending-sel))
          "Should show sacrifice selection")
      (is (= #{petal-id} (set (:selection/valid-targets pending-sel)))
          "Only artifact should be a valid sacrifice target"))))


(deftest tinker-sacrifice-selection-shows-all-artifacts-test
  (testing "Sacrifice selection includes all controller artifacts on battlefield"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db led-id] (th/add-card-to-zone db :lions-eye-diamond :battlefield :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card tinker-id})
          pending-sel (:game/pending-selection app-db)]
      (is (= #{petal-id led-id} (set (:selection/valid-targets pending-sel)))
          "All artifacts should be valid sacrifice candidates"))))


;; === F. Tutor Target Tests ===

(deftest tinker-fail-to-find-shuffles-library-test
  (testing "Fail-to-find shuffles library but doesn't put anything onto battlefield"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Non-artifact in library (won't be tutored)
          [db [ritual-id]] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card tinker-id})
          pending-sel (:game/pending-selection app-db)
          ;; Confirm sacrifice — executor casts Tinker (finalized lifecycle)
          {:keys [db]} (th/confirm-selection (:game/db app-db) pending-sel #{petal-id})
          ;; Resolve Tinker — tutor selection fires with no artifact candidates
          {:keys [db selection]} (th/resolve-top db)
          _ (is (= :tutor (:selection/domain selection)) "Should show tutor selection")
          _ (is (empty? (:selection/candidates selection)) "Should have no artifact candidates")
          ;; Fail-to-find: confirm empty selection
          {:keys [db]} (th/confirm-selection db selection #{})]
      ;; Lotus Petal was sacrificed (cost)
      (is (= :graveyard (:object/zone (q/get-object db petal-id)))
          "Sacrificed artifact should be in graveyard even on fail-to-find")
      ;; Dark Ritual stays in library
      (is (= :library (:object/zone (q/get-object db ritual-id)))
          "Non-artifact stays in library on fail-to-find")
      ;; No permanents added to battlefield
      (is (= 0 (th/get-zone-count db :battlefield :player-1))
          "No permanents on battlefield after fail-to-find"))))


;; === G. Edge Cases ===

(deftest tinker-sacrifices-the-only-artifact-test
  (testing "Can sacrifice only artifact — that artifact is gone before tutor"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Library has artifacts to tutor
          [db [led-id]] (th/add-cards-to-library db [:lions-eye-diamond] :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card tinker-id})
          pending-sel (:game/pending-selection app-db)
          ;; Confirm sacrifice — executor casts Tinker (finalized lifecycle)
          {:keys [db]} (th/confirm-selection (:game/db app-db) pending-sel #{petal-id})
          ;; Resolve Tinker
          {:keys [db selection]} (th/resolve-top db)
          ;; LED should still be in library as candidate (Lotus Petal was sacrificed, not LED)
          _ (is (= #{led-id} (:selection/candidates selection))
                "LED should still be a tutor candidate (was in library)")
          {:keys [db]} (th/confirm-selection db selection #{led-id})]
      ;; Petal is in graveyard (sacrificed as cost)
      (is (= :graveyard (:object/zone (q/get-object db petal-id)))
          "Sacrificed petal should be in graveyard")
      ;; LED is on battlefield (tutored)
      (is (= :battlefield (:object/zone (q/get-object db led-id)))
          "Tutored LED should be on battlefield"))))


(deftest sacrifice-permanent-generic-criteria-test
  (testing "sacrifice-permanent cost works with creature criteria (not just artifacts)"
    (let [db (th/create-test-db)
          ;; Add a creature on battlefield
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          ;; Test can-pay? with creature criteria
          creature-cost {:sacrifice-permanent {:match/types #{:creature}}}
          ;; Add an artifact too (doesn't match creature criteria)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (true? (costs/can-pay? db creature-id creature-cost))
          "can-pay? should return true with creature criteria when creature is on battlefield")))

  (testing "sacrifice-permanent cost returns false when no matching permanents"
    (let [db (th/create-test-db)
          ;; Only artifact on battlefield
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          creature-cost {:sacrifice-permanent {:match/types #{:creature}}}]
      (is (false? (costs/can-pay? db petal-id creature-cost))
          "can-pay? should return false when no creature on battlefield to sacrifice"))))


(deftest tinker-sacrifice-permanent-cost-builder-test
  (testing "build-sacrifice-permanent-selection returns correct selection"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 2}})
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db tinker-id] (th/add-card-to-zone db :tinker :hand :player-1)
          modes (rules/get-casting-modes db :player-1 tinker-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))
          sac-cost (sel-costs/get-sacrifice-permanent-cost primary)
          sel (sel-costs/build-sacrifice-permanent-selection db :player-1 tinker-id primary sac-cost)]
      (is (= :sacrifice-cost (:selection/domain sel))
          "Selection type should be :sacrifice-permanent-cost")
      (is (= :finalized (:selection/lifecycle sel))
          "Lifecycle should be :finalized (Tinker has no targeting)")
      (is (= 1 (:selection/select-count sel))
          "Should select exactly 1 permanent")
      (is (= :exact (:selection/validation sel))
          "Validation should be :exact")
      (is (false? (:selection/auto-confirm? sel))
          "Should not auto-confirm — player always picks, even with one candidate (design decision)")
      (is (= #{petal-id} (set (:selection/valid-targets sel)))
          "Valid targets should be all battlefield artifacts"))))
