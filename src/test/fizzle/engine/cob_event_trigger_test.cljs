(ns fizzle.engine.cob-event-trigger-test
  "Tests for City of Brass event-based trigger system.

   These tests verify the migration from scanning-based triggers
   (fire-matching-triggers) to event-based triggers (dispatch-event).

   Key behaviors being tested:
   - Trigger registration on ETB
   - Trigger unregistration on zone leave
   - Event dispatch on tap
   - Trigger resolution deals damage to controller"
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.engine.zones :as zones]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]))


;; === Test fixtures ===

(defn reset-registry
  "Clear trigger registry before and after each test.
   Re-registers turn-based actions after clearing."
  [f]
  (registry/clear-registry!)
  (turn-based/register-turn-based-actions!)
  (f)
  (registry/clear-registry!))


(use-fixtures :each reset-registry)


;; === Test helpers ===

(defn create-test-db
  "Create a game state with card definitions and player."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-land-to-hand
  "Add a land card to the player's hand.
   Returns [db object-id] tuple."
  [db card-id player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :hand
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn add-land-to-battlefield
  "Add a land card directly to battlefield (bypassing ETB).
   Use this when testing non-ETB scenarios.
   Returns [db object-id] tuple."
  [db card-id player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn register-cob-trigger!
  "Register a City of Brass trigger in the registry.
   Returns the trigger-id."
  [object-id controller-id]
  (let [trigger-id (random-uuid)]
    (registry/register-trigger!
      {:trigger/id trigger-id
       :trigger/event-type :permanent-tapped
       :trigger/source object-id
       :trigger/controller controller-id
       :trigger/filter {:event/object-id :self}
       :trigger/uses-stack? true
       :trigger/effects [{:effect/type :deal-damage
                          :effect/amount 1
                          :effect/target :controller}]
       :trigger/description "deals 1 damage to you"})
    trigger-id))


;; === Trigger registration on ETB ===

(deftest test-cob-trigger-registered-on-etb
  (testing "City of Brass trigger is registered when it enters the battlefield"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-hand db :city-of-brass :player-1)
          ;; Count existing triggers (turn-based actions)
          initial-count (count (registry/get-all-triggers))
          ;; Play the land (trigger should be registered on ETB)
          _db-after-etb (game/play-land db' :player-1 obj-id)
          ;; Find CoB triggers (those with :permanent-tapped event type)
          cob-triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                               (registry/get-all-triggers))]
      ;; Verify one new trigger was registered
      (is (= (inc initial-count) (count (registry/get-all-triggers)))
          "One additional trigger should be registered after CoB ETB")
      (is (= 1 (count cob-triggers))
          "Exactly one :permanent-tapped trigger should exist")
      (let [trigger (first cob-triggers)]
        (is (= :permanent-tapped (:trigger/event-type trigger))
            "Trigger should listen for :permanent-tapped event")
        (is (= obj-id (:trigger/source trigger))
            "Trigger source should be the CoB object")))))


(deftest test-cob-trigger-unregistered-on-leave
  (testing "City of Brass trigger is unregistered when it leaves the battlefield"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          ;; Count initial triggers (turn-based actions)
          initial-count (count (registry/get-all-triggers))
          ;; Manually register the trigger (simulating post-ETB state)
          _ (register-cob-trigger! obj-id :player-1)
          _ (is (= (inc initial-count) (count (registry/get-all-triggers)))
                "One additional trigger should be registered")
          ;; Verify CoB trigger exists
          cob-triggers-before (filter #(= obj-id (:trigger/source %))
                                      (registry/get-all-triggers))
          _ (is (= 1 (count cob-triggers-before))
                "CoB trigger should exist before leaving")
          ;; Move to graveyard (sacrifice) - should unregister trigger
          _db-after-leave (zones/move-to-zone db' obj-id :graveyard)
          ;; Find CoB triggers after leave
          cob-triggers-after (filter #(= obj-id (:trigger/source %))
                                     (registry/get-all-triggers))]
      ;; Verify CoB trigger is unregistered (but turn-based actions remain)
      (is (= initial-count (count (registry/get-all-triggers)))
          "Registry should return to initial count (turn-based actions only)")
      (is (empty? cob-triggers-after)
          "CoB trigger should be unregistered when CoB leaves battlefield"))))


;; === Event dispatch on tap ===

(deftest test-cob-tap-dispatches-event
  (testing "Tapping City of Brass dispatches :permanent-tapped event"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          ;; Register trigger
          _ (register-cob-trigger! obj-id :player-1)
          ;; Activate mana ability (should dispatch event via dispatch-event)
          db-after-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      ;; Verify stack-item is on the stack
      (is (= 1 (count (stack/get-all-stack-items db-after-tap)))
          "One stack-item should be on the stack after tapping CoB")
      (let [item (stack/get-top-stack-item db-after-tap)]
        (is (= :permanent-tapped (:stack-item/type item))
            "Stack item should be a :permanent-tapped stack-item")))))


(deftest test-cob-trigger-deals-damage
  (testing "City of Brass trigger deals 1 damage to controller when resolved"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Register trigger
          _ (register-cob-trigger! obj-id :player-1)
          ;; Activate mana ability (trigger goes on stack)
          db-after-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 20 (q/get-life-total db-after-tap :player-1))
                "Life unchanged before trigger resolves")
          ;; Resolve the trigger
          db-after-resolve (game/resolve-top-of-stack db-after-tap :player-1)]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Player should lose 1 life after trigger resolves"))))


