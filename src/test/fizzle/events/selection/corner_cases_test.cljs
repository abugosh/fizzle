(ns fizzle.events.selection.corner-cases-test
  "Corner case tests for the core selection mechanism (events/selection/core.cljs).

   Covers:
   1. Invalid :selection/lifecycle value — case expression throws
   2. standard-path with interactive remaining-effects — chained interactive effect
      causes a NEW pending-selection to be built rather than clearing it
   3. toggle-selection-impl with no :game/pending-selection — nil safety
   4. Scry executor unit test — top-pile stays on top, bottom-pile goes to bottom
   5. Modal card (REB) with 0 valid modes — cast-spell-handler does NOT crash
   6. Zone-pick builder with nil effect/count — returns selection without crashing
   7. Mana allocation confirm with generic-remaining > 0 — spell still casts
   8. :selection/exact? false path in toggle-selection-impl (unlimited multi-select)
   9. :resolve-one-and-stop continuation on non-empty stack"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.costs :as costs]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register interceptor and db-effect so dispatch-sync tests work correctly.
;; (Tests 9 use rf/dispatch-sync which requires these for history and SBAs.)
(interceptor/register!)
(db-effect/register!)


;; =====================================================
;; Test executor defmethods — used only by corner case tests
;; =====================================================

;; A minimal domain policy that returns db unchanged, for routing tests.
(defmethod core/apply-domain-policy :test-corner-noop
  [game-db _selection]
  {:db game-db})


;; =====================================================
;; 1. Invalid lifecycle value — case throws
;; =====================================================

(deftest test-invalid-lifecycle-throws
  (testing "confirm-selection-impl throws when :selection/lifecycle is not :standard/:finalized/:chaining"
    ;; :selection/lifecycle :bogus is not a valid lifecycle value.
    ;; The case expression at core.cljs:434 has no default — ClojureScript
    ;; throws an exception for unmatched case values.
    ;;
    ;; We build app-db directly (bypassing set-pending-selection) because
    ;; the spec rejects :bogus at the chokepoint — this test is specifically
    ;; probing what happens if malformed data reaches confirm-selection-impl.
    (let [db (th/create-test-db)
          sel {:selection/type :test-corner-noop
               :selection/mechanism :pick-from-zone
               :selection/domain :test-corner-noop
               :selection/lifecycle :bogus   ; invalid — no case match
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/validation :always
               :selection/auto-confirm? false}
          app-db {:game/db db :game/pending-selection sel}]
      (is (thrown? js/Error (core/confirm-selection-impl app-db))
          "confirm-selection-impl must throw when :selection/lifecycle is an unrecognized value"))))


;; =====================================================
;; 2. standard-path with interactive remaining-effects
;; =====================================================
;; The standard-path function (core.cljs:350-378) has an if-branch:
;;   (if (:needs-selection remaining-result) …)
;; When remaining-effects contain a :discard effect, reduce-effects
;; returns {:db … :needs-selection effect :remaining-effects []}.
;; standard-path must build a NEW pending-selection (for the next interactive
;; effect) instead of clearing the selection.

(deftest test-standard-path-interactive-remaining-effect-builds-next-selection
  (testing "standard-path: when remaining-effects has an interactive effect,
            a NEW pending-selection is set rather than clearing it"
    (let [db (th/create-test-db)
          ;; Add a card to hand so the :discard effect has a real hand to discard from
          [db card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; First selection: a no-op executor with :standard lifecycle.
          ;; remaining-effects contains a :discard effect that requires player selection.
          sel {:selection/type :test-corner-noop
               :selection/mechanism :pick-from-zone
               :selection/domain :test-corner-noop
               :selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/spell-id (random-uuid)
               :selection/selected #{}
               :selection/validation :always
               :selection/auto-confirm? false
               ;; This discard effect is interactive — execute-effect-impl returns
               ;; {:db db :needs-selection effect} which signals standard-path to pause
               :selection/remaining-effects [{:effect/type :discard
                                              :effect/selection :player
                                              :effect/count 1}]}
          app-db {:game/db db :game/pending-selection sel}
          result (core/confirm-selection-impl app-db)]

      ;; The interactive discard effect was encountered while processing remaining-effects.
      ;; standard-path must NOT clear pending-selection — it must set a NEW one.
      (is (some? (:game/pending-selection result))
          "A new pending-selection should be set when remaining-effects has an interactive effect")

      ;; The new selection should be for the :discard type (the interactive effect)
      (is (= :discard (:selection/type (:game/pending-selection result)))
          "New pending-selection type should be :discard (the next interactive effect)")

      ;; The original card should still exist in hand (discard not yet confirmed)
      (is (= :hand (th/get-object-zone (:game/db result) card-id))
          "Card should remain in hand — discard selection is pending, not yet resolved"))))


(deftest test-standard-path-non-interactive-remaining-effects-clears-selection
  (testing "standard-path: when remaining-effects are all non-interactive, pending-selection is cleared"
    (let [db (th/create-test-db)
          ;; Add a card to the library so :draw can pull from it
          [db _card-id] (th/add-card-to-zone db :cabal-ritual :library :player-1)
          sel {:selection/type :test-corner-noop
               :selection/mechanism :pick-from-zone
               :selection/domain :test-corner-noop
               :selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/spell-id (random-uuid)
               :selection/selected #{}
               :selection/validation :always
               :selection/auto-confirm? false
               ;; :draw is non-interactive (draws from library, no player choice needed)
               :selection/remaining-effects [{:effect/type :draw :effect/amount 1}]}
          app-db {:game/db db :game/pending-selection sel}
          result (core/confirm-selection-impl app-db)]

      ;; Non-interactive remaining effects complete without pausing.
      ;; Pending selection should be cleared.
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared when all remaining-effects are non-interactive"))))


;; =====================================================
;; 3. toggle-selection-impl with nil pending-selection
;; =====================================================

(deftest test-toggle-selection-impl-with-nil-pending-selection
  (testing "toggle-selection-impl throws when :game/pending-selection is absent (no select-count)"
    ;; With no pending-selection, select-count is nil.
    ;; The pos-int? entry guard fires and throws ex-info.
    ;; This replaces the old silent-noop behavior that was the dx7b bug class.
    (let [db (th/create-test-db)
          app-db {:game/db db}   ; no :game/pending-selection key
          ]
      (is (thrown?
            js/Error
            (core/toggle-selection-impl app-db :some-id))
          "toggle-selection-impl must throw when pending-selection is absent — no silent noop"))))


(deftest test-toggle-selection-impl-with-nil-key-present
  (testing "toggle-selection-impl throws when :game/pending-selection is nil (no select-count)"
    ;; Same contract: nil selection means nil select-count; pos-int? guard fires and throws.
    (let [db (th/create-test-db)
          app-db {:game/db db :game/pending-selection nil}]
      (is (thrown?
            js/Error
            (core/toggle-selection-impl app-db :some-id))
          "toggle-selection-impl must throw when pending-selection is nil — no silent noop"))))


;; =====================================================
;; 4. Scry executor unit test
;; =====================================================
;; Calls execute-confirmed-selection with a :scry selection directly.
;; Verifies top-pile cards stay on top and bottom-pile cards go to bottom.

(deftest test-scry-executor-top-pile-stays-on-top
  (testing "scry executor: top-pile cards remain at top of library"
    (let [db (th/create-test-db)
          ;; Add 3 cards to library so we can verify ordering after scry
          [db lib-ids] (th/add-cards-to-library
                         db [:dark-ritual :cabal-ritual :brain-freeze] :player-1)
          [top-id mid-id bot-id] lib-ids
          ;; Add a spell to stack so move-to-zone has a real object to move
          [db spell-id] (th/add-card-to-zone db :opt :stack :player-1)
          ;; Build scry selection: scry 3 — put top-id and mid-id on top,
          ;; bot-id on bottom
          selection {:selection/type :scry
                     :selection/pattern :reorder
                     :selection/lifecycle :finalized
                     :selection/player-id :player-1
                     :selection/cards [top-id mid-id bot-id]
                     :selection/top-pile [top-id mid-id]
                     :selection/bottom-pile [bot-id]
                     :selection/spell-id spell-id
                     :selection/remaining-effects []
                     :selection/validation :always
                     :selection/auto-confirm? false}
          result (core/execute-confirmed-selection db selection)
          result-db (:db result)]

      (is (map? result) "execute-confirmed-selection must return a map")
      (is (contains? result :db) "Result must have :db key")

      ;; The library should have top-id and mid-id at the top positions
      ;; and bot-id at the bottom position
      (let [top-2 (q/get-top-n-library result-db :player-1 2)
            all-3 (q/get-top-n-library result-db :player-1 3)]
        (is (= [top-id mid-id] top-2)
            "top-pile cards must be at the top of the library in declared order")
        (is (= bot-id (last all-3))
            "bottom-pile card must be at the bottom of the library")))))


(deftest test-scry-executor-bottom-pile-goes-to-bottom
  (testing "scry executor: bottom-pile cards go to the bottom of the library"
    (let [db (th/create-test-db)
          [db lib-ids] (th/add-cards-to-library
                         db [:dark-ritual :cabal-ritual :brain-freeze] :player-1)
          [a-id b-id c-id] lib-ids
          [db spell-id] (th/add-card-to-zone db :opt :stack :player-1)
          ;; Put all cards on bottom — player scryed 3, chose to put all on bottom
          selection {:selection/type :scry
                     :selection/pattern :reorder
                     :selection/lifecycle :finalized
                     :selection/player-id :player-1
                     :selection/cards [a-id b-id c-id]
                     :selection/top-pile []
                     :selection/bottom-pile [a-id b-id c-id]
                     :selection/spell-id spell-id
                     :selection/remaining-effects []
                     :selection/validation :always
                     :selection/auto-confirm? false}
          result (core/execute-confirmed-selection db selection)
          result-db (:db result)
          all-3 (q/get-top-n-library result-db :player-1 3)]

      ;; When all are in bottom-pile, they should appear in declared order at bottom.
      ;; In reorder-library-for-scry: top-pile first, then unassigned, then bottom-pile.
      ;; With top-pile=[] and unassigned=[] all 3 should be in the bottom-pile order.
      (is (= [a-id b-id c-id] all-3)
          "All-bottom scry: cards should be in bottom-pile declared order"))))


;; =====================================================
;; 5. Modal card with 0 valid modes
;; =====================================================
;; Red Elemental Blast requires a blue target. Without any blue permanents/spells,
;; get-valid-spell-modes returns an empty seq, and can-cast? returns false
;; (has-valid-targets? fails for the card). cast-spell-handler returns app-db unchanged.

(deftest test-modal-card-no-valid-modes-does-not-crash
  (testing "cast-spell-handler does not crash when modal card has 0 valid modes"
    (let [db (th/create-test-db)
          ;; Red Elemental Blast requires a blue target — there are none in the default db
          [db reb-id] (th/add-card-to-zone db :red-elemental-blast :hand :player-1)
          ;; Give player red mana so cost is payable
          db (mana/add-mana db :player-1 {:red 1})
          app-db {:game/db db :game/selected-card reb-id}
          result (casting/cast-spell-handler app-db)]

      ;; No crash — returns app-db unchanged (can-cast? is false due to no valid targets)
      (is (map? result)
          "cast-spell-handler must return a map (not throw) for 0-valid-mode card")

      ;; Spell stays in hand — cast was rejected
      (is (= :hand (th/get-object-zone (:game/db result) reb-id))
          "REB must stay in hand when there are no valid targets for any mode"))))


;; =====================================================
;; 6. Zone-pick builder with nil effect/count
;; =====================================================
;; build-selection-for-effect :discard (derives :zone-pick) with nil :effect/count.
;; The builder uses (or (:effect/count effect) (:effect/select-count effect)) —
;; if both are nil, :selection/select-count is nil. Builder should not crash.

(deftest test-zone-pick-builder-nil-effect-count-does-not-crash
  (testing "zone-pick builder for :discard with nil :effect/count does not crash"
    (let [db (th/create-test-db)
          [db card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          _ card-id  ; suppress unused warning
          effect {:effect/type :discard}  ; no :effect/count
          result (core/build-selection-for-effect db :player-1 (random-uuid) effect [])]

      (is (map? result)
          "build-selection-for-effect must return a map for :discard with no count")
      (is (= :discard (:selection/type result))
          "Selection type must be :discard")
      ;; :selection/select-count will be nil — that is acceptable (builder doesn't crash)
      (is (contains? result :selection/select-count)
          "Result must contain :selection/select-count key (even if nil)"))))


;; =====================================================
;; 7. Mana allocation confirm with generic-remaining > 0
;; =====================================================
;; build-mana-allocation-selection is only produced when generic > 0.
;; Confirming such a selection via confirm-selection-impl (lifecycle :finalized,
;; validation :always) calls confirm-spell-mana-allocation regardless of
;; generic-remaining. The spell is cast with the partial allocation.

(deftest test-mana-allocation-confirm-with-generic-remaining-casts-spell
  (testing "mana-allocation confirm with generic-remaining > 0 still casts the spell"
    (let [db (th/create-test-db)
          ;; Add dark-ritual to hand (any card works — the mode defines the cost)
          db (mana/add-mana db :player-1 {:black 3})
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Build a mode with a generic component so build-mana-allocation-selection
          ;; returns a non-nil selection (it only returns one when generic > 0).
          ;; We use {:colorless 2 :black 1} as the mode cost — generic=2.
          mode {:mode/id :primary
                :mode/mana-cost {:colorless 2 :black 1}
                :mode/effects [{:effect/type :add-mana :effect/mana {:black 3}}]
                :mode/on-resolve :graveyard}
          selection (costs/build-mana-allocation-selection
                      db :player-1 spell-id mode {:colorless 2 :black 1})
          _ (assert selection "build-mana-allocation-selection must return non-nil for generic > 0")
          ;; Override to simulate partially-filled allocation: generic-remaining still 2
          selection (assoc selection
                           :selection/generic-remaining 2
                           :selection/allocation {})
          app-db {:game/db db :game/pending-selection selection}
          result (core/confirm-selection-impl app-db)]

      ;; :always validation passes — confirm-selection-impl should not reject
      (is (map? result)
          "confirm-selection-impl must return a map (not crash) with generic-remaining > 0")

      ;; The executor (confirm-spell-mana-allocation) calls cast-spell-mode-with-allocation
      ;; without rechecking generic-remaining. Spell is moved from hand to stack.
      (is (= :stack (th/get-object-zone (:game/db result) spell-id))
          "Spell should be on stack after mana-allocation confirm (partial allocation allowed)"))))


;; =====================================================
;; 8. HIGH corner case: :selection/exact? false — unlimited multi-select
;; =====================================================
;; toggle-selection-impl has a specific branch for :selection/exact? false:
;;   (false? (:selection/exact? selection))
;;   → always add to selected set (no limit check)
;; This branch is for selections like :exile-cards-cost where the player can
;; select any number of cards (no upper bound enforced by toggle logic).
;; Bug caught: if this branch were removed or changed to (false? ...)→ignore,
;; the player could never select more than select-count items in an unlimited
;; multi-select, silently capping their selection.

(deftest test-toggle-selection-impl-exact-false-allows-more-than-select-count
  (testing "toggle-selection-impl with :selection/exact? false allows selecting more items than select-count"
    ;; Bug caught: if the (false? :selection/exact?) branch is removed from toggle-selection-impl,
    ;; selecting item 2 when select-count=1 would be ignored (at limit branch fires).
    ;; With exact?=false, unlimited items can be added regardless of select-count.
    ;; Note: select-count must NOT be 1. When select-count=1 the single-select branch fires first
    ;; (cond is ordered). Use select-count=5 so the exact?=false branch is reached.
    (let [db (th/create-test-db)
          ;; Create a selection with select-count=5 but exact?=false (unlimited multi-select)
          sel {:selection/type :test-corner-noop
               :selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/select-count 5       ; >1 so single-select branch doesn't fire
               :selection/exact? false          ; exact?=false means: bypass the <count limit
               :selection/validation :always
               :selection/auto-confirm? false}
          app-db {:game/db db :game/pending-selection sel}
          ;; Toggle items — the exact?=false branch fires before "multi-select under limit"
          result1 (core/toggle-selection-impl app-db :item-a)
          result2 (core/toggle-selection-impl (:app-db result1) :item-b)
          result3 (core/toggle-selection-impl (:app-db result2) :item-c)
          final-selected (get-in (:app-db result3) [:game/pending-selection :selection/selected])]
      ;; All 3 items must be in selected — exact?=false branch fires (before "under limit" check)
      (is (= #{:item-a :item-b :item-c} final-selected)
          ":selection/exact? false must accumulate all toggled items"))))


(deftest test-toggle-selection-impl-exact-false-auto-confirm-not-triggered
  (testing "toggle-selection-impl with :selection/exact? false does not auto-confirm on first selection"
    ;; auto-confirm? is only true when BOTH selected?=true AND select-count=1 AND auto-confirm?=true.
    ;; When exact?=false with select-count=5, the exact?=false branch fires (not single-select),
    ;; so auto-confirm? is false even though the selection has auto-confirm?=true.
    ;; Bug caught: if exact?=false branch incorrectly triggered auto-confirm via the single-select
    ;; path, the selection would auto-complete after the first item, preventing multi-select.
    (let [db (th/create-test-db)
          sel {:selection/type :test-corner-noop
               :selection/lifecycle :standard
               :selection/player-id :player-1
               :selection/selected #{}
               :selection/select-count 5        ; >1 so single-select branch doesn't fire
               :selection/exact? false
               :selection/validation :always
               :selection/auto-confirm? true}   ; auto-confirm? true, but exact?=false with count=5
          app-db {:game/db db :game/pending-selection sel}
          result (core/toggle-selection-impl app-db :item-a)]
      ;; auto-confirm? must be false — auto-confirm only triggers when (= select-count 1)
      ;; The exact?=false branch fires here (select-count=5), and auto-confirm computation
      ;; requires (= select-count 1) which is false.
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when select-count != 1 (exact?=false path, count=5)"))))


;; =====================================================
;; 9. HIGH corner case: :resolve-one-and-stop on non-empty stack
;; =====================================================
;; The existing test only covers the empty-stack case (apply-continuation no-op).
;; This test verifies that when the stack has an item (e.g., a resolved spell),
;; :resolve-one-and-stop actually resolves it and returns a db with empty stack.
;; Bug caught: if resolve-one-and-stop has an off-by-one in the director call,
;; the stack item would remain unresolved.

(deftest test-resolve-one-and-stop-resolves-top-item-when-stack-non-empty
  (testing ":resolve-one-and-stop continuation resolves top stack item when stack is non-empty"
    ;; Bug caught: if apply-continuation :resolve-one-and-stop returns app-db unchanged
    ;; when stack is non-empty (missing the director.run-to-decision call), the stack
    ;; item would remain unresolved. This test verifies the non-empty-stack path.
    ;;
    ;; NOTE: Must use th/create-game-scenario (not th/create-test-db) because dispatch-sync
    ;; requires a full game state with :game/priority set. th/create-test-db only creates
    ;; the Datascript db without player/priority initialization.
    (let [base-app-db (th/create-game-scenario {:bot-archetype :goldfish :mana {:black 1}})
          game-db (:game/db base-app-db)
          [game-db spell-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          app-db (assoc base-app-db :game/db game-db)
          ;; Cast the spell via production path — puts it on the stack
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::casting/cast-spell {:object-id spell-id}])
          app-db-after-cast @rf-db/app-db
          ;; Stack should have 1 item (the cast spell)
          _ (is (= 1 (count (q/get-all-stack-items (:game/db app-db-after-cast))))
                "Precondition: spell is on stack after cast")
          ;; Now apply :resolve-one-and-stop continuation
          continuation {:continuation/type :resolve-one-and-stop}
          result (core/apply-continuation continuation app-db-after-cast)
          result-db (:game/db (:app-db result))]
      ;; Stack must be empty — the spell was resolved
      (is (= 0 (count (q/get-all-stack-items result-db)))
          ":resolve-one-and-stop must resolve the top stack item when stack is non-empty")
      ;; Spell moved to graveyard (dark-ritual resolves to graveyard)
      (is (= 1 (count (q/get-objects-in-zone result-db :player-1 :graveyard)))
          "Dark Ritual must be in graveyard after :resolve-one-and-stop resolves it"))))
