(ns fizzle.cards.artifacts.chromatic-sphere-test
  "Tests for Chromatic Sphere artifact card.

   Chromatic Sphere: {1} - Artifact
   {1}, {T}, Sacrifice this artifact: Add one mana of any color. Draw a card.

   Tests cover:
   - A. Card definition (exact fields)
   - B. Cast-resolve happy path (artifact into battlefield)
   - C. Cannot-cast guards (insufficient mana, wrong zone)
   - D. Storm count on cast (artifact spells increment storm, mana abilities do not)
   - E. Event-path ability integration (the user-reported bug regression)
   - F. Mana ability does NOT increment storm"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.artifacts.chromatic-sphere :as chromatic-sphere]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register history interceptor + SBA dispatch for dispatch-sync tests
(interceptor/register!)
(db-effect/register!)


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest test-chromatic-sphere-card-definition
  ;; Oracle: "{1}, {T}, Sacrifice this artifact: Add one mana of any color. Draw a card."
  (testing "Chromatic Sphere core attributes"
    (is (= :chromatic-sphere (:card/id chromatic-sphere/card))
        "Card id should be :chromatic-sphere")
    (is (= "Chromatic Sphere" (:card/name chromatic-sphere/card))
        "Card name should be 'Chromatic Sphere'")
    (is (= 1 (:card/cmc chromatic-sphere/card))
        "CMC should be 1")
    (is (= {:colorless 1} (:card/mana-cost chromatic-sphere/card))
        "Mana cost should be {:colorless 1} (Scryfall {1})")
    (is (= #{} (:card/colors chromatic-sphere/card))
        "Colors should be empty set (colorless)")
    (is (= #{:artifact} (:card/types chromatic-sphere/card))
        "Type should be exactly #{:artifact}")
    (is (= "{1}, {T}, Sacrifice this artifact: Add one mana of any color. Draw a card."
           (:card/text chromatic-sphere/card))
        "Card text should match oracle exactly")
    (is (= 1 (count (:card/abilities chromatic-sphere/card)))
        "Should have exactly 1 ability"))

  (testing "Ability 0: {1}, {T}, Sac: Add one mana of any color. Draw a card."
    (let [a0 (first (:card/abilities chromatic-sphere/card))]
      (is (= :mana (:ability/type a0))
          "Ability type should be :mana (mana ability — does not use stack per 2008-08-01 ruling)")
      (is (= {:mana {:colorless 1}
              :tap true
              :sacrifice-self true}
             (:ability/cost a0))
          "Ability cost should be {:mana {:colorless 1} :tap true :sacrifice-self true}")
      (is (= {:any 1} (:ability/produces a0))
          "Ability produces {:any 1} — caller chooses color at activation time")
      (is (= [{:effect/type :draw :effect/amount 1}]
             (:ability/effects a0))
          "Ability effects should have exactly one :draw 1 effect"))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest test-chromatic-sphere-cast-to-battlefield
  ;; Oracle: "{1} - Artifact"
  (testing "Chromatic Sphere enters battlefield when cast with {1}"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 obj-id))
                "Precondition: should be castable with {1}")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db-resolved obj-id))
          "Chromatic Sphere should be on battlefield after resolve")
      (is (= 0 (:colorless (q/get-mana-pool db-resolved :player-1)))
          "Mana pool should be drained to 0 (paid {1})"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest test-chromatic-sphere-cannot-cast-without-mana
  (testing "Cannot cast Chromatic Sphere without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast with empty mana pool"))))


(deftest test-chromatic-sphere-cannot-cast-from-graveyard
  (testing "Cannot cast Chromatic Sphere from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast from graveyard"))))


;; =====================================================
;; D. Storm Count on Cast
;; =====================================================

(deftest test-chromatic-sphere-cast-increments-storm
  (testing "Casting Chromatic Sphere increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :chromatic-sphere :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count is 0")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting Chromatic Sphere"))))


;; =====================================================
;; E. Event-Path Ability Integration (User-Reported Bug Regression)
;; =====================================================

;; User-reported bug regression: pool {B} + activate Sphere choosing :blue would silently
;; produce {B U colorless -1} instead of spending B for U. This test exercises the fix end-to-end:
;; dispatch → assert pending-selection → allocate → assert final state.

(deftest test-chromatic-sphere-event-path-black-to-blue-filter
  ;; The exact user-reported scenario: pool {:black 1}, activate Sphere choosing :blue.
  ;; Expected: pool {:black 0 :blue 1 :colorless 0}, Sphere in graveyard, 1 card drawn.
  (testing "Chromatic Sphere event path: spend Black to produce Blue (user bug scenario)"
    (let [game-scenario (th/create-game-scenario {:mana {:black 1}})
          game-db (:game/db game-scenario)
          [game-db obj-id] (th/add-card-to-zone game-db :chromatic-sphere :battlefield :player-1)
          [game-db _] (th/add-cards-to-library game-db [:island :island :island] :player-1)
          _ (is (= :battlefield (th/get-object-zone game-db obj-id))
                "Precondition: Sphere is on battlefield")
          _ (is (= 1 (:black (q/get-mana-pool game-db :player-1)))
                "Precondition: pool has exactly 1 black mana")
          _ (is (= 0 (th/get-hand-count game-db :player-1))
                "Precondition: hand is empty")
          app-db (assoc game-scenario :game/db game-db)
          ;; Dispatch activate-mana-ability through full re-frame chain
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::ability-events/activate-mana-ability obj-id :blue nil 0])
          app-db-after-dispatch @rf-db/app-db
          pending-sel (:game/pending-selection app-db-after-dispatch)
          _ (is (some? pending-sel)
                "After dispatch: pending-selection must be non-nil (selection opened for {1} generic cost)")
          _ (is (= :mana-allocation (:selection/domain pending-sel))
                "Pending selection type must be :mana-allocation")
          ;; Allocate :black toward the {1} generic cost.
          ;; auto-confirm fires when generic-remaining hits 0 (no explicit confirm needed).
          _ (reset! rf-db/app-db app-db-after-dispatch)
          _ (rf/dispatch-sync [::sel-costs/allocate-mana-color :black])
          app-db-final @rf-db/app-db
          final-db (:game/db app-db-final)
          pool (q/get-mana-pool final-db :player-1)]
      (is (= 0 (:black pool 0))
          "Black mana must be deducted (spent as {1} generic cost)")
      (is (= 1 (:blue pool 0))
          "Blue mana must be added (produced by Sphere choosing :blue)")
      (is (= :graveyard (th/get-object-zone final-db obj-id))
          "Chromatic Sphere must be in graveyard (sacrifice-self cost)")
      (is (= 1 (th/get-hand-count final-db :player-1))
          "Draw effect must have fired: player should hold 1 card"))))


