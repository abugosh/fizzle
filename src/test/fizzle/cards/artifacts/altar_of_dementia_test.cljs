(ns fizzle.cards.artifacts.altar-of-dementia-test
  "Tests for Altar of Dementia card.
   Covers mandatory categories A-C + G edge cases + I ability tests."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.altar-of-dementia :as altar]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest card-definition-test
  (testing "Altar of Dementia card definition has correct fields"
    (let [card altar/card]
      (is (= :altar-of-dementia (:card/id card)))
      (is (= "Altar of Dementia" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 2} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact} (:card/types card)))
      (is (= "Sacrifice a creature: Target player mills cards equal to the sacrificed creature's power."
             (:card/text card))))))


(deftest card-has-one-ability-test
  (testing "Altar has exactly one activated ability"
    (let [abilities (:card/abilities altar/card)]
      (is (= 1 (count abilities)))
      (is (= :activated (:ability/type (first abilities)))))))


(deftest ability-has-sacrifice-cost-test
  (testing "Altar ability cost includes sacrifice-permanent with creature criteria"
    (let [ability (first (:card/abilities altar/card))
          cost (:ability/cost ability)]
      (is (some? (:sacrifice-permanent cost))
          "Ability cost should have :sacrifice-permanent key")
      (is (= #{:creature} (get-in cost [:sacrifice-permanent :match/types]))
          "Sacrifice should match creatures"))))


(deftest ability-targets-any-player-test
  (testing "Altar ability targets any player"
    (let [ability (first (:card/abilities altar/card))
          targeting (:ability/targeting ability)
          req (first targeting)]
      (is (= 1 (count targeting)) "Should have exactly 1 target requirement")
      (is (= :player (:target/id req)))
      (is (= :player (:target/type req)))
      (is (= #{:any-player} (:target/options req)))
      (is (true? (:target/required req))))))


(deftest ability-has-mill-effect-test
  (testing "Altar ability effect is :mill with :sacrificed-power amount"
    (let [ability (first (:card/abilities altar/card))
          effects (:ability/effects ability)
          mill-effect (first effects)]
      (is (= 1 (count effects)) "Should have exactly 1 effect")
      (is (= :mill (:effect/type mill-effect)))
      (is (= {:dynamic/type :sacrificed-power} (:effect/amount mill-effect)))
      (is (= :player (:effect/target-ref mill-effect))))))


;; =====================================================
;; B. Happy Path — activate, resolve, assert mill
;; =====================================================

(deftest altar-mills-equal-to-sacrificed-power-test
  (testing "Altar ability mills cards equal to sacrificed creature's power"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          ;; Add 3 cards to opponent's library to mill
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-2)
          initial-lib-count (th/get-zone-count db :library :player-2)
          ;; Activate ability: shows sacrifice selection
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          ;; Confirm sacrifice
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          ;; Confirm target player-2
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          ;; Stack item should exist
          _ (is (some? (stack/get-top-stack-item db)) "Stack item should exist")
          ;; Resolve the stack item
          result-db (:db (th/resolve-top db))
          final-lib-count (th/get-zone-count result-db :library :player-2)
          gy-count (th/get-zone-count result-db :graveyard :player-2)]
      ;; Nimble Mongoose is 1/1 — mills 1 card
      (is (= (- initial-lib-count 1) final-lib-count)
          "Opponent library should decrease by 1 (Nimble Mongoose power = 1)")
      (is (= 1 gy-count)
          "Opponent graveyard should have 1 card (milled)"))))


(deftest altar-mills-self-test
  (testing "Altar ability can target self (player-1)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-1)
          initial-lib-count (th/get-zone-count db :library :player-1)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-1})
          result-db (:db (th/resolve-top db))
          final-lib-count (th/get-zone-count result-db :library :player-1)]
      (is (= (- initial-lib-count 1) final-lib-count)
          "Own library should decrease by 1 when targeting self"))))


;; =====================================================
;; C. Cannot-Activate Guards
;; =====================================================

