(ns fizzle.events.selection.corner-cases-test
  "Corner case tests for the core selection mechanism (events/selection/core.cljs).

   Covers:
   1. Invalid :selection/lifecycle value — case expression throws
   2. standard-path with interactive remaining-effects — chained interactive effect
      causes a NEW pending-selection to be built rather than clearing it
   3. toggle-selection-impl with no :game/pending-selection — nil safety"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection.core :as core]
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
