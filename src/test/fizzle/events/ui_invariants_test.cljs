(ns fizzle.events.ui-invariants-test
  "Regression tests for the :game/selected-card post-event interceptor.

   Per ADR-031 (revised 2026-04-26): the interceptor reconciles
   :game/selected-card using an actionability predicate — not a static
   zone set. :game/selected-card is cleared when the referenced card is
   no longer eligible for any user action (cast, play-land, or cycle).

   Key regressions:
   - fizzle-ktba: play-land clears stale selection (::play-land lacks
     a per-handler dissoc; interceptor provides it)
   - gr9a/ktba bug class: non-flashback graveyard card with stale
     selection is cleared (old #{:hand :graveyard} predicate would
     have preserved it — this is the motivating fix for this task)

   Test structure:
   - fizzle-ktba regression: play-land clears selection via interceptor
   - Interceptor no-op: hand card with mana → can-cast? true → preserved
   - Bug-class regression: non-flashback graveyard card → cleared"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.lands :as land-events]
    [fizzle.events.ui-invariants :as ui-invariants]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register interceptors for dispatch-sync tests.
;; All three must be registered: history, ui-invariants, and the SBA db-effect.
(interceptor/register!)
(ui-invariants/register!)
(db-effect/register!)


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; ============================================================
;; fizzle-ktba Regression: play-land clears stale selected-card
;; ============================================================

(deftest play-land-clears-selected-card-via-interceptor
  (testing "::play-land clears :game/selected-card when land moves to battlefield (fizzle-ktba root cause)"
    ;; Setup: land selected in hand, ready to be played.
    ;; ::play-land has no per-handler dissoc for :game/selected-card.
    ;; After play-land, the land is on battlefield (zone not in #{:hand :graveyard}).
    ;; The interceptor should detect the zone change and clear the stale reference.
    (let [base-db (th/create-game-scenario {:bot-archetype :goldfish})
          game-db (:game/db base-db)
          ;; Add an Island to hand
          [game-db land-id] (th/add-card-to-zone game-db :island :hand :player-1)
          ;; Set selected-card to the Island (simulates user clicking a hand card)
          app-db (assoc base-db
                        :game/db game-db
                        :game/selected-card land-id)]
      ;; Precondition: selected-card is set and land is in hand
      (is (= land-id (:game/selected-card app-db))
          "Precondition: :game/selected-card should be set to the land's object-id")
      (is (= :hand (th/get-object-zone game-db land-id))
          "Precondition: land should be in hand")
      ;; Dispatch ::play-land — moves the land to battlefield
      (let [result (dispatch-event app-db [::land-events/play-land land-id])]
        ;; Land should now be on battlefield
        (is (= :battlefield (th/get-object-zone (:game/db result) land-id))
            "Land should be on battlefield after play-land")
        ;; :game/selected-card should be cleared by the interceptor
        ;; (zone=:battlefield not in #{:hand :graveyard})
        (is (nil? (:game/selected-card result))
            ":game/selected-card should be nil after play-land moves land to battlefield (fizzle-ktba)")))))


;; ============================================================
;; Interceptor no-op: selected hand card stays in hand
;; ============================================================

(deftest selected-card-preserved-when-stays-in-hand
  (testing "interceptor preserves :game/selected-card when selected object remains in hand"
    ;; An unrelated game event fires while a hand card is selected.
    ;; The selected card doesn't move — interceptor should NOT clear.
    ;; Dark Ritual costs {black 1} — give player black mana so can-cast? returns true.
    (let [base-db (th/create-game-scenario {:bot-archetype :goldfish :mana {:black 1}})
          game-db (:game/db base-db)
          ;; Add a card to hand (will be the selected card)
          [game-db hand-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          ;; Set selected-card to the hand card (Dark Ritual)
          app-db (assoc base-db
                        :game/db game-db
                        :game/selected-card hand-id)]
      ;; Precondition: hand card is selected
      (is (= hand-id (:game/selected-card app-db))
          "Precondition: :game/selected-card set to hand card")
      ;; Use ::play-land on a SECOND land to fire a game event while
      ;; leaving hand-id (Dark Ritual) in hand.
      ;; After play-land, dark-ritual stays in hand (zone=:hand, can-cast? true) → preserved.
      (let [[game-db land2-id] (th/add-card-to-zone (:game/db app-db) :island :hand :player-1)
            app-db' (assoc app-db :game/db game-db)]
        ;; Play the island (not the selected dark-ritual)
        ;; After play-land, dark-ritual stays in hand (zone=:hand)
        ;; Interceptor checks: dark-ritual can-cast? true → preserve
        (let [result (dispatch-event app-db' [::land-events/play-land land2-id])]
          (is (= :battlefield (th/get-object-zone (:game/db result) land2-id))
              "Island should be on battlefield after play-land")
          ;; Dark Ritual should still be selected (still in hand, can-cast? true with black mana)
          (is (= hand-id (:game/selected-card result))
              "interceptor should NOT clear :game/selected-card when selected card stays in hand"))))))


;; ============================================================
;; Graveyard non-flashback card cleared (gr9a/ktba bug class)
;; ============================================================

(deftest graveyard-non-flashback-card-cleared-via-interceptor
  (testing "interceptor clears :game/selected-card when selected card is non-flashback card in graveyard"
    ;; This is the core gr9a/ktba bug class: a card in graveyard with no
    ;; flashback (or other graveyard-active mechanic) is not actionable.
    ;; Highlighting it is meaningless. The interceptor must clear it.
    ;;
    ;; With the old static-zone #{:hand :graveyard} predicate, this would NOT
    ;; be cleared (graveyard is in the set). With the actionability predicate,
    ;; can-cast? = false (no flashback), can-play-land? = false, can-cycle? = false
    ;; → the selection must be cleared.
    (let [base-db (th/create-game-scenario {:bot-archetype :goldfish})
          game-db (:game/db base-db)
          ;; Add Dark Ritual (no flashback) directly to graveyard
          [game-db gy-id] (th/add-card-to-zone game-db :dark-ritual :graveyard :player-1)
          ;; Simulate: user clicked this graveyard card (stale selected-card)
          app-db (assoc base-db
                        :game/db game-db
                        :game/selected-card gy-id)
          ;; Add a land so we can dispatch a game-mutating event
          [game-db land-id] (th/add-card-to-zone game-db :island :hand :player-1)
          app-db (assoc app-db :game/db game-db)]
      ;; Precondition: stale selection points to a graveyard card
      (is (= gy-id (:game/selected-card app-db))
          "Precondition: :game/selected-card set to graveyard card")
      (is (= :graveyard (th/get-object-zone game-db gy-id))
          "Precondition: selected card is in graveyard")
      ;; Dispatch a game event — the interceptor should clear the stale reference
      ;; because Dark Ritual in graveyard is not actionable:
      ;; can-cast? false (no flashback modes), can-play-land? false, can-cycle? false
      (let [result (dispatch-event app-db [::land-events/play-land land-id])]
        (is (nil? (:game/selected-card result))
            "interceptor must clear :game/selected-card for non-flashback graveyard card (gr9a/ktba bug class)")))))
