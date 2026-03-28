(ns fizzle.events.priority-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.bots.definitions :as definitions]
    [fizzle.bots.interceptor :as bot-interceptor]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.queries :as q]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.events.phases :as phases]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.events.ui :as ui-events]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register interceptors for dispatch-sync tests
(interceptor/register!)


(defn- setup-app-db
  "Create a full app-db with two players (player + goldfish bot),
   player stops at main1+main2, starting at main1."
  ([]
   (setup-app-db {}))
  ([opts]
   (let [stops (or (:stops opts) #{:main1 :main2})
         db (-> (h/create-test-db (merge {:stops stops} (select-keys opts [:mana :life])))
                (h/add-opponent {:bot-archetype :goldfish}))]
     {:game/db db})))


(defn- dispatch-event
  "Dispatch an event through re-frame synchronously, return resulting app-db."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


(defn- process-bot-action!
  "Simulate the bot interceptor synchronously. Calls bot-decide-handler,
   applies db changes, and dispatches non-yield fx effects synchronously.
   When bot passes (dispatches ::yield), only applies db changes — the main
   loop will handle the next yield. When bot acts (play land, cast), dispatches
   the action events synchronously.
   Returns true if a bot action was taken, false if bot passed or no bot."
  []
  (let [current @rf-db/app-db
        game-db (:game/db current)]
    (if (or (not game-db)
            (:game/pending-selection current)
            (not (bot-interceptor/bot-should-act? game-db)))
      false
      (let [effects (bot-interceptor/bot-decide-handler current)
            fx-entries (:fx effects)
            ;; Check if this is a pass (dispatches ::yield) or an action
            is-pass? (some (fn [[fx-type payload]]
                             (and (= :dispatch fx-type)
                                  (= (first payload) :fizzle.events.priority-flow/yield)))
                           fx-entries)]
        ;; Always apply db changes
        (when (:db effects)
          (reset! rf-db/app-db (:db effects)))
        (if is-pass?
          ;; Bot passes — don't dispatch ::yield (main loop handles next yield)
          false
          ;; Bot acts — dispatch action events synchronously
          (do
            (doseq [[fx-type payload] fx-entries]
              (when (= :dispatch fx-type)
                (rf/dispatch-sync payload)))
            true))))))


(defn- dispatch-yield-all
  "Dispatch ::yield-all and drain the yield cascade synchronously.
   ::yield-all sets auto-mode + step-count and dispatches ::yield via :dispatch.
   Since :dispatch is async, we drain the cascade by repeatedly calling
   dispatch-sync [::yield] until the cascade settles.

   After each yield, simulates the bot interceptor (db_effect fires ::bot-decide
   after game-db mutations). When a yield transfers priority to a bot (no auto-pass),
   the bot interceptor passes, and another yield is dispatched to complete the
   priority round-trip.

   The cascade is complete when step-count is absent AND the bot has no pending action."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::priority-flow/yield-all])
  (loop [n 300]
    (if (or (zero? n) (:game/pending-selection @rf-db/app-db))
      @rf-db/app-db
      (let [has-step-count? (contains? @rf-db/app-db :yield/step-count)
            game-db (:game/db @rf-db/app-db)
            bot-can-act? (and game-db (bot-interceptor/bot-should-act? game-db))]
        (if (and (not has-step-count?) (not bot-can-act?))
          @rf-db/app-db
          (do
            ;; Process bot actions (land play, cast, pass)
            (loop [bot-n 20]
              (when (and (pos? bot-n) (process-bot-action!))
                (recur (dec bot-n))))
            ;; Dispatch yield to continue the cascade
            (rf/dispatch-sync [::priority-flow/yield])
            (recur (dec n))))))))


;; === Test 1: yield on empty stack, stops at main1+main2 ===

(deftest yield-empty-stack-advances-past-combat-to-main2
  (testing "yield from main1 with stops at main1+main2 skips combat, lands on main2"
    (let [app-db (setup-app-db)
          ;; First yield: human passes, priority transfers to bot (no auto-pass)
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes (bot holds priority), both passed, phase advances
          result2 (priority-flow/yield-impl (:app-db result1))]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result2)))))
          "Should advance to main2, skipping upkeep/draw/combat"))))


;; === Test 2: yield with Dark Ritual on stack ===

