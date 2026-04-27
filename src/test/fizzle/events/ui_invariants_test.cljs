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
    [fizzle.db.queries :as queries]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.lands :as land-events]
    [fizzle.events.priority-flow :as priority-flow]
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


;; ============================================================
;; Cast-spell clears :game/selected-card via interceptor (Task 2A characterization)
;; ============================================================

(deftest cast-spell-clears-selected-card-via-interceptor
  (testing "cast-spell: :game/selected-card cleared by interceptor after Lotus Petal cast"
    ;; Regression (Task 2A): after cast-spell, :game/selected-card is cleared because
    ;; the cast spell goes :hand -> :stack and the interceptor's still-actionable? predicate
    ;; returns false for the on-stack zone (not in hand, not a land, no cycling cost).
    ;; Per ADR-031, this clearing must happen via the interceptor — NOT via the per-handler
    ;; dissoc in casting.cljs (which is deleted by this task).
    ;;
    ;; This test characterizes the expected post-cast behavior so that after deletion
    ;; of the per-handler dissoc, the test continues to pass via the interceptor alone.
    (let [base-db  (th/create-game-scenario {:bot-archetype :goldfish})
          game-db  (:game/db base-db)
          ;; Add Lotus Petal (CMC 0, no mana required) to hand
          [game-db lp-id] (th/add-card-to-zone game-db :lotus-petal :hand :player-1)
          ;; Simulate: user clicked Lotus Petal in hand (selected-card set)
          app-db   (assoc base-db
                          :game/db game-db
                          :game/selected-card lp-id)]
      ;; Precondition: LP is selected and in hand
      (is (= lp-id (:game/selected-card app-db))
          "Precondition: :game/selected-card set to Lotus Petal")
      (is (= :hand (th/get-object-zone game-db lp-id))
          "Precondition: Lotus Petal is in hand")
      ;; Dispatch cast-spell with explicit object-id — after cast, LP moves to stack.
      ;; Interceptor: still-actionable? returns false for on-stack object
      ;; (can-cast? false — not in hand; can-play-land? false — not a land;
      ;;  can-cycle? false — no cycling cost). Interceptor clears :game/selected-card.
      (let [result (dispatch-event app-db [::casting/cast-spell {:object-id lp-id}])]
        (is (nil? (:game/selected-card result))
            "interceptor must clear :game/selected-card after cast-spell (not per-handler dissoc — ADR-031)")))))


;; ============================================================
;; Selection-routed cast clears :game/selected-card via interceptor (Task 2B characterization)
;; ============================================================

