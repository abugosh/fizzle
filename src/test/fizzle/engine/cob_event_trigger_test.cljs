(ns fizzle.engine.cob-event-trigger-test
  "Tests for City of Brass event-based trigger system.

   These tests verify trigger behavior using Datascript trigger entities.

   Key behaviors being tested:
   - Trigger registration on ETB
   - Trigger unregistration on zone leave
   - Event dispatch on tap
   - Trigger resolution deals damage to controller"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.zones :as zones]
    [fizzle.events.lands :as lands]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Trigger registration on ETB ===

(deftest test-cob-trigger-registered-on-etb
  (testing "City of Brass trigger is registered when it enters the battlefield"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          ;; Play the land (trigger should be registered on ETB)
          db-after-etb (lands/play-land db' :player-1 obj-id)
          ;; Find CoB triggers (those with :permanent-tapped event type)
          all-triggers (trigger-db/get-all-triggers db-after-etb)
          cob-triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                               all-triggers)]
      ;; Verify one new trigger was registered
      (is (= 1 (count cob-triggers))
          "Exactly one :permanent-tapped trigger should exist")
      (let [trigger (first cob-triggers)
            obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                         db-after-etb obj-id)]
        (is (= :permanent-tapped (:trigger/event-type trigger))
            "Trigger should listen for :permanent-tapped event")
        (is (= obj-eid (:db/id (:trigger/source trigger)))
            "Trigger source should be the CoB object entity")))))


(deftest test-cob-trigger-unregistered-on-leave
  (testing "City of Brass trigger is unregistered when it leaves the battlefield"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Trigger is registered at object creation via build-object-tx — no manual registration needed
          cob-triggers-before (filter #(= :permanent-tapped (:trigger/event-type %))
                                      (trigger-db/get-all-triggers db'))
          _ (is (= 1 (count cob-triggers-before))
                "CoB trigger should exist (registered at object creation)")
          ;; Move to graveyard (sacrifice) - should unregister trigger
          db-after-leave (zones/move-to-zone* db' obj-id :graveyard)
          ;; Find CoB triggers after leave
          cob-triggers-after (filter #(= :permanent-tapped (:trigger/event-type %))
                                     (trigger-db/get-all-triggers db-after-leave))]
      (is (empty? cob-triggers-after)
          "CoB trigger should be unregistered when CoB leaves battlefield"))))


;; === Event dispatch on tap ===

(deftest test-cob-tap-dispatches-event
  (testing "Tapping City of Brass dispatches :permanent-tapped event"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Trigger registered at object creation via build-object-tx — no manual registration needed
          ;; Activate mana ability (should dispatch event via dispatch-event)
          db-after-tap (engine-mana/activate-mana-ability db' :player-1 obj-id :black)]
      ;; Verify stack-item is on the stack
      (is (= 1 (count (q/get-all-stack-items db-after-tap)))
          "One stack-item should be on the stack after tapping CoB")
      (let [item (stack/get-top-stack-item db-after-tap)]
        (is (= :permanent-tapped (:stack-item/type item))
            "Stack item should be a :permanent-tapped stack-item")))))


(deftest test-cob-trigger-deals-damage
  (testing "City of Brass trigger deals 1 damage to controller when resolved"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Trigger registered at object creation via build-object-tx
          ;; Activate mana ability (trigger goes on stack)
          db-after-tap (engine-mana/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 20 (q/get-life-total db-after-tap :player-1))
                "Life unchanged before trigger resolves")
          ;; Resolve the trigger
          db-after-resolve (:db (resolution/resolve-one-item db-after-tap))]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Player should lose 1 life after trigger resolves"))))


(deftest test-cob-multiple-taps
  (testing "Tapping CoB multiple times (after untapping) creates multiple triggers"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Trigger registered at object creation via build-object-tx
          ;; First tap
          db-after-first-tap (engine-mana/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 1 (count (q/get-all-stack-items db-after-first-tap)))
                "One trigger on stack after first tap")
          ;; Resolve first trigger
          db-after-first-resolve (:db (resolution/resolve-one-item db-after-first-tap))
          _ (is (= 19 (q/get-life-total db-after-first-resolve :player-1))
                "Player at 19 life after first trigger")
          ;; Untap the land manually
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-after-first-resolve obj-id)
          db-untapped (d/db-with db-after-first-resolve
                                 [[:db/add obj-eid :object/tapped false]])
          ;; Second tap
          db-after-second-tap (engine-mana/activate-mana-ability db-untapped :player-1 obj-id :blue)
          _ (is (= 1 (count (q/get-all-stack-items db-after-second-tap)))
                "One trigger on stack after second tap")
          ;; Resolve second trigger
          db-after-second-resolve (:db (resolution/resolve-one-item db-after-second-tap))]
      (is (= 18 (q/get-life-total db-after-second-resolve :player-1))
          "Player at 18 life after both triggers resolved (2 damage total)"))))