(deftest yield-with-spell-on-stack-resolves-it
  (testing "yield resolves top of stack when both pass on non-empty stack"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast Dark Ritual to put it on the stack
          game-db (rules/cast-spell db' :player-1 _obj-id)
          app-db {:game/db game-db}
          ;; First yield: human passes, priority transfers to bot
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes, both passed, spell resolves
          result2 (priority-flow/yield-impl (:app-db result1))
          result-db (:game/db (:app-db result2))
          pool (q/get-mana-pool result-db :player-1)]
      (is (= 3 (:black pool))
          "Dark Ritual should resolve, adding BBB to mana pool")
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should stay in main1 after resolving (stop is set)"))))


;; === Test 3: yield at main2 advances through turn boundary ===

(deftest yield-at-main2-advances-to-new-turn-main1
  (testing "yield from main2 advances through end, cleanup, opponent turn, to main1"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Advance to main2 manually
          game-db (-> db
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1)  ; combat -> main2
                      )
          ;; Use yield-all which cascades through turns via event dispatch
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should advance to main1 of next player turn")
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should be turn 3 (player T1 -> opponent T2 -> player T3)"))))


;; === Test 4: yield with selection-needed spell ===

(deftest yield-with-selection-spell-returns-pending-selection
  (testing "yield with a spell that needs player selection pauses"
    (let [db (-> (h/create-test-db {:mana {:blue 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add Careful Study (draw 2, then discard 2 — interactive discard)
          [db' obj-id] (h/add-card-to-zone db :careful-study :hand :player-1)
          ;; Need cards in library for Careful Study draw
          [db'' _] (h/add-cards-to-library db' [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          game-db (rules/cast-spell db'' :player-1 obj-id)
          app-db {:game/db game-db}
          ;; First yield: human passes, priority transfers to bot
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes, both passed, spell resolves with selection
          result2 (priority-flow/yield-impl (:app-db result1))]
      (is (some? (:game/pending-selection (:app-db result2)))
          "Should return pending-selection for interactive spell"))))


;; === Test 5: yield transfers priority to bot (no auto-pass) ===

(deftest yield-transfers-priority-to-bot
  (testing "yield without auto-mode transfers priority to bot instead of auto-passing"
    (let [app-db (setup-app-db)
          result (priority-flow/yield-impl app-db)
          result-db (:game/db (:app-db result))
          holder-eid (priority/get-priority-holder-eid result-db)
          opp-eid (q/get-player-eid result-db :player-2)]
      ;; Without auto-mode, priority transfers to bot — no auto-pass
      (is (= opp-eid holder-eid)
          "Priority should transfer to bot (not auto-passed)")
      ;; Phase should NOT have advanced (bot hasn't passed yet)
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Phase should stay at main1 (bot hasn't passed yet)"))))


;; === Test 6: cast spell retains priority ===

(deftest cast-spell-does-not-auto-yield
  (testing "casting a spell should not automatically yield priority"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)]
      ;; After casting, spell should be on stack, not resolved
      (is (= 1 (count (q/get-all-stack-items game-db)))
          "Stack should have exactly 1 item after casting")
      ;; Mana should have been spent, not gained
      (is (= 0 (:black (q/get-mana-pool game-db :player-1)))
          "Mana should be spent on casting, not refunded by resolution"))))


;; === Test 7: yield with no stops set ===

(deftest yield-with-no-stops-advances-to-turn-boundary
  (testing "yield from main1 with no stops advances to turn boundary (untap of new turn)"
    (let [app-db (setup-app-db {:stops #{}})
          ;; First yield: human passes, priority transfers to bot
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes, both passed, phase advances to turn boundary
          result2 (priority-flow/yield-impl (:app-db result1))
          result-db (:game/db (:app-db result2))]
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should advance to turn 2")
      (is (= :untap (:game/phase (q/get-game-state result-db)))
          "Should stop at untap (turn boundary) when no stops set"))))


;; === Test 8: manual yield resolves one stack item and stops ===

(deftest yield-resolves-one-and-stops-without-auto-mode
  (testing "Manual yield resolves one stack item and stops (no cascade without auto-mode)"
    (let [db (-> (h/create-test-db {:mana {:black 2} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Cast two Dark Rituals
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db {:game/db game-db2}
          result (priority-flow/yield-impl app-db)]
      ;; Without auto-mode, yield resolves one item and stops
      ;; This gives the player priority to respond to remaining stack items
      (is (not (:continue-yield? result))
          "Manual yield should not cascade when stack has more items"))))


;; === yield-all tests ===

(deftest yield-all-resolves-entire-stack
  (testing "yield-all with non-empty stack resolves all items"
    (let [db (-> (h/create-test-db {:mana {:black 2} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db (merge (history/init-history)
                        {:game/db game-db2})
          result (dispatch-yield-all app-db)]
      ;; Both rituals should resolve: 2*(BBB) - 2*B spent = 4B net
      ;; Actually: cast costs 1B each, so 2B spent. Each resolves for BBB.
      ;; But storm items also on stack. Let's just check stack is empty and mana > 0
      (is (empty? (q/get-all-stack-items (:game/db result)))
          "Stack should be empty after yield-all")
      (is (< 0 (:black (q/get-mana-pool (:game/db result) :player-1)))
          "Should have gained mana from resolved rituals")
      (is (= :main1 (:game/phase (q/get-game-state (:game/db result))))
          "Should stay in main1 (stop is set, stack resolved)"))))


(deftest yield-all-empty-stack-f6-advances-to-new-turn
  (testing "yield-all with empty stack enters F6 mode, advances through turn and opponent turn"
    (let [app-db (merge (history/init-history)
                        (setup-app-db))
          result (dispatch-yield-all app-db)]
      ;; F6 ignores player stops, advances through player turn, opponent turn, to next player turn
      (is (= 3 (:game/turn (q/get-game-state (:game/db result))))
          "Should advance to turn 3 (player T1 -> opponent T2 -> player T3)")
      ;; Auto-mode should be cleared after completion
      (is (nil? (priority/get-auto-mode (:game/db result)))
          "Auto-mode should be cleared after F6 completes"))))


;; === cast-and-yield tests ===

(deftest cast-and-yield-dispatches-yield
  (testing "cast-and-yield casts then yields (resolves via priority system)"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          app-db (merge (history/init-history)
                        {:game/db db'
                         :game/selected-card obj-id})
          result (dispatch-event app-db [::priority-flow/cast-and-yield])]
      (is (= :graveyard (:object/zone (q/get-object (:game/db result) obj-id)))
          "Dark Ritual should be in graveyard after cast-and-yield")
      (is (= 3 (:black (q/get-mana-pool (:game/db result) :player-1)))
          "Should have 3 black mana after resolving Dark Ritual"))))


(deftest cast-and-yield-with-selection-does-not-yield
  (testing "cast-and-yield with pending selection does not dispatch yield"
    (let [db (-> (h/create-test-db {:mana {:blue 1 :black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :brain-freeze :hand :player-1)
          app-db (merge (history/init-history)
                        {:game/db db'
                         :game/selected-card obj-id})
          result (dispatch-event app-db [::priority-flow/cast-and-yield])]
      ;; Brain Freeze has targeting — should show targeting selection, not yield
      (is (some? (:game/pending-selection result))
          "Should have pending selection for targeted spell")
      (is (empty? (q/get-all-stack-items (:game/db result)))
          "Spell should not be on stack yet (targeting is pre-cast)"))))


;; === Integration Tests (Step Group 10) ===

(deftest integration-cast-yield-resolve-mana
  (testing "Full cast -> yield -> resolve -> mana added flow"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast spell (puts on stack, spends mana)
          app-db (merge (history/init-history)
                        {:game/db (rules/cast-spell db' :player-1 obj-id)})
          ;; First yield: human passes, priority transfers to bot
          result1 (dispatch-event app-db [::priority-flow/yield])
          ;; Second yield: bot passes, both passed, spell resolves
          result2 (dispatch-event result1 [::priority-flow/yield])
          result-db (:game/db result2)]
      ;; Spell should have resolved
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "Dark Ritual should be in graveyard after resolution")
      (is (= 3 (:black (q/get-mana-pool result-db :player-1)))
          "Should have BBB from resolved Dark Ritual")
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should stay in main1 (stop is set)")
      ;; History should have an entry
      (is (< 0 (count (history/effective-entries result2)))
          "Should have created history entries"))))


(deftest integration-f6-through-full-turn-cycle
  (testing "F6 (yield-all on empty stack) advances through full turn cycle"
    (let [app-db (merge (history/init-history)
                        (setup-app-db))
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should advance to turn 3 (player T1 -> opponent T2 -> player T3)")
      (is (nil? (priority/get-auto-mode result-db))
          "Auto-mode should be cleared after F6")
      ;; History should have entries for the turn transition
      (is (< 0 (count (history/effective-entries result)))
          "Should have created history entries for turn transition"))))


(deftest integration-stop-toggle-updates-game-state
  (testing "toggle-stop event updates player stops in game-db"
    (let [app-db (merge (history/init-history)
                        (setup-app-db {:stops #{:main1 :main2}}))
          ;; Toggle off main2
          result1 (dispatch-event app-db [::ui-events/toggle-stop :player :main2])
          stops1 (:player/stops (d/pull (:game/db result1) [:player/stops]
                                        (q/get-player-eid (:game/db result1) :player-1)))]
      (is (= #{:main1} stops1)
          "Should have removed main2 from stops")
      ;; Toggle on combat
      (let [result2 (dispatch-event result1 [::ui-events/toggle-stop :player :combat])
            stops2 (:player/stops (d/pull (:game/db result2) [:player/stops]
                                          (q/get-player-eid (:game/db result2) :player-1)))]
        (is (= #{:main1 :combat} stops2)
            "Should have added combat to stops")))))


(deftest integration-stop-toggle-opponent-updates-game-state
  (testing "toggle-stop :opponent updates opponent stops in game-db"
    (let [app-db (merge (history/init-history)
                        (setup-app-db {:stops #{:main1 :main2}}))
          ;; Goldfish bot has auto-derived stops #{:main1}
          ;; Toggle on end for opponent
          result1 (dispatch-event app-db [::ui-events/toggle-stop :opponent :end])
          opp-eid (q/get-player-eid (:game/db result1) :player-2)
          stops1 (:player/stops (d/pull (:game/db result1) [:player/stops] opp-eid))]
      (is (= #{:main1 :end} stops1)
          "Should have added end to opponent stops (main1 from bot spec)")
      ;; Toggle off end for opponent
      (let [result2 (dispatch-event result1 [::ui-events/toggle-stop :opponent :end])
            stops2 (:player/stops (d/pull (:game/db result2) [:player/stops] opp-eid))]
        (is (= #{:main1} stops2)
            "Should have removed end from opponent stops (main1 remains from bot spec)")))))


;; === Goldfish bot plays land integration tests ===

(deftest goldfish-plays-land-on-main1
  (testing "Goldfish bot plays a land from hand during its main1 phase"
    (let [;; Start at main2 so yield-all takes us through opponent turn
          db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _] (h/add-card-to-zone db :plains :hand :player-2)
          ;; Advance to main2 manually
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; After full turn cycle, opponent should have played the land
      (is (= 1 (count (q/get-objects-in-zone result-db :player-2 :battlefield)))
          "Opponent should have 1 land on battlefield")
      (is (= 0 (count (q/get-hand result-db :player-2)))
          "Opponent hand should be empty after playing land"))))


(deftest goldfish-no-land-in-hand-passes
  (testing "Goldfish bot with no land in hand just passes through main1"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Advance to main2 manually (no lands in opponent hand)
          game-db (-> db
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; Should complete turn cycle normally
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should advance to turn 3 even without land to play")
      (is (= 0 (count (q/get-objects-in-zone result-db :player-2 :battlefield)))
          "Opponent should have no permanents (no land was played)"))))


(deftest goldfish-full-turn-cycle-with-library
  (testing "Full turn cycle: goldfish draws card and plays land"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add lands to opponent library (draw step will draw one)
          [db' _] (h/add-cards-to-library db [:plains :island :swamp] :player-2)
          ;; Advance to main2
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; Opponent should have drawn 1 card and played it as a land
      (is (= 1 (count (q/get-objects-in-zone result-db :player-2 :battlefield)))
          "Opponent should have played 1 land on battlefield")
      (is (= 2 (count (q/get-objects-in-zone result-db :player-2 :library)))
          "Opponent library should have 2 cards remaining (drew 1 of 3)")
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should be turn 3 after full cycle"))))


;; === Bot turn history tests ===
;; Verify that bot turns create multiple history entries (one per ::yield re-dispatch)
;; rather than a single entry for the entire turn.

(deftest bot-turn-creates-multiple-history-entries
  (testing "Opponent turn creates separate history entries for each phase"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _] (h/add-cards-to-library db [:plains :island :swamp] :player-2)
          ;; Start at main2 so yield-all crosses turn boundary into opponent turn
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)]
      ;; There should be multiple entries, not just 1 for the whole cycle.
      ;; yield-all from main2 -> through opponent turn -> player turn 3 main1
      ;; Minimum: at least one for each ::yield re-dispatch during opponent phases
      (is (< 1 (count entries))
          "Should have multiple history entries for the turn cycle"))))


(deftest bot-turn-history-includes-phase-advance-entries
  (testing "Bot phase advances create history entries (same as human)"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _] (h/add-cards-to-library db [:plains :island :swamp] :player-2)
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)]
      ;; Bot phase advances now create entries (no more bot noise filtering)
      (let [turn-2-entries (filterv #(= 2 (:entry/turn %)) entries)]
        (is (pos? (count turn-2-entries))
            "Bot turn should have history entries for phase advances")))))


(deftest bot-turn-history-replay-works-with-filtered-entries
  (testing "Stepping through history works correctly with filtered bot entries"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _] (h/add-cards-to-library db [:plains :island :swamp] :player-2)
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)]
      ;; History should still have entries (human turn entries at minimum)
      (is (pos? (count entries))
          "Should have at least one history entry")
      ;; Each entry has a valid snapshot for replay
      (doseq [entry entries]
        (is (some? (:entry/snapshot entry))
            "Each entry should have a snapshot for replay")))))


(deftest bot-turn-no-actions-produces-entries
  (testing "Bot turn without actions still produces phase-advance history entries"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; No cards in opponent library or hand — bot has nothing to do
          game-db (-> db
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)
          turn-2-entries (filterv #(= 2 (:entry/turn %)) entries)]
      ;; Bot phase advances create entries (no more filtering)
      (is (pos? (count turn-2-entries))
          "Bot turn should have history entries"))))


;; === Human stops during opponent (bot) turn ===

(deftest bot-turn-respects-human-opponent-stop
  (testing "Opponent turn pauses at phase where bot has a stop configured"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish :stops #{:draw}}))
          ;; Advance to main2 — last player stop before turn boundary
          game-db (-> db
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1)) ; combat -> main2
          app-db {:game/db game-db}
          ;; Loop yield-impl + bot-decide until cascade settles.
          ;; yield-impl handles both player types: bot passes via interceptor sim,
          ;; human passes via yield-impl calls.
          final (loop [adb app-db n 50]
                  (if (zero? n) adb
                      (let [result (priority-flow/yield-impl adb)]
                        (if (:continue-yield? result)
                          (recur (:app-db result) (dec n))
                          ;; No continue — simulate bot interceptor if bot holds priority
                          (let [result-adb (:app-db result)
                                gdb (:game/db result-adb)]
                            (if (and gdb (bot-interceptor/bot-should-act? gdb))
                              (let [effects (bot-interceptor/bot-decide-handler result-adb)
                                    new-adb (or (:db effects) result-adb)]
                                (recur new-adb (dec n)))
                              ;; Not bot's turn to act — run one more yield for human pass
                              ;; then check if we've settled
                              (let [result2 (priority-flow/yield-impl result-adb)]
                                (if (:continue-yield? result2)
                                  (recur (:app-db result2) (dec n))
                                  (:app-db result2)))))))))
          result-db (:game/db final)]
      (is (= :draw (:game/phase (q/get-game-state result-db)))
          "Should stop at draw phase on opponent's turn")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should be opponent's turn (turn 2)"))))


(deftest human-yield-at-opponent-stop-advances-to-next-stop
  (testing "Human yielding from an opponent-turn stop advances to next stop, not one phase"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish :stops #{:draw :end}}))
          ;; Set up: bot's turn at draw phase, human holds priority (stopped at draw)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          human-eid (q/get-player-eid db :player-1)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority human-eid]
                                 [:db/add game-eid :game/phase :draw]
                                 [:db/add game-eid :game/turn 2]])
          app-db {:game/db game-db}
          ;; First yield: human passes, priority transfers to bot
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes, both passed, advance-with-stops to next stop
          ;; Bot has stops #{:draw :end}, currently at draw → next stop is :end
          result2 (priority-flow/yield-impl (:app-db result1))
          result-db (:game/db (:app-db result2))]
      (is (= :end (:game/phase (q/get-game-state result-db)))
          "Should advance to next opponent stop (:end), not just one phase (:main1)")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should still be opponent's turn"))))


(deftest bot-turn-f6-ignores-opponent-stops
  (testing "F6 mode advances through opponent's turn ignoring human stops"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish :stops #{:draw}}))
          game-db (-> db
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1)) ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          ;; yield-all sets F6 mode, which should skip all stops
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "F6 should advance through opponent's turn to player's main1")
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should be player's turn 3 (past opponent's turn 2)"))))


;; === negotiate-priority tests (moved from game_loop_test) ===

(deftest negotiate-priority-normal-mode-transfers-to-bot
  (testing "bot opponent gets priority transfer in normal mode (no auto-pass)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Should not be all-passed — priority transfers to bot")
      (let [result-db (:game/db (:app-db result))
            holder-eid (priority/get-priority-holder-eid result-db)
            opp-eid (q/get-player-eid result-db :player-2)]
        (is (= opp-eid holder-eid)
            "Priority should be transferred to bot")))))


(deftest negotiate-priority-auto-mode-both-pass
  (testing "both pass in auto-mode (:resolving)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish})
                 (priority/set-auto-mode :resolving))
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)]
      (is (true? (:all-passed? result))))))


(deftest negotiate-priority-bot-turn-transfers-priority
  (testing "bot turn without auto-mode transfers priority to human"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Without auto-mode, priority transfers — not all-passed"))))


(deftest negotiate-priority-single-player-one-pass-suffices
  (testing "single player: one pass suffices"
    (let [db (h/create-test-db {:stops #{:main1}})
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)]
      (is (true? (:all-passed? result))
          "Single player should have all-passed after one pass"))))


(deftest negotiate-priority-transfers-when-not-all-passed
  (testing "transfers priority when opponent hasn't passed"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {}))
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Should not be all-passed when opponent is human and didn't pass"))))


(deftest negotiate-priority-returns-app-db-with-reset-passes
  (testing "resets passes in returned app-db when all passed (auto-mode)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish})
                 (priority/set-auto-mode :resolving))
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)
          result-db (:game/db (:app-db result))]
      (is (true? (:all-passed? result))
          "Should be all-passed in auto-mode")
      (is (empty? (priority/get-passed-eids result-db))
          "Passes should be reset after all-passed"))))


(deftest negotiate-priority-human-not-auto-passed-when-stack-non-empty-on-bot-turn
  (testing "human is NOT auto-passed when stack is non-empty during bot turn"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db game-db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Human should get priority when stack is non-empty during bot turn"))))


(deftest negotiate-priority-bot-turn-empty-stack-transfers-priority
  (testing "priority transfers during bot turn with empty stack (no auto-pass without auto-mode)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Without auto-mode, priority transfers even with empty stack"))))


(deftest negotiate-priority-auto-mode-overrides-stack-check
  (testing "auto-mode overrides stack-non-empty check during bot turn"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (-> (rules/cast-spell db' :player-1 obj-id)
                      (priority/set-auto-mode :resolving))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]])
          app-db {:game/db game-db}
          result (priority-flow/negotiate-priority app-db)]
      (is (true? (:all-passed? result))
          "Auto-mode (F6/resolving) should still auto-pass even with stack non-empty"))))


(deftest negotiate-priority-human-not-auto-passed-when-bot-holds-priority-on-human-turn
  (testing "human is NOT auto-passed when bot holds priority during human turn with non-empty stack"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          ;; Human is active player (default) — it's the human's turn
          ;; Transfer priority to the bot (simulating bot received priority and cast)
          opp-eid (q/get-player-eid game-db :player-2)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          game-db (d/db-with game-db [[:db/add game-eid :game/priority opp-eid]])
          app-db {:game/db game-db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Human should get priority to respond when bot passes during human turn with stack"))))


;; === Unique yield-impl tests (moved from game_loop_test) ===

(deftest yield-impl-clears-resolving-auto-mode-when-stack-empties
  (testing "yield-impl clears :resolving auto-mode when stack empties"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (-> (rules/cast-spell db' :player-1 obj-id)
                      (priority/set-auto-mode :resolving))
          app-db {:game/db game-db}
          result (priority-flow/yield-impl app-db)]
      (is (nil? (priority/get-auto-mode (:game/db (:app-db result))))
          "Auto-mode should be cleared when stack empties during :resolving"))))


(deftest yield-impl-bot-turn-advances-to-stop
  (testing "yield-impl during bot turn advances to bot's configured stop"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch active player to bot, start at untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]
                                 [:db/add game-eid :game/phase :untap]])
          app-db {:game/db game-db}
          ;; First yield: bot passes (no auto-mode), priority transfers to human
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: human passes, both passed, advance-with-stops to bot's main1 stop
          result2 (priority-flow/yield-impl (:app-db result1))
          result-db (:game/db (:app-db result2))]
      ;; Goldfish has stops #{:main1} — advance-with-stops pauses at main1
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should advance to main1 (bot's configured stop)")
      (is (not (:continue-yield? result2))
          "Should pause at bot's stop for bot interceptor to fire"))))


(deftest yield-impl-cleanup-discard-returns-pending-selection
  (testing "cleanup discard returns pending-selection"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add 8 cards to hand (need to discard 1 at cleanup)
          [db' _] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db' _] (h/add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Advance to end phase (one before cleanup)
          game-db (-> db'
                      (phases/advance-phase :player-1)   ; main1 -> combat
                      (phases/advance-phase :player-1)   ; combat -> main2
                      (phases/advance-phase :player-1))  ; main2 -> end
          app-db {:game/db game-db}
          ;; First yield: human passes, priority transfers to bot
          result1 (priority-flow/yield-impl app-db)
          ;; Second yield: bot passes, both passed, advance to cleanup with discard
          result2 (priority-flow/yield-impl (:app-db result1))]
      (is (some? (:game/pending-selection (:app-db result2)))
          "Should return pending-selection for cleanup discard"))))


(deftest yield-impl-bot-turn-stops-at-configured-stop
  (testing "yield-impl during bot turn uses advance-with-stops, pausing at bot's configured stop"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch to bot's turn at untap phase
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]
                                 [:db/add game-eid :game/phase :untap]])
          app-db {:game/db game-db}
          ;; First yield: bot passes, priority transfers to human
          result-1 (priority-flow/yield-impl app-db)
          ;; Second yield: human passes, both passed, advance-with-stops
          ;; Goldfish has stops #{:main1}, so advance batches untap→main1
          result-2 (priority-flow/yield-impl (:app-db result-1))
          phase-after (:game/phase (q/get-game-state (:game/db (:app-db result-2))))]
      (is (= :main1 phase-after)
          "Should advance to main1 (goldfish's configured stop)")
      (is (not (:continue-yield? result-2))
          "Should pause at bot's stop for bot interceptor"))))


(deftest yield-impl-bot-phase-does-not-cast-spells
  (testing "yield-impl during bot turn does not cast spells (only phase actions)"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]])
          app-db {:game/db game-db}
          result (priority-flow/yield-impl app-db)
          result-db (:game/db (:app-db result))]
      (is (q/stack-empty? result-db)
          "Bot phase should not put anything on stack"))))


