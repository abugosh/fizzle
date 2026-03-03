(ns fizzle.events.spell-mode-selection-test
  "Tests for spell mode selection flow (modal spells like REB/BEB).

   When a card has :card/modes, the player must choose a spell mode
   before casting. This differs from casting mode selection (kicker/flashback)
   which chooses how to pay costs. Spell mode selection chooses which
   effect/targeting to use.

   Flow: cast-spell -> pending-selection (type :spell-mode) -> toggle-selection
         (auto-confirm) -> store chosen-mode on object -> continuation -> proceed to targeting"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.red.red-elemental-blast :as reb]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.test-helpers :as th]))


;; === Spell Mode Selection Shows for Modal Cards ===

(deftest modal-card-shows-spell-mode-selector-test
  (testing "Casting a modal card shows spell mode selection via standard selection system"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack (valid target for REB mode 1)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Put a blue permanent on battlefield (valid target for REB mode 2)
          [db _blue-perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          ;; Add REB to player's hand with mana
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          app-db {:game/db db
                  :game/selected-card reb-id}
          result-db (game/cast-spell-handler app-db)]
      ;; Should show spell mode selection via standard selection system
      (is (some? (:game/pending-selection result-db))
          "Should show selection for modal card")
      (let [selection (:game/pending-selection result-db)]
        (is (= :spell-mode (:selection/type selection))
            "Selection type should be :spell-mode")
        (is (= reb-id (:selection/object-id selection))
            "Should track the object id")
        (is (= 2 (count (:selection/candidates selection)))
            "Should have both modes as candidates")
        (is (= 1 (:selection/select-count selection))
            "Should select exactly one mode")
        (is (true? (:selection/auto-confirm? selection))
            "Should auto-confirm on selection"))
      ;; Card should NOT be on stack yet
      (is (= :hand (:object/zone (q/get-object (:game/db result-db) reb-id)))
          "Card should still be in hand")
      ;; Should NOT have old parallel mechanism
      (is (nil? (:game/pending-spell-mode-selection result-db))
          "Old parallel mechanism should not be used"))))


(deftest modal-card-only-shows-modes-with-valid-targets-test
  (testing "Only modes with valid targets are shown as candidates"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue permanent on battlefield (valid for mode 2 only)
          ;; NO blue spell on stack (mode 1 has no valid targets)
          [db _perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          app-db {:game/db db
                  :game/selected-card reb-id}
          result-db (game/cast-spell-handler app-db)]
      ;; Should show only modes with valid targets
      (let [selection (:game/pending-selection result-db)
            candidates (:selection/candidates selection)]
        (is (= 1 (count candidates))
            "Should only show mode 2 (destroy blue permanent)")
        (is (= "Destroy target blue permanent" (:mode/label (first candidates)))
            "Available mode should be the destroy mode")))))


;; === Spell Mode Executor and Continuation ===

(deftest spell-mode-executor-stores-chosen-mode-test
  (testing "Confirming spell mode selection stores chosen mode on object"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          chosen-mode (first (:card/modes reb/card))
          ;; Build spell-mode selection as cast-spell-handler would
          app-db {:game/db db
                  :game/selected-card reb-id}
          app-db-with-sel (game/cast-spell-handler app-db)
          ;; Simulate toggle-selection (select the first mode)
          app-db-with-toggle (assoc-in app-db-with-sel
                                       [:game/pending-selection :selection/selected]
                                       #{chosen-mode})
          ;; Confirm selection through production path
          result-db (sel-core/confirm-selection-impl app-db-with-toggle)]
      ;; Object should have chosen mode stored
      (let [obj (q/get-object (:game/db result-db) reb-id)]
        (is (= chosen-mode (:object/chosen-mode obj))
            "Object should have chosen mode stored"))
      ;; Should have proceeded to targeting (continuation triggers initiate-cast-with-mode)
      (is (some? (:game/pending-selection result-db))
          "Should proceed to target selection after spell mode chosen")
      ;; Spell mode selection should be cleared (new selection is targeting, not spell-mode)
      (is (not= :spell-mode (:selection/type (:game/pending-selection result-db)))
          "Should no longer be in spell-mode selection"))))


;; === Non-Modal Card Not Affected ===

(deftest non-modal-card-unaffected-test
  (testing "Non-modal card (Counterspell) does not trigger spell mode selection"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Add Counterspell
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          app-db {:game/db db
                  :game/selected-card cs-id}
          result-db (game/cast-spell-handler app-db)]
      ;; Should NOT show spell mode selection
      (is (not= :spell-mode (:selection/type (:game/pending-selection result-db)))
          "Non-modal card should not trigger spell mode selection")
      ;; Should proceed to targeting directly
      (is (some? (:game/pending-selection result-db))
          "Should proceed directly to targeting"))))
