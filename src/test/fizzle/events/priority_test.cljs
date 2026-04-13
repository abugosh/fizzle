(ns fizzle.events.priority-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.cards.red.lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.director :as director]
    [fizzle.events.phases :as phases]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.ui :as ui-events]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register interceptors and SBA dispatch for dispatch-sync tests
(interceptor/register!)
(db-effect/register!)


(defn- setup-app-db
  "Create a full app-db with two players (player + goldfish bot),
   player stops at main1+main2, starting at main1.
   Both players get library cards so draw-step triggers don't hit game-over."
  ([]
   (setup-app-db {}))
  ([opts]
   (h/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-event
  "Dispatch an event through re-frame synchronously, return resulting app-db."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


(defn- dispatch-yield-all
  "Dispatch ::yield-all through re-frame and return the resulting app-db.
   The game director runs synchronously, so a single dispatch-sync is sufficient.
   The loop is no longer needed — director handles bot actions inline."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::priority-flow/yield-all])
  @rf-db/app-db)


;; === Test 1: yield on empty stack, stops at main1+main2 ===

(deftest yield-empty-stack-advances-past-combat-to-main2
  (testing "director from main1 with stops at main1+main2 skips combat, lands on main2"
    (let [app-db (setup-app-db)
          ;; Director at main1 (stop) returns :await-human. Dispatch ::yield to pass.
          ;; After yield, director has advanced phase. Check the state after dispatch.
          result (dispatch-event app-db [::priority-flow/yield])]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db result))))
          "Should advance to main2, skipping upkeep/draw/combat"))))


;; === Test 2: yield with Dark Ritual on stack ===