;; === Reactive bot priority tests ===
;; Tests for negotiate-priority consulting bot protocol before auto-passing.

(def ^:private reactive-test-spec
  "Test-only bot spec with a reactive priority rule.
   Responds to opponent spells on stack by casting lightning-bolt."
  {:bot/name "Reactive Test"
   :bot/priority-rules
   [{:rule/mode :auto
     :rule/conditions [{:check :stack-has :type :spell :owner :opponent}]
     :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}]})


(defn- with-reactive-spec
  "Execute body with :reactive-test temporarily registered in the bot spec registry."
  [f]
  (let [original-get-spec definitions/get-spec]
    (with-redefs [definitions/get-spec
                  (fn [arch]
                    (if (= :reactive-test arch)
                      reactive-test-spec
                      (original-get-spec arch)))]
      (f))))


(deftest negotiate-priority-reactive-bot-gets-priority
  (testing "reactive bot with matching conditions gets priority (not auto-passed)"
    (with-reactive-spec
      (fn []
        (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                     (h/add-opponent {:bot-archetype :reactive-test}))
              [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
              ;; Add a bolt + mountain in opponent hand/battlefield for resolve-action
              [db' _] (h/add-card-to-zone db' :lightning-bolt :hand :player-2)
              [db' _] (h/add-card-to-zone db' :mountain :battlefield :player-2)
              game-db (rules/cast-spell db' :player-1 obj-id)
              app-db {:game/db game-db}
              result (priority-flow/negotiate-priority app-db)]
          (is (false? (:all-passed? result))
              "Reactive bot should get priority when opponent spell on stack"))))))


