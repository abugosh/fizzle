(ns fizzle.events.selection.discard-ability-cost-test
  "Mechanism tests for :discard-specific ability cost activation pipeline.

   Covers the ability path for :discard-specific costs, mirroring the
   sacrifice-permanent ability cost pattern (ADR-030).

   These tests exercise the mechanism independently of any per-card oracle test.
   They use synthetic test cards with :activated ability + :discard-specific cost.

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT create
   a coverage gap here. These tests prove the defmethod mechanism independently
   of any per-card oracle tests."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as eng-abilities]
    [fizzle.engine.objects :as objects]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Synthetic card helpers
;; =====================================================

(def ^:private discard-ability
  "A :discard-specific ability cost: discard any 1 card from hand.
   No targeting. Effect: draw 1 card."
  {:ability/type        :activated
   :ability/description "Discard a card: Draw a card."
   :ability/cost        {:discard-specific {:groups [{:count 1}]
                                            :total  1}}
   :ability/effects     [{:effect/type   :draw
                          :effect/amount 1}]})


(def ^:private discard-ability-with-targeting
  "A :discard-specific ability cost: discard any 1 card from hand.
   Has targeting: target any player. Effect: draw 1 card."
  {:ability/type        :activated
   :ability/description "Discard a card: Draw a card targeting a player."
   :ability/cost        {:discard-specific {:groups [{:count 1}]
                                            :total  1}}
   :ability/targeting   [{:target/id       :target-player
                          :target/type     :player
                          :target/options  #{:any-player}
                          :target/required true}]
   :ability/effects     [{:effect/type       :draw
                          :effect/amount     1
                          :effect/target-ref :target-player}]})


(defn- make-synthetic-discard-artifact
  "Register a synthetic artifact card with :discard-specific ability.
   Returns [db card-eid]."
  [db ability]
  (let [card-id (keyword (str "test-discard-artifact-" (random-uuid)))
        card-tx {:card/id       card-id
                 :card/name     "Test Discard Artifact"
                 :card/cmc      0
                 :card/mana-cost {}
                 :card/colors   #{}
                 :card/types    #{:artifact}
                 :card/text     "Discard a card: Draw a card."
                 :card/abilities [ability]}
        db      (d/db-with db [card-tx])
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)]
    [db card-eid]))


(defn- add-discard-artifact-to-battlefield
  "Add a synthetic artifact with :discard-specific ability to battlefield.
   Returns [db artifact-obj-id]."
  ([db player-id]
   (add-discard-artifact-to-battlefield db player-id discard-ability))
  ([db player-id ability]
   (let [[db card-eid] (make-synthetic-discard-artifact db ability)
         card-data     (d/pull db [:card/types :card/power :card/toughness
                                   :card/triggers :card/replacement-effects]
                               card-eid)
         player-eid    (q/get-player-eid db player-id)
         obj-id        (random-uuid)
         obj-tx        (objects/build-object-tx db card-eid card-data :battlefield player-eid 0
                                                :id obj-id)
         db            (d/db-with db [obj-tx])]
     [db obj-id])))


;; =====================================================
;; A. can-activate? guards
;; =====================================================

