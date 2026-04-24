(ns fizzle.events.integration.casting-test
  "Integration (dispatch-sync) tests for events/casting.cljs.

   Covers all 3 reg-event-db handlers:
     ::cast-spell            — cast-spell-handler delegation
     ::select-casting-mode   — select-casting-mode-handler delegation (retired)
     ::cancel-mode-selection — cancel-mode-selection-handler delegation (retired)

   Deletion-test standard: if src/test/fizzle/cards/** were deleted,
   these tests must still independently prove the handlers work.

   Pattern A (reg-event-db dispatch-sync) — follows phases_test.cljs and
   abilities_test.cljs from epic-fizzle-87pw. Template for Pattern A in
   the fizzle-z9ep epic (library.cljs is the next Pattern A file)."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.cards.black.cabal-ritual]
    ;; Direct card requires — NO cards.registry / cards.all-cards lookups
    [fizzle.cards.black.dark-ritual]
    [fizzle.cards.blue.flash-of-insight]
    [fizzle.cards.blue.vision-charm]
    [fizzle.cards.red.lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Install history interceptor AND SBA dispatch — required for dispatch-sync
;; tests to exercise the full production chain.
;; interceptor/register! wires up history entry consumption.
;; db-effect/register! wires up SBAs.
(interceptor/register!)
(db-effect/register!)


(defn- setup-app-db
  "Create a full app-db with human + goldfish bot, libraries populated,
   starting at :main1 phase. Mirrors phases_test.cljs setup exactly."
  ([]
   (setup-app-db {}))
  ([opts]
   (h/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db.
   Resets rf-db/app-db before dispatch for test isolation."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; ============================================================
;; ::cast-spell — Branch 1: Happy path, immediate cast (no pre-cast steps)
;; Dark Ritual: {B} instant, no targeting, no X, no modes
;; Expected: spell on stack, storm incremented, pending-entry set by interceptor
;; ============================================================

(deftest cast-spell-happy-path-immediate
  (testing "::cast-spell with a simple instant (Dark Ritual) casts immediately to stack"
    (let [app-db (setup-app-db {:mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :dark-ritual :hand :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          ;; Precondition: can-cast? passes
          _ (is (rules/can-cast? game-db' :player-1 obj-id)
                "Precondition: can-cast? must be true for Dark Ritual with 1 black mana")
          result (dispatch-event app-db' [::casting/cast-spell])
          result-db (:game/db result)]
      ;; Spell moved from hand to stack
      (is (= :stack (:object/zone (q/get-object result-db obj-id)))
          "Dark Ritual should be on the stack after casting")
      ;; Storm count incremented
      (is (= 1 (q/get-storm-count result-db :player-1))
          "Storm count should be 1 after casting Dark Ritual")
      ;; No pending selection (immediate cast — no pre-cast steps required)
      (is (nil? (:game/pending-selection result))
          "No pending selection for a simple instant with no pre-cast requirements")
      ;; No pending mode selection
      (is (nil? (:game/pending-mode-selection result))
          "No pending mode selection for a simple non-modal spell")
      ;; :game/selected-card cleared
      (is (nil? (:game/selected-card result))
          ":game/selected-card should be cleared after cast"))))


;; ============================================================
;; ::cast-spell — Branch 2: Pre-cast targeting needed
;; Lightning Bolt: {R} instant, requires player targeting
;; When both players are valid targets, shows targeting selection
;; ============================================================

(deftest cast-spell-pre-cast-targeting-pauses-for-selection
  (testing "::cast-spell with a targeted spell (Lightning Bolt) pauses for target selection"
    (let [app-db (setup-app-db {:mana {:red 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :lightning-bolt :hand :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          _ (is (rules/can-cast? game-db' :player-1 obj-id)
                "Precondition: can-cast? must be true for Lightning Bolt with 1 red mana")
          result (dispatch-event app-db' [::casting/cast-spell])
          sel (:game/pending-selection result)]
      ;; Should pause for targeting selection (two valid targets: player-1 and player-2)
      (is (some? sel)
          "Lightning Bolt should create a pending-selection for targeting")
      (is (= :cast-time-targeting (:selection/type sel))
          "Selection type should be :cast-time-targeting for Lightning Bolt")
      ;; Deferred entry set (history entry deferred until cast completes)
      (is (some? (:history/deferred-entry result))
          ":history/deferred-entry should be set when pre-cast selection is needed")
      ;; Spell still in hand (not yet cast — waiting for target)
      (is (= :hand (:object/zone (q/get-object (:game/db result) obj-id)))
          "Spell should still be in hand while waiting for target selection"))))


;; ============================================================
;; ::cast-spell — Branch 3: Modal spell (Vision Charm — 3 modes)
;; Expected: spell-mode selection created, deferred-entry set
;; ============================================================

(deftest cast-spell-modal-spell-creates-mode-selection
  (testing "::cast-spell with a modal spell (Vision Charm) creates spell-mode selection"
    (let [app-db (setup-app-db {:mana {:blue 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :vision-charm :hand :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          _ (is (rules/can-cast? game-db' :player-1 obj-id)
                "Precondition: can-cast? must be true for Vision Charm with 1 blue mana")
          result (dispatch-event app-db' [::casting/cast-spell])
          sel (:game/pending-selection result)]
      ;; Modal spell: spell-mode selection should be created
      (is (some? sel)
          "Vision Charm should create a pending-selection for mode choice")
      (is (= :spell-mode (:selection/type sel))
          "Selection type should be :spell-mode for a modal card")
      ;; At least 2 valid modes (modes without unreachable targets)
      (is (>= (count (:selection/candidates sel)) 2)
          "Vision Charm should have at least 2 valid modes as candidates")
      ;; Deferred entry set (history entry deferred until cast completes)
      (is (some? (:history/deferred-entry result))
          ":history/deferred-entry should be set for modal spell casting")
      ;; Spell still in hand (waiting for mode selection)
      (is (= :hand (:object/zone (q/get-object (:game/db result) obj-id)))
          "Spell should still be in hand while waiting for mode selection"))))


;; ============================================================
;; ::cast-spell — Branch 4: X cost spell pauses for X selection
;; Flash of Insight: {X}{1}{U} — requires X mana cost selection
;; ============================================================

(deftest cast-spell-x-cost-spell-pauses-for-x-selection
  (testing "::cast-spell with an X-cost spell (Flash of Insight) pauses for X-mana selection"
    (let [app-db (setup-app-db {:mana {:blue 3 :colorless 2}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :flash-of-insight :hand :player-1)
          ;; Add cards to library so Flash of Insight has targets to look at
          [game-db'' _] (h/add-cards-to-library game-db' [:island :island :island] :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db'')
                      (assoc :game/selected-card obj-id))
          _ (is (rules/can-cast? game-db'' :player-1 obj-id)
                "Precondition: can-cast? must be true for Flash of Insight with sufficient mana")
          result (dispatch-event app-db' [::casting/cast-spell])
          sel (:game/pending-selection result)]
      ;; Should pause for X mana selection
      (is (some? sel)
          "Flash of Insight should create a pending-selection for X cost")
      (is (= :x-mana-cost (:selection/type sel))
          "Selection type should be :x-mana-cost for Flash of Insight")
      ;; Deferred entry set
      (is (some? (:history/deferred-entry result))
          ":history/deferred-entry should be set when pre-cast selection is needed")
      ;; Spell still in hand
      (is (= :hand (:object/zone (q/get-object (:game/db result) obj-id)))
          "Spell should still be in hand while waiting for X selection"))))


;; ============================================================
;; ::cast-spell — Branch 5: Multiple non-modal castable modes
;; Cabal Ritual: has primary mode + threshold conditional mode.
;; However, both threshold and non-threshold modes are returned as
;; one castable mode (threshold is not a separate "mode" at the get-casting-modes level).
;; Instead, use two spells in hand — one castable from hand, one from graveyard.
;;
;; After investigation: Cabal Ritual's threshold is a conditional effect (not a separate mode).
;; get-casting-modes returns 1 mode for a card in hand. To test multiple castable modes,
;; we would need a card with :card/alternate-costs that applies from :hand zone.
;; This branch (multiple castable modes → pending-mode-selection) requires a card with
;; alternate costs applicable from hand (e.g., kicker). No such card exists in the current
;; card library. Branch is documented as unreachable with current card set.
;;
;; DOCUMENTED SKIP: pending-mode-selection branch (> 1 castable modes) requires a card
;; with multiple hand-zone castable modes. No such card in the current library.
;; See: rules.cljs:get-casting-modes — only primary + :hand alternates are combined.
;; ============================================================


;; ============================================================
;; ::cast-spell — Branch 6: Guard — no object-id and no selected-card
;; Expected: app-db unchanged
;; ============================================================

(deftest cast-spell-guard-no-object-id-no-op
  (testing "::cast-spell with no selected card and no opts is a no-op"
    (let [app-db (setup-app-db)
          ;; No :game/selected-card set, no opts
          app-db-no-card (dissoc app-db :game/selected-card)
          result (dispatch-event app-db-no-card [::casting/cast-spell])]
      ;; app-db should be unchanged
      (is (= app-db-no-card result)
          "app-db should be unchanged when no selected card and no opts provided"))))


;; ============================================================
;; ::cast-spell — Branch 7: Guard — can-cast? returns false
;; Expected: app-db unchanged
;; ============================================================

(deftest cast-spell-guard-insufficient-mana-no-op
  (testing "::cast-spell when can-cast? returns false (insufficient mana) is a no-op"
    (let [app-db (setup-app-db)
          ;; No mana in pool — can't cast Dark Ritual ({B})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :dark-ritual :hand :player-1)
          ;; Verify mana pool is empty (no black mana)
          _ (is (= 0 (:black (q/get-mana-pool game-db' :player-1)))
                "Precondition: player-1 has no black mana")
          _ (is (not (rules/can-cast? game-db' :player-1 obj-id))
                "Precondition: can-cast? must be false with 0 mana")
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          result (dispatch-event app-db' [::casting/cast-spell])]
      ;; Spell should still be in hand
      (is (= :hand (:object/zone (q/get-object (:game/db result) obj-id)))
          "Spell should remain in hand when can-cast? is false")
      ;; No stack items
      (is (empty? (q/get-all-stack-items (:game/db result)))
          "Stack should be empty when cast is blocked by insufficient mana")
      ;; Storm unchanged
      (is (= 0 (q/get-storm-count (:game/db result) :player-1))
          "Storm count should remain 0 when cast is blocked"))))


;; ============================================================
;; ::cast-spell — Branch 8: Explicit opts map (object-id + target)
;; Lightning Bolt with explicit opts: target opponent directly
;; (single auto-cast path when target pre-determined)
;; ============================================================

(deftest cast-spell-with-explicit-opts-target
  (testing "::cast-spell with explicit opts :target casts directly without selection"
    (let [app-db (setup-app-db {:mana {:red 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :lightning-bolt :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          _ (is (rules/can-cast? game-db' :player-1 obj-id)
                "Precondition: can-cast? must be true")
          ;; Provide explicit opts with pre-determined target (opponent)
          result (dispatch-event app-db' [::casting/cast-spell
                                          {:player-id :player-1
                                           :object-id obj-id
                                           :target :player-2}])
          result-db (:game/db result)]
      ;; Spell cast directly to stack (no selection needed — target pre-determined)
      (is (= :stack (:object/zone (q/get-object result-db obj-id)))
          "Lightning Bolt should be on stack when target is pre-determined")
      ;; No pending selection (target provided, no targeting dialog)
      (is (nil? (:game/pending-selection result))
          "No pending selection when target is pre-determined via opts")
      ;; Storm count incremented
      (is (= 1 (q/get-storm-count result-db :player-1))
          "Storm count should be 1 after casting with explicit target"))))


;; ============================================================
;; ::cast-spell — Branch 9 (open question investigation):
;; cast-and-yield? in ::cast-spell
;;
;; Finding: The cast-and-yield? check is ONLY in apply-continuation :cast-after-spell-mode,
;; not in ::cast-spell → cast-spell-handler itself. cast-spell-handler always returns a
;; plain app-db map (not {:app-db ... :then ...}). The map-vs-plain-db issue does NOT
;; apply to reg-event-db ::cast-spell. Branches 9/10 from the task spec cannot be reached
;; through ::cast-spell directly — they only arise in the apply-continuation path which
;; is triggered by the spell-mode selection resolving, not by direct ::cast-spell dispatch.
;;
;; No production bug found. Documented for completion report.
;; ============================================================


;; ============================================================
;; ::cast-spell — Branch 10: History entry created for immediate cast
;; Verify interceptor moves pending-entry to history/main (same pattern as phases_test)
;; ============================================================

(deftest cast-spell-immediate-creates-history-entry
  (testing "::cast-spell with immediate cast creates a history entry via interceptor"
    (let [app-db (setup-app-db {:mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :dark-ritual :hand :player-1)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/selected-card obj-id))
          result (dispatch-event app-db' [::casting/cast-spell])
          history-entries (:history/main result)]
      ;; The interceptor moves pending-entry to history/main — check history/main
      (is (seq history-entries)
          "history/main should have entries after an immediate cast")
      (let [last-entry (last history-entries)]
        (is (= ::casting/cast-spell (:entry/event-type last-entry))
            "last history entry event-type should be ::casting/cast-spell")
        (is (some? (:entry/description last-entry))
            "last history entry should have a description")))))


;; ============================================================
;; ::select-casting-mode — Retired (ADR-023)
;; Mode selection now goes through the standard :game/pending-selection pipeline.
;; This handler is a no-op. Tests verify the no-op contract.
;; ============================================================

(deftest select-casting-mode-happy-path
  (testing "::select-casting-mode (retired) is a no-op regardless of arguments"
    (let [app-db (setup-app-db {:mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :dark-ritual :hand :player-1)
          modes (rules/get-casting-modes game-db' :player-1 obj-id)
          primary-mode (first modes)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::casting/select-casting-mode primary-mode])]
      ;; Retired handler: app-db must be unchanged
      (is (= app-db' result)
          "::select-casting-mode is retired (ADR-023): must be a no-op")
      ;; Spell must still be in hand (handler did not cast it)
      (is (= :hand (:object/zone (q/get-object (:game/db result) obj-id)))
          "Spell must remain in hand (retired handler does not cast)"))))


;; ============================================================
;; ::select-casting-mode — Branch 2: Guard — no pending-mode-selection
;; Expected: app-db unchanged
;; ============================================================

(deftest select-casting-mode-guard-no-pending-no-op
  (testing "::select-casting-mode with no pending-mode-selection is a no-op"
    (let [app-db (setup-app-db)
          ;; No pending-mode-selection set
          app-db-no-pending (dissoc app-db :game/pending-mode-selection)
          result (dispatch-event app-db-no-pending [::casting/select-casting-mode :some-mode])]
      (is (= app-db-no-pending result)
          "app-db should be unchanged when no pending-mode-selection exists"))))


;; ============================================================
;; ::select-casting-mode — Branch 3: Guard — pending present but object-id missing
;; Expected: app-db unchanged
;; ============================================================

(deftest select-casting-mode-guard-no-object-id-no-op
  (testing "::select-casting-mode with pending but missing :object-id is a no-op"
    (let [app-db (setup-app-db)
          ;; Pending without :object-id
          app-db-malformed (assoc app-db :game/pending-mode-selection {:modes []})
          result (dispatch-event app-db-malformed [::casting/select-casting-mode :some-mode])]
      (is (= app-db-malformed result)
          "app-db should be unchanged when pending-mode-selection has no :object-id"))))


;; ============================================================
;; ::select-casting-mode — Branch 4: Guard — nil mode arg
;; Expected: app-db unchanged
;; ============================================================

(deftest select-casting-mode-guard-nil-mode-no-op
  (testing "::select-casting-mode with nil mode arg is a no-op"
    (let [app-db (setup-app-db {:mana {:black 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :dark-ritual :hand :player-1)
          modes (rules/get-casting-modes game-db' :player-1 obj-id)
          app-db' (-> app-db
                      (assoc :game/db game-db')
                      (assoc :game/pending-mode-selection
                             {:object-id obj-id
                              :modes modes}))
          result (dispatch-event app-db' [::casting/select-casting-mode nil])]
      (is (= app-db' result)
          "app-db should be unchanged when mode arg is nil"))))


;; ============================================================
;; ::select-casting-mode — Branch 5 (retired): now a no-op (ADR-023)
;; The standard pipeline is tested in cast-spell-multi-mode-confirm-mode-puts-spell-on-stack.
;; ============================================================

(deftest select-casting-mode-with-targeting-creates-selection
  (testing "::select-casting-mode (retired) is a no-op even with chosen-mode set on object"
    (let [app-db (setup-app-db {:mana {:blue 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :vision-charm :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          modes (rules/get-casting-modes game-db' :player-1 obj-id)
          primary-mode (first modes)
          result (dispatch-event app-db' [::casting/select-casting-mode primary-mode])]
      ;; Retired handler: app-db unchanged
      (is (= app-db' result)
          "::select-casting-mode is retired (ADR-023): must be a no-op")
      ;; Legacy key is absent (no pending-mode-selection was set here)
      (is (nil? (:game/pending-mode-selection result))
          ":game/pending-mode-selection must be nil (retired per ADR-023)"))))


;; ============================================================
;; ::select-casting-mode — Branch 6 (retired): now a no-op (ADR-023)
;; X-cost mode selection via standard pipeline not yet testable here
;; (requires multi-mode card with X-cost alternate, not in current card library).
;; ============================================================

(deftest select-casting-mode-with-x-cost-creates-x-selection
  (testing "::select-casting-mode (retired) is a no-op even with X-cost mode argument"
    (let [app-db (setup-app-db {:mana {:blue 3 :colorless 2}})
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :flash-of-insight :hand :player-1)
          [game-db'' _] (h/add-cards-to-library game-db' [:island :island :island] :player-1)
          modes (rules/get-casting-modes game-db'' :player-1 obj-id)
          primary-mode (first modes)
          app-db' (assoc app-db :game/db game-db'')
          result (dispatch-event app-db' [::casting/select-casting-mode primary-mode])]
      ;; Retired handler: app-db unchanged
      (is (= app-db' result)
          "::select-casting-mode is retired (ADR-023): must be a no-op")
      ;; Legacy key absent
      (is (nil? (:game/pending-mode-selection result))
          ":game/pending-mode-selection must be nil (retired per ADR-023)"))))


;; ============================================================
;; ::cancel-mode-selection — Retired (ADR-023)
;; Mode selection cancellation now uses ::selection/cancel-selection.
;; This handler is a no-op. Tests verify the no-op contract.
;; ============================================================

(deftest cancel-mode-selection-clears-pending
  (testing "::cancel-mode-selection (retired) is a no-op (ADR-023)"
    (let [app-db (setup-app-db)
          ;; :game/pending-mode-selection should never be set (retired), but test
          ;; the no-op contract regardless.
          result (dispatch-event app-db [::casting/cancel-mode-selection])]
      ;; Retired handler: app-db must be unchanged
      (is (= app-db result)
          "::cancel-mode-selection is retired (ADR-023): must be a no-op")
      ;; :game/pending-mode-selection is absent (retired key)
      (is (nil? (:game/pending-mode-selection result))
          ":game/pending-mode-selection must be nil (retired per ADR-023)"))))


;; ============================================================
;; ::cancel-mode-selection — Branch 2: Idempotent no-op when absent
;; Expected: app-db unchanged
;; ============================================================

(deftest cancel-mode-selection-idempotent-when-absent
  (testing "::cancel-mode-selection is idempotent (always a no-op)"
    (let [app-db (setup-app-db)
          result (dispatch-event app-db [::casting/cancel-mode-selection])]
      (is (= app-db result)
          "::cancel-mode-selection is retired (ADR-023): always a no-op"))))


;; ============================================================
;; ::cancel-mode-selection — Branch 3: Does not affect unrelated state
;; Expected: app-db unchanged (no-op preserves all keys)
;; ============================================================

(deftest cancel-mode-selection-preserves-unrelated-state
  (testing "::cancel-mode-selection (retired) no-op preserves all app-db keys"
    (let [app-db (setup-app-db)
          sentinel-value (random-uuid)
          app-db' (assoc app-db :game/selected-card sentinel-value)
          result (dispatch-event app-db' [::casting/cancel-mode-selection])]
      ;; No-op: app-db unchanged
      (is (= app-db' result)
          "::cancel-mode-selection is retired (ADR-023): app-db must be unchanged")
      ;; :game/selected-card preserved (no-op does not touch any keys)
      (is (= sentinel-value (:game/selected-card result))
          ":game/selected-card should be preserved (no-op handler)"))))


;; ============================================================
;; ADR-023 completion: multi-mode casting uses standard selection pipeline
;;
;; After retirement of :game/pending-mode-selection, when ::cast-spell is
;; called on a spell with >1 castable modes, it must use :game/pending-selection
;; with :selection/type :spell-mode (standard pipeline) rather than setting
;; :game/pending-mode-selection directly.
;;
;; Test card: inline alternate-cost card castable from hand with 2 modes.
;; ============================================================

(defn- setup-multi-mode-app-db
  "Create an app-db with a test card that has 2 castable modes from hand.
   Primary mode: {U}{U}. Alternate (hand-zone): {U} + pay 2 life.
   Returns [app-db obj-id]."
  []
  (let [app-db (setup-app-db {:mana {:blue 2}})
        game-db (:game/db app-db)
        ;; Add test card with 2 castable modes from hand: primary + hand-zone alternate
        conn (d/conn-from-db game-db)
        _ (d/transact! conn [{:card/id :test-dual-mode-adr023
                              :card/name "Test Dual Mode ADR-023"
                              :card/mana-cost {:blue 2}
                              :card/cmc 2
                              :card/types #{:instant}
                              :card/colors #{:blue}
                              :card/effects [{:effect/type :draw :effect/amount 1}]
                              :card/alternate-costs [{:alternate/id :phyrexian-cost
                                                      :alternate/zone :hand
                                                      :alternate/mana-cost {:blue 1}
                                                      :alternate/additional-costs [{:cost/type :pay-life
                                                                                    :cost/amount 2}]
                                                      :alternate/on-resolve :graveyard}]}])
        game-db' @conn
        [game-db'' obj-id] (h/add-card-to-zone game-db' :test-dual-mode-adr023 :hand :player-1)
        app-db' (assoc app-db :game/db game-db'' :game/selected-card obj-id)]
    [app-db' obj-id]))


(deftest cast-spell-multi-mode-uses-standard-selection-pipeline
  (testing "::cast-spell on multi-mode spell uses :game/pending-selection (not :game/pending-mode-selection)"
    (let [[app-db obj-id] (setup-multi-mode-app-db)
          game-db (:game/db app-db)
          _ (is (rules/can-cast? game-db :player-1 obj-id)
                "Precondition: can-cast? must be true for test-dual-mode-adr023")
          _ (is (> (count (rules/get-casting-modes game-db :player-1 obj-id)) 1)
                "Precondition: must have >1 castable mode")
          result (dispatch-event app-db [::casting/cast-spell])
          pending-sel (:game/pending-selection result)]
      ;; Standard selection pipeline: :game/pending-selection must be present
      (is (some? pending-sel)
          "Multi-mode spell must use :game/pending-selection (standard pipeline)")
      (is (= :spell-mode (:selection/type pending-sel))
          ":selection/type must be :spell-mode for casting mode selection")
      ;; Mechanism check: :pick-mode is the mechanism for mode selection (per ADR-030)
      (is (= :pick-mode (:selection/mechanism pending-sel))
          ":selection/mechanism must be :pick-mode for casting mode selection")
      ;; MUST NOT set legacy key
      (is (nil? (:game/pending-mode-selection result))
          ":game/pending-mode-selection must be nil (retired per ADR-023)")
      ;; Spell still in hand (awaiting mode choice)
      (is (= :hand (:object/zone (q/get-object (:game/db result) obj-id)))
          "Spell must remain in hand while awaiting mode selection"))))


(deftest cast-spell-multi-mode-priority-gate-holds
  (testing "Priority is NOT yielded after casting multi-mode spell until mode is confirmed"
    (let [[app-db _] (setup-multi-mode-app-db)
          result (dispatch-event app-db [::casting/cast-spell])]
      ;; A pending-selection blocks priority — stack must be empty
      (is (empty? (q/get-all-stack-items (:game/db result)))
          "Spell must not be on stack yet while awaiting mode selection")
      ;; pending-selection is present (priority gate)
      (is (some? (:game/pending-selection result))
          "Pending selection must be present to block priority yield"))))


(deftest cast-spell-multi-mode-confirm-mode-puts-spell-on-stack
  (testing "Confirming mode selection puts spell on stack via standard confirm-selection pipeline"
    (let [[app-db obj-id] (setup-multi-mode-app-db)
          ;; Cast to get pending-selection
          after-cast (dispatch-event app-db [::casting/cast-spell])
          pending-sel (:game/pending-selection after-cast)
          _ (is (= :spell-mode (:selection/type pending-sel))
                "Precondition: pending-selection is spell-mode after cast")
          ;; Select the primary mode (first candidate)
          primary-mode (first (:selection/candidates pending-sel))
          _ (is (some? primary-mode) "Precondition: has at least one candidate mode")
          ;; Use standard selection pipeline to confirm mode
          ;; confirm-selection takes (game-db selection selected-items)
          after-confirm (h/confirm-selection
                          (:game/db after-cast)
                          pending-sel
                          #{primary-mode})]
      ;; After confirming mode: spell should be on stack (or pre-cast selection for mana)
      (is (or (= :stack (:object/zone (q/get-object (:db after-confirm) obj-id)))
              (some? (:selection after-confirm)))
          "After confirming mode, spell is on stack or pending a pre-cast selection"))))
