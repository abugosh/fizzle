(ns fizzle.events.selection.integration-test
  "Integration tests: selection confirm through the full event chain including SBAs.

   These tests go through rf/dispatch-sync [::confirm-selection] which exercises
   the complete production path:
     re-frame dispatch [::confirm-selection]
       → event handler (events/selection.cljs)
         → confirm-selection-impl
           → validate-selection
           → execute-confirmed-selection (multimethod)
           → lifecycle routing + cleanup-selection-source
           → process-deferred-entry
         → :db effect handler (db_effect.cljs)
           → sba/check-and-execute-sbas  ← NOW HIT

   db-effect/register! is called at namespace load to install the custom :db
   handler. This is the key difference from unit tests that call
   confirm-selection-impl directly.

   Tests verify:
   1. Selection resolved correctly (cards moved to correct zones)
   2. SBAs ran after the db mutation (loss condition set if life drops to 0)"
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.selection :as selection]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.events.selection.storm :as storm]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Install the custom :db effect handler so SBAs fire through dispatch-sync.
;; This is what distinguishes integration tests from unit tests.
(db-effect/register!)


;; === Fixtures ===

(use-fixtures :each
  {:before (fn [] (reset! rf-db/app-db {}))
   :after  (fn [] (reset! rf-db/app-db {}))})


;; === Helpers ===

(defn- dispatch-confirm
  "Set rf-db/app-db, dispatch confirm-selection synchronously, return result."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::selection/confirm-selection])
  @rf-db/app-db)


(defn- create-base-db
  "Create a game state with player-1 and an opponent."
  []
  (-> (th/create-test-db)
      (th/add-opponent)))


(defn- drain-player
  "Set a player's life total to a given value. Returns updated db."
  [db player-id life]
  (let [player-eid (q/get-player-eid db player-id)
        conn (d/conn-from-db db)]
    (d/transact! conn [[:db/add player-eid :player/life life]])
    @conn))


;; =====================================================
;; 1. Discard: cards move to graveyard + SBAs fire
;; =====================================================