(deftest negotiate-priority-goldfish-transfers-priority
  (testing "goldfish bot gets priority transfer (no auto-pass without auto-mode)"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          app-db {:game/db game-db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Without auto-mode, priority transfers to bot (not auto-passed)"))))


(deftest negotiate-priority-burn-non-matching-transfers-priority
  (testing "burn bot gets priority transfer (no auto-pass without auto-mode)"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                 (h/add-opponent {:bot-archetype :burn}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)
          app-db {:game/db game-db}
          result (priority-flow/negotiate-priority app-db)]
      (is (false? (:all-passed? result))
          "Without auto-mode, priority transfers to bot"))))


(deftest negotiate-priority-reactive-bot-empty-stack-transfers-priority
  (testing "reactive bot gets priority transfer when stack is empty (no auto-pass)"
    (with-reactive-spec
      (fn []
        (let [db (-> (h/create-test-db {:stops #{:main1}})
                     (h/add-opponent {:bot-archetype :reactive-test}))
              app-db {:game/db db}
              result (priority-flow/negotiate-priority app-db)]
          (is (false? (:all-passed? result))
              "Without auto-mode, priority transfers to bot"))))))


(deftest negotiate-priority-auto-mode-bypasses-bot-protocol
  (testing "auto-mode (:resolving) bypasses bot protocol check"
    (with-reactive-spec
      (fn []
        (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                     (h/add-opponent {:bot-archetype :reactive-test}))
              [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
              [db' _] (h/add-card-to-zone db' :lightning-bolt :hand :player-2)
              [db' _] (h/add-card-to-zone db' :mountain :battlefield :player-2)
              game-db (-> (rules/cast-spell db' :player-1 obj-id)
                          (priority/set-auto-mode :resolving))
              app-db {:game/db game-db}
              result (priority-flow/negotiate-priority app-db)]
          (is (true? (:all-passed? result))
              "Auto-mode should bypass bot protocol and auto-pass"))))))


(deftest negotiate-priority-reactive-bot-card-not-in-hand-transfers-priority
  (testing "reactive bot gets priority transfer even when card not in hand (no auto-pass)"
    (with-reactive-spec
      (fn []
        (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1}})
                     (h/add-opponent {:bot-archetype :reactive-test}))
              [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
              game-db (rules/cast-spell db' :player-1 obj-id)
              app-db {:game/db game-db}
              result (priority-flow/negotiate-priority app-db)]
          (is (false? (:all-passed? result))
              "Without auto-mode, priority transfers to bot"))))))