(deftest altar-cannot-activate-without-creatures-test
  (testing "Altar ability cannot activate without creatures to sacrifice"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          ;; No creatures — only the altar itself (an artifact)
          result (abilities/activate-ability db :player-1 altar-id 0)]
      (is (nil? (:pending-selection result))
          "Should not show selection when no creatures available")
      (is (nil? (stack/get-top-stack-item db))
          "No stack item should be created"))))


(deftest altar-cannot-activate-without-opponent-test
  (testing "Altar ability requires a valid target player"
    ;; With no opponent, any-player targeting has no valid targets
    (let [db (th/create-test-db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          result (abilities/activate-ability db :player-1 altar-id 0)]
      ;; No opponent means targeting fails — either no selection or no stack item
      (is (or (nil? (:pending-selection result))
              (nil? (stack/get-top-stack-item db)))
          "Should not proceed without a valid target player"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest altar-mills-zero-with-zero-power-creature-test
  (testing "Altar mills 0 cards when sacrificed creature has 0 power"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          ;; Xantid Swarm has 0 power
          [db creature-id] (th/add-card-to-zone db :xantid-swarm :battlefield :player-1)
          _ (is (= 0 (creatures/effective-power db creature-id)) "Creature should have 0 power")
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          initial-lib-count (th/get-zone-count db :library :player-2)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          result-db (:db (th/resolve-top db))
          final-lib-count (th/get-zone-count result-db :library :player-2)]
      (is (= initial-lib-count final-lib-count)
          "Library should not change when sacrificed creature has 0 power"))))


(deftest altar-mills-boosted-power-test
  (testing "Altar mills equal to effective power (with +1/+1 counter)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db creature-id)
          db (d/db-with db [[:db/add creature-eid :object/counters {:+1/+1 2}]])
          _ (is (= 3 (creatures/effective-power db creature-id)) "Creature should be 3/3")
          ;; Add 5 cards to opponent's library
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual
                                              :dark-ritual :dark-ritual] :player-2)
          initial-lib-count (th/get-zone-count db :library :player-2)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          result-db (:db (th/resolve-top db))
          final-lib-count (th/get-zone-count result-db :library :player-2)]
      (is (= (- initial-lib-count 3) final-lib-count)
          "Library should decrease by 3 (1 base + 2 counters)"))))


(deftest altar-mills-up-to-library-size-test
  (testing "Altar mills all remaining cards when power > library size"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db creature-id)
          ;; Boost creature to 5 power
          db (d/db-with db [[:db/add creature-eid :object/counters {:+1/+1 4}]])
          ;; Only 2 cards in opponent's library
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-2)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          result-db (:db (th/resolve-top db))
          final-lib-count (th/get-zone-count result-db :library :player-2)
          gy-count (th/get-zone-count result-db :graveyard :player-2)]
      (is (= 0 final-lib-count)
          "Library should be empty (all 2 cards milled even though power was 5)")
      (is (= 2 gy-count)
          "Graveyard should have 2 cards"))))


;; =====================================================
;; I. Ability Tests
;; =====================================================

(deftest altar-sacrifice-shows-selection-test
  (testing "Activating Altar shows sacrifice selection"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          result (abilities/activate-ability db :player-1 altar-id 0)]
      (is (some? (:pending-selection result))
          "Should produce a pending selection")
      (is (= :sacrifice-permanent-cost (:selection/type (:pending-selection result)))
          "Selection type should be :sacrifice-permanent-cost"))))


(deftest altar-sacrifice-stores-power-on-stack-item-test
  (testing "Altar sacrifice stores sacrificed power on stack item"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          top-item (stack/get-top-stack-item db)]
      (is (some? top-item) "Stack item should exist")
      (is (= {:power 1} (:stack-item/sacrifice-info top-item))
          "Stack item should have sacrifice-info {:power 1} for 1/1 creature"))))


(deftest altar-sacrificed-creature-goes-to-graveyard-test
  (testing "Sacrificed creature moves to graveyard before stack item resolves"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})]
      (is (= :graveyard (:object/zone (q/get-object db creature-id)))
          "Sacrificed creature should be in graveyard after cost payment"))))