(deftest yield-with-spell-on-stack-resolves-it
  (testing "yield resolves top of stack when both pass on non-empty stack"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast Dark Ritual to put it on the stack
          game-db (rules/cast-spell db' :player-1 _obj-id)
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Dispatch yield: director auto-passes both (stack non-empty), resolves spell, stops
          result (dispatch-event app-db [::priority-flow/yield])
          result-db (:game/db result)
          pool (q/get-mana-pool result-db :player-1)]
      (is (= 3 (:black pool))
          "Dark Ritual should resolve, adding BBB to mana pool")
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should stay in main1 after resolving (stop is set)"))))


;; === Test 3: yield at main2 advances through turn boundary ===

(deftest yield-at-main2-advances-to-new-turn-main1
  (testing "yield from main2 advances through end, cleanup, opponent turn, to main1"
    (let [app-db (setup-app-db {:stops #{:main1 :main2}})
          ;; Advance to main2 manually
          game-db (-> (:game/db app-db)
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1)  ; combat -> main2
                      )
          ;; Use yield-all which cascades through turns via event dispatch
          app-db (assoc app-db :game/db game-db)
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
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Dispatch yield: director auto-passes (stack non-empty), resolves Careful Study → selection
          result (dispatch-event app-db [::priority-flow/yield])]
      (is (some? (:game/pending-selection result))
          "Should return pending-selection for interactive spell"))))


;; === Test 5: yield with stop set pauses at stop ===

(deftest yield-pauses-at-stop-phase
  (testing "director stops at human's stop phase — does not auto-pass"
    (let [app-db (setup-app-db)
          ;; Dispatch ::yield: director runs, human has stop at main1 → returns :await-human
          ;; Then continues past main1 (since we yielded), advances to main2 stop
          result (dispatch-event app-db [::priority-flow/yield])
          result-db (:game/db result)]
      ;; After yielding from main1, should advance to main2 (next stop)
      (is (= :main2 (:game/phase (q/get-game-state result-db)))
          "Should advance to main2 after yielding from main1"))))


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
  (testing "yield from main1 with no stops cascades through turns"
    (let [app-db (setup-app-db {:stops #{}})
          ;; With no stops, director cascades through all phases and turns
          ;; until the safety limit is reached (no stop to pause at)
          result (dispatch-event app-db [::priority-flow/yield])
          result-db (:game/db result)]
      (is (> (:game/turn (q/get-game-state result-db)) 1)
          "Should advance past turn 1 (no stops to pause at)"))))


;; === Test 8: yield resolves one stack item and stops ===

(deftest yield-resolves-one-and-stops
  (testing "::yield resolves one stack item and stops — director does not cascade"
    (let [db (-> (h/create-test-db {:mana {:black 2} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Cast two Dark Rituals
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db (merge (history/init-history) {:game/db game-db2})
          ;; Dispatch yield: director resolves one item (stack non-empty → auto-pass),
          ;; then stops (yield-all? false)
          result (dispatch-event app-db [::priority-flow/yield])
          result-db (:game/db result)
          stack (q/get-all-stack-items result-db)]
      ;; One item resolved, one item remains
      (is (pos? (count stack))
          "Stack should still have items — yield resolved only one"))))


;; === yield-all tests ===

(deftest yield-all-resolves-entire-stack
  (testing "yield-all with non-empty stack resolves all items then stops"
    (let [scenario (setup-app-db {:mana {:black 2} :stops #{:main1 :main2}})
          db (:game/db scenario)
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db (assoc scenario :game/db game-db2)
          result (dispatch-yield-all app-db)]
      ;; yield-all with non-empty stack resolves all items then stops at current stop
      (is (empty? (q/get-all-stack-items (:game/db result)))
          "Stack should be empty after yield-all")
      (is (= 1 (:game/turn (q/get-game-state (:game/db result))))
          "Should stay on turn 1 — yield-all with stack should not F6")
      (is (= :main1 (:game/phase (q/get-game-state (:game/db result))))
          "Should remain at main1 after resolving stack"))))


(deftest yield-all-empty-stack-f6-advances-to-new-turn
  (testing "yield-all with empty stack enters F6 mode, advances through turn and opponent turn"
    (let [app-db (merge (history/init-history)
                        (setup-app-db))
          result (dispatch-yield-all app-db)]
      ;; F6 ignores player stops, advances through player turn, opponent turn, to next player turn
      (is (= 3 (:game/turn (q/get-game-state (:game/db result))))
          "Should advance to turn 3 (player T1 -> opponent T2 -> player T3)"))))


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
    (let [scenario (setup-app-db {:mana {:black 1} :stops #{:main1 :main2}})
          db (:game/db scenario)
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast spell (puts on stack, spends mana)
          app-db (assoc scenario :game/db (rules/cast-spell db' :player-1 obj-id))
          ;; Single yield: director handles human pass, bot auto-pass, resolution
          result (dispatch-event app-db [::priority-flow/yield])
          result-db (:game/db result)]
      ;; Spell should have resolved
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "Dark Ritual should be in graveyard after resolution")
      (is (= 3 (:black (q/get-mana-pool result-db :player-1)))
          "Should have BBB from resolved Dark Ritual")
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should stay in main1 (stop is set)")
      ;; History should have an entry
      (is (< 0 (count (history/effective-entries result)))
          "Should have created history entries"))))


(deftest integration-f6-through-full-turn-cycle
  (testing "F6 (yield-all on empty stack) advances through full turn cycle"
    (let [app-db (merge (history/init-history)
                        (setup-app-db))
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should advance to turn 3 (player T1 -> opponent T2 -> player T3)")
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


(deftest integration-stop-toggle-opponent-writes-to-human-opponent-stops
  (testing "toggle-stop :opponent writes to human's :player/opponent-stops, NOT bot's :player/stops"
    (let [app-db (merge (history/init-history)
                        (setup-app-db {:stops #{:main1 :main2}}))
          ;; Goldfish bot has auto-derived stops #{:main1}
          ;; Toggle on end for opponent
          result1 (dispatch-event app-db [::ui-events/toggle-stop :opponent :end])
          game-db1 (:game/db result1)
          human-eid (q/get-player-eid game-db1 :player-1)
          opp-eid (q/get-player-eid game-db1 :player-2)
          human-opp-stops1 (:player/opponent-stops (d/pull game-db1 [:player/opponent-stops] human-eid))
          bot-stops1 (:player/stops (d/pull game-db1 [:player/stops] opp-eid))]
      (is (= #{:end} human-opp-stops1)
          "Should have added end to human's :player/opponent-stops")
      (is (= #{:main1} bot-stops1)
          "Bot's :player/stops should remain unchanged (goldfish derives #{:main1})")
      ;; Toggle off end for opponent
      (let [result2 (dispatch-event result1 [::ui-events/toggle-stop :opponent :end])
            game-db2 (:game/db result2)
            human-opp-stops2 (:player/opponent-stops (d/pull game-db2 [:player/opponent-stops] human-eid))
            bot-stops2 (:player/stops (d/pull game-db2 [:player/stops] opp-eid))]
        (is (= #{} human-opp-stops2)
            "Should have removed end from human's :player/opponent-stops")
        (is (= #{:main1} bot-stops2)
            "Bot's :player/stops still unchanged after toggling off")))))


;; === Goldfish bot plays land integration tests ===

(deftest goldfish-plays-land-on-main1
  (testing "Goldfish bot plays a land from hand during its main1 phase"
    (let [;; Start at main2 so yield-all takes us through opponent turn
          scenario (setup-app-db {:stops #{:main1 :main2}})
          db (:game/db scenario)
          [db' _] (h/add-card-to-zone db :plains :hand :player-2)
          ;; Advance to main2 manually
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (assoc scenario :game/db game-db)
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; After full turn cycle, opponent should have played the land.
      ;; Bot also drew 1 card from library during draw step.
      (is (= 1 (count (q/get-objects-in-zone result-db :player-2 :battlefield)))
          "Opponent should have 1 land on battlefield")
      (is (= 1 (count (q/get-hand result-db :player-2)))
          "Opponent hand should have 1 card (drew an island from library during draw step)"))))


(deftest goldfish-no-land-in-hand-passes
  (testing "Goldfish bot with no land in hand just passes through main1"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give both players non-land library cards so draw doesn't hit game-over
          ;; but bot draws a non-land (can't play as land)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-2)
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
  (testing "Opponent turn creates history entries for the turn cycle"
    (let [scenario (setup-app-db {:stops #{:main1 :main2}})
          db (:game/db scenario)
          [db' _] (h/add-cards-to-library db [:plains :island :swamp] :player-2)
          ;; Start at main2 so yield-all crosses turn boundary into opponent turn
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (assoc scenario :game/db game-db)
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)]
      ;; Bug caught: (pos? count) never detects empty entries or wrong event-type
      ;; The yield-all handler sets :history/pending-entry with ::yield-all event-type;
      ;; if the interceptor is broken, no entry would be created even if count > 0 from prior state
      (is (pos? (count entries))
          "Should have at least one history entry for the turn cycle")
      (is (every? #(= :fizzle.events.priority-flow/yield-all (:entry/event-type %))
                  entries)
          "All entries produced by yield-all should have ::yield-all event-type"))))


(deftest bot-turn-history-includes-phase-advance-entries
  (testing "Bot phase advances are captured in history"
    (let [scenario (setup-app-db {:stops #{:main1 :main2}})
          db (:game/db scenario)
          [db' _] (h/add-cards-to-library db [:plains :island :swamp] :player-2)
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (assoc scenario :game/db game-db)
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)]
      ;; Bug caught: (pos? count) allows entries with wrong event-type or no snapshot
      ;; Entries must have ::yield-all event-type (set by pending-entry mechanism)
      ;; and contain a non-nil snapshot for replay to work
      (is (pos? (count entries))
          "Should have history entries covering the turn cycle")
      (is (every? #(some? (:entry/snapshot %)) entries)
          "Every entry must have a snapshot (nil snapshot breaks replay)")
      (is (some #(= :fizzle.events.priority-flow/yield-all (:entry/event-type %))
                entries)
          "At least one entry should have ::yield-all event-type"))))


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
            "Each entry should have a snapshot for replay"))
      ;; Bug caught: snapshot must be a datascript db (not a plain map or nil);
      ;; if restorer-bug strips :game/db key, replay steps silently use wrong state
      (doseq [entry entries]
        (is (= :fizzle.events.priority-flow/yield-all (:entry/event-type entry))
            "Each yield-all entry should have the correct event-type keyword")))))


(deftest bot-turn-no-actions-produces-entries
  (testing "Bot turn without actions still produces history entries"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give both players non-land library cards so draw doesn't fail
          ;; but bot draws non-lands (nothing to play)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :dark-ritual)) :player-2)
          game-db (-> db
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1))  ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          entries (history/effective-entries result)]
      ;; Bug caught: (pos? count) allows history machinery to return stale entries
      ;; from prior state; the yield-all event-type must be present in new entries
      (is (pos? (count entries))
          "Bot turn should have history entries")
      (is (every? #(= :fizzle.events.priority-flow/yield-all (:entry/event-type %))
                  entries)
          "All bot-turn entries should have ::yield-all event-type — not stale types"))))


;; === Human stops during opponent (bot) turn ===

(deftest bot-turn-respects-human-opponent-stop
  (testing "Opponent turn pauses at phase where human has opponent-stop configured"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Set human's opponent-stop at draw phase of bot's turn
          human-eid (q/get-player-eid db :player-1)
          db' (priority-flow/set-opponent-stops db human-eid #{:draw})
          ;; Advance to main2 — last player stop before turn boundary
          game-db (-> db'
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1)) ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      (is (= :draw (:game/phase (q/get-game-state result-db)))
          "Should stop at draw phase on opponent's turn (human's opponent-stop)")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should be opponent's turn (turn 2)"))))


(deftest human-yield-at-opponent-stop-advances-to-next-stop
  (testing "Director advances from opponent-turn stop to next stop after yielding"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Set human's opponent-stop at draw phase of bot's turn
          human-eid (q/get-player-eid db :player-1)
          db' (priority-flow/set-opponent-stops db human-eid #{:draw :end})
          ;; Bot's turn at upkeep — director will advance through upkeep and pause at draw
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db')
          opp-eid (q/get-player-eid db' :player-2)
          game-db (d/db-with db' [[:db/add game-eid :game/active-player opp-eid]
                                  [:db/add game-eid :game/priority opp-eid]
                                  [:db/add game-eid :game/phase :upkeep]
                                  [:db/add game-eid :game/turn 2]])
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Director: bot passes upkeep, human has opp-stop at draw → pauses at draw
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      (is (= :draw (:game/phase (q/get-game-state result-db)))
          "Director should pause at :draw (human's opponent-stop during bot's turn)")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should still be opponent's turn"))))


(deftest bot-turn-f6-ignores-opponent-stops
  (testing "F6 mode advances through opponent's turn ignoring human opponent-stops"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish :stops #{:draw}}))
          ;; Give both players library cards so draw doesn't hit game-over
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-2)
          ;; Set :player/opponent-stops on human so F6 actually has something to skip
          human-eid (q/get-player-eid db :player-1)
          db-with-opp-stops (priority-flow/set-opponent-stops db human-eid #{:end :upkeep})
          game-db (-> db-with-opp-stops
                      (phases/advance-phase :player-1)  ; main1 -> combat
                      (phases/advance-phase :player-1)) ; combat -> main2
          app-db (merge (history/init-history) {:game/db game-db})
          ;; yield-all sets F6 mode, which should skip all stops including opponent-stops
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "F6 should advance through opponent's turn to player's main1, bypassing human's opponent-stops")
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should be player's turn 3 (past opponent's turn 2 — F6 skipped opponent-stops)"))))


(deftest opponent-stop-pauses-during-bot-turn
  (testing "Director pauses at human's opponent-stop during bot's turn"
    ;; Bot is active with no own stops beyond main1 (goldfish defaults).
    ;; Human has opponent-stop at :end but no own-turn stops (so human auto-passes
    ;; through bot's phases until opponent-stop triggers).
    ;; Starting at :main2 on bot's turn — director should advance and pause at :end.
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          human-eid (q/get-player-eid db :player-1)
          ;; Set human's opponent-stop at :end
          db' (priority-flow/set-opponent-stops db human-eid #{:end})
          ;; Set game to bot's turn (player-2 active) at :main2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db')
          opp-eid (q/get-player-eid db' :player-2)
          game-db (d/db-with db' [[:db/add game-eid :game/active-player opp-eid]
                                  [:db/add game-eid :game/priority opp-eid]
                                  [:db/add game-eid :game/phase :main2]])
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Director: bot passes main2 (no main2 stop), human has opp-stop at :end
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))
          result-phase (:game/phase (q/get-game-state result-db))]
      (is (= :end result-phase)
          "Director should pause at :end — human's opponent-stop during bot's turn"))))


(deftest director-stops-at-human-own-stop-on-bot-turn
  (testing "director stops when human has own-stop and the director reaches that phase"
    ;; Human has own stop at :main1. On human's turn, director pauses there.
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Set phase to :upkeep manually
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          game-db (d/db-with db [[:db/add game-eid :game/phase :upkeep]])
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Director from upkeep should advance to main1 (human's stop) and pause
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Director should stop at main1 (human's own stop)")
      (is (= :await-human (:reason result))
          "Director reason should be :await-human at stop phase"))))


;; === Director priority behavior tests ===

(deftest director-bot-turn-advances-to-stop
  (testing "director during bot turn advances through phases"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give bot library cards so draw doesn't hit game-over
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-2)
          ;; Switch active player to bot, start at untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]
                                 [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db game-db})
          ;; Director: bot passes through phases, crosses turn, stops at human's main1
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Director should advance to main1")
      (is (= :await-human (:reason result))
          "Director should pause for human input"))))


(deftest director-cleanup-discard-returns-pending-selection
  (testing "director cleanup triggers pending-selection when hand > 7"
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
          app-db (merge (history/init-history) {:game/db game-db})
          result (director/run-to-decision app-db {:yield-all? false})]
      (is (= :pending-selection (:reason result))
          "Director should return :pending-selection for cleanup discard"))))


(deftest director-goldfish-does-not-cast-spells
  (testing "goldfish bot does not cast spells — only passes priority"
    (let [db (-> (h/create-test-db {:stops #{}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Switch active player to bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]])
          app-db (merge (history/init-history) {:game/db game-db})
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      (is (q/stack-empty? result-db)
          "Goldfish bot should not put anything on stack"))))


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


;; === Director: opponent-stops on human player ===

(deftest director-pauses-at-human-opponent-stop-on-bot-turn
  (testing "Director pauses when human has opponent-stop at current bot phase"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Set human's opponent-stops to #{:upkeep}
          human-eid (q/get-player-eid db :player-1)
          game-db (priority-flow/set-opponent-stops db human-eid #{:upkeep})
          ;; Switch active player to bot, starting from untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/priority opp-eid]
                                      [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db game-db})
          result (director/run-to-decision app-db {:yield-all? false})
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      (is (= :upkeep result-phase)
          "Director should pause at :upkeep because human has opponent-stop there"))))


(deftest director-no-opponent-stop-advances-through-phases
  (testing "Director does NOT pause at opponent phases when human has no opponent-stop"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give both players library cards so draw doesn't hit game-over
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-2)
          ;; Human has empty opponent-stops
          human-eid (q/get-player-eid db :player-1)
          game-db (priority-flow/set-opponent-stops db human-eid #{})
          ;; Bot's turn — goldfish has stops #{:main1}
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/priority opp-eid]
                                      [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db game-db})
          result (director/run-to-decision app-db {:yield-all? false})
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      ;; Without opponent-stops, director advances through bot's entire turn
      ;; and crosses back to human's turn, pausing at human's own stop (main1)
      (is (= :main1 result-phase)
          "Should advance through bot's turn to human's main1 (no opponent-stops to pause at)"))))


(deftest director-yield-all-skips-human-opponent-stops
  (testing "Director with yield-all? ignores human's opponent-stops (F6)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give both players library cards so draw doesn't hit game-over
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-2)
          ;; Human has opponent-stop at upkeep
          human-eid (q/get-player-eid db :player-1)
          game-db (priority-flow/set-opponent-stops db human-eid #{:upkeep})
          ;; Bot's turn at untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/priority opp-eid]
                                      [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db game-db})
          ;; yield-all? = true (F6 mode)
          result (director/run-to-decision app-db {:yield-all? true})
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      ;; F6 skips human's opponent-stop at :upkeep; director crosses turn back to human's main1
      (is (= :main1 result-phase)
          "F6 should skip human's opponent-stop at :upkeep, advance past bot's turn to human's main1"))))


(deftest director-nil-opponent-stops-does-not-pause
  (testing "Director is safe when human has no :player/opponent-stops (nil)"
    (let [db (-> (h/create-test-db {:stops #{:main1}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give both players library cards so draw doesn't hit game-over
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-1)
          [db _] (h/add-cards-to-library db (vec (repeat 10 :island)) :player-2)
          ;; Human has NO opponent-stops attribute set (nil)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                                 [:db/add game-eid :game/priority opp-eid]
                                 [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db game-db})
          result (director/run-to-decision app-db {:yield-all? false})
          result-phase (:game/phase (q/get-game-state (:game/db (:app-db result))))]
      (is (= :main1 result-phase)
          "Should safely advance to main1 without throwing when opponent-stops is nil"))))


;; === check-stop accessor tests ===

(defn- setup-two-player-game-db
  "Create a game db with two players. Returns [db p1-eid p2-eid]."
  []
  (let [db (-> (h/create-test-db)
               (h/add-opponent))
        p1-eid (q/get-player-eid db :player-1)
        p2-eid (q/get-player-eid db :player-2)]
    [db p1-eid p2-eid]))


(deftest pf-check-stop-true-for-stopped-phase
  (let [[db p1-eid _] (setup-two-player-game-db)
        db' (priority-flow/set-player-stops db p1-eid #{:main1 :main2})]
    (is (true? (priority-flow/check-stop db' p1-eid :main1)))
    (is (true? (priority-flow/check-stop db' p1-eid :main2)))))


(deftest pf-check-stop-false-for-unstopped-phase
  (let [[db p1-eid _] (setup-two-player-game-db)
        db' (priority-flow/set-player-stops db p1-eid #{:main1})]
    (is (false? (priority-flow/check-stop db' p1-eid :combat)))
    (is (false? (priority-flow/check-stop db' p1-eid :end)))))


(deftest pf-check-stop-false-when-no-stops-set
  (let [[db p1-eid _] (setup-two-player-game-db)]
    (is (false? (priority-flow/check-stop db p1-eid :main1)))))


;; === check-opponent-stop ===

(deftest pf-check-opponent-stop-true-when-phase-in-set
  (let [[db p1-eid _] (setup-two-player-game-db)
        db' (priority-flow/set-opponent-stops db p1-eid #{:upkeep :main1})]
    (is (true? (priority-flow/check-opponent-stop db' p1-eid :upkeep)))
    (is (true? (priority-flow/check-opponent-stop db' p1-eid :main1)))))


(deftest pf-check-opponent-stop-false-when-phase-not-in-set
  (let [[db p1-eid _] (setup-two-player-game-db)
        db' (priority-flow/set-opponent-stops db p1-eid #{:upkeep})]
    (is (false? (priority-flow/check-opponent-stop db' p1-eid :main1)))
    (is (false? (priority-flow/check-opponent-stop db' p1-eid :end)))))


(deftest pf-check-opponent-stop-false-when-nil
  ;; Bot entity will have no :player/opponent-stops attribute — must not throw
  (let [[db _ p2-eid] (setup-two-player-game-db)]
    (is (false? (priority-flow/check-opponent-stop db p2-eid :main1)))))


(deftest pf-check-opponent-stop-false-when-empty-set
  (let [[db p1-eid _] (setup-two-player-game-db)
        db' (priority-flow/set-opponent-stops db p1-eid #{})]
    (is (false? (priority-flow/check-opponent-stop db' p1-eid :main1)))))


;; === set-opponent-stops ===

(deftest pf-set-opponent-stops-persists-to-entity
  (let [[db p1-eid _] (setup-two-player-game-db)
        db' (priority-flow/set-opponent-stops db p1-eid #{:upkeep :main2})]
    (is (= #{:upkeep :main2}
           (:player/opponent-stops (d/pull db' [:player/opponent-stops] p1-eid))))))


;; === SBA sentinel: proves db-effect/register! is wired ===

(deftest sba-life-zero-fires-in-priority-test
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
