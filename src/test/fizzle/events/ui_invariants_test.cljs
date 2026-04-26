(ns fizzle.events.ui-invariants-test
  "Regression tests for the :game/selected-card post-event interceptor.

   Per ADR-031: a re-frame post-event interceptor reconciles
   :game/selected-card against :game/db zone state after each
   game-mutating event. The interceptor dissocs :game/selected-card
   when the referenced object's post-event zone is not in
   #{:hand :graveyard} (the selectable zones where selection is
   meaningful).

   Key regression: fizzle-ktba — Rain of Filth granted sacrifice-self
   mana ability did not clear :game/selected-card. The root cause is
   that the selected land was selected while in HAND, played to
   battlefield (zone changed to :battlefield), but :game/selected-card
   was never cleared. After Rain of Filth grants sacrifice abilities,
   the stale reference persists. The interceptor prevents this by
   clearing :game/selected-card whenever the referenced object moves
   to a non-selectable zone (e.g., :battlefield after play-land).

   Test structure:
   - fizzle-ktba regression: play-land clears selection via interceptor
     (::play-land lacks a per-handler dissoc; interceptor provides it)
   - Interceptor no-op: hand card stays in hand → selection preserved"
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
    (let [base-db (th/create-game-scenario {:bot-archetype :goldfish})
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
      ;; After play-land, dark-ritual zone=:hand → in #{:hand :graveyard} → preserved.
      (let [[game-db land2-id] (th/add-card-to-zone (:game/db app-db) :island :hand :player-1)
            app-db' (assoc app-db :game/db game-db)]
        ;; Play the island (not the selected dark-ritual)
        ;; After play-land, dark-ritual stays in hand (zone=:hand)
        ;; Interceptor checks: dark-ritual zone=:hand → in #{:hand :graveyard} → preserve
        (let [result (dispatch-event app-db' [::land-events/play-land land2-id])]
          (is (= :battlefield (th/get-object-zone (:game/db result) land2-id))
              "Island should be on battlefield after play-land")
          ;; Dark Ritual should still be selected (still in hand, zone=:hand)
          (is (= hand-id (:game/selected-card result))
              "interceptor should NOT clear :game/selected-card when selected card stays in hand"))))))
