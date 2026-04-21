(ns fizzle.cards.artifacts.mind-stone-test
  "Tests for Mind Stone artifact card.

   Mind Stone: {2} - Artifact
   {T}: Add {C}.
   {1}, {T}, Sacrifice this artifact: Draw a card.

   Tests cover:
   - Card definition (exact fields)
   - Cast-resolve happy path (artifact into battlefield with {2} mana cost)
   - Cannot-cast guards (insufficient mana, wrong zone)
   - Storm count on cast (artifact spells increment storm)
   - Ability 0: tap for 1 colorless (mana ability, does not use stack)
   - Ability 1: pay 1 + tap + sac, draws a card and sends to graveyard
   - Ability 1 cost guards (no mana, already tapped)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.mind-stone :as mind-stone]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition ===

(deftest test-mind-stone-card-definition
  ;; Oracle: "{T}: Add {C}.\n{1}, {T}, Sacrifice this artifact: Draw a card."
  (testing "Mind Stone core attributes"
    (is (= :mind-stone (:card/id mind-stone/card))
        "Card id should be :mind-stone")
    (is (= "Mind Stone" (:card/name mind-stone/card))
        "Card name should be 'Mind Stone'")
    (is (= 2 (:card/cmc mind-stone/card))
        "CMC should be 2")
    (is (= {:colorless 2} (:card/mana-cost mind-stone/card))
        "Mana cost should be {:colorless 2} (Scryfall {2})")
    (is (= #{} (:card/colors mind-stone/card))
        "Colors should be empty set (colorless)")
    (is (= #{:artifact} (:card/types mind-stone/card))
        "Type should be exactly :artifact")
    (is (= "{T}: Add {C}.\n{1}, {T}, Sacrifice this artifact: Draw a card."
           (:card/text mind-stone/card))
        "Card text should match oracle exactly")
    (is (= 2 (count (:card/abilities mind-stone/card)))
        "Should have exactly 2 abilities"))

  (testing "Ability 0: {T}: Add {C}."
    (let [a0 (first (:card/abilities mind-stone/card))]
      (is (= :mana (:ability/type a0))
          "Ability 0 should be a :mana ability (no stack)")
      (is (= {:tap true} (:ability/cost a0))
          "Ability 0 cost is exactly {:tap true}")
      (is (= {:colorless 1} (:ability/produces a0))
          "Ability 0 produces exactly 1 colorless")))

  (testing "Ability 1: {1}, {T}, Sacrifice Mind Stone: Draw a card."
    (let [a1 (second (:card/abilities mind-stone/card))]
      (is (= :activated (:ability/type a1))
          "Ability 1 should be :activated (uses stack — effect is not mana production)")
      (is (= {:mana {:colorless 1}
              :tap true
              :sacrifice-self true}
             (:ability/cost a1))
          "Ability 1 cost should be mana {1} + tap + sacrifice-self")
      (is (= [{:effect/type :draw :effect/amount 1}]
             (:ability/effects a1))
          "Ability 1 should have a single :draw 1 effect"))))


;; === B. Cast-Resolve Happy Path ===

(deftest test-mind-stone-cast-to-battlefield
  ;; Oracle: "{2} - Artifact"
  (testing "Mind Stone enters battlefield when cast with {2}"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 obj-id))
                "Should be castable with {2}")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db-resolved obj-id))
          "Mind Stone should be on battlefield after resolve")
      (is (= 0 (:colorless (q/get-mana-pool db-resolved :player-1)))
          "Mana pool should be drained to 0 (paid {2})"))))


;; === C. Cannot-Cast Guards ===

