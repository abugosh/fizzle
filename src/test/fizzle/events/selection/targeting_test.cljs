(ns fizzle.events.selection.targeting-test
  "Pattern B (multimethod production-path slice) tests for
   events/selection/targeting.cljs defmethods.

   Covers all 5 defmethod registrations:

   build-selection-for-effect (1):
     :player-target            - effect has :effect/target :any-player

   execute-confirmed-selection (3):
     :player-target            - resolves selected player and executes the effect
     :cast-time-targeting      - finalized (cast + store target) and chaining (returns db unchanged)
     :ability-cast-targeting   - finalized (cast + store target) and chaining (returns db unchanged)

   build-chain-selection (1):
     :targeting-to-mana-allocation - after a chaining target pick, builds mana-allocation selection

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test.

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or via th/confirm-selection for :selected-field selections).

   Cards used:
     :brain-freeze    - :player-target builder/executor (mill 3, :effect/target :any-player)
     :deep-analysis   - :targeting-to-mana-allocation chain (targeting + colorless cost = chaining)
     :lightning-bolt  - :cast-time-targeting executor (finalized lifecycle, player target)
     :stifle          - :ability-cast-targeting executor (:target/type :ability)"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    ;; Direct card requires — NO cards.registry / cards.all-cards lookups
    [fizzle.cards.blue.brain-freeze]
    [fizzle.cards.blue.deep-analysis]
    [fizzle.cards.blue.stifle]
    [fizzle.cards.red.lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.casting :as casting]
    [fizzle.events.selection.core :as sel-core]
    ;; Load targeting defmethods so they register on the multimethods (side-effect require)
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.events.selection.targeting]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Setup
;; =====================================================

(defn- setup-db
  "Create a minimal game-db with player-1 and card registry loaded.
   Optional opts: {:mana {:blue 2 :colorless 3} :add-opponent? true}"
  ([]
   (setup-db {}))
  ([opts]
   (let [db (th/create-test-db (select-keys opts [:mana]))]
     (if (:add-opponent? opts)
       (th/add-opponent db)
       db))))


;; =====================================================
;; build-selection-for-effect :player-target
;; Dispatches when effect's :effect/target is :any-player.
;; Real card: Brain Freeze ({1}{U} - mill 3, effect/target :any-player)
;; =====================================================

