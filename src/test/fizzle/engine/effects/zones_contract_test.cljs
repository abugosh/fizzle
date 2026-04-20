(ns fizzle.engine.effects.zones-contract-test
  "Contract tests for engine/effects/zones.cljs defmethods.

   Verifies that:
   - :return-from-graveyard throws on unmatched :effect/selection (not :random or :player)
   - :return-from-graveyard throws when :effect/count is missing, nil, 0, or negative
   - :return-from-graveyard :random arm still moves cards with valid count
   - :return-from-graveyard :player arm still returns :needs-selection
   - :shuffle-from-graveyard-to-library throws on unmatched :effect/selection
   - :shuffle-from-graveyard-to-library :auto arm still shuffles GY to library
   - :shuffle-from-graveyard-to-library :player arm still returns :needs-selection"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]
    [fizzle.test-helpers :as th]))


;; ===========================================================================
;; :return-from-graveyard — Layer 1 contract tests (throw on bad inputs)
;; ===========================================================================

(deftest zones-return-from-graveyard-throws-on-unmatched-selection-test
  (testing ":return-from-graveyard throws ex-info with :effect-type and :selection when :effect/selection is unrecognized"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/selection :bogus
                  :effect/count 3}]
      (is (thrown-with-msg? js/Error #"return-from-graveyard"
            (fx/execute-effect-checked db :player-1 effect)))
      (try
        (fx/execute-effect-checked db :player-1 effect)
        (is false "Should have thrown")
        (catch :default e
          (is (= :return-from-graveyard (:effect-type (ex-data e)))
              "ex-data should contain :effect-type :return-from-graveyard")
          (is (= :bogus (:selection (ex-data e)))
              "ex-data should contain :selection :bogus"))))))


(deftest zones-return-from-graveyard-throws-on-missing-effect-count-test
  (testing ":return-from-graveyard throws ex-info when :effect/count is absent"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/selection :random}]  ; no :effect/count key
      (is (thrown-with-msg? js/Error #"return-from-graveyard"
            (fx/execute-effect-checked db :player-1 effect)))
      (try
        (fx/execute-effect-checked db :player-1 effect)
        (is false "Should have thrown")
        (catch :default e
          (is (= :return-from-graveyard (:effect-type (ex-data e)))
              "ex-data should contain :effect-type")
          (is (nil? (:effect/count (ex-data e)))
              "ex-data should show nil :effect/count"))))))


(deftest zones-return-from-graveyard-throws-on-nil-effect-count-test
  (testing ":return-from-graveyard throws ex-info when :effect/count is nil"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/selection :random
                  :effect/count nil}]
      (is (thrown-with-msg? js/Error #"return-from-graveyard"
            (fx/execute-effect-checked db :player-1 effect)))
      (try
        (fx/execute-effect-checked db :player-1 effect)
        (is false "Should have thrown")
        (catch :default e
          (is (= :return-from-graveyard (:effect-type (ex-data e)))
              "ex-data should contain :effect-type")
          (is (nil? (:effect/count (ex-data e)))
              "ex-data should show nil :effect/count"))))))


(deftest zones-return-from-graveyard-throws-on-zero-effect-count-test
  (testing ":return-from-graveyard throws ex-info when :effect/count is 0"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/selection :random
                  :effect/count 0}]
      (is (thrown-with-msg? js/Error #"return-from-graveyard"
            (fx/execute-effect-checked db :player-1 effect)))
      (try
        (fx/execute-effect-checked db :player-1 effect)
        (is false "Should have thrown")
        (catch :default e
          (is (= :return-from-graveyard (:effect-type (ex-data e)))
              "ex-data should contain :effect-type")
          (is (= 0 (:effect/count (ex-data e)))
              "ex-data should show :effect/count 0"))))))


(deftest zones-return-from-graveyard-throws-on-negative-effect-count-test
  (testing ":return-from-graveyard throws ex-info when :effect/count is negative"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/selection :random
                  :effect/count -1}]
      (is (thrown-with-msg? js/Error #"return-from-graveyard"
            (fx/execute-effect-checked db :player-1 effect)))
      (try
        (fx/execute-effect-checked db :player-1 effect)
        (is false "Should have thrown")
        (catch :default e
          (is (= :return-from-graveyard (:effect-type (ex-data e)))
              "ex-data should contain :effect-type")
          (is (= -1 (:effect/count (ex-data e)))
              "ex-data should show :effect/count -1"))))))


;; ===========================================================================
;; :return-from-graveyard — positive regression tests
;; ===========================================================================

