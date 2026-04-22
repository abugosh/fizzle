(ns fizzle.events.phases-test
  "Integration (dispatch-sync) + unit tests for events/phases.cljs.

   Integration tests dispatch through re-frame and verify state changes including
   SBA-adjacent behavior (cleanup discard trigger) and history entry creation.
   Unit tests call pure functions directly for edge cases and guard conditions."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [clojure.string :as str]
    [datascript.core :as d]
    [fizzle.cards.red.lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.phases :as phases]
    [fizzle.events.resolution :as resolution]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register history interceptor AND SBA dispatch for dispatch-sync tests
(interceptor/register!)
(db-effect/register!)


(defn- setup-app-db
  "Create a full app-db with human + goldfish bot, libraries populated,
   starting at :main1 phase. Mirrors priority_test.cljs setup exactly."
  ([]
   (setup-app-db {}))
  ([opts]
   (h/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; ============================================================
;; Integration Tests (dispatch-sync through re-frame)
;; ============================================================

;; Test 1: Full turn cycle E2E
(deftest full-turn-cycle-e2e
  (testing "full cycle from :main1 through cleanup to new turn works end-to-end"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          ;; Confirm starting state
          _ (is (= :main1 (:game/phase (q/get-game-state game-db)))
                "create-game-scenario starts at :main1")
          ;; main1 → main2 (combat skipped — no creatures)
          after-1 (dispatch-event app-db [::phases/advance-phase])
          _ (is (= :main2 (:game/phase (q/get-game-state (:game/db after-1))))
                "main1 → main2 (combat skipped)")
          ;; main2 → end
          after-2 (dispatch-event after-1 [::phases/advance-phase])
          _ (is (= :end (:game/phase (q/get-game-state (:game/db after-2))))
                "main2 → end")
          ;; end → cleanup
          after-3 (dispatch-event after-2 [::phases/advance-phase])
          _ (is (= :cleanup (:game/phase (q/get-game-state (:game/db after-3))))
                "end → cleanup")
          ;; cleanup → start new turn (lands at :untap)
          after-turn (dispatch-event after-3 [::phases/start-turn])
          _ (is (= :untap (:game/phase (q/get-game-state (:game/db after-turn))))
                "new turn starts at :untap")
          _ (is (= 2 (:game/turn (q/get-game-state (:game/db after-turn))))
                "turn counter incremented to 2")
          _ (is (= :player-2 (q/get-active-player-id (:game/db after-turn)))
                "active player switched to player-2 (bot)")
          ;; Continue the cycle: untap → upkeep → draw → main1
          after-upkeep (dispatch-event after-turn [::phases/advance-phase])
          _ (is (= :upkeep (:game/phase (q/get-game-state (:game/db after-upkeep))))
                "untap → upkeep")
          after-draw (dispatch-event after-upkeep [::phases/advance-phase])
          _ (is (= :draw (:game/phase (q/get-game-state (:game/db after-draw))))
                "upkeep → draw")
          after-main1 (dispatch-event after-draw [::phases/advance-phase])
          result-db (:game/db after-main1)
          game-state (q/get-game-state result-db)]
      (is (= :main1 (:game/phase game-state))
          "draw → main1 completes the untap→upkeep→draw→main1 cycle")
      (is (= 0 (q/get-storm-count result-db :player-2))
          "storm count is 0 for new active player")
      (is (= 1 (d/q '[:find ?plays .
                      :in $ ?pid
                      :where [?e :player/id ?pid]
                      [?e :player/land-plays-left ?plays]]
                    result-db :player-2))
          "land plays reset to 1 for new active player"))))


;; Test 2: advance-phase empties mana pool
(deftest advance-phase-empties-mana-pool
  (testing "advancing phase clears the active player's mana pool"
    (let [app-db (setup-app-db {:mana {:black 3 :blue 2}})
          result (dispatch-event app-db [::phases/advance-phase])
          pool (q/get-mana-pool (:game/db result) :player-1)]
      (is (every? zero? (vals pool))
          "mana pool should be empty after phase advance"))))


;; Test 3: advance-phase skips combat when no creatures
(deftest advance-phase-skips-combat-no-creatures
  (testing "advance-phase from :main1 skips :combat to :main2 when no creatures exist"
    (let [app-db (setup-app-db)
          ;; Default scenario has no creatures on battlefield
          result (dispatch-event app-db [::phases/advance-phase])
          phase (:game/phase (q/get-game-state (:game/db result)))]
      (is (= :main2 phase)
          "should skip :combat and land on :main2"))))


;; Test 4: advance-phase enters combat when creatures exist
(deftest advance-phase-enters-combat-with-creatures
  (testing "advance-phase from :main1 enters :combat when a creature is on battlefield"
    (let [base-db (:game/db (setup-app-db))
          [db-with-creature _] (h/add-test-creature base-db :player-1 2 2)
          app-db (merge (h/create-game-scenario {})
                        {:game/db db-with-creature})
          result (dispatch-event app-db [::phases/advance-phase])
          phase (:game/phase (q/get-game-state (:game/db result)))]
      (is (= :combat phase)
          "should enter :combat when creatures are on battlefield"))))


;; Test 5: advance-phase to cleanup triggers discard when hand > 7
(deftest advance-phase-cleanup-triggers-discard-selection
  (testing "advancing to cleanup phase triggers pending-selection for discard when hand > 7"
    (let [base-db (:game/db (setup-app-db))
          ;; Add 8 cards to hand (> 7 max)
          [db-with-cards _] (h/add-cards-to-library base-db
                                                    (vec (repeat 8 :island))
                                                    :player-1)
          ;; Move 8 islands to hand by transacting directly
          conn (d/conn-from-db db-with-cards)
          player-eid (q/get-player-eid db-with-cards :player-1)
          card-eid (d/q '[:find ?e . :where [?e :card/id :island]] db-with-cards)
          _ (d/transact! conn (vec (for [_ (range 8)]
                                     {:object/id (random-uuid)
                                      :object/card card-eid
                                      :object/zone :hand
                                      :object/owner player-eid
                                      :object/controller player-eid
                                      :object/tapped false})))
          db-8-in-hand @conn
          ;; Advance through phases to cleanup: main1→main2→end→cleanup
          app-db-1 (merge (setup-app-db) {:game/db db-8-in-hand})
          after-main2 (dispatch-event app-db-1 [::phases/advance-phase])
          after-end (dispatch-event after-main2 [::phases/advance-phase])
          after-cleanup (dispatch-event after-end [::phases/advance-phase])
          selection (:game/pending-selection after-cleanup)]
      (is (some? selection)
          "pending-selection should exist when hand exceeds max size")
      (is (= :discard (:selection/type selection))
          "selection type should be :discard")
      (is (= 1 (:selection/select-count selection))
          "should need to discard exactly 1 card (8 - 7 = 1)")
      (is (true? (:selection/cleanup? selection))
          "selection should be flagged as cleanup discard"))))


;; Test 6: advance-phase blocked by pending selection
(deftest advance-phase-blocked-by-pending-selection
  (testing "advance-phase is a no-op when :game/pending-selection exists"
    (let [app-db (setup-app-db)
          initial-game-db (:game/db app-db)
          initial-phase (:game/phase (q/get-game-state initial-game-db))
          ;; Inject a fake pending selection
          app-db-with-sel (assoc app-db
                                 :game/pending-selection
                                 {:selection/type :discard
                                  :selection/player-id :player-1})
          result (dispatch-event app-db-with-sel [::phases/advance-phase])]
      (is (= initial-phase (:game/phase (q/get-game-state (:game/db result))))
          "phase should not change when pending-selection blocks advance"))))


;; Test 7: advance-phase blocked by non-empty stack
(deftest advance-phase-blocked-by-non-empty-stack
  (testing "advance-phase is a no-op when the stack is non-empty"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          ;; Put a spell on the stack by casting Dark Ritual
          [game-db-with-card obj-id] (h/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-with-spell (rules/cast-spell game-db-with-card :player-1 obj-id)
          app-db-with-stack (assoc app-db :game/db game-db-with-spell)
          result (dispatch-event app-db-with-stack [::phases/advance-phase])
          result-phase (:game/phase (q/get-game-state (:game/db result)))]
      (is (= :main1 result-phase)
          "phase should not advance when spell is on stack"))))


;; Test 8: start-turn creates history pending entry
(deftest start-turn-creates-history-pending-entry
  (testing "::start-turn creates a :history/pending-entry with correct event type"
    ;; We need to be at cleanup to call start-turn meaningfully.
    ;; Advance to cleanup first, then call start-turn.
    (let [app-db (setup-app-db)
          after-main2 (dispatch-event app-db [::phases/advance-phase])
          after-end (dispatch-event after-main2 [::phases/advance-phase])
          after-cleanup (dispatch-event after-end [::phases/advance-phase])
          ;; Dispatch start-turn — the history interceptor processes pending-entry
          ;; and moves it to history. So we check history/main for the new entry.
          after-start-turn (dispatch-event after-cleanup [::phases/start-turn])
          history-entries (:history/main after-start-turn)]
      ;; The interceptor moves pending-entry into history, so check history/main
      (is (seq history-entries)
          "history should have entries after start-turn")
      (let [last-entry (last history-entries)]
        (is (= ::phases/start-turn (:entry/event-type last-entry))
            "last history entry event-type should be ::phases/start-turn")
        (is (not (str/blank? (:entry/description last-entry)))
            "last history entry should have a non-blank :entry/description")))))


;; Test 9: start-turn blocked by pending selection
(deftest start-turn-blocked-by-pending-selection
  (testing "::start-turn is a no-op when :game/pending-selection exists"
    (let [app-db (setup-app-db)
          app-db-with-sel (assoc app-db
                                 :game/pending-selection
                                 {:selection/type :discard
                                  :selection/player-id :player-1})
          initial-turn (:game/turn (q/get-game-state (:game/db app-db-with-sel)))
          result (dispatch-event app-db-with-sel [::phases/start-turn])
          result-turn (:game/turn (q/get-game-state (:game/db result)))]
      (is (= initial-turn result-turn)
          "turn should not increment when pending-selection blocks start-turn"))))


;; ============================================================
;; Unit Tests (direct function calls on pure functions)
;; ============================================================

;; Test 10: next-phase boundary — each phase maps to the correct next phase
(deftest next-phase-sequence
  (testing "next-phase returns correct successor for each phase"
    (is (= :upkeep (phases/next-phase :untap)))
    (is (= :draw (phases/next-phase :upkeep)))
    (is (= :main1 (phases/next-phase :draw)))
    (is (= :combat (phases/next-phase :main1)))
    (is (= :main2 (phases/next-phase :combat)))
    (is (= :end (phases/next-phase :main2)))
    (is (= :cleanup (phases/next-phase :end)))
    ;; cleanup stays at cleanup — requires explicit start-turn
    (is (= :cleanup (phases/next-phase :cleanup))
        "cleanup should stay at cleanup (not wrap around)")))


;; Test 11: next-phase with unknown phase stays at cleanup behavior
(deftest next-phase-unknown-phase
  (testing "next-phase returns :cleanup for unknown phase (same as -1 idx)"
    ;; According to implementation: neg? idx returns current-phase
    (is (= :unknown-phase (phases/next-phase :unknown-phase))
        "unknown phase stays at itself (not found in phases vector)")))


;; Test 12: untap-all-permanents untaps all tapped permanents
(deftest untap-all-permanents-untaps-all
  (testing "untap-all-permanents sets :object/tapped false on all tapped player permanents"
    (let [base-db (:game/db (setup-app-db))
          ;; Add 3 creatures to battlefield (they start untapped from add-test-creature)
          [db1 _] (h/add-test-creature base-db :player-1 1 1)
          [db2 _] (h/add-test-creature db1 :player-1 2 2)
          [db3 _] (h/add-test-creature db2 :player-1 3 3)
          ;; Tap all 3
          player-eid (q/get-player-eid db3 :player-1)
          obj-eids (d/q '[:find [?e ...]
                          :in $ ?controller
                          :where [?e :object/controller ?controller]
                          [?e :object/zone :battlefield]]
                        db3 player-eid)
          tapped-db (d/db-with db3 (mapv (fn [eid] [:db/add eid :object/tapped true]) obj-eids))
          ;; Verify they are tapped
          _ (is (= 3 (count (filter #(:object/tapped %)
                                    (map #(d/entity tapped-db %) obj-eids))))
                "all 3 should be tapped before untap")
          result (triggers/untap-all-permanents tapped-db :player-1)]
      (is (every? #(false? (:object/tapped (d/entity result %))) obj-eids)
          "all permanents should be untapped after untap-all-permanents"))))


;; Test 13: untap-all-permanents is a no-op when no tapped permanents
(deftest untap-all-permanents-no-tapped
  (testing "untap-all-permanents returns unchanged db when no tapped permanents exist"
    (let [db (:game/db (setup-app-db))
          result (triggers/untap-all-permanents db :player-1)]
      (is (= db result)
          "db should be unchanged when no tapped permanents exist"))))


;; Test 14: untap-all-permanents only affects the specified player
(deftest untap-all-permanents-only-affects-player
  (testing "untap-all-permanents does not affect the opponent's tapped permanents"
    (let [base-db (:game/db (setup-app-db))
          [db1 _] (h/add-test-creature base-db :player-1 2 2)
          [db2 _] (h/add-test-creature db1 :player-2 3 3)
          ;; Tap all permanents for both players
          p2-eid (q/get-player-eid db2 :player-2)
          all-bf-eids (d/q '[:find [?e ...]
                             :where [?e :object/zone :battlefield]]
                           db2)
          tapped-db (d/db-with db2 (mapv (fn [eid] [:db/add eid :object/tapped true]) all-bf-eids))
          ;; Untap only player-1's permanents
          result (triggers/untap-all-permanents tapped-db :player-1)
          p2-bf-eids (d/q '[:find [?e ...]
                            :in $ ?controller
                            :where [?e :object/controller ?controller]
                            [?e :object/zone :battlefield]]
                          result p2-eid)]
      (is (every? #(true? (:object/tapped (d/entity result %))) p2-bf-eids)
          "opponent permanents should still be tapped"))))


;; Test 15: start-turn fires untap-step trigger — new active player's permanents untapped
(deftest start-turn-untaps-new-active-player-permanents
  (testing "::start-turn fires :untap-step trigger that untaps new active player's permanents"
    (let [base-db (:game/db (setup-app-db))
          ;; Add a land for player-2 (the bot) and manually tap it
          [db-with-land land-id] (h/add-card-to-zone base-db :island :battlefield :player-2)
          land-eid (q/get-object-eid db-with-land land-id)
          db-tapped (d/db-with db-with-land [[:db/add land-eid :object/tapped true]])
          ;; Rebuild app-db with the tapped state
          app-db (assoc (setup-app-db) :game/db db-tapped)
          ;; Verify land is tapped before dispatch
          _ (is (true? (:object/tapped (q/get-object db-tapped land-id)))
                "land should be tapped before start-turn")
          ;; Dispatch ::start-turn — switches to player-2 and fires untap-step trigger
          result (dispatch-event app-db [::phases/start-turn])
          land-after (q/get-object (:game/db result) land-id)]
      (is (false? (:object/tapped land-after))
          "player-2's permanent should be untapped after start-turn fires untap trigger"))))


;; Test deferred: advance-phase-fires-delayed-effects-in-upkeep
;; Requires a card with :grant/delayed-effect (e.g., Rain of Filth) which
;; is not available in test helpers. Setup complexity exceeds value for
;; this epic. Tracked for future work.


;; === SBA sentinel: proves db-effect/register! is wired ===

(deftest sba-life-zero-fires-in-phases-test
  (testing "db-effect/register! wired: :life-zero SBA fires after bolt kills 1-life opponent"
    ;; Bug caught: if db-effect/register! is missing, life reaches -2 but
    ;; :game/loss-condition is never set — SBAs silently skip.
    (let [base-app-db (h/create-game-scenario {:bot-archetype :goldfish :mana {:red 1}})
          game-db (:game/db base-app-db)
          p2-eid (q/get-player-eid game-db :player-2)
          game-db' (d/db-with game-db [[:db/add p2-eid :player/life 1]])
          [game-db'' obj-id] (h/add-card-to-zone game-db' :lightning-bolt :hand :player-1)
          app-db (assoc base-app-db :game/db game-db'')
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::casting/cast-spell {:object-id obj-id :target :player-2}])
          _ (rf/dispatch-sync [::resolution/resolve-top])
          result-db (:game/db @rf-db/app-db)
          game-state (q/get-game-state result-db)]
      (is (= :life-zero (:game/loss-condition game-state))
          ":life-zero SBA must fire when bolt kills 1-life opponent — proves db-effect/register! is wired"))))
