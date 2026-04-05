(ns fizzle.events.selection.auto-confirm-chain-test
  "End-to-end tests for the toggle→auto-confirm→confirm dispatch chain.

   These tests verify the COMPLETE production path for single-select auto-confirm
   selection types:

     rf/dispatch-sync [::toggle-selection id]   ← toggle handler
       → toggle-selection-impl (sets selected, returns {:auto-confirm? true})
       → :fx [[:dispatch [::confirm-selection]]] (queued, not yet fired)
     rf/dispatch-sync [::confirm-selection]      ← simulates the async fx dispatch
       → confirm-selection-handler
         → confirm-selection-impl
           → execute-confirmed-selection (multimethod)
           → continuation chain (e.g. :cast-after-spell-mode)
           → process-deferred-entry
         → :db effect handler (db_effect.cljs)
           → sba/check-and-execute-sbas          ← SBAs NOW RUN

   db-effect/register! is called at namespace level to install the custom :db handler
   so SBAs fire through the :db effect path. This is what distinguishes these tests from
   the unit tests in auto_confirm_test.cljs (which do not call db-effect/register!).

   Tests prove THREE things per scenario:
     1. Selection resolved correctly (game state updated)
     2. Continuation chain ran (spell cast, effect applied, etc.)
     3. SBAs fired after the :db effect handler"
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.selection :as selection]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Install the custom :db effect handler so SBAs fire through dispatch-sync.
;; Called once at namespace load — replaces re-frame's default :db handler.
;; Tests reset app-db in fixtures so this is safe across the test run.
(db-effect/register!)


;; === Fixtures ===

(use-fixtures :each
  {:before (fn [] (reset! rf-db/app-db {}))
   :after  (fn [] (reset! rf-db/app-db {}))})


;; === Helpers ===

(defn- drain-player
  "Set a player's life total to a given value. Returns updated game-db."
  [db player-id life]
  (let [player-eid (q/get-player-eid db player-id)
        conn (d/conn-from-db db)]
    (d/transact! conn [[:db/add player-eid :player/life life]])
    @conn))


(defn- dispatch-toggle-then-confirm
  "Simulate the auto-confirm dispatch chain:
    1. Set rf-db to app-db
    2. dispatch-sync toggle (sets selected, queues confirm as fx)
    3. dispatch-sync confirm (simulates the queued async fx dispatch)
   Returns final rf-db."
  [app-db toggle-event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync toggle-event)
  (rf/dispatch-sync [::selection/confirm-selection])
  @rf-db/app-db)


(defn- create-base-db
  "Create a game state with player-1 and an opponent, standard mana."
  [mana]
  (-> (th/create-test-db {:mana mana})
      (th/add-opponent)))


;; =====================================================
;; 1. spell-mode: toggle mode → continuation casts spell → SBAs fire
;;
;; Vision Charm mode 2 (change-land-types) has no targeting and the spell
;; has {U} cost (no generic). The cast-after-spell-mode continuation calls
;; initiate-cast-with-mode which skips all pre-cast steps → casts directly
;; to stack. We verify: spell on stack + SBAs ran.
;; =====================================================

