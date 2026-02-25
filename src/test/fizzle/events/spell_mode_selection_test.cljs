(ns fizzle.events.spell-mode-selection-test
  "Tests for spell mode selection flow (modal spells like REB/BEB).

   When a card has :card/modes, the player must choose a spell mode
   before casting. This differs from casting mode selection (kicker/flashback)
   which chooses how to pay costs. Spell mode selection chooses which
   effect/targeting to use.

   Flow: cast-spell -> pending-spell-mode-selection -> select-spell-mode
         -> store chosen-mode on object -> proceed to targeting"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.red.red-elemental-blast :as reb]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === Spell Mode Selection Shows for Modal Cards ===

(deftest modal-card-shows-spell-mode-selector-test
  (testing "Casting a modal card shows spell mode selector"
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
      ;; Should show spell mode selection
      (is (some? (:game/pending-spell-mode-selection result-db))
          "Should show spell mode selector for modal card")
      ;; Should have the card's modes available
      (let [pending (:game/pending-spell-mode-selection result-db)]
        (is (= reb-id (:object-id pending))
            "Should track the object id")
        (is (= 2 (count (:modes pending)))
            "Should have both modes available"))
      ;; Card should NOT be on stack yet
      (is (= :hand (:object/zone (q/get-object (:game/db result-db) reb-id)))
          "Card should still be in hand"))))


(deftest modal-card-only-shows-modes-with-valid-targets-test
  (testing "Only modes with valid targets are shown"
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
      (let [pending (:game/pending-spell-mode-selection result-db)
            modes (:modes pending)]
        (is (= 1 (count modes))
            "Should only show mode 2 (destroy blue permanent)")
        (is (= "Destroy target blue permanent" (:mode/label (first modes)))
            "Available mode should be the destroy mode")))))


;; === Select Spell Mode Handler ===

(deftest select-spell-mode-proceeds-to-targeting-test
  (testing "Selecting a spell mode stores chosen mode and proceeds to targeting"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add REB
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          chosen-mode (first (:card/modes reb/card))
          ;; Setup app-db as if spell mode selector is showing
          app-db {:game/db db
                  :game/selected-card reb-id
                  :game/pending-spell-mode-selection {:object-id reb-id
                                                      :modes (:card/modes reb/card)}}
          result-db (game/select-spell-mode-handler app-db chosen-mode)]
      ;; Pending spell mode should be cleared
      (is (nil? (:game/pending-spell-mode-selection result-db))
          "Spell mode selection should be cleared")
      ;; Object should have chosen mode stored
      (let [obj (q/get-object (:game/db result-db) reb-id)]
        (is (= chosen-mode (:object/chosen-mode obj))
            "Object should have chosen mode stored"))
      ;; Should have pending selection for targeting (since mode has targets)
      (is (some? (:game/pending-selection result-db))
          "Should proceed to target selection"))))


(deftest select-spell-mode-shows-targeting-for-object-targets-test
  (testing "When mode has object targets, proceeds to target selection"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put exactly one blue spell on stack
          [db _opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 _opt-id)
          ;; Add REB
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          chosen-mode (first (:card/modes reb/card))
          app-db {:game/db db
                  :game/selected-card reb-id
                  :game/pending-spell-mode-selection {:object-id reb-id
                                                      :modes (:card/modes reb/card)}}
          result-db (game/select-spell-mode-handler app-db chosen-mode)]
      ;; Object targeting shows selection (even with single target)
      (is (some? (:game/pending-selection result-db))
          "Should show target selection for object targets")
      ;; Spell should still be in hand (not yet cast, waiting for target)
      (is (= :hand (:object/zone (q/get-object (:game/db result-db) reb-id)))
          "REB should still be in hand until target is selected"))))


;; === Cancel Spell Mode Selection ===

(deftest cancel-spell-mode-selection-test
  (testing "Canceling spell mode selection clears pending state"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          app-db {:game/db db
                  :game/selected-card reb-id
                  :game/pending-spell-mode-selection {:object-id reb-id
                                                      :modes (:card/modes reb/card)}}
          result-db (game/cancel-spell-mode-selection-handler app-db)]
      (is (nil? (:game/pending-spell-mode-selection result-db))
          "Pending spell mode selection should be cleared")
      (is (= :hand (:object/zone (q/get-object (:game/db result-db) reb-id)))
          "Card should still be in hand"))))


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
      (is (nil? (:game/pending-spell-mode-selection result-db))
          "Non-modal card should not trigger spell mode selection")
      ;; Should proceed to targeting directly
      (is (some? (:game/pending-selection result-db))
          "Should proceed directly to targeting"))))
