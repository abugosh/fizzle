(ns fizzle.history.principal-integration-test
  "Integration tests for history principal-determination and global interceptor.

   These tests drive real re-frame events via rf/dispatch-sync and assert that:
   1. :entry/principal is correctly set per event type (principal-determination logic)
   2. The global interceptor registration path (interceptor/register!) actually works
      — interceptor.cljs:28-31 reg-global-interceptor path was never exercised by tests.

   Bug caught: if principal-determination logic in a handler changed to return nil or
   wrong player-id, or if interceptor/register! is broken, these tests would fail.

   Pattern follows: events/integration/casting_test.cljs"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.lands :as lands]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register global interceptor so :history/pending-entry is consumed on dispatch-sync.
;; This exercises interceptor.cljs:28-31 reg-global-interceptor path.
;; Without this call, :history/main remains empty after every dispatch-sync.
(interceptor/register!)


;; Register SBA db-effect handler so game-db mutations trigger SBAs.
(db-effect/register!)


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db.
   Resets rf-db/app-db before dispatch for test isolation."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; ============================================================
;; Area 1: Principal-determination via real dispatch
;; Each test exercises a different event handler's principal-setting logic.
;; ============================================================

(deftest cast-spell-history-entry-principal-is-caster
  (testing "::cast-spell sets :entry/principal to the casting player-id"
    ;; Bug caught: if casting.cljs stops setting :principal in the pending-entry,
    ;; the history entry would have nil principal. The deleted principal_test.cljs
    ;; bypassed this by hand-building the pending-entry map directly.
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish
                                           :mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          _ (is (rules/can-cast? game-db' :player-1 obj-id)
                "Precondition: can-cast? must be true for Dark Ritual with 1 black mana")
          result (dispatch-event app-db' [::casting/cast-spell])
          entries (:history/main result)]
      (is (= 1 (count entries))
          "Exactly 1 history entry should be appended after cast-spell dispatch")
      (is (= :player-1 (:entry/principal (first entries)))
          "History entry principal should be :player-1 (the caster)"))))


(deftest play-land-history-entry-principal-is-active-player
  (testing "::play-land sets :entry/principal to the player who played the land"
    ;; Bug caught: if lands.cljs play-land handler omits principal from pending-entry,
    ;; the history entry would have nil principal. The deleted principal_test.cljs
    ;; never exercised the lands handler dispatch path.
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish})
          game-db (:game/db app-db)
          [game-db' obj-id] (th/add-card-to-zone game-db :island :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          _ (is (rules/can-play-land? game-db' :player-1 obj-id)
                "Precondition: can-play-land? must be true for an island in hand")
          result (dispatch-event app-db' [::lands/play-land obj-id :player-1])
          entries (:history/main result)]
      (is (= 1 (count entries))
          "Exactly 1 history entry should be appended after play-land dispatch")
      (is (= :player-1 (:entry/principal (first entries)))
          "History entry principal should be :player-1 (the player who played the land)"))))


(deftest activate-mana-ability-history-entry-principal-is-activating-player
  (testing "::activate-mana-ability sets :entry/principal to the activating player"
    ;; Bug caught: if abilities.cljs activate-mana-ability handler omits principal
    ;; from pending-entry, the history entry would have nil principal.
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish})
          game-db (:game/db app-db)
          [game-db' land-id] (th/add-card-to-zone game-db :island :battlefield :player-1)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::abilities/activate-mana-ability land-id :blue :player-1])
          entries (:history/main result)]
      (is (= 1 (count entries))
          "Exactly 1 history entry should be appended after activate-mana-ability dispatch")
      (is (= :player-1 (:entry/principal (first entries)))
          "History entry principal should be :player-1 (the ability activator)"))))


;; ============================================================
;; Area 2: Global interceptor registration path
;; Verifies interceptor/register! (interceptor.cljs:28-31) actually wires up the
;; global re-frame interceptor — not just that the :after fn works in isolation.
;; ============================================================

(deftest cast-spell-creates-history-entry-via-global-interceptor
  (testing "dispatch-sync cast-spell creates entry in :history/main via registered global interceptor"
    ;; Bug caught: if interceptor/register! is broken or the history-interceptor's
    ;; :after fn is not wired into re-frame's global interceptors,
    ;; :history/main remains empty even after a valid cast-spell dispatch.
    ;; The existing interceptor_test.cljs calls run-interceptor directly (bypasses this path).
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish
                                           :mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          result (dispatch-event app-db' [::casting/cast-spell])
          entries (:history/main result)
          entry (first entries)]
      (is (= 1 (count entries))
          "Global interceptor must append 1 entry — empty means interceptor/register! is broken")
      (is (string? (:entry/description entry))
          "Entry description should be a string (from describe-cast-spell)")
      (is (pos? (count (:entry/description entry)))
          "Entry description should be non-empty")
      (is (= :player-1 (:entry/principal entry))
          "Entry principal set by casting handler, consumed by global interceptor"))))
