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
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection :as selection]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register history interceptor and SBA dispatch — required for dispatch-sync tests.
;; Both are idempotent: safe to call even if other test files registered first.
(interceptor/register!)
(db-effect/register!)


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


(deftest debug-full-fling-flow-test
  (testing "full fling flow puts it on stack with sacrifice-info"
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


;; =====================================================
;; End-to-End Dispatch Test (rf/dispatch-sync)
;; =====================================================

(deftest fling-end-to-end-dispatch-sync-test
  (testing "Fling sacrifice→targeting chain via rf/dispatch-sync exercises interceptor + SBAs"
    ;; Bug caught: the handler-fn-direct tests above bypass re-frame pipeline so they never
    ;; exercise the history interceptor (no :history/main entries) and don't trigger SBAs.
    ;; This test verifies the full production path:
    ;;   cast-spell → sacrifice selection → confirm → targeting selection → confirm
    ;;   → resolve → damage applied → SBAs run → history entry created.
    (let [base-app-db (th/create-game-scenario {:bot-archetype :goldfish
                                                :mana {:red 1 :colorless 1}})
          game-db (:game/db base-app-db)
          [game-db creature-id] (th/add-card-to-zone game-db :nimble-mongoose :battlefield :player-1)
          [game-db fling-id] (th/add-card-to-zone game-db :fling :hand :player-1)
          app-db (assoc base-app-db :game/db game-db)
          ;; Step 1: cast Fling — triggers sacrifice selection (deferred-entry set)
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::casting/cast-spell {:object-id fling-id}])
          after-cast @rf-db/app-db
          _ (is (= :sacrifice-permanent-cost
                   (:selection/type (:game/pending-selection after-cast)))
                "Precondition: sacrifice selection pending after cast")
          ;; Step 2: toggle the creature (select it for sacrifice)
          _ (rf/dispatch-sync [::selection/toggle-selection creature-id])
          ;; Step 3: confirm sacrifice — chains to targeting, deferred-entry → pending-entry
          _ (rf/dispatch-sync [::selection/confirm-selection])
          after-sac @rf-db/app-db
          _ (is (= :cast-time-targeting
                   (:selection/type (:game/pending-selection after-sac)))
                "Precondition: targeting selection pending after sacrifice")
          ;; Step 4: toggle target player (auto-confirm kicks in via fx dispatch, need explicit confirm)
          _ (rf/dispatch-sync [::selection/toggle-selection :player-2])
          _ (rf/dispatch-sync [::selection/confirm-selection])
          ;; Step 5: resolve Fling — deals 1 damage (Nimble Mongoose base power = 1)
          _ (rf/dispatch-sync [::resolution/resolve-top])
          result @rf-db/app-db
          result-db (:game/db result)]
      ;; Deferred-entry processing: history interceptor captured the cast-spell event
      ;; and converted pending-entry → history/main entry
      (is (= 1 (count (:history/main result)))
          "Exactly 1 history entry should be created for Fling cast-spell")
      ;; Fling deals damage = sacrificed power (Nimble Mongoose = 1)
      (is (= 19 (q/get-life-total result-db :player-2))
          "Player-2 life should be exactly 19 after Fling for 1 (20 - 1 from Mongoose power)")
      ;; Fling should be in graveyard after resolving (along with sacrificed creature = 2 total)
      (is (= 2 (count (q/get-objects-in-zone result-db :player-1 :graveyard)))
          "Player-1's graveyard should have exactly 2 cards: Fling + sacrificed Mongoose")
      ;; Creature was sacrificed (should not be on battlefield)
      (is (= :graveyard (:object/zone (q/get-object result-db creature-id)))
          "Sacrificed creature should remain in graveyard after full resolution"))))
