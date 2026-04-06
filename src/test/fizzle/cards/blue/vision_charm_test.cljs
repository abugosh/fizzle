(ns fizzle.cards.blue.vision-charm-test
  "Tests for Vision Charm card.

   Vision Charm: {U} — Instant
   Choose one —
   * Target player mills four cards.
   * Choose a land type and a basic land type. Each land of the first
     chosen type becomes the second chosen type until end of turn.
   * Target artifact phases out.

   Key behaviors:
   - Modal instant with 3 modes
   - Mode 1: Mill 4 (player-targeted, any player)
   - Mode 2: Change land types until EOT (interactive, 2-step selection)
   - Mode 3: Phase out target artifact (artifact-targeted)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.vision-charm :as vision-charm]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.mana-activation :as mana-activation]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.land-types]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest vision-charm-card-definition-test
  (testing "card has correct oracle properties"
    (let [card vision-charm/card]
      (is (= :vision-charm (:card/id card))
          "Card ID should be :vision-charm")
      (is (= "Vision Charm" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:blue 1} (:card/mana-cost card))
          "Mana cost should be {:blue 1}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "card has exactly 3 modes"
    (let [modes (:card/modes vision-charm/card)]
      (is (vector? modes)
          "Modes must be a vector")
      (is (= 3 (count modes))
          "Should have exactly 3 modes")))

  (testing "mode 1: mill 4 (player targeted)"
    (let [mode (get (:card/modes vision-charm/card) 0)]
      (is (= "Target player mills four cards" (:mode/label mode))
          "Mode 1 label should match oracle")
      (let [targeting (:mode/targeting mode)]
        (is (= 1 (count targeting))
            "Mode 1 should have one target requirement")
        (let [req (first targeting)]
          (is (= :mill-player (:target/id req))
              "Target ID should be :mill-player")
          (is (= :player (:target/type req))
              "Target type should be :player")
          (is (= #{:any-player} (:target/options req))
              "Target options should be #{:any-player}")
          (is (true? (:target/required req))
              "Target should be required")))
      (let [effects (:mode/effects mode)]
        (is (= 1 (count effects))
            "Mode 1 should have one effect")
        (let [effect (first effects)]
          (is (= :mill (:effect/type effect))
              "Effect type should be :mill")
          (is (= 4 (:effect/amount effect))
              "Mill amount should be 4")
          (is (= :mill-player (:effect/target-ref effect))
              "Effect should reference :mill-player")))))

  (testing "mode 2: change land types (no targeting)"
    (let [mode (get (:card/modes vision-charm/card) 1)]
      (is (= "Change land types until end of turn" (:mode/label mode))
          "Mode 2 label should match oracle")
      (is (nil? (:mode/targeting mode))
          "Mode 2 should have no targeting")
      (let [effects (:mode/effects mode)]
        (is (= 1 (count effects))
            "Mode 2 should have one effect")
        (is (= :change-land-types (:effect/type (first effects)))
            "Mode 2 effect should be :change-land-types"))))

  (testing "mode 3: phase out artifact (object targeted)"
    (let [mode (get (:card/modes vision-charm/card) 2)]
      (is (= "Target artifact phases out" (:mode/label mode))
          "Mode 3 label should match oracle")
      (let [targeting (:mode/targeting mode)]
        (is (= 1 (count targeting))
            "Mode 3 should have one target requirement")
        (let [req (first targeting)]
          (is (= :target-artifact (:target/id req))
              "Target ID should be :target-artifact")
          (is (= :object (:target/type req))
              "Target type should be :object")
          (is (= :battlefield (:target/zone req))
              "Target zone should be :battlefield")
          (is (= :any (:target/controller req))
              "Controller should be :any")
          (is (= {:match/types #{:artifact}} (:target/criteria req))
              "Criteria should match artifacts only")
          (is (true? (:target/required req))
              "Target should be required")))
      (let [effects (:mode/effects mode)]
        (is (= 1 (count effects))
            "Mode 3 should have one effect")
        (let [effect (first effects)]
          (is (= :phase-out (:effect/type effect))
              "Effect type should be :phase-out")
          (is (= :target-artifact (:effect/target-ref effect))
              "Effect should reference :target-artifact"))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest vision-charm-mode1-mills-four-test
  (testing "Mode 1: mills exactly 4 cards from target player's library"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Give player-2 a library to mill
          [db _] (th/add-cards-to-library db (vec (repeat 6 :dark-ritual)) :player-2)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-1 (get (:card/modes vision-charm/card) 0)
          ;; Count opponent's library before
          lib-before (count (q/get-objects-in-zone db :player-2 :library))
          gy-before (count (q/get-objects-in-zone db :player-2 :graveyard))
          db-cast (th/cast-mode-with-target db :player-1 vc-id mode-1 :player-2)
          {:keys [db]} (th/resolve-top db-cast)
          lib-after (count (q/get-objects-in-zone db :player-2 :library))
          gy-after (count (q/get-objects-in-zone db :player-2 :graveyard))]
      (is (= 4 (- lib-before lib-after))
          "Exactly 4 cards should be milled from target player's library")
      (is (= 4 (- gy-after gy-before))
          "Exactly 4 cards should move to graveyard"))))


(deftest vision-charm-mode1-mills-opponent-test
  (testing "Mode 1: player 1 mills player 2's library (player targeting works)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Give player-2 a library to mill
          [db _] (th/add-cards-to-library db (vec (repeat 6 :dark-ritual)) :player-2)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-1 (get (:card/modes vision-charm/card) 0)
          lib-p2-before (count (q/get-objects-in-zone db :player-2 :library))
          lib-p1-before (count (q/get-objects-in-zone db :player-1 :library))
          db-cast (th/cast-mode-with-target db :player-1 vc-id mode-1 :player-2)
          {:keys [db]} (th/resolve-top db-cast)
          lib-p2-after (count (q/get-objects-in-zone db :player-2 :library))
          lib-p1-after (count (q/get-objects-in-zone db :player-1 :library))]
      (is (= 4 (- lib-p2-before lib-p2-after))
          "Player 2's library should lose exactly 4 cards")
      (is (= lib-p1-before lib-p1-after)
          "Player 1's library should be unaffected"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest vision-charm-cannot-cast-without-blue-mana-test
  (testing "Cannot cast Vision Charm without blue mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 vc-id))
          "Should not be castable without blue mana"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest vision-charm-increments-storm-count-test
  (testing "Casting Vision Charm increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          storm-before (q/get-storm-count db :player-1)
          mode-1 (get (:card/modes vision-charm/card) 0)
          db-cast (th/cast-mode-with-target db :player-1 vc-id mode-1 :player-2)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1 on cast"))))


;; =====================================================
;; E. Mode 3 — Phase Out
;; =====================================================

(deftest vision-charm-mode3-phases-out-artifact-test
  (testing "Mode 3: target artifact moves to :phased-out zone"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db artifact-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-3 (get (:card/modes vision-charm/card) 2)
          db-cast (th/cast-mode-with-target db :player-1 vc-id mode-3 artifact-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :phased-out (:object/zone (q/get-object db artifact-id)))
          "Artifact should be in :phased-out zone after Mode 3 resolution"))))


(deftest vision-charm-mode3-preserves-tapped-state-test
  (testing "Mode 3: tapped artifact preserves tapped state at phase-out and phase-in (pre-untap)"
    (let [db (th/create-test-db)
          [db artifact-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Tap the artifact manually
          obj-eid (q/get-object-eid db artifact-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])
          _ (is (true? (:object/tapped (q/get-object db artifact-id)))
                "Precondition: artifact is tapped")
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-3 (get (:card/modes vision-charm/card) 2)
          db-cast (th/cast-mode-with-target db :player-1 vc-id mode-3 artifact-id)
          resolve-result (th/resolve-top db-cast)
          db-phased-out (:db resolve-result)
          _ (is (= :phased-out (:object/zone (q/get-object db-phased-out artifact-id)))
                "Precondition: artifact is now phased out")
          _ (is (true? (:object/tapped (q/get-object db-phased-out artifact-id)))
                "Tapped state must be preserved in :phased-out zone (direct zone change)")
          ;; Phase back in directly (bypassing untap step which would also untap)
          db-phased-in (zones/phase-in db-phased-out artifact-id)]
      (is (= :battlefield (:object/zone (q/get-object db-phased-in artifact-id)))
          "Artifact should be back on battlefield after phase-in")
      (is (true? (:object/tapped (q/get-object db-phased-in artifact-id)))
          "Tapped state should still be true immediately after phase-in (before untap step runs)"))))


;; =====================================================
;; F. Mode 2 — Land Type Change (Selection-Based)
;; =====================================================

(deftest vision-charm-mode2-returns-needs-selection-test
  (testing "Mode 2: resolve returns :needs-selection (interactive effect)"
    (let [db (th/create-test-db)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          ;; Cast with mode 2 selected — set chosen-mode on the stack object
          obj-eid (q/get-object-eid db vc-id)
          db (d/db-with db [[:db/add obj-eid :object/chosen-mode mode-2]])
          ;; Pay the mana and put on stack
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-on-stack (rules/cast-spell db :player-1 vc-id)
          ;; Resolve — should return a selection
          result (th/resolve-top db-on-stack)]
      (is (= :land-type-source (:selection/type (:selection result)))
          "Mode 2 resolve should return a :land-type-source pending selection"))))


(deftest vision-charm-mode2-full-flow-island-to-mountain-test
  (testing "Mode 2 full 2-step selection: Island→Mountain, grants applied to islands"
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db swamp-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          ;; Set chosen mode on object so cast-and-resolve knows which effects to run
          obj-eid (q/get-object-eid db vc-id)
          db (d/db-with db [[:db/add obj-eid :object/chosen-mode mode-2]])
          ;; Cast the spell (puts it on stack with chosen mode)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-on-stack (rules/cast-spell db :player-1 vc-id)
          ;; Resolve → should return source land type selection
          {:keys [db selection]} (th/resolve-top db-on-stack)
          _ (is (= :land-type-source (:selection/type selection))
                "Should return :land-type-source selection after resolve")
          ;; Step 1: Confirm source type = :island
          {:keys [db selection]} (th/confirm-selection db selection #{:island})
          _ (is (= :land-type-target (:selection/type selection))
                "Should chain to :land-type-target selection")
          ;; Step 2: Confirm target type = :mountain
          {:keys [db]} (th/confirm-selection db selection #{:mountain})
          ;; Assert grants on islands, not swamp
          island-grants (grants/get-grants-by-type db island-id :land-type-override)
          swamp-grants (grants/get-grants-by-type db swamp-id :land-type-override)]
      (is (= 1 (count island-grants))
          "Island should have exactly 1 :land-type-override grant")
      (is (= 0 (count swamp-grants))
          "Swamp should have no :land-type-override grant")
      (let [grant (first island-grants)]
        (is (= :island (get-in grant [:grant/data :original-subtype]))
            "Grant should record :island as original subtype")
        (is (= :mountain (get-in grant [:grant/data :new-subtype]))
            "Grant should record :mountain as new subtype")))))


(deftest vision-charm-mode2-overridden-island-produces-red-mana-test
  (testing "Mode 2: island overridden to mountain produces red mana when tapped"
    (let [db (th/create-test-db)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-2 (get (:card/modes vision-charm/card) 1)
          obj-eid (q/get-object-eid db vc-id)
          db (d/db-with db [[:db/add obj-eid :object/chosen-mode mode-2]])
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-on-stack (rules/cast-spell db :player-1 vc-id)
          {:keys [db selection]} (th/resolve-top db-on-stack)
          {:keys [db selection]} (th/confirm-selection db selection #{:island})
          {:keys [db]} (th/confirm-selection db selection #{:mountain})
          ;; Now tap the overridden island for red
          db-after-tap (mana-activation/activate-mana-ability db :player-1 island-id :red)]
      (is (= 1 (get (q/get-mana-pool db-after-tap :player-1) :red 0))
          "Overridden island should produce 1 red mana")
      (is (= 0 (get (q/get-mana-pool db-after-tap :player-1) :blue 0))
          "Overridden island should NOT produce blue mana"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest vision-charm-mode1-fewer-than-four-cards-test
  (testing "Mode 1: mills all available cards when library has fewer than 4"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add exactly 2 cards to opponent's library (fewer than 4)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          _ (is (= 2 (count (q/get-objects-in-zone db :player-2 :library)))
                "Precondition: opponent has only 2 library cards")
          [db vc-id] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-1 (get (:card/modes vision-charm/card) 0)
          db-cast (th/cast-mode-with-target db :player-1 vc-id mode-1 :player-2)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= 0 (count (q/get-objects-in-zone db :player-2 :library)))
          "All remaining library cards should be milled (no crash)"))))


(deftest vision-charm-mode3-cannot-target-non-artifact-test
  (testing "Mode 3 cannot target non-artifact permanents"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a non-artifact land on battlefield
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-2)
          [db _] (th/add-card-to-zone db :vision-charm :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          mode-3 (get (:card/modes vision-charm/card) 2)
          target-req (first (:mode/targeting mode-3))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      ;; The land should NOT be a valid target for mode 3
      (is (not (contains? (set targets) land-id))
          "Island should not be a valid target for Mode 3 (artifact only)"))))