(deftest test-cob-multiple-taps
  (testing "Tapping CoB multiple times (after untapping) creates multiple triggers"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          ;; Register trigger
          _ (register-cob-trigger! obj-id :player-1)
          ;; First tap
          db-after-first-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 1 (count (stack/get-all-stack-items db-after-first-tap)))
                "One trigger on stack after first tap")
          ;; Resolve first trigger
          db-after-first-resolve (game/resolve-top-of-stack db-after-first-tap :player-1)
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
          db-after-second-tap (ability-events/activate-mana-ability db-untapped :player-1 obj-id :blue)
          _ (is (= 1 (count (stack/get-all-stack-items db-after-second-tap)))
                "One trigger on stack after second tap")
          ;; Resolve second trigger
          db-after-second-resolve (game/resolve-top-of-stack db-after-second-tap :player-1)]
      (is (= 18 (q/get-life-total db-after-second-resolve :player-1))
          "Player at 18 life after both triggers resolved (2 damage total)"))))


;; === Edge cases ===

(deftest test-two-cobs-only-tapped-one-triggers
  (testing "When two City of Brass are on battlefield, only the tapped one's trigger fires"
    (let [db (create-test-db)
          [db' obj-id-1] (add-land-to-battlefield db :city-of-brass :player-1)
          [db'' obj-id-2] (add-land-to-battlefield db' :city-of-brass :player-1)
          ;; Count initial triggers (turn-based actions)
          initial-count (count (registry/get-all-triggers))
          ;; Register triggers for both
          _ (register-cob-trigger! obj-id-1 :player-1)
          _ (register-cob-trigger! obj-id-2 :player-1)
          ;; Verify 2 CoB triggers registered (plus turn-based actions)
          cob-triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                               (registry/get-all-triggers))
          _ (is (= 2 (count cob-triggers))
                "Two CoB triggers registered")
          _ (is (= (+ 2 initial-count) (count (registry/get-all-triggers)))
                "Total triggers = initial + 2 CoB triggers")
          ;; Tap only the first CoB
          db-after-tap (ability-events/activate-mana-ability db'' :player-1 obj-id-1 :black)]
      ;; Only one trigger should fire (the tapped CoB's trigger)
      (is (= 1 (count (stack/get-all-stack-items db-after-tap)))
          "Only one trigger should be on stack (the tapped CoB's trigger)"))))


(deftest test-cob-trigger-source-leaves-before-resolution
  (testing "City of Brass trigger still resolves if source sacrificed with trigger on stack"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Register trigger
          _ (register-cob-trigger! obj-id :player-1)
          ;; Tap to create trigger on stack
          db-after-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 1 (count (stack/get-all-stack-items db-after-tap)))
                "Trigger on stack")
          ;; Sacrifice CoB with trigger on stack (unregisters future triggers)
          db-after-sacrifice (zones/move-to-zone db-after-tap obj-id :graveyard)
          _ (is (= :graveyard (:object/zone (q/get-object db-after-sacrifice obj-id)))
                "CoB is in graveyard")
          ;; Resolve trigger - should still deal damage even though source is gone
          db-after-resolve (game/resolve-top-of-stack db-after-sacrifice :player-1)]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Trigger should still deal damage even if source is gone"))))


;; === resolve stack-item :permanent-tapped tests ===

(deftest test-resolve-trigger-permanent-tapped-deals-damage
  (testing "resolving :permanent-tapped stack-item executes effect to deal damage"
    (let [db (create-test-db)
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
          db-after-resolve (game/resolve-top-of-stack db-with-item :player-1)]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Player should lose 1 life when :permanent-tapped trigger resolves"))))


;; === dispatch-event integration tests ===

(deftest test-dispatch-permanent-tapped-event-adds-trigger-to-stack
  (testing "dispatch-event with :permanent-tapped adds matching trigger to stack"
    (let [db (create-test-db)
          obj-id (random-uuid)
          ;; Register a trigger that matches this object
          _ (registry/register-trigger!
              {:trigger/id (random-uuid)
               :trigger/event-type :permanent-tapped
               :trigger/source obj-id
               :trigger/controller :player-1
               :trigger/filter {:event/object-id :self}
               :trigger/uses-stack? true
               :trigger/effects [{:effect/type :deal-damage
                                  :effect/amount 1
                                  :effect/target :controller}]})
          ;; Dispatch event for this object
          event (game-events/permanent-tapped-event obj-id :player-1)
          db-after-dispatch (dispatch/dispatch-event db event)]
      ;; Trigger should be on stack
      (is (= 1 (count (stack/get-all-stack-items db-after-dispatch)))
          "Trigger should be added to stack when matching event dispatched"))))


(deftest test-dispatch-permanent-tapped-event-no-match
  (testing "dispatch-event with :permanent-tapped doesn't trigger for different objects"
    (let [db (create-test-db)
          obj-id-1 (random-uuid)
          obj-id-2 (random-uuid)
          ;; Register a trigger for obj-id-1
          _ (registry/register-trigger!
              {:trigger/id (random-uuid)
               :trigger/event-type :permanent-tapped
               :trigger/source obj-id-1
               :trigger/controller :player-1
               :trigger/filter {:event/object-id :self}
               :trigger/uses-stack? true
               :trigger/effects [{:effect/type :deal-damage
                                  :effect/amount 1
                                  :effect/target :controller}]})
          ;; Dispatch event for a DIFFERENT object (obj-id-2)
          event (game-events/permanent-tapped-event obj-id-2 :player-1)
          db-after-dispatch (dispatch/dispatch-event db event)]
      ;; No trigger should fire (filter doesn't match)
      (is (= 0 (count (stack/get-all-stack-items db-after-dispatch)))
          "Trigger should NOT fire for a different object"))))