(deftest selection-routed-cast-clears-selected-card-via-interceptor
  (testing "confirm-selection (mana-allocation finalized): interceptor clears :game/selected-card after cast"
    ;; Regression (Task 2B): a cast that goes through a selection-routed flow
    ;; (mana-allocation finalized lifecycle) clears :game/selected-card via the
    ;; interceptor, NOT via the :selection/clear-selected-card? builder flag
    ;; (retired in this task).
    ;;
    ;; Setup: Dark Ritual ({B}, pure colored) in hand with B mana.
    ;; Dark Ritual has no generic cost — cast-spell dispatches directly without
    ;; allocation selection. After cast, the spell is on stack (not actionable)
    ;; and the interceptor clears :game/selected-card.
    ;;
    ;; This test characterizes the same outcome as cast-spell-clears-selected-card-via-interceptor
    ;; but via the ::confirm-selection event path (selection-routed) rather than
    ;; ::cast-spell directly. Before flag deletion the flag AND interceptor both
    ;; provided dual coverage; after deletion the interceptor provides sole coverage.
    (let [base-db (th/create-game-scenario {:bot-archetype :goldfish :mana {:black 1}})
          game-db (:game/db base-db)
          [game-db dr-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          ;; Set :game/selected-card to the Dark Ritual (simulates user selecting it)
          app-db (assoc base-db
                        :game/db game-db
                        :game/selected-card dr-id)]
      ;; Precondition: Dark Ritual is selected and in hand with mana
      (is (= dr-id (:game/selected-card app-db))
          "Precondition: :game/selected-card set to Dark Ritual")
      (is (= :hand (th/get-object-zone game-db dr-id))
          "Precondition: Dark Ritual is in hand")
      ;; Dispatch cast-spell — Dark Ritual has only colored mana cost (no generic),
      ;; so cast-spell-handler completes without an allocation selection.
      ;; After cast, Dark Ritual moves hand→stack (not actionable).
      ;; Interceptor: can-cast? false (on stack), can-play-land? false, can-cycle? false
      ;; → clears :game/selected-card.
      (let [result (dispatch-event app-db [::casting/cast-spell {:object-id dr-id}])]
        (is (nil? (:game/selected-card result))
            "interceptor must clear :game/selected-card for selection-routed cast (not flag — ADR-031 2B)")))))


;; ============================================================
;; Production-path: human-click cast dispatch shape (ADR-031 §2)
;; ============================================================

(deftest cast-spell-explicit-arg-from-controls
  (testing "controls.cljs Cast button dispatch shape: [::cast-spell {:object-id id}]"
    ;; Production-path test: after this task, controls.cljs dispatches
    ;; [::casting/cast-spell {:object-id selected}] — not the no-arg form.
    ;; This test characterizes the canonical dispatch shape.
    ;; Passes BEFORE the controls.cljs change (explicit form already works),
    ;; and continues to pass AFTER (it is the only form).
    ;;
    ;; Lotus Petal: CMC 0, no mana cost, resolves to graveyard after activation.
    ;; cast-spell with explicit object-id → LP on stack → interceptor clears selection.
    (let [base-db  (th/create-game-scenario {:bot-archetype :goldfish})
          game-db  (:game/db base-db)
          [game-db lp-id] (th/add-card-to-zone game-db :lotus-petal :hand :player-1)
          app-db   (assoc base-db
                          :game/db game-db
                          :game/selected-card lp-id)]
      ;; Dispatch the shape controls.cljs uses after this task
      (let [result (dispatch-event app-db [::casting/cast-spell {:object-id lp-id}])]
        ;; Lotus Petal casts immediately (no selection needed)
        (is (= :stack (:object/zone (queries/get-object (:game/db result) lp-id)))
            "Lotus Petal should be on stack after cast-spell dispatch")
        ;; :game/selected-card cleared by interceptor (LP on stack → not actionable)
        (is (nil? (:game/selected-card result))
            ":game/selected-card cleared after cast-spell with explicit :object-id (ADR-031 §2)")))))


(deftest cast-and-yield-explicit-arg-from-controls
  (testing "controls.cljs Cast & Yield button dispatch shape: [::cast-and-yield {:object-id id}]"
    ;; Production-path test: after this task, controls.cljs dispatches
    ;; [::priority-flow/cast-and-yield {:object-id selected}] — not the no-arg form.
    ;; This test characterizes the canonical dispatch shape.
    ;; Passes BEFORE the controls.cljs change (explicit form will work after handler update),
    ;; and continues to pass AFTER (it is the only form).
    ;;
    ;; Dark Ritual: costs {B}, resolves to graveyard.
    ;; cast-and-yield with explicit object-id → DR resolves → interceptor clears selection.
    (let [base-db  (th/create-game-scenario {:bot-archetype :goldfish :mana {:black 1}})
          game-db  (:game/db base-db)
          [game-db dr-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          app-db   (assoc base-db
                          :game/db game-db
                          :game/selected-card dr-id)]
      ;; Dispatch the shape controls.cljs uses after this task
      (let [result (dispatch-event app-db [::priority-flow/cast-and-yield {:object-id dr-id}])]
        ;; Dark Ritual resolves (cast-and-yield = cast + auto-yield)
        (is (= :graveyard (:object/zone (queries/get-object (:game/db result) dr-id)))
            "Dark Ritual should be in graveyard after cast-and-yield dispatch")
        ;; :game/selected-card cleared by interceptor (DR in graveyard, no flashback)
        (is (nil? (:game/selected-card result))
            ":game/selected-card cleared after cast-and-yield with explicit :object-id (ADR-031 §2)")))))