(deftest zones-return-from-graveyard-random-with-valid-count-moves-cards-test
  (testing ":return-from-graveyard :random with valid count moves cards to hand"
    (let [db (init-game-state)
          [db-with-gy _ids] (th/add-cards-to-graveyard db
                                                       [:dark-ritual :dark-ritual :dark-ritual
                                                        :dark-ritual :dark-ritual]
                                                       :player-1)
          gy-before (count (q/get-objects-in-zone db-with-gy :player-1 :graveyard))
          hand-before (count (q/get-hand db-with-gy :player-1))
          effect {:effect/type :return-from-graveyard
                  :effect/selection :random
                  :effect/count 2}
          result (fx/execute-effect-checked db-with-gy :player-1 effect)
          db-after (:db result)]
      (is (= 5 gy-before) "Should have 5 cards in GY before")
      (is (not (contains? result :needs-selection))
          ":random selection should not be interactive")
      (is (= (+ hand-before 2) (count (q/get-hand db-after :player-1)))
          "Hand should have 2 more cards")
      (is (= (- gy-before 2) (count (q/get-objects-in-zone db-after :player-1 :graveyard)))
          "GY should have 2 fewer cards"))))


(deftest zones-return-from-graveyard-player-returns-needs-selection-test
  (testing ":return-from-graveyard :player returns {:db :needs-selection}"
    (let [db (init-game-state)
          effect {:effect/type :return-from-graveyard
                  :effect/selection :player
                  :effect/count 3}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection)
          ":player selection should be interactive")
      (is (= :return-from-graveyard (:effect/type (:needs-selection result)))
          ":needs-selection value should be the original effect"))))


;; ===========================================================================
;; :shuffle-from-graveyard-to-library — Layer 1 contract tests
;; ===========================================================================

(deftest zones-shuffle-from-graveyard-throws-on-unmatched-selection-test
  (testing ":shuffle-from-graveyard-to-library throws ex-info when :effect/selection is unrecognized"
    (let [db (init-game-state)
          effect {:effect/type :shuffle-from-graveyard-to-library
                  :effect/selection :bogus}]
      (is (thrown-with-msg? js/Error #"shuffle-from-graveyard-to-library"
            (fx/execute-effect-checked db :player-1 effect)))
      (try
        (fx/execute-effect-checked db :player-1 effect)
        (is false "Should have thrown")
        (catch :default e
          (is (= :shuffle-from-graveyard-to-library (:effect-type (ex-data e)))
              "ex-data should contain :effect-type :shuffle-from-graveyard-to-library")
          (is (= :bogus (:selection (ex-data e)))
              "ex-data should contain :selection :bogus"))))))


;; ===========================================================================
;; :shuffle-from-graveyard-to-library — positive regression tests
;; ===========================================================================

(deftest zones-shuffle-from-graveyard-auto-shuffles-all-test
  (testing ":shuffle-from-graveyard-to-library :auto moves all GY cards to library"
    (let [db (init-game-state)
          [db-with-gy _ids] (th/add-cards-to-graveyard db
                                                       [:dark-ritual :dark-ritual :dark-ritual]
                                                       :player-1)
          gy-before (count (q/get-objects-in-zone db-with-gy :player-1 :graveyard))
          lib-before (count (q/get-objects-in-zone db-with-gy :player-1 :library))
          effect {:effect/type :shuffle-from-graveyard-to-library
                  :effect/selection :auto}
          result (fx/execute-effect-checked db-with-gy :player-1 effect)
          db-after (:db result)]
      (is (= 3 gy-before) "Should have 3 cards in GY before")
      (is (not (contains? result :needs-selection))
          ":auto selection should not be interactive")
      (is (= 0 (count (q/get-objects-in-zone db-after :player-1 :graveyard)))
          "GY should be empty after :auto shuffle")
      (is (= (+ lib-before gy-before)
             (count (q/get-objects-in-zone db-after :player-1 :library)))
          "Library should have grown by the number of GY cards"))))


(deftest zones-shuffle-from-graveyard-player-returns-needs-selection-test
  (testing ":shuffle-from-graveyard-to-library :player returns {:db :needs-selection}"
    (let [db (init-game-state)
          effect {:effect/type :shuffle-from-graveyard-to-library
                  :effect/selection :player
                  :effect/count 3}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection)
          ":player selection should be interactive")
      (is (= :shuffle-from-graveyard-to-library (:effect/type (:needs-selection result)))
          ":needs-selection value should be the original effect"))))