(deftest test-mind-stone-cannot-cast-with-insufficient-mana
  (testing "Cannot cast with only {1}"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast with only 1 colorless mana"))))


(deftest test-mind-stone-cannot-cast-from-graveyard
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast from graveyard"))))


(deftest test-mind-stone-cannot-cast-from-battlefield
  (testing "Cannot cast from battlefield (already a permanent)"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast a permanent that is already on the battlefield"))))


;; === D. Storm Count ===

(deftest test-mind-stone-cast-increments-storm
  (testing "Casting Mind Stone increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count is 0")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting Mind Stone"))))


(deftest test-mind-stone-mana-ability-does-not-increment-storm
  ;; MTG CR 700.2: activated mana abilities don't use the stack.
  ;; Storm only counts spells, so activating the mana ability must not increment storm.
  (testing "Activating the mana ability does NOT increment storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count is 0")
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless)]
      (is (= 0 (q/get-storm-count db-after :player-1))
          "Mana ability activation must not change storm count"))))


;; === E. Ability 0: Tap for {C} ===

(deftest test-mind-stone-tap-produces-one-colorless
  ;; Oracle: "{T}: Add {C}."
  (testing "Tapping Mind Stone adds 1 colorless mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          _ (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
                "Precondition: colorless mana is 0")
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless)]
      (is (= 1 (:colorless (q/get-mana-pool db-after :player-1)))
          "Colorless mana should be 1 after tap")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Mind Stone should still be on battlefield")
      (is (true? (:object/tapped (q/get-object db-after obj-id)))
          "Mind Stone should be tapped"))))


(deftest test-mind-stone-mana-ability-cannot-activate-when-tapped
  (testing "Already-tapped Mind Stone cannot activate mana ability"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-tapped (d/db-with db [[:db/add obj-eid :object/tapped true]])
          db-after (engine-mana/activate-mana-ability db-tapped :player-1 obj-id :colorless)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana should be added when already tapped"))))


(deftest test-mind-stone-mana-ability-cannot-activate-from-graveyard
  (testing "Mind Stone in graveyard cannot produce mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mind-stone :graveyard :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana produced from graveyard"))))


;; === F. Ability 1: {1}, {T}, Sacrifice → Draw a card ===

(deftest test-mind-stone-sacrifice-draws-one-card
  ;; Oracle: "{1}, {T}, Sacrifice this artifact: Draw a card."
  (testing "Activating ability 1 pays costs, allocates {1}, draws 1 card, and sends Mind Stone to graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :island] :player-1)
          _ (is (= 0 (th/get-hand-count db :player-1))
                "Precondition: hand is empty")
          ability (second (:card/abilities mind-stone/card))
          _ (is (true? (abilities/can-activate? db obj-id ability :player-1))
                "Ability should be activatable with {1} in pool, untapped, on battlefield")
          ;; Activate ability 1 — pays non-mana costs (tap + sacrifice-self)
          ;; and opens a mana-allocation selection for the {1} generic cost.
          result (ability-events/activate-ability db :player-1 obj-id 1)
          db-after-non-mana (:db result)
          sel (:pending-selection result)
          _ (is (= :graveyard (th/get-object-zone db-after-non-mana obj-id))
                "Mind Stone should be in graveyard after non-mana costs paid (tap+sacrifice-self)")
          _ (is (= :mana-allocation (:selection/type sel))
                "Pending selection should be :mana-allocation for the {1} generic cost")
          ;; Allocate the one colorless toward the generic cost — allocator
          ;; auto-confirms when generic-remaining hits 0, which creates the
          ;; activated-ability stack item and drains the mana pool.
          app-db {:game/db db-after-non-mana :game/pending-selection sel}
          app-db-confirmed (sel-costs/allocate-mana-color-impl app-db :colorless)
          db-after-confirm (:game/db app-db-confirmed)
          _ (is (= 0 (:colorless (q/get-mana-pool db-after-confirm :player-1)))
                "Mana pool should be drained to 0 after allocation auto-confirms")
          ;; Resolve the activated ability on the stack
          db-resolved (:db (resolution/resolve-one-item db-after-confirm))]
      (is (= 1 (th/get-hand-count db-resolved :player-1))
          "Player should have drawn 1 card after resolution"))))


(deftest test-mind-stone-ability-1-cannot-activate-without-mana
  (testing "Ability 1 cannot be activated without {1} in mana pool"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          ability (second (:card/abilities mind-stone/card))]
      (is (false? (abilities/can-activate? db obj-id ability :player-1))
          "Ability must not be activatable with empty mana pool"))))


(deftest test-mind-stone-ability-1-cannot-activate-when-tapped
  (testing "Ability 1 cannot be activated when Mind Stone is already tapped"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :mind-stone :battlefield :player-1)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-tapped (d/db-with db [[:db/add obj-eid :object/tapped true]])
          ability (second (:card/abilities mind-stone/card))]
      (is (false? (abilities/can-activate? db-tapped obj-id ability :player-1))
          "Ability must not be activatable when tapped (tap cost unpayable)"))))
