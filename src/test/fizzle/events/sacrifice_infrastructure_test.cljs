(ns fizzle.events.sacrifice-infrastructure-test
  "Tests for sacrifice-permanent cost infrastructure extensions:
   - Power capture before sacrifice (stored on stack item as :stack-item/sacrifice-info)
   - :sacrificed-power dynamic value type
   - Ability activation path for interactive sacrifice costs
   - Auto-confirm disabled (player always picks)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.casting :as casting]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helpers
;; =====================================================

(defn- cast-fling-and-confirm-sacrifice
  "Cast Fling, confirm the sacrifice of creature-id, then confirm targeting
   of target-player-id. Returns db with Fling on stack."
  [db creature-id target-player-id]
  (let [[db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
        app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
        sac-sel (:game/pending-selection app-db)
        ;; Confirm sacrifice — chains to targeting (lifecycle :chaining)
        {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})
        ;; Confirm targeting — Fling goes on stack
        {:keys [db]} (th/confirm-selection db selection #{target-player-id})]
    [db fling-id]))


;; =====================================================
;; Power Tracking Tests
;; =====================================================

(deftest sacrifice-stores-base-power-on-stack-item-test
  (testing "sacrificing a 1/1 creature stores {:power 1} on stack item"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (cast-fling-and-confirm-sacrifice db creature-id :player-2)
          fling-eid (q/get-object-eid db fling-id)
          stack-item (when fling-eid
                       (d/q '[:find (pull ?e [*]) .
                              :in $ ?obj-eid
                              :where [?e :stack-item/object-ref ?obj-eid]]
                            db fling-eid))]
      ;; Creature should be in graveyard (sacrificed)
      (is (= :graveyard (:object/zone (q/get-object db creature-id)))
          "Sacrificed creature should be in graveyard")
      ;; Stack item should have sacrifice-info with power 1 (Nimble Mongoose base power)
      (is (= {:power 1} (:stack-item/sacrifice-info stack-item))
          "Stack item should have sacrifice-info {:power 1} for 1/1 creature"))))


(deftest sacrifice-stores-effective-power-with-counters-test
  (testing "sacrificing a creature with +1/+1 counter stores effective power (base + counter)"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db creature-id)
          db (d/db-with db [[:db/add creature-eid :object/counters {:+1/+1 1}]])
          _ (is (= 2 (creatures/effective-power db creature-id))
                "Effective power should be 2 (1 base + 1 counter)")
          [db fling-id] (cast-fling-and-confirm-sacrifice db creature-id :player-2)
          fling-eid (q/get-object-eid db fling-id)
          stack-item (when fling-eid
                       (d/q '[:find (pull ?e [*]) .
                              :in $ ?obj-eid
                              :where [?e :stack-item/object-ref ?obj-eid]]
                            db fling-eid))]
      (is (= {:power 2} (:stack-item/sacrifice-info stack-item))
          "Stack item should have sacrifice-info {:power 2} for 1/1 with +1/+1 counter"))))


(deftest sacrifice-stores-zero-power-test
  (testing "sacrificing a 0-power creature stores {:power 0}, not nil"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db creature-id)
          ;; Add a -1/-1 counter so effective power = 1 - 1 = 0
          db (d/db-with db [[:db/add creature-eid :object/counters {:-1/-1 1}]])
          _ (is (= 0 (creatures/effective-power db creature-id))
                "Effective power should be 0")
          [db fling-id] (cast-fling-and-confirm-sacrifice db creature-id :player-2)
          fling-eid (q/get-object-eid db fling-id)
          stack-item (when fling-eid
                       (d/q '[:find (pull ?e [*]) .
                              :in $ ?obj-eid
                              :where [?e :stack-item/object-ref ?obj-eid]]
                            db fling-eid))]
      (is (= {:power 0} (:stack-item/sacrifice-info stack-item))
          "Stack item should have sacrifice-info {:power 0}, not nil, for 0-power creature"))))