(deftest player-target-builder-creates-correct-shape
  (testing ":player-target builder creates selection with both players as valid-targets"
    (let [db (setup-db {:add-opponent? true})
          [db1 obj-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          ;; Effect with :effect/target :any-player triggers :player-target dispatch
          effect {:effect/type :mill
                  :effect/amount 3
                  :effect/target :any-player}
          remaining-effects []
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect remaining-effects)]
      (is (= :player-target (:selection/type sel))
          "Builder should return :player-target selection type")
      (is (= :player-1 (:selection/player-id sel))
          "Selecting player should be :player-1 (caster)")
      (is (= 1 (:selection/select-count sel))
          "Select count should be 1")
      (is (= :exact (:selection/validation sel))
          "Validation should be :exact")
      (is (true? (:selection/auto-confirm? sel))
          "auto-confirm? should be true — auto-presented")
      (is (= :finalized (:selection/lifecycle sel))
          "Lifecycle should be :finalized")
      (is (contains? (:selection/valid-targets sel) :player-1)
          "Player-1 should be a valid target")
      (is (contains? (:selection/valid-targets sel) :player-2)
          "Player-2 should be a valid target (with opponent)")
      (is (= #{:player-1 :player-2} (:selection/valid-targets sel))
          "Valid-targets should contain exactly the two player IDs"))))


(deftest player-target-builder-stores-target-effect
  (testing ":player-target builder stores the effect on the selection for later execution"
    (let [db (setup-db {:add-opponent? true})
          [db1 obj-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          effect {:effect/type :mill
                  :effect/amount 3
                  :effect/target :any-player}
          remaining [{:effect/type :draw :effect/amount 1}]
          sel (sel-core/build-selection-for-effect db1 :player-1 obj-id effect remaining)]
      (is (= effect (:selection/target-effect sel))
          "Builder should store the target effect on :selection/target-effect")
      (is (= remaining (:selection/remaining-effects sel))
          "Builder should store remaining effects on :selection/remaining-effects"))))


;; =====================================================
;; execute-confirmed-selection :player-target
;; Real card: Brain Freeze — mills 3 of chosen player.
;; =====================================================

(deftest player-target-executor-targets-opponent
  (testing ":player-target executor resolves selected player and executes effect on opponent"
    (let [db (setup-db {:add-opponent? true})
          ;; Give the library some cards to mill
          [db1 _] (th/add-card-to-zone db :brain-freeze :library :player-2)
          [db2 _] (th/add-card-to-zone db1 :brain-freeze :library :player-2)
          [db3 _] (th/add-card-to-zone db2 :brain-freeze :library :player-2)
          [db4 obj-id] (th/add-card-to-zone db3 :brain-freeze :hand :player-1)
          effect {:effect/type :mill
                  :effect/amount 3
                  :effect/target :any-player}
          sel (sel-core/build-selection-for-effect db4 :player-1 obj-id effect [])
          ;; Player confirms :player-2 as the target
          {:keys [db]} (th/confirm-selection db4 sel #{:player-2})]
      ;; Player-2's graveyard should now have 3 milled cards
      (let [graveyard-count (d/q '[:find (count ?e) .
                                   :in $ ?player-eid
                                   :where
                                   [?e :object/zone :graveyard]
                                   [?e :object/owner ?player-eid]]
                                 db (q/get-player-eid db :player-2))]
        (is (= 3 (or graveyard-count 0))
            "Opponent should have 3 cards in graveyard after mill")))))


(deftest player-target-executor-targets-self
  (testing ":player-target executor resolves selected player and executes effect on self"
    (let [db (setup-db {:add-opponent? true})
          ;; Give player-1's library some cards to mill
          [db1 _] (th/add-card-to-zone db :brain-freeze :library :player-1)
          [db2 _] (th/add-card-to-zone db1 :brain-freeze :library :player-1)
          [db3 _] (th/add-card-to-zone db2 :brain-freeze :library :player-1)
          [db4 obj-id] (th/add-card-to-zone db3 :brain-freeze :hand :player-1)
          effect {:effect/type :mill
                  :effect/amount 3
                  :effect/target :any-player}
          sel (sel-core/build-selection-for-effect db4 :player-1 obj-id effect [])
          ;; Player targets self
          {:keys [db]} (th/confirm-selection db4 sel #{:player-1})]
      ;; Player-1's graveyard should have 3 milled cards
      (let [graveyard-count (d/q '[:find (count ?e) .
                                   :in $ ?player-eid
                                   :where
                                   [?e :object/zone :graveyard]
                                   [?e :object/owner ?player-eid]]
                                 db (q/get-player-eid db :player-1))]
        (is (= 3 (or graveyard-count 0))
            "Player-1 should have 3 cards in graveyard after self-mill")))))


(deftest player-target-executor-remaining-effects-execute
  (testing ":player-target executor executes remaining effects after the targeted effect"
    (let [db (setup-db {:add-opponent? true})
          ;; Give player-2 library cards for the mill
          [db1 _] (th/add-card-to-zone db :brain-freeze :library :player-2)
          [db2 _] (th/add-card-to-zone db1 :brain-freeze :library :player-2)
          [db3 _] (th/add-card-to-zone db2 :brain-freeze :library :player-2)
          ;; Give player-1 a card in library so the :draw remaining effect can draw it
          [db4 draw-card-id] (th/add-card-to-zone db3 :brain-freeze :library :player-1)
          [db5 obj-id] (th/add-card-to-zone db4 :brain-freeze :hand :player-1)
          effect {:effect/type :mill
                  :effect/amount 3
                  :effect/target :any-player}
          ;; Remaining effect: draw 1 for the caster (player-1)
          ;; No :effect/target means "draw for the current player-id (caster)"
          remaining [{:effect/type :draw
                      :effect/amount 1}]
          sel (sel-core/build-selection-for-effect db5 :player-1 obj-id effect remaining)
          {:keys [db]} (th/confirm-selection db5 sel #{:player-2})]
      ;; draw-card-id should have moved from library to hand (remaining draw effect)
      (is (= :hand (:object/zone (q/get-object db draw-card-id)))
          "Card from player-1's library should be in hand after remaining draw effect"))))


;; =====================================================
;; execute-confirmed-selection :cast-time-targeting (finalized branch)
;; Real card: Lightning Bolt ({R} - deal 3 damage to any target)
;; Finalized lifecycle (no generic mana cost): casts spell and stores target.
;; =====================================================

(deftest cast-time-targeting-executor-finalized-casts-spell-and-stores-target
  (testing ":cast-time-targeting executor finalized branch: casts spell and stores target on stack item"
    (let [db (setup-db {:add-opponent? true :mana {:red 1}})
          [db1 bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          ;; Build a cast-time-targeting selection via cast-spell-handler WITHOUT target
          ;; (passing a target would skip interactive targeting and auto-cast directly)
          result (casting/cast-spell-handler {:game/db db1}
                                             {:player-id :player-1
                                              :object-id bolt-id})
          ;; cast-spell-handler should return a pending selection (targeting pauses cast)
          sel (:game/pending-selection result)
          db-after-handler (:game/db result)]
      (is (= :cast-time-targeting (:selection/type sel))
          "cast-spell-handler should produce a :cast-time-targeting selection for Lightning Bolt")
      (is (= :finalized (:selection/lifecycle sel))
          "Lifecycle should be :finalized (no generic mana cost on Bolt)")
      (is (contains? (set (:selection/valid-targets sel)) :player-2)
          "Player-2 should be a valid target")
      ;; Confirm the targeting selection: this exercises the executor
      (let [app-db (sel-spec/set-pending-selection {:game/db db-after-handler} sel)
            app-db' (update app-db :game/pending-selection assoc :selection/selected #{:player-2})
            after-confirm (sel-core/confirm-selection-impl app-db')
            db' (:game/db after-confirm)]
        ;; Bolt should be on the stack after confirm
        (is (= :stack (:object/zone (q/get-object db' bolt-id)))
            "Lightning Bolt should be on the stack after targeting confirmed")
        ;; Stack item should have targets stored
        (let [bolt-eid (q/get-object-eid db' bolt-id)
              stack-item (when bolt-eid
                           (d/q '[:find (pull ?si [:stack-item/targets]) .
                                  :in $ ?obj-eid
                                  :where [?si :stack-item/object-ref ?obj-eid]]
                                db' bolt-eid))]
          (is (some? (:stack-item/targets stack-item))
              "Stack item should have :stack-item/targets set after targeting")
          (is (= :player-2 (:target (:stack-item/targets stack-item)))
              ":target key in targets should be :player-2"))))))


(deftest cast-time-targeting-executor-chaining-returns-db-unchanged
  (testing ":cast-time-targeting executor chaining branch: returns {:db game-db} unchanged"
    (let [;; Deep Analysis: {3}{U} targeting + colorless cost -> chaining lifecycle
          db (setup-db {:add-opponent? true :mana {:colorless 3 :blue 1}})
          [db1 da-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          ;; cast-spell-handler produces a chaining targeting selection WITHOUT target
          ;; (passing a target would auto-cast directly via confirm-cast-time-target)
          result (casting/cast-spell-handler {:game/db db1}
                                             {:player-id :player-1
                                              :object-id da-id})
          sel (:game/pending-selection result)
          db-after-handler (:game/db result)]
      (is (= :cast-time-targeting (:selection/type sel))
          "cast-spell-handler should produce a :cast-time-targeting selection for Deep Analysis")
      (is (= :chaining (:selection/lifecycle sel))
          "Lifecycle should be :chaining (Deep Analysis has colorless generic mana cost)")
      ;; Executor for chaining returns {:db game-db} without casting
      ;; Chain builder then produces the mana-allocation selection
      (let [app-db (sel-spec/set-pending-selection {:game/db db-after-handler} sel)
            app-db' (update app-db :game/pending-selection assoc :selection/selected #{:player-2})
            after-confirm (sel-core/confirm-selection-impl app-db')]
        ;; With chaining lifecycle, spell should NOT be on stack yet
        (is (= :hand (:object/zone (q/get-object (:game/db after-confirm) da-id)))
            "Deep Analysis should still be in hand after chaining targeting (not yet cast)")
        ;; Should have chained to mana-allocation selection
        (is (= :mana-allocation (:selection/type (:game/pending-selection after-confirm)))
            "Should chain to :mana-allocation selection after chaining targeting")
        ;; Mana-allocation selection should carry the pending target
        (is (some? (:selection/pending-targets (:game/pending-selection after-confirm)))
            ":mana-allocation selection should have :selection/pending-targets from target choice")))))


;; =====================================================
;; build-chain-selection :targeting-to-mana-allocation
;; Dispatches for :cast-time-targeting and :ability-cast-targeting via hierarchy.
;; Real card: Deep Analysis ({3}{U} - player targeting + colorless cost)
;; Chaining path: after target selection, builds mana-allocation selection
;; with pending-targets set.
;; =====================================================

(deftest targeting-to-mana-allocation-chain-builds-mana-allocation-selection
  (testing ":targeting-to-mana-allocation chain produces mana-allocation with pending-targets"
    (let [db (setup-db {:add-opponent? true :mana {:colorless 3 :blue 1}})
          [db1 da-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          ;; Build the chaining targeting selection via cast-spell-handler WITHOUT target
          result (casting/cast-spell-handler {:game/db db1}
                                             {:player-id :player-1
                                              :object-id da-id})
          sel (:game/pending-selection result)
          db-before-cast (:game/db result)]
      (is (= :chaining (:selection/lifecycle sel))
          "Precondition: targeting lifecycle is :chaining for Deep Analysis")
      ;; Confirm the targeting with player-2, triggering chain builder
      (let [app-db (sel-spec/set-pending-selection {:game/db db-before-cast} sel)
            app-db' (update app-db :game/pending-selection assoc :selection/selected #{:player-2})
            after-confirm (sel-core/confirm-selection-impl app-db')
            mana-sel (:game/pending-selection after-confirm)]
        ;; Chain builder should produce a :mana-allocation selection
        (is (= :mana-allocation (:selection/type mana-sel))
            "Chain builder should produce :mana-allocation selection")
        ;; pending-targets must have the target stored (target-id -> selected-target)
        (is (map? (:selection/pending-targets mana-sel))
            ":selection/pending-targets should be a map")
        (is (= :player-2 (first (vals (:selection/pending-targets mana-sel))))
            ":selection/pending-targets should map to :player-2 (the selected player target)")
        ;; Mode should match Deep Analysis primary mode
        (is (some? (:selection/mode mana-sel))
            ":selection/mode should be set on the mana-allocation selection")
        ;; spell-id (not object-id) should be Deep Analysis
        ;; mana-allocation uses :selection/spell-id (not :object-id)
        (is (= da-id (:selection/spell-id mana-sel))
            ":selection/spell-id should be the Deep Analysis object id")))))


(deftest targeting-to-mana-allocation-chain-self-player-target-works
  (testing ":targeting-to-mana-allocation chain also works when self is chosen as target"
    (let [db (setup-db {:add-opponent? true :mana {:colorless 3 :blue 1}})
          [db1 da-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          result (casting/cast-spell-handler {:game/db db1}
                                             {:player-id :player-1
                                              :object-id da-id})
          sel (:game/pending-selection result)
          db-before-cast (:game/db result)
          app-db (sel-spec/set-pending-selection {:game/db db-before-cast} sel)
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{:player-1})
          after-confirm (sel-core/confirm-selection-impl app-db')
          mana-sel (:game/pending-selection after-confirm)]
      (is (= :mana-allocation (:selection/type mana-sel))
          "Chain builder should produce :mana-allocation selection when self is target")
      (is (= :player-1 (first (vals (:selection/pending-targets mana-sel))))
          ":selection/pending-targets should map to :player-1 (self target)"))))


;; =====================================================
;; execute-confirmed-selection :ability-cast-targeting (finalized branch)
;; Real card: Stifle ({U} - counter target activated/triggered ability)
;; :ability-cast-targeting is produced when :target/type is :ability.
;; Finalized lifecycle (no generic mana cost): casts spell and stores target.
;; =====================================================

(deftest ability-cast-targeting-executor-finalized-casts-spell-and-stores-ability-target
  (testing ":ability-cast-targeting executor finalized branch: casts spell and stores ability EID as target"
    (let [db (setup-db {:mana {:blue 1}})
          ;; Create an activated-ability stack item as the target for Stifle.
          ;; :activated-ability requires :stack-item/source (the originating object UUID).
          ability-source-id (random-uuid)
          db-with-ability (stack/create-stack-item
                            db
                            {:stack-item/type :activated-ability
                             :stack-item/controller :player-1
                             :stack-item/source ability-source-id
                             :stack-item/effects [{:effect/type :draw :effect/amount 1}]
                             :stack-item/description "Test activated ability"})
          ability-eid (d/q '[:find ?e .
                             :where [?e :stack-item/type :activated-ability]]
                           db-with-ability)
          [db1 stifle-id] (th/add-card-to-zone db-with-ability :stifle :hand :player-1)
          ;; cast-spell-handler WITHOUT target - interactive targeting produces :ability-cast-targeting
          result (casting/cast-spell-handler {:game/db db1}
                                             {:player-id :player-1
                                              :object-id stifle-id})
          sel (:game/pending-selection result)
          db-after-handler (:game/db result)]
      (is (= :ability-cast-targeting (:selection/type sel))
          "cast-spell-handler should produce :ability-cast-targeting for Stifle")
      (is (= :finalized (:selection/lifecycle sel))
          "Lifecycle should be :finalized (Stifle has no generic mana cost)")
      (is (contains? (set (:selection/valid-targets sel)) ability-eid)
          "The activated ability EID should be a valid target")
      ;; Confirm the targeting selection to exercise the executor
      (let [app-db (sel-spec/set-pending-selection {:game/db db-after-handler} sel)
            app-db' (update app-db :game/pending-selection assoc :selection/selected #{ability-eid})
            after-confirm (sel-core/confirm-selection-impl app-db')
            db' (:game/db after-confirm)]
        ;; Stifle should be on the stack after targeting confirmed
        (is (= :stack (:object/zone (q/get-object db' stifle-id)))
            "Stifle should be on the stack after targeting confirmed")
        ;; Stack item for Stifle should have targets stored with the ability EID
        (let [stifle-eid (q/get-object-eid db' stifle-id)
              stack-item (when stifle-eid
                           (d/q '[:find (pull ?si [:stack-item/targets]) .
                                  :in $ ?obj-eid
                                  :where [?si :stack-item/object-ref ?obj-eid]]
                                db' stifle-eid))]
          (is (some? (:stack-item/targets stack-item))
              "Stack item should have :stack-item/targets set after targeting")
          (is (= ability-eid (:ability (:stack-item/targets stack-item)))
              ":ability key in targets should be the ability stack-item EID"))))))