;; === Bot stops derivation tests ===

(deftest bot-stops-derived-from-phase-actions
  (testing "bot-stops derives stops from phase-actions keys in bot spec"
    (is (= #{:main1} (bot-protocol/bot-stops :goldfish))
        "Goldfish has {:main1 :play-land} → stops at #{:main1}")
    (is (= #{:main1} (bot-protocol/bot-stops :burn))
        "Burn has {:main1 :play-land} → stops at #{:main1}")
    (is (= #{} (bot-protocol/bot-stops :nonexistent))
        "Unknown archetype returns empty stops")))


(deftest game-init-sets-bot-stops-from-spec
  (testing "init-game-state sets bot player stops from bot spec phase-actions"
    (let [app-db (h/create-test-db {:stops #{:main1 :main2}})
          db (h/add-opponent app-db {:bot-archetype :goldfish})
          opp-eid (q/get-player-eid db :player-2)
          opp-stops (:player/stops (d/pull db [:player/stops] opp-eid))]
      (is (= #{:main1} opp-stops)
          "Goldfish bot should have stops #{:main1} derived from phase-actions"))))


(deftest burn-bot-casts-at-main1-stop
  (testing "Burn bot gets opportunity to cast at main1 during its turn"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2} :life 20})
                 (h/add-opponent {:bot-archetype :burn}))
          ;; Add mountain to bot battlefield and bolt to bot hand
          [db' _] (h/add-card-to-zone db :mountain :battlefield :player-2)
          [db' _] (h/add-card-to-zone db' :lightning-bolt :hand :player-2)
          ;; Advance to main2 so yield-all crosses to bot turn
          game-db (-> db'
                      (phases/advance-phase :player-1)   ; main1 -> combat
                      (phases/advance-phase :player-1))   ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; After full cycle including bot turn, bot should have cast bolt
      ;; (player took 3 damage from bolt)
      (is (> 20 (:player/life (d/pull result-db [:player/life]
                                      (q/get-player-eid result-db :player-1))))
          "Human should have taken damage from burn bot's bolt"))))


;; === advance-with-stops: opponent-stops on human player ===

(deftest advance-with-stops-pauses-at-human-opponent-stop-on-bot-turn
  (testing "advance-with-stops pauses when human has opponent-stop at current bot phase"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Set human's opponent-stops to #{:upkeep}
          human-eid (q/get-player-eid db :player-1)
          game-db (priority/set-opponent-stops db human-eid #{:upkeep})
          ;; Switch active player to bot, starting from untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/priority opp-eid]
                                      [:db/add game-eid :game/phase :untap]])
          app-db {:game/db game-db}
          ;; advance-with-stops: ignore-stops? false, ignore-opponent-stops? false
          result (priority-flow/advance-with-stops app-db false false)
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      (is (= :upkeep result-phase)
          "advance-with-stops should pause at :upkeep because human has opponent-stop there"))))


