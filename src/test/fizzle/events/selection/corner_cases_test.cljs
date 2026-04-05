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
   7. Mana allocation confirm with generic-remaining > 0 — spell still casts"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.events.casting :as casting]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.costs :as costs]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test executor defmethods — used only by corner case tests
;; =====================================================

;; A minimal executor that returns db unchanged, for routing tests.
(defmethod core/execute-confirmed-selection :test-corner-noop
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
  (testing "toggle-selection-impl does not crash when :game/pending-selection is absent"
    ;; When toggle-selection-impl is called with no pending-selection,
    ;; (:game/pending-selection app-db) returns nil.
    ;; The function reads nil as the selection, then:
    ;;   selected  = (get nil :selection/selected #{}) = #{}
    ;;   valid-targets = nil (no filter applied)
    ;;   select-count  = (get nil :selection/select-count 0) = 0
    ;;   currently-selected? = (contains? #{} id) = false
    ;; Falls to the :else branch (at limit with 0 select-count): returns [app-db false].
    ;; So the result is {:app-db app-db :auto-confirm? false} — no crash.
    (let [db (th/create-test-db)
          app-db {:game/db db}   ; no :game/pending-selection key
          result (core/toggle-selection-impl app-db :some-id)]

      (is (map? result)
          "toggle-selection-impl must return a map even when pending-selection is absent")
      (is (contains? result :app-db)
          "Result must have :app-db key")
      (is (contains? result :auto-confirm?)
          "Result must have :auto-confirm? key")
      (is (= app-db (:app-db result))
          "app-db must be unchanged — no pending-selection to modify")
      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when no selection exists"))))


(deftest test-toggle-selection-impl-with-nil-key-present
  (testing "toggle-selection-impl handles explicit nil :game/pending-selection"
    ;; Same nil-safety contract as above, but the key is present with nil value.
    (let [db (th/create-test-db)
          app-db {:game/db db :game/pending-selection nil}
          result (core/toggle-selection-impl app-db :some-id)]

      (is (false? (:auto-confirm? result))
          "auto-confirm? must be false when pending-selection is nil")
      (is (= app-db (:app-db result))
          "app-db must be unchanged when pending-selection is nil"))))


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
