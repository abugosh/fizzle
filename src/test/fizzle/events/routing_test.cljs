(ns fizzle.events.routing-test
  "Integration tests for screen routing via production re-frame dispatch.

   Tests drive ::ui/set-active-screen through rf/dispatch-sync to verify:
   1. Screen transitions work end-to-end (event registered, handler correct)
   2. Game state is preserved across transitions
   3. Selection state is preserved across transitions

   Bug caught: if set-active-screen handler is incorrectly registered (wrong
   event keyword, reg-event-fx vs reg-event-db) or mutates game state,
   these tests catch it. The deleted test was tautological (called handler
   directly instead of dispatching through re-frame).

   Hard cap: max 4 tests — set-active-screen is a trivial 1-line assoc."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.events.ui :as ui]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register interceptors for dispatch-sync tests
(interceptor/register!)
(db-effect/register!)


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db.
   Resets rf-db/app-db before dispatch for test isolation."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; Test 1: Basic screen transition via dispatch
(deftest set-active-screen-routes-to-game
  (testing "dispatch-sync ::set-active-screen :game sets :active-screen to :game"
    ;; Bug caught: if set-active-screen event handler is not registered or uses
    ;; wrong event keyword, :active-screen would not change to :game.
    (let [app-db (merge (history/init-history)
                        {:active-screen :setup
                         :game/db (th/create-test-db)})
          result (dispatch-event app-db [::ui/set-active-screen :game])]
      (is (= :game (:active-screen result))
          "Active screen should be :game after dispatching set-active-screen"))))


;; Test 2: game/db is preserved during screen transition
(deftest set-active-screen-preserves-game-db-state
  (testing "::set-active-screen does not mutate :game/db"
    ;; Bug caught: if handler incorrectly uses reg-event-fx and replaces db
    ;; rather than merging, game state would be lost during screen transition.
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish})
          game-db-before (:game/db app-db)
          app-db' (assoc app-db :active-screen :setup)
          result (dispatch-event app-db' [::ui/set-active-screen :game])
          game-db-after (:game/db result)]
      (is (= :game (:active-screen result))
          "Screen should transition to :game")
      (is (identical? game-db-before game-db-after)
          ":game/db should be identical (not mutated) after screen transition"))))


;; Test 3: pending-selection is preserved during screen transition
(deftest set-active-screen-preserves-pending-selection
  (testing "::set-active-screen does not clear :game/pending-selection"
    ;; Bug caught: if the handler assocs game state instead of just the screen,
    ;; an in-progress selection would be lost when the screen changes.
    ;; Example: player opens graveyard view mid-cast (future UI feature).
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish})
          sel (sel-spec/minimal-valid-selections :discard)
          app-db' (-> app-db
                      (assoc :active-screen :game)
                      (sel-spec/set-pending-selection sel))
          result (dispatch-event app-db' [::ui/set-active-screen :setup])
          sel-after (:game/pending-selection result)]
      (is (= :setup (:active-screen result))
          "Screen should transition to :setup")
      (is (some? sel-after)
          ":game/pending-selection should be preserved after screen transition")
      (is (= :discard (:selection/type sel-after))
          ":game/pending-selection type should remain :discard"))))


;; Test 4: Unrecognized screen stored as-is (handler is pure assoc, no guard)
(deftest set-active-screen-stores-arbitrary-keyword
  (testing "::set-active-screen stores any keyword screen without error"
    ;; Bug caught: if handler has an allowlist guard or throws on unknown screens,
    ;; new screen keywords added to the app would break silently.
    (let [app-db (merge (history/init-history)
                        {:active-screen :setup
                         :game/db (th/create-test-db)})
          result (dispatch-event app-db [::ui/set-active-screen :calculator])]
      (is (= :calculator (:active-screen result))
          "Any keyword screen should be stored without guard or error"))))