(deftest sacrifice-stores-threshold-effective-power-test
  (testing "sacrificing Nimble Mongoose at threshold stores power 3 (1+2 threshold bonus)"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Fill graveyard to 7 (threshold)
          [db _] (th/add-cards-to-graveyard db
                                            [:dark-ritual :dark-ritual :dark-ritual :dark-ritual
                                             :dark-ritual :dark-ritual :dark-ritual]
                                            :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          _ (is (= 3 (creatures/effective-power db creature-id))
                "Nimble Mongoose should be 3/3 at threshold")
          [db fling-id] (cast-fling-and-confirm-sacrifice db creature-id :player-2)
          fling-eid (q/get-object-eid db fling-id)
          stack-item (when fling-eid
                       (d/q '[:find (pull ?e [*]) .
                              :in $ ?obj-eid
                              :where [?e :stack-item/object-ref ?obj-eid]]
                            db fling-eid))]
      (is (= {:power 3} (:stack-item/sacrifice-info stack-item))
          "Should store effective power 3 (threshold bonus applied before sacrifice)"))))


;; =====================================================
;; :sacrificed-power Dynamic Value Tests
;; =====================================================

(deftest sacrificed-power-dynamic-value-test
  (testing ":sacrificed-power resolves to stored power from stack item sacrifice-info"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (cast-fling-and-confirm-sacrifice db creature-id :player-2)
          dynamic {:dynamic/type :sacrificed-power}
          resolved (effects/resolve-dynamic-value db :player-1 dynamic fling-id)]
      (is (= 1 resolved)
          ":sacrificed-power should resolve to 1 (Nimble Mongoose base power)"))))


(deftest sacrificed-power-resolves-to-zero-for-zero-power-test
  (testing ":sacrificed-power resolves to 0 when sacrifice-info has {:power 0}"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          creature-eid (q/get-object-eid db creature-id)
          db (d/db-with db [[:db/add creature-eid :object/counters {:-1/-1 1}]])
          [db fling-id] (cast-fling-and-confirm-sacrifice db creature-id :player-2)
          dynamic {:dynamic/type :sacrificed-power}
          resolved (effects/resolve-dynamic-value db :player-1 dynamic fling-id)]
      (is (= 0 resolved)
          ":sacrificed-power should resolve to 0 when creature had 0 power"))))


;; =====================================================
;; Auto-Confirm Disabled Tests
;; =====================================================

(deftest sacrifice-selection-no-auto-confirm-test
  (testing "sacrifice selection has auto-confirm? false even with one creature"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          ;; Only one creature on battlefield
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          pending-sel (:game/pending-selection app-db)]
      (is (= :sacrifice-permanent-cost (:selection/type pending-sel))
          "Should show sacrifice-permanent-cost selection")
      (is (false? (:selection/auto-confirm? pending-sel))
          "auto-confirm? should be false — player always picks even with one creature"))))


;; =====================================================
;; Ability Activation Path Tests
;; =====================================================

(deftest ability-sacrifice-cost-shows-selection-test
  (testing "ability with sacrifice-permanent cost shows selection modal"
    (let [db (th/create-test-db)
          ;; Add Altar of Dementia to battlefield
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          ;; Add a creature to sacrifice
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          ;; Add opponent for targeting
          db (th/add-opponent db)
          ;; Activate ability (index 0)
          result (abilities/activate-ability db :player-1 altar-id 0)]
      (is (some? (:pending-selection result))
          "Activating sacrifice ability should produce a pending selection")
      (is (= :sacrifice-permanent-cost (:selection/type (:pending-selection result)))
          "Pending selection should be :sacrifice-permanent-cost"))))