(deftest test-chromatic-sphere-event-path-colorless-pool
  ;; Variant: pool {:colorless 1} — trivial allocation (colorless covers colorless).
  (testing "Chromatic Sphere event path: spend Colorless to produce Red"
    (let [game-scenario (th/create-game-scenario {:mana {:colorless 1}})
          game-db (:game/db game-scenario)
          [game-db obj-id] (th/add-card-to-zone game-db :chromatic-sphere :battlefield :player-1)
          [game-db _] (th/add-cards-to-library game-db [:island :island :island] :player-1)
          app-db (assoc game-scenario :game/db game-db)
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::ability-events/activate-mana-ability obj-id :red nil 0])
          app-db-after @rf-db/app-db
          pending-sel (:game/pending-selection app-db-after)
          _ (is (some? pending-sel)
                "Pending selection must exist even with colorless pool")
          _ (reset! rf-db/app-db app-db-after)
          _ (rf/dispatch-sync [::sel-costs/allocate-mana-color :colorless])
          app-db-final @rf-db/app-db
          final-db (:game/db app-db-final)
          pool (q/get-mana-pool final-db :player-1)]
      (is (= 0 (:colorless pool 0))
          "Colorless spent as {1} generic")
      (is (= 1 (:red pool 0))
          "Red produced by Sphere choosing :red")
      (is (= :graveyard (th/get-object-zone final-db obj-id))
          "Sphere in graveyard")
      (is (= 1 (th/get-hand-count final-db :player-1))
          "Drew 1 card"))))


(deftest test-chromatic-sphere-cannot-activate-with-empty-pool
  ;; Guard: with empty pool, can-activate? blocks before selection opens.
  ;; The engine's can-activate? returns false (no mana to pay {1}).
  (testing "Chromatic Sphere cannot activate with empty pool (can-activate? blocks)"
    (let [game-scenario (th/create-game-scenario {})
          game-db (:game/db game-scenario)
          [game-db obj-id] (th/add-card-to-zone game-db :chromatic-sphere :battlefield :player-1)
          app-db (assoc game-scenario :game/db game-db)
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::ability-events/activate-mana-ability obj-id :blue nil 0])
          app-db-after @rf-db/app-db]
      (is (nil? (:game/pending-selection app-db-after))
          "No selection should open when pool is empty (can-activate? check)")
      (is (= :battlefield (th/get-object-zone (:game/db app-db-after) obj-id))
          "Sphere must remain on battlefield (no costs paid)"))))


;; =====================================================
;; F. Mana Ability Does NOT Increment Storm
;; =====================================================

(deftest test-chromatic-sphere-mana-ability-does-not-increment-storm
  ;; MTG CR 605.1: mana abilities don't use the stack.
  ;; Storm counts spells (and activated abilities that use the stack), not mana abilities.
  (testing "Activating Chromatic Sphere via event path does NOT increment storm count"
    (let [game-scenario (th/create-game-scenario {:mana {:colorless 1}})
          game-db (:game/db game-scenario)
          [game-db obj-id] (th/add-card-to-zone game-db :chromatic-sphere :battlefield :player-1)
          [game-db _] (th/add-cards-to-library game-db [:island :island :island] :player-1)
          _ (is (= 0 (q/get-storm-count game-db :player-1))
                "Precondition: storm is 0")
          app-db (assoc game-scenario :game/db game-db)
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::ability-events/activate-mana-ability obj-id :green nil 0])
          app-db-after @rf-db/app-db
          _ (reset! rf-db/app-db app-db-after)
          _ (rf/dispatch-sync [::sel-costs/allocate-mana-color :colorless])
          app-db-final @rf-db/app-db
          final-db (:game/db app-db-final)]
      (is (= 0 (q/get-storm-count final-db :player-1))
          "Storm count must remain 0 after mana ability activation (mana abilities don't count)"))))