(deftest advance-with-stops-no-opponent-stop-passes-through-phase
  (testing "advance-with-stops does NOT pause when human has no opponent-stop at the phase"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Human has empty opponent-stops
          human-eid (q/get-player-eid db :player-1)
          game-db (priority/set-opponent-stops db human-eid #{})
          ;; Bot's turn — goldfish has stops #{:main1}
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/priority opp-eid]
                                      [:db/add game-eid :game/phase :untap]])
          app-db {:game/db game-db}
          result (priority-flow/advance-with-stops app-db false false)
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      (is (= :main1 result-phase)
          "Should advance past upkeep/draw to main1 (no opponent-stop, only bot's own stop)"))))


(deftest advance-with-stops-ignore-opponent-stops-skips-human-opponent-stop
  (testing "advance-with-stops ignores human's opponent-stops when ignore-opponent-stops? is true (F6)"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Human has opponent-stop at upkeep
          human-eid (q/get-player-eid db :player-1)
          game-db (priority/set-opponent-stops db human-eid #{:upkeep})
          ;; Bot's turn at untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/priority opp-eid]
                                      [:db/add game-eid :game/phase :untap]])
          app-db {:game/db game-db}
          ;; ignore-opponent-stops? = true (F6 mode)
          result (priority-flow/advance-with-stops app-db false true)
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      (is (= :main1 result-phase)
          "F6 should skip human's opponent-stop at :upkeep, advance to bot's :main1 stop"))))


(deftest advance-with-stops-nil-opponent-stops-does-not-pause
  (testing "advance-with-stops is safe when human has no :player/opponent-stops (nil)"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Human has NO opponent-stops attribute set (nil)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]
                                 [:db/add game-eid :game/phase :untap]])
          app-db {:game/db game-db}
          result (priority-flow/advance-with-stops app-db false false)
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      (is (= :main1 result-phase)
          "Should safely advance to main1 without throwing when opponent-stops is nil"))))