(deftest can-activate-true-when-card-in-hand-test
  (testing "can-activate? returns true when hand has at least 1 card for discard-specific cost"
    (let [db            (th/create-test-db)
          [db art-id]   (add-discard-artifact-to-battlefield db :player-1)
          [db _card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ability       (first (:card/abilities (:object/card (q/get-object db art-id))))
          result        (eng-abilities/can-activate? db art-id ability)]
      (is (true? result)
          "can-activate? should be true when hand has a card to discard"))))


(deftest can-activate-false-when-hand-empty-test
  (testing "can-activate? returns false when hand is empty (no card to discard)"
    (let [db          (th/create-test-db)
          [db art-id] (add-discard-artifact-to-battlefield db :player-1)
          ;; No cards added to hand
          ability     (first (:card/abilities (:object/card (q/get-object db art-id))))
          result      (eng-abilities/can-activate? db art-id ability)]
      (is (false? result)
          "can-activate? should be false when hand is empty"))))


;; =====================================================
;; B. Activation creates discard selection
;; =====================================================

(deftest activation-creates-discard-selection-test
  (testing "Activating ability with :discard-specific cost creates a pick-from-zone selection"
    (let [db              (th/create-test-db)
          [db art-id]     (add-discard-artifact-to-battlefield db :player-1)
          [db _card-id]   (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result          (abilities/activate-ability db :player-1 art-id 0)
          sel             (:pending-selection result)]
      (is (some? sel)
          "Activation should produce a pending selection")
      (is (= :pick-from-zone (:selection/mechanism sel))
          "Selection mechanism should be :pick-from-zone")
      (is (= :discard-cost (:selection/domain sel))
          "Selection domain should be :discard-cost")
      (is (= :ability (:selection/source-type sel))
          "Selection source-type should be :ability")
      (is (= 1 (:selection/select-count sel))
          "Selection select-count should be 1 (total cost)")
      (is (= :player-1 (:selection/player-id sel))
          "Selection player-id should be :player-1"))))


(deftest activation-selection-has-ability-on-it-test
  (testing "Discard selection includes :selection/ability and :selection/ability-index"
    (let [db            (th/create-test-db)
          [db art-id]   (add-discard-artifact-to-battlefield db :player-1)
          [db _card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result        (abilities/activate-ability db :player-1 art-id 0)
          sel           (:pending-selection result)]
      (is (some? (:selection/ability sel))
          "Selection should include :selection/ability")
      (is (= 0 (:selection/ability-index sel))
          "Selection ability-index should be 0"))))


(deftest activation-selection-lifecycle-finalized-when-no-targeting-test
  (testing "Selection lifecycle is :finalized when ability has no targeting"
    (let [db            (th/create-test-db)
          [db art-id]   (add-discard-artifact-to-battlefield db :player-1)
          [db _card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result        (abilities/activate-ability db :player-1 art-id 0)
          sel           (:pending-selection result)]
      (is (= :finalized (:selection/lifecycle sel))
          "Lifecycle should be :finalized when ability has no targeting"))))


(deftest activation-selection-lifecycle-chaining-when-has-targeting-test
  (testing "Selection lifecycle is :chaining when ability has targeting"
    (let [db            (th/create-test-db)
          db            (th/add-opponent db)
          [db art-id]   (add-discard-artifact-to-battlefield db :player-1 discard-ability-with-targeting)
          [db _card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result        (abilities/activate-ability db :player-1 art-id 0)
          sel           (:pending-selection result)]
      (is (some? sel)
          "Activation with targeting ability should produce a selection")
      (is (= :chaining (:selection/lifecycle sel))
          "Lifecycle should be :chaining when ability has targeting"))))


(deftest discard-cost-chains-to-targeting-when-ability-has-targeting-test
  (testing "Confirming discard selection chains to targeting selection for ability with targeting"
    (let [db              (th/create-test-db)
          db              (th/add-opponent db)
          [db art-id]     (add-discard-artifact-to-battlefield db :player-1 discard-ability-with-targeting)
          [db card-id]    (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Step 1: activate ability — produces discard selection (lifecycle :chaining)
          result          (abilities/activate-ability db :player-1 art-id 0)
          discard-sel     (:pending-selection result)
          ;; Step 2: confirm discard — chains to targeting selection
          {:keys [db selection]} (th/confirm-selection (:db result) discard-sel #{card-id})]
      ;; Discard moved card to graveyard
      (is (= :graveyard (:object/zone (q/get-object db card-id)))
          "Discarded card should be in graveyard after confirm")
      ;; A targeting selection appeared as the next pending selection
      (is (some? selection)
          "Confirming discard should chain to a targeting selection")
      (is (= :n-slot-targeting (:selection/mechanism selection))
          "Chained selection should use :n-slot-targeting mechanism")
      (is (= :ability-targeting (:selection/domain selection))
          "Chained selection domain should be :ability-targeting")
      ;; Step 3: confirm targeting with player-2 as target — creates stack item
      (let [valid-targets (:selection/valid-targets selection)
            target-id     (first (filter #{:player-2} valid-targets))
            _             (is (some? target-id) "player-2 should be a valid target")
            {:keys [db]}  (th/confirm-selection db selection #{target-id})
            stack-items   (q/get-all-stack-items db)]
        (is (seq stack-items)
            "Stack should have an activated-ability item after targeting confirm")
        (is (= :activated-ability (:stack-item/type (first stack-items)))
            "Stack item type should be :activated-ability")))))


;; =====================================================
;; C. Confirm discard — no targeting path
;; =====================================================

(deftest confirm-discard-moves-card-to-graveyard-test
  (testing "Confirming discard selection moves the selected card to graveyard"
    (let [db              (th/create-test-db)
          [db art-id]     (add-discard-artifact-to-battlefield db :player-1)
          [db card-id]    (th/add-card-to-zone db :dark-ritual :hand :player-1)
          _               (is (= :hand (:object/zone (q/get-object db card-id)))
                              "Precondition: card starts in hand")
          result          (abilities/activate-ability db :player-1 art-id 0)
          sel             (:pending-selection result)
          {:keys [db]}    (th/confirm-selection (:db result) sel #{card-id})
          card-after      (q/get-object db card-id)]
      (is (= :graveyard (:object/zone card-after))
          "Discarded card should be in graveyard"))))


(deftest confirm-discard-creates-stack-item-test
  (testing "Confirming discard selection creates a stack item (lifecycle :finalized)"
    (let [db           (th/create-test-db)
          [db art-id]  (add-discard-artifact-to-battlefield db :player-1)
          [db card-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result       (abilities/activate-ability db :player-1 art-id 0)
          sel          (:pending-selection result)
          {:keys [db]} (th/confirm-selection (:db result) sel #{card-id})
          stack-items  (q/get-all-stack-items db)]
      (is (seq stack-items)
          "Stack should have an activated-ability item after discard confirm")
      (is (= :activated-ability (:stack-item/type (first stack-items)))
          "Stack item type should be :activated-ability"))))


;; =====================================================
;; D. Full chain — activate → discard → resolve → effects
;; =====================================================

(deftest full-chain-discard-and-resolve-draws-card-test
  (testing "Full chain: activate → discard → resolve → draw effect fires"
    (let [db              (th/create-test-db)
          ;; Ability: discard a card, draw a card
          [db art-id]     (add-discard-artifact-to-battlefield db :player-1)
          [db card-id]    (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Add library card so draw has something to draw
          [db _]          (th/add-cards-to-library db [:island] :player-1)
          initial-hand    (th/get-hand-count db :player-1)
          ;; Activate
          result          (abilities/activate-ability db :player-1 art-id 0)
          sel             (:pending-selection result)
          ;; Confirm discard
          {:keys [db]}    (th/confirm-selection (:db result) sel #{card-id})
          ;; Resolve the stack item
          hand-before-resolve (th/get-hand-count db :player-1)
          resolve-result  (resolution/resolve-one-item db)
          result-db       (:db resolve-result)
          hand-after      (th/get-hand-count result-db :player-1)]
      ;; After discard: 1 card less in hand (dark-ritual discarded)
      (is (= (- initial-hand 1) hand-before-resolve)
          "Hand should have 1 fewer card after discard")
      ;; After resolve (draw): back to initial-hand count (drew 1 from library)
      (is (= initial-hand hand-after)
          "Hand should be back to initial count after draw effect resolves"))))


(deftest full-chain-no-cards-in-hand-blocks-activation-test
  (testing "activate-ability returns no pending-selection when hand is empty"
    (let [db           (th/create-test-db)
          [db art-id]  (add-discard-artifact-to-battlefield db :player-1)
          ;; No cards in hand
          result       (abilities/activate-ability db :player-1 art-id 0)]
      (is (nil? (:pending-selection result))
          "No selection when hand is empty")
      (is (= db (:db result))
          "db should be unchanged when activation is blocked"))))
