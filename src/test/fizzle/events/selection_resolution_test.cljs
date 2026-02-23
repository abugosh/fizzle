(ns fizzle.events.selection-resolution-test
  "Tests for the selection protocol: tagged return values from execute-effect-impl.

   Verifies:
   - Interactive effects signal :needs-selection via execute-effect-checked
   - Non-interactive effects return plain {:db db'} via execute-effect-checked
   - Sync test: every build-selection-for-effect dispatch value has a
     corresponding effect type that returns :needs-selection"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.engine.effects :as fx]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Sync test: builder dispatch values → tagged effects
;; =====================================================

(def ^:private dispatch-representative-effects
  "Map of build-selection-for-effect dispatch values to representative
   effect maps that should return :needs-selection from execute-effect-checked.
   Excludes :default (fallback handler) and :player-target (detected by
   :any-player target, not by execute-effect-impl)."
  {:tutor               {:effect/type :tutor}
   :scry                {:effect/type :scry :effect/amount 1}
   :peek-and-select     {:effect/type :peek-and-select}
   :discard             {:effect/type :discard :effect/selection :player}
   :return-from-graveyard {:effect/type :return-from-graveyard :effect/selection :player}
   :discard-from-revealed-hand {:effect/type :discard-from-revealed-hand :effect/target :player-1}})


(deftest test-tagged-returns-cover-all-builder-dispatch-values
  (testing "Every build-selection-for-effect dispatch value has an effect that signals :needs-selection"
    (let [db (init-game-state)
          dispatch-keys (disj (set (keys (methods core/build-selection-for-effect)))
                              :default :player-target)]
      ;; Verify every dispatch key has a representative effect
      (doseq [k dispatch-keys]
        (is (contains? dispatch-representative-effects k)
            (str "Missing representative effect for dispatch value: " k
                 ". Add an entry to dispatch-representative-effects.")))
      ;; Verify each representative effect returns :needs-selection
      (doseq [[k effect] dispatch-representative-effects]
        (let [result (fx/execute-effect-checked db :player-1 effect)]
          (is (contains? result :needs-selection)
              (str "execute-effect-checked should return :needs-selection for: " k)))))))


(deftest test-non-interactive-effects-return-plain-db
  (testing "Non-interactive effects return {:db db'} without :needs-selection"
    (let [db (init-game-state)]
      (doseq [effect [{:effect/type :add-mana :effect/mana {:black 1}}
                      {:effect/type :draw :effect/amount 1}
                      {:effect/type :mill :effect/amount 1}
                      {:effect/type :exile-self}
                      {:effect/type :sacrifice}]]
        (let [result (fx/execute-effect-checked db :player-1 effect)]
          (is (not (contains? result :needs-selection))
              (str "Effect " (:effect/type effect) " should not return :needs-selection")))))))


(deftest test-scry-zero-is-not-interactive
  (testing "Scry 0 and negative are not interactive (no cards to arrange)"
    (let [db (init-game-state)]
      (doseq [amount [0 -1]]
        (let [result (fx/execute-effect-checked db :player-1
                                                {:effect/type :scry :effect/amount amount})]
          (is (not (contains? result :needs-selection))
              (str "Scry " amount " should not be interactive")))))))


(deftest test-return-from-graveyard-random-is-not-interactive
  (testing ":return-from-graveyard with :random selection is handled by engine"
    (let [db (init-game-state)
          result (fx/execute-effect-checked db :player-1
                                            {:effect/type :return-from-graveyard
                                             :effect/count 1
                                             :effect/selection :random})]
      (is (not (contains? result :needs-selection))))))
