(ns fizzle.engine.tokens-test
  "Tests for token creation effect and token cleanup SBA."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.effects.tokens]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; === Token effect data ===

(def beast-token-effect
  {:effect/type :create-token
   :effect/token {:token/name "Beast"
                  :token/power 4
                  :token/toughness 4
                  :token/types #{:creature}
                  :token/subtypes #{:beast}
                  :token/colors #{:green}}})


(def spirit-token-effect
  {:effect/type :create-token
   :effect/token {:token/name "Spirit"
                  :token/power 1
                  :token/toughness 1
                  :token/types #{:creature}
                  :token/subtypes #{:spirit}
                  :token/colors #{:white}}})


;; === Token creation ===

(deftest test-create-token-on-battlefield
  (testing "create-token effect creates a token creature on the battlefield"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))]
      (is (= 1 (count (filter :object/is-token bf)))
          "Should have one token on battlefield")
      (is (= :battlefield (:object/zone token))
          "Token should be on battlefield")
      (is (true? (:object/is-token token))
          "Should be marked as token"))))


(deftest test-token-has-correct-pt
  (testing "Token has correct power and toughness"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))]
      (is (= 4 (:object/power token))
          "Token power should be 4")
      (is (= 4 (:object/toughness token))
          "Token toughness should be 4"))))


(deftest test-token-has-correct-types-and-colors
  (testing "Token has correct types, subtypes, and colors"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))
          card (:object/card token)]
      (is (= #{:creature} (set (:card/types card)))
          "Token card should have creature type")
      (is (= #{:beast} (set (:card/subtypes card)))
          "Token card should have beast subtype")
      (is (= #{:green} (set (:card/colors card)))
          "Token card should have green color"))))


(deftest test-token-has-summoning-sickness
  (testing "Token gets summoning sickness on creation"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))]
      (is (true? (:object/summoning-sick token))
          "Token should have summoning sickness")
      (is (= 0 (:object/damage-marked token))
          "Token should have 0 damage"))))


(deftest test-token-creature-predicate
  (testing "creature? returns true for tokens"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))]
      (is (true? (creatures/creature? db (:object/id token)))
          "Token should be recognized as creature"))))


(deftest test-token-effective-pt
  (testing "effective-power/toughness works for tokens"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))
          token-id (:object/id token)]
      (is (= 4 (creatures/effective-power db token-id))
          "Token effective power should be 4")
      (is (= 4 (creatures/effective-toughness db token-id))
          "Token effective toughness should be 4"))))


(deftest test-token-owner-and-controller
  (testing "Token is owned and controlled by the creating player"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))]
      (is (some? (:object/owner token))
          "Token should have an owner")
      (is (some? (:object/controller token))
          "Token should have a controller"))))


(deftest test-multiple-tokens
  (testing "Multiple create-token effects create separate tokens"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          db (effects/execute-effect db :player-1 spirit-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          tokens (filter :object/is-token bf)]
      (is (= 2 (count tokens))
          "Should have two tokens"))))


;; === Token cleanup SBA ===

(deftest test-token-cleanup-in-graveyard
  (testing "Token in graveyard ceases to exist (entity retracted)"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))
          token-id (:object/id token)
          ;; Move token to graveyard
          db (zones/move-to-zone* db token-id :graveyard)
          ;; Run SBAs
          db (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object db token-id))
          "Token should be completely removed from game"))))


(deftest test-token-cleanup-in-hand
  (testing "Token bounced to hand ceases to exist"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))
          token-id (:object/id token)
          ;; Move token to hand
          db (zones/move-to-zone* db token-id :hand)
          ;; Run SBAs
          db (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object db token-id))
          "Token bounced to hand should be removed"))))


(deftest test-token-cleanup-in-exile
  (testing "Token exiled ceases to exist"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))
          token-id (:object/id token)
          ;; Move token to exile
          db (zones/move-to-zone* db token-id :exile)
          ;; Run SBAs
          db (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object db token-id))
          "Token in exile should be removed"))))


(deftest test-token-stays-on-battlefield
  (testing "Token on battlefield is NOT cleaned up by SBA"
    (let [db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token (first (filter :object/is-token bf))
          token-id (:object/id token)
          ;; Run SBAs — token should survive
          db (sba/check-and-execute-sbas db)]
      (is (some? (q/get-object db token-id))
          "Token on battlefield should not be removed"))))


(deftest test-non-token-not-cleaned-up
  (testing "Non-token objects are not affected by token cleanup SBA"
    (let [db (th/create-test-db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :graveyard :player-1)
          ;; Run SBAs — non-token should survive
          db (sba/check-and-execute-sbas db)]
      (is (some? (q/get-object db petal-id))
          "Non-token in graveyard should not be removed"))))