(deftest test-spell-mode-toggle-confirm-chain-casts-spell
  (testing "spell-mode toggle→confirm: chosen-mode stored, spell casts to stack, SBAs fire"
    (let [game-db (create-base-db {:blue 1})
          ;; Add Vision Charm to hand
          [game-db spell-id] (th/add-card-to-zone game-db :vision-charm :hand :player-1)
          ;; Drain opponent to 0 to verify SBAs fire after the selection chain
          game-db (drain-player game-db :player-2 0)
          ;; Build spell-mode selection using the production builder
          ;; Vision Charm mode 2 (change-land-types) — no targeting, no generic cost
          vision-charm-mode-2 {:mode/label "Change land types until end of turn"
                               :mode/effects [{:effect/type :change-land-types}]}
          pending-sel (casting/build-spell-mode-selection :player-1 spell-id [vision-charm-mode-2])
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          ;; Toggle mode 2, then confirm (simulating auto-confirm dispatch)
          result (dispatch-toggle-then-confirm app-db [::selection/toggle-selection vision-charm-mode-2])
          result-game-db (:game/db result)]

      ;; 1. Selection cleared after the chain
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared after spell-mode chain completes")

      ;; 2. Spell cast to stack via cast-after-spell-mode continuation
      (is (= :stack (th/get-object-zone result-game-db spell-id))
          "Vision Charm should be on the stack after spell-mode auto-confirm")

      ;; 3. SBA fired through :db effect handler (opponent at 0 life)
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should fire through :db effect handler after spell-mode chain")))))


;; =====================================================
;; 2. player-target: toggle player → effect runs → SBAs fire
;;
;; Player-target is lifecycle :finalized. The executor runs the target-effect
;; against the selected player immediately. We set opponent to 1 life, then
;; target opponent with a :lose-life 1 effect → opponent hits 0 → SBA fires.
;; =====================================================

(deftest test-player-target-toggle-confirm-chain-runs-effect
  (testing "player-target toggle→confirm: lose-life effect runs, opponent hits 0, SBA fires"
    (let [game-db (create-base-db {:black 1})
          ;; Set opponent to 1 life — player-target with :lose-life 1 should trigger SBA
          game-db (drain-player game-db :player-2 1)
          ;; Add dark ritual to hand (spell context)
          [game-db spell-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          pending-sel {:selection/type :player-target
                       :selection/lifecycle :finalized
                       :selection/player-id :player-1
                       :selection/selected #{}
                       :selection/select-count 1
                       :selection/valid-targets #{:player-1 :player-2}
                       :selection/spell-id spell-id
                       :selection/target-effect {:effect/type :lose-life
                                                 :effect/amount 1
                                                 :effect/target :any-player}
                       :selection/remaining-effects []
                       :selection/validation :exact
                       :selection/auto-confirm? true}
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          ;; Toggle player-2 (opponent), then confirm
          result (dispatch-toggle-then-confirm app-db [::selection/toggle-selection :player-2])
          result-game-db (:game/db result)]

      ;; 1. Selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared after player-target confirm")

      ;; 2. Lose-life effect ran: opponent life dropped to 0
      (let [opponent-life (q/get-life-total result-game-db :player-2)]
        (is (= 0 opponent-life)
            "Opponent should have 0 life after :lose-life 1 effect"))

      ;; 3. SBA fired through :db effect handler
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should fire through :db effect handler after player-target chain")))))


;; =====================================================
;; 3. tutor (single-select auto-confirm): toggle card → card moves to hand → SBAs fire
;;
;; Tutor is already tested in auto_confirm_test.cljs but WITHOUT db-effect/register!
;; so SBAs were never verified. Here we confirm the full chain including SBAs.
;; =====================================================

(deftest test-tutor-toggle-confirm-chain-moves-card-with-sba
  (testing "tutor toggle→confirm: card moves to hand AND SBAs fire"
    (let [game-db (create-base-db {})
          ;; Add a card to library for tutor to find
          [game-db [bf-id]] (th/add-cards-to-library game-db [:brain-freeze] :player-1)
          ;; Drain opponent to 0 to verify SBAs fire
          game-db (drain-player game-db :player-2 0)
          pending-sel {:selection/type :tutor
                       :selection/lifecycle :standard
                       :selection/player-id :player-1
                       :selection/selected #{}
                       :selection/select-count 1
                       :selection/exact? true
                       :selection/candidates #{bf-id}
                       :selection/spell-id (random-uuid)
                       :selection/target-zone :hand
                       :selection/shuffle? true
                       :selection/allow-fail-to-find? true
                       :selection/validation :exact-or-zero
                       :selection/auto-confirm? true}
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          ;; Toggle card, then confirm (auto-confirm chain)
          result (dispatch-toggle-then-confirm app-db [::selection/toggle-selection bf-id])
          result-game-db (:game/db result)]

      ;; 1. Selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared after tutor confirm")

      ;; 2. Card moved to hand (tutor effect executed)
      (is (= :hand (th/get-object-zone result-game-db bf-id))
          "Tutored card should be in hand after auto-confirm")

      ;; 3. SBA fired through :db effect handler
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should fire through :db effect handler after tutor chain")))))


;; =====================================================
;; 4. Deselect does NOT trigger auto-confirm chain
;;
;; Even for auto-confirm types (spell-mode, player-target, tutor),
;; deselecting an already-selected item must NOT fire confirm.
;; This verifies that the toggle→auto-confirm gate only opens on SELECT,
;; not on deselect — matching toggle-selection-impl's [:selected? false] path.
;; =====================================================

(deftest test-deselect-does-not-trigger-auto-confirm-chain
  (testing "Deselecting a spell-mode candidate does NOT trigger the confirm chain"
    (let [game-db (create-base-db {:blue 1})
          [game-db spell-id] (th/add-card-to-zone game-db :vision-charm :hand :player-1)
          vision-charm-mode-2 {:mode/label "Change land types until end of turn"
                               :mode/effects [{:effect/type :change-land-types}]}
          ;; Start with mode-2 already selected
          pending-sel (assoc (casting/build-spell-mode-selection :player-1 spell-id [vision-charm-mode-2])
                             :selection/selected #{vision-charm-mode-2})
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)]
      ;; Toggle the already-selected mode (deselect)
      (reset! rf-db/app-db app-db)
      (rf/dispatch-sync [::selection/toggle-selection vision-charm-mode-2])
      (let [result @rf-db/app-db]
        ;; Pending selection still present (deselect did NOT confirm)
        (is (some? (:game/pending-selection result))
            "Deselect should NOT clear pending selection")
        ;; spell NOT on stack (cast-after-spell-mode continuation did NOT fire)
        (is (= :hand (th/get-object-zone (:game/db result) spell-id))
            "Spell should remain in hand after deselect — no confirm fired")
        ;; Selection shows empty set after deselect
        (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
            "Selection should be empty after deselecting the previously-selected mode")))))