;; === Edge cases ===

(deftest test-two-cobs-only-tapped-one-triggers
  (testing "When two City of Brass are on battlefield, only the tapped one's trigger fires"
    (let [db (th/create-test-db)
          [db' obj-id-1] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          [db'' _obj-id-2] (th/add-card-to-zone db' :city-of-brass :battlefield :player-1)
          ;; Both triggers registered at object creation via build-object-tx
          cob-triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                               (trigger-db/get-all-triggers db''))
          _ (is (= 2 (count cob-triggers))
                "Two CoB triggers registered (one per object at creation)")
          ;; Tap only the first CoB
          db-after-tap (engine-mana/activate-mana-ability db'' :player-1 obj-id-1 :black)]
      ;; Only one trigger should fire (the tapped CoB's :self filter matches only obj-id-1)
      (is (= 1 (count (q/get-all-stack-items db-after-tap)))
          "Only one trigger should be on stack (the tapped CoB's trigger)"))))


(deftest test-cob-trigger-source-leaves-before-resolution
  (testing "City of Brass trigger still resolves if source sacrificed with trigger on stack"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Trigger registered at object creation via build-object-tx
          ;; Tap to create trigger on stack
          db-after-tap (engine-mana/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 1 (count (q/get-all-stack-items db-after-tap)))
                "Trigger on stack")
          ;; Sacrifice CoB with trigger on stack (unregisters future triggers)
          db-after-sacrifice (zones/move-to-zone* db-after-tap obj-id :graveyard)
          _ (is (= :graveyard (:object/zone (q/get-object db-after-sacrifice obj-id)))
                "CoB is in graveyard")
          ;; Resolve trigger - should still deal damage even though source is gone
          db-after-resolve (:db (resolution/resolve-one-item db-after-sacrifice))]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Trigger should still deal damage even if source is gone"))))


;; === resolve stack-item :permanent-tapped tests ===

(deftest test-resolve-trigger-permanent-tapped-deals-damage
  (testing "resolving :permanent-tapped stack-item executes effect to deal damage"
    (let [db (th/create-test-db)
          initial-life (q/get-life-total db :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Create a stack-item for the trigger
          db-with-item (stack/create-stack-item db
                                                {:stack-item/type :permanent-tapped
                                                 :stack-item/source (random-uuid)
                                                 :stack-item/controller :player-1
                                                 :stack-item/effects [{:effect/type :deal-damage
                                                                       :effect/amount 1
                                                                       :effect/target :controller}]})
          ;; Resolve via stack resolution
          db-after-resolve (:db (resolution/resolve-one-item db-with-item))]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Player should lose 1 life when :permanent-tapped trigger resolves"))))


;; === dispatch-event integration tests ===

(deftest test-dispatch-permanent-tapped-event-adds-trigger-to-stack
  (testing "dispatch-event with :permanent-tapped adds matching trigger to stack"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Trigger registered at object creation via build-object-tx — no manual registration needed
          ;; Dispatch event for this object
          event (game-events/permanent-tapped-event obj-id :player-1)
          db-after-dispatch (dispatch/dispatch-event db' event)]
      ;; Trigger should be on stack
      (is (= 1 (count (q/get-all-stack-items db-after-dispatch)))
          "Trigger should be added to stack when matching event dispatched"))))


(deftest test-dispatch-permanent-tapped-event-no-match
  (testing "dispatch-event with :permanent-tapped: each CoB trigger only fires for its own tap"
    (let [db (th/create-test-db)
          ;; Create two objects on battlefield — both get triggers at object creation
          [db' _obj-id-1] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          [db'' obj-id-2] (th/add-card-to-zone db' :city-of-brass :battlefield :player-1)
          ;; Dispatch event for obj-id-2 only
          event (game-events/permanent-tapped-event obj-id-2 :player-1)
          db-after-dispatch (dispatch/dispatch-event db'' event)]
      ;; Only obj-id-2's trigger should fire (obj-id-1's :self filter doesn't match obj-id-2)
      ;; The :event/object-id :self filter means obj-id-1's trigger only fires for obj-id-1 events
      (is (= 1 (count (q/get-all-stack-items db-after-dispatch)))
          "Only obj-id-2's trigger fires — obj-id-1's :self filter excludes obj-id-2 tap events"))))