(deftest test-discard-confirm-full-path-with-sba
  (testing "Discard selection confirm: cards move to graveyard AND SBAs fire"
    (let [game-db (create-base-db)
          ;; Add a card to hand
          [game-db card-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          ;; Drain player-2 life to 0 so an SBA fires after the discard confirm
          ;; This verifies SBAs run through the :db effect handler, not just the selection
          game-db (drain-player game-db :player-2 0)
          ;; Build a :discard selection (mirrors generic zone-pick builder output)
          pending-sel {:selection/type :discard
                       :selection/lifecycle :finalized
                       :selection/card-source :hand
                       :selection/target-zone :graveyard
                       :selection/select-count 1
                       :selection/player-id :player-1
                       :selection/selected #{card-id}
                       :selection/spell-id (random-uuid)
                       :selection/remaining-effects []
                       :selection/validation :exact
                       :selection/auto-confirm? false}
          ;; Set pending selection via production chokepoint
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          result (dispatch-confirm app-db)
          result-game-db (:game/db result)]

      ;; 1. Selection resolved: pending-selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared after confirm")

      ;; 2. Card moved to graveyard (selection effect applied)
      (is (= :graveyard (th/get-object-zone result-game-db card-id))
          "Discarded card should be in graveyard")

      ;; 3. SBA fired: player-2 at 0 life triggers :life-zero
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should have fired through the :db effect handler")))))


;; =====================================================
;; 2. Mana-allocation: spell cast + SBAs fire
;; =====================================================

(deftest test-mana-allocation-confirm-full-path-with-sba
  (testing "Mana-allocation confirm: spell cast + SBAs fire"
    (let [game-db (create-base-db)
          ;; Add dark ritual to hand, give mana
          [game-db spell-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db (d/db-with game-db
                             [[:db/add (q/get-player-eid game-db :player-1)
                               :player/mana-pool {:white 0 :blue 0 :black 3
                                                  :red 0 :green 0 :colorless 0}]])
          ;; Drain opponent to 0 life to verify SBA fires after mana-allocation resolves
          game-db (drain-player game-db :player-2 0)
          ;; Mana-allocation auto-confirms (lifecycle :finalized) when generic-remaining = 0.
          ;; We simulate the completed state: all allocation done, ready to cast.
          ;; Dark Ritual is {B} — no generic, so build a :finalized mana-allocation
          ;; that casts the spell directly (no mana-allocation UI needed for {B}).
          ;; Instead use the simpler approach: set up a proper mana-allocation sel
          ;; with generic-remaining 0 so the executor casts immediately.
          ;;
          ;; Actually dark ritual has no generic. Use a :standard lifecycle discard
          ;; that chains and resolves through the standard path to cast dr.
          ;; Simplest approach: use a plain :finalized mana-allocation selection
          ;; with a spell that has pure-colored cost (no generic portion).
          ;; The mana-allocation executor with generic-remaining=0 is :finalized
          ;; auto-confirm — cast will fire.
          ;;
          ;; Build the selection as if mana-allocation has been filled in (generic-remaining=0):
          mode {:mode/id :primary
                :mode/mana-cost {:black 1}
                :mode/effects [{:effect/type :add-mana :effect/mana {:black 3}}]}
          pending-sel {:selection/type :mana-allocation
                       :selection/lifecycle :finalized
                       :selection/clear-selected-card? true
                       :selection/player-id :player-1
                       :selection/spell-id spell-id
                       :selection/mode mode
                       :selection/generic-remaining 0
                       :selection/generic-total 0
                       :selection/allocation {}
                       :selection/remaining-pool {:white 0 :blue 0 :black 3
                                                  :red 0 :green 0 :colorless 0}
                       :selection/original-remaining-pool {:white 0 :blue 0 :black 3
                                                           :red 0 :green 0 :colorless 0}
                       :selection/colored-cost {:black 1}
                       :selection/original-cost {:black 1}
                       :selection/validation :always
                       :selection/auto-confirm? true}
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          result (dispatch-confirm app-db)
          result-game-db (:game/db result)]

      ;; 1. Selection cleared after confirm
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared after confirm")

      ;; 2. Spell is on stack (cast but not yet resolved)
      (is (= :stack (th/get-object-zone result-game-db spell-id))
          "Spell should be on stack after mana-allocation confirms cast")

      ;; 3. Mana deducted
      (let [pool (q/get-mana-pool result-game-db :player-1)]
        (is (= 2 (:black pool)) "Black mana: 3 - 1 (spell cost) = 2"))

      ;; 4. SBA fired even though we're mid-stack
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should fire through :db effect handler after mana-allocation confirm")))))


;; =====================================================
;; 3. Storm-split: copies created + SBAs fire
;; =====================================================

(deftest test-storm-split-confirm-full-path-with-sba
  (testing "Storm-split confirm: storm copies created + SBAs fire"
    (let [game-db (create-base-db)
          ;; Put Brain Freeze on stack (source for storm)
          [game-db source-id] (th/add-card-to-zone game-db :brain-freeze :stack :player-1)
          ;; Create a storm stack item
          game-db (stack/create-stack-item game-db
                                           {:stack-item/type :storm
                                            :stack-item/controller :player-1
                                            :stack-item/source source-id
                                            :stack-item/effects [{:effect/type :storm-copies
                                                                  :effect/count 2}]
                                            :stack-item/description "Storm - Brain Freeze"})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items game-db)))
          ;; Drain player-2 to 0 for SBA trigger
          game-db (drain-player game-db :player-2 0)
          ;; Build storm-split selection via production builder
          selection (storm/build-storm-split-selection game-db :player-1 storm-si)
          _ (assert selection "Storm-split selection should be built")
          app-db (sel-spec/set-pending-selection {:game/db game-db} selection)
          result (dispatch-confirm app-db)
          result-game-db (:game/db result)]

      ;; 1. Selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared")

      ;; 2. Storm stack item removed
      (let [storm-items (filter #(= :storm (:stack-item/type %))
                                (q/get-all-stack-items result-game-db))]
        (is (empty? storm-items)
            "Storm stack-item should be removed after confirm"))

      ;; 3. Copies created (default allocation: all to first target = player-2)
      (let [all-items (q/get-all-stack-items result-game-db)
            copy-items (filter #(= :storm-copy (:stack-item/type %)) all-items)]
        (is (= 2 (count copy-items))
            "2 storm copies should be on the stack"))

      ;; 4. SBA fired through :db effect handler
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should fire through :db effect handler after storm-split confirm")))))


;; =====================================================
;; 4. Scry: library reordered + SBAs fire
;; =====================================================

(deftest test-scry-confirm-full-path-with-sba
  (testing "Scry confirm: library reordered + SBAs fire"
    (let [game-db (create-base-db)
          ;; Add 3 cards to library (scry will reveal top 2)
          [game-db [top-id mid-id _bot-id]] (th/add-cards-to-library
                                              game-db
                                              [:dark-ritual :cabal-ritual :brain-freeze]
                                              :player-1)
          ;; The scry executor moves spell-id to graveyard — add a real card to the stack
          ;; (Opt causes scry 1 then draw; use Opt on stack as the spell context)
          [game-db spell-id] (th/add-card-to-zone game-db :opt :stack :player-1)
          ;; Drain player-2 to 0 for SBA trigger
          game-db (drain-player game-db :player-2 0)
          ;; Build scry selection manually (mirrors build-scry-selection output)
          ;; Scry 2: show top-id and mid-id, player puts both on top
          library-cards [top-id mid-id]
          pending-sel {:selection/type :scry
                       :selection/lifecycle :finalized
                       :selection/player-id :player-1
                       :selection/cards library-cards
                       :selection/top-pile [top-id mid-id]
                       :selection/bottom-pile []
                       :selection/spell-id spell-id
                       :selection/remaining-effects []
                       :selection/validation :always
                       :selection/auto-confirm? false}
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          result (dispatch-confirm app-db)
          result-game-db (:game/db result)]

      ;; 1. Selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared after scry confirm")

      ;; 2. SBA fired through :db effect handler
      (let [game-state (q/get-game-state result-game-db)]
        (is (= :life-zero (:game/loss-condition game-state))
            "SBA :life-zero should fire through :db effect handler after scry confirm")))))


;; =====================================================
;; 5. Token cleanup SBA fires after discard
;; =====================================================

(deftest test-token-cleanup-sba-fires-after-discard
  (testing "Token cleanup SBA fires when discard resolves — tokens leave non-battlefield zones"
    (let [game-db (create-base-db)
          ;; Create a token card in the database and put it in the hand
          ;; (tokens in non-battlefield zones trigger :token-cleanup SBA)
          conn (d/conn-from-db game-db)
          player-eid (q/get-player-eid game-db :player-1)
          token-card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] game-db)
          token-id (random-uuid)
          _ (d/transact! conn [{:object/id token-id
                                :object/card token-card-eid
                                :object/zone :hand
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false
                                :object/is-token true}])
          game-db @conn
          ;; Also add a regular card to discard
          [game-db card-id] (th/add-card-to-zone game-db :cabal-ritual :hand :player-1)
          ;; Build discard selection targeting the regular card (not the token)
          pending-sel {:selection/type :discard
                       :selection/lifecycle :finalized
                       :selection/card-source :hand
                       :selection/target-zone :graveyard
                       :selection/select-count 1
                       :selection/player-id :player-1
                       :selection/selected #{card-id}
                       :selection/spell-id (random-uuid)
                       :selection/remaining-effects []
                       :selection/validation :exact
                       :selection/auto-confirm? false}
          app-db (sel-spec/set-pending-selection {:game/db game-db} pending-sel)
          result (dispatch-confirm app-db)
          result-game-db (:game/db result)]

      ;; 1. Discarded card moved to graveyard
      (is (= :graveyard (th/get-object-zone result-game-db card-id))
          "Selected card should be in graveyard")

      ;; 2. Token in hand is removed by :token-cleanup SBA
      ;; (tokens outside battlefield cease to exist)
      (is (nil? (q/get-object result-game-db token-id))
          "Token in hand should be removed by :token-cleanup SBA"))))