(deftest ability-sacrifice-creates-stack-item-after-confirmation-test
  (testing "confirming sacrifice in ability context chains to targeting then creates stack item"
    (let [db (th/create-test-db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          db (th/add-opponent db)
          ;; Activate ability — get sacrifice selection
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          ;; Confirm sacrifice — should chain to ability-targeting selection
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          ;; Should have a targeting selection (Altar targets a player)
          _ (is (some? selection) "Should chain to targeting selection after sacrifice")
          _ (is (= :ability-targeting (:selection/type selection))
                "Chain selection should be ability-targeting")
          ;; Confirm target player
          {:keys [db]} (th/confirm-selection db selection #{:player-2})]
      ;; Stack item should exist
      (is (some? (stack/get-top-stack-item db))
          "Stack item should be created after full ability activation"))))


(deftest ability-sacrifice-stores-power-on-stack-item-test
  (testing "ability sacrifice stores power on stack item"
    (let [db (th/create-test-db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          ;; Nimble Mongoose is 1/1
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          db (th/add-opponent db)
          result (abilities/activate-ability db :player-1 altar-id 0)
          sac-sel (:pending-selection result)
          ;; Confirm sacrifice — chains to targeting
          {:keys [db selection]} (th/confirm-selection (:db result) sac-sel #{creature-id})
          ;; Confirm target player — creates stack item
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          ;; Get top stack item
          top-item (stack/get-top-stack-item db)]
      (is (some? top-item) "Stack item should exist after ability activation")
      (is (= {:power 1} (:stack-item/sacrifice-info top-item))
          "Stack item should have sacrifice-info {:power 1}"))))


(deftest ability-cannot-activate-without-matching-creatures-test
  (testing "cannot activate ability with sacrifice-permanent cost when no matching creatures"
    (let [db (th/create-test-db)
          [db altar-id] (th/add-card-to-zone db :altar-of-dementia :battlefield :player-1)
          ;; No creatures on battlefield (only the altar itself, which is an artifact)
          db (th/add-opponent db)
          result (abilities/activate-ability db :player-1 altar-id 0)]
      (is (nil? (:pending-selection result))
          "Should not produce a pending selection when no creatures to sacrifice")
      (is (nil? (stack/get-top-stack-item db))
          "No stack item should be created when costs cannot be paid"))))


(deftest smoke-test
  (testing "smoke test to verify tests are running"
    (is (= 1 1))))


(deftest debug-can-cast-fling-test
  (testing "debug: can-cast? returns true for Fling with creature on battlefield"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)]
      (is (true? (rules/can-cast? db :player-1 fling-id))
          "Fling should be castable"))))


(deftest debug-cast-fling-shows-sacrifice-test
  (testing "debug: casting Fling shows sacrifice selection"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          pending-sel (:game/pending-selection app-db)]
      (is (some? pending-sel) "Should show a pending selection")
      (is (= :sacrifice-permanent-cost (:selection/type pending-sel))
          "Should show sacrifice selection"))))


(deftest debug-confirm-sacrifice-chains-test
  (testing "debug: confirming sacrifice chains to targeting"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {selection :selection} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})]
      (is (some? selection) "Should chain to targeting selection")
      (is (= :cast-time-targeting (:selection/type selection))
          "Should chain to cast-time-targeting"))))


(deftest debug-full-fling-flow-test
  (testing "debug: full fling flow puts it on stack"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)
          app-db (casting/cast-spell-handler {:game/db db :game/selected-card fling-id})
          sac-sel (:game/pending-selection app-db)
          {:keys [db selection]} (th/confirm-selection (:game/db app-db) sac-sel #{creature-id})
          {:keys [db]} (th/confirm-selection db selection #{:player-2})
          fling-eid (fizzle.db.queries/get-object-eid db fling-id)
          stack-item (when fling-eid
                       (d/q '[:find (pull ?e [*]) .
                              :in $ ?obj-eid
                              :where [?e :stack-item/object-ref ?obj-eid]]
                            db fling-eid))]
      (is (= :stack (:object/zone (fizzle.db.queries/get-object db fling-id)))
          "Fling should be on stack")
      (is (some? stack-item) "Stack item should exist")
      (is (= {:power 1} (:stack-item/sacrifice-info stack-item))
          "Stack item should have sacrifice-info"))))
