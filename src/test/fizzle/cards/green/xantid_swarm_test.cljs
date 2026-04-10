(ns fizzle.cards.green.xantid-swarm-test
  "Tests for Xantid Swarm — G, 0/1 Insect with Flying and attack trigger.

   Xantid Swarm: G - 0/1 Creature — Insect
   Flying
   Whenever Xantid Swarm attacks, defending player can't cast spells this turn."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.green.xantid-swarm :as xantid-swarm]
    [fizzle.db.queries :as q]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.combat :as combat]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.zones :as zones]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Helpers ===

(defn- add-creature-to-battlefield
  "Add a creature directly to the battlefield (bypasses summoning sickness from
   move-to-zone, creatures added this way still have summoning sickness field
   from the object creation)."
  [db card-id owner]
  (let [[db obj-id] (th/add-card-to-zone db card-id :hand owner)
        db (zones/move-to-zone* db obj-id :battlefield)]
    [db obj-id]))


(defn- clear-summoning-sickness
  "Clear summoning sickness from a creature."
  [db obj-id]
  (let [obj-eid (q/get-object-eid db obj-id)]
    (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])))


;; === A. Card definition ===

(deftest xantid-swarm-card-definition-test
  (testing "Xantid Swarm card data has correct values"
    (let [card xantid-swarm/card]
      (is (= :xantid-swarm (:card/id card)))
      (is (= "Xantid Swarm" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:green 1} (:card/mana-cost card)))
      (is (= #{:green} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:insect} (:card/subtypes card)))
      (is (= 0 (:card/power card)))
      (is (= 1 (:card/toughness card)))
      (is (= #{:flying} (:card/keywords card)))
      (is (= "Flying\nWhenever Xantid Swarm attacks, defending player can't cast spells this turn."
             (:card/text card)))))

  (testing "Xantid Swarm has exactly one :creature-attacks trigger"
    (let [triggers (:card/triggers xantid-swarm/card)]
      (is (= 1 (count triggers))
          "Should have exactly one trigger")
      (let [t (first triggers)]
        (is (= :creature-attacks (:trigger/type t)))
        (is (= "defending player can't cast spells this turn" (:trigger/description t)))
        (is (= 1 (count (:trigger/effects t))))
        (let [effect (first (:trigger/effects t))]
          (is (= :add-restriction (:effect/type effect)))
          (is (= :opponent (:effect/target effect)))
          (is (= :cannot-cast-spells (:restriction/type effect))))))))


(deftest xantid-swarm-passes-card-spec-test
  (testing "Xantid Swarm passes card spec validation"
    (is (true? (card-spec/valid-card? xantid-swarm/card))
        (str "Card spec failure: "
             (card-spec/explain-card xantid-swarm/card)))))


;; === B. Cast-resolve happy path ===

(deftest xantid-swarm-enters-battlefield-test
  (testing "Xantid Swarm enters battlefield as 0/1 with summoning sickness"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj))
          "Should be on the battlefield")
      (is (= 0 (:object/power obj))
          "Base power should be 0")
      (is (= 1 (:object/toughness obj))
          "Base toughness should be 1")
      (is (true? (:object/summoning-sick obj))
          "Should have summoning sickness")
      (is (= 0 (:object/damage-marked obj))
          "Damage should be 0"))))


(deftest xantid-swarm-has-flying-on-battlefield-test
  (testing "Xantid Swarm has :flying keyword after entering battlefield"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (true? (creatures/has-keyword? db obj-id :flying))
          "Should have flying keyword"))))


(deftest xantid-swarm-triggers-registered-on-resolve-test
  (testing "Xantid Swarm has triggers registered in Datascript after resolving"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj-eid (q/get-object-eid db obj-id)
          obj-triggers (:object/triggers (d/entity db obj-eid))]
      (is (= 1 (count obj-triggers))
          "Object should have one linked trigger entity after resolving"))))


;; === C. Cannot-cast guards ===

(deftest xantid-swarm-cannot-cast-without-mana-test
  (testing "Cannot cast Xantid Swarm without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest xantid-swarm-cannot-cast-wrong-color-test
  (testing "Cannot cast Xantid Swarm with wrong color mana"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with blue mana"))))


(deftest xantid-swarm-cannot-cast-from-graveyard-test
  (testing "Cannot cast Xantid Swarm from graveyard"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm count ===

(deftest xantid-swarm-increments-storm-test
  (testing "Casting Xantid Swarm increments storm count"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1"))))


;; === E. Attack trigger tests ===

(deftest xantid-swarm-attack-creates-trigger-stack-item-test
  (testing "Xantid Swarm attacking creates a trigger stack item"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          ;; Cast so triggers are registered in Datascript
          db (th/cast-and-resolve db :player-1 obj-id)
          db (clear-summoning-sickness db obj-id)
          ;; Begin combat and resolve declare-attackers (returns pending-selection)
          db (combat/begin-combat db :player-1)
          result (resolution/resolve-one-item db)
          selection (:pending-selection result)
          ;; Confirm attackers with Xantid Swarm
          confirmed (th/confirm-selection (:db result) selection #{obj-id})
          db-after (:db confirmed)
          stack-items (q/get-all-stack-items db-after)]
      ;; Stack should have: declare-blockers, combat-damage, AND the creature-attacks trigger
      (is (= 3 (count stack-items))
          "Stack should have 3 items: trigger + declare-blockers + combat-damage")
      (let [types (set (map :stack-item/type stack-items))]
        (is (contains? types :creature-attacked)
            "Stack should contain :creature-attacked trigger item")))))


(deftest xantid-swarm-trigger-on-top-of-stack-test
  (testing "Attack trigger goes on top of stack (resolves before blockers)"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-1)
          result (resolution/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{obj-id})
          db-after (:db confirmed)
          top (q/get-top-stack-item db-after)]
      (is (= :creature-attacked (:stack-item/type top))
          "Creature-attacked trigger should be on top of stack"))))


(deftest xantid-swarm-trigger-resolves-opponent-restriction-test
  (testing "Trigger resolves and adds :cannot-cast-spells to opponent"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-1)
          result (resolution/resolve-one-item db)
          selection (:pending-selection result)
          ;; Confirm attackers
          confirmed (th/confirm-selection (:db result) selection #{obj-id})
          db-after (:db confirmed)
          ;; Resolve the trigger (creature-attacked is on top)
          trigger-result (resolution/resolve-one-item db-after)
          db-resolved (:db trigger-result)
          p2-grants (grants/get-player-grants db-resolved :player-2)]
      (is (= 1 (count p2-grants))
          "Opponent should have exactly one grant after trigger resolves")
      (is (some #(= :cannot-cast-spells (:restriction/type (:grant/data %))) p2-grants)
          "Opponent should have :cannot-cast-spells restriction"))))


(deftest xantid-swarm-opponent-cannot-cast-after-trigger-test
  (testing "Opponent cannot cast spells after Xantid Swarm trigger resolves"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          ;; Give opponent mana + spell
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          p2-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add p2-eid :player/mana-pool
                             {:white 0 :blue 0 :black 1 :red 0 :green 0 :colorless 0}]])
          _ (is (rules/can-cast? db :player-2 spell-id)
                "Precondition: opponent can cast Dark Ritual without restriction")
          ;; Apply the restriction directly (simulating trigger resolution)
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-spells}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      (is (false? (rules/can-cast? db-restricted :player-2 spell-id))
          "Opponent should not be able to cast after :cannot-cast-spells restriction"))))


(deftest xantid-swarm-direct-trigger-dispatch-test
  (testing "Dispatching :creature-attacked event fires trigger from Datascript"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          ;; Cast to register triggers in Datascript
          db (th/cast-and-resolve db :player-1 obj-id)
          ;; Directly dispatch the creature-attacked event
          event (game-events/creature-attacked-event obj-id :player-1)
          db-after (dispatch/dispatch-event db event)
          items (q/get-all-stack-items db-after)]
      (is (= 1 (count items))
          "Dispatch should create one trigger stack item")
      (is (= :creature-attacked (:stack-item/type (first items)))
          "Stack item type should be :creature-attacked"))))


(deftest xantid-swarm-multiple-swarms-multiple-triggers-test
  (testing "Two Xantid Swarms attacking create two separate triggers"
    (let [db (th/create-test-db {:mana {:green 2}})
          db (th/add-opponent db)
          [db obj-id-1] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          [db obj-id-2] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          ;; Cast both (each resolves and registers triggers)
          db (th/cast-and-resolve db :player-1 obj-id-1)
          ;; Need to re-add mana after casting first
          p1-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add p1-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 1 :colorless 0}]])
          db (th/cast-and-resolve db :player-1 obj-id-2)
          db (clear-summoning-sickness db obj-id-1)
          db (clear-summoning-sickness db obj-id-2)
          db (combat/begin-combat db :player-1)
          result (resolution/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{obj-id-1 obj-id-2})
          db-after (:db confirmed)
          stack-items (q/get-all-stack-items db-after)
          trigger-items (filter #(= :creature-attacked (:stack-item/type %)) stack-items)]
      (is (= 2 (count trigger-items))
          "Two Xantid Swarms attacking should create two trigger stack items"))))


;; === F. Flying tests ===

(deftest xantid-swarm-has-flying-keyword-test
  (testing "Xantid Swarm has :flying keyword on battlefield"
    (let [db (th/create-test-db {:mana {:green 1}})
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (true? (creatures/has-keyword? db obj-id :flying))
          "Should have :flying keyword"))))


(deftest xantid-swarm-blocked-by-flying-test
  (testing "Xantid Swarm can be blocked by a creature with flying"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add attacker with flying directly to battlefield
          [db atk-id] (add-creature-to-battlefield db :xantid-swarm :player-1)
          db (clear-summoning-sickness db atk-id)
          ;; Add flying blocker for player-2
          [db blk-id] (add-creature-to-battlefield db :xantid-swarm :player-2)
          eligible (combat/get-eligible-blockers db :player-2 atk-id)]
      (is (contains? (set eligible) blk-id)
          "Flying creature can block a flying attacker"))))


(deftest xantid-swarm-blocked-by-reach-test
  (testing "Xantid Swarm can be blocked by a creature with reach"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :xantid-swarm :player-1)
          db (clear-summoning-sickness db atk-id)
          ;; Nimble Mongoose has no reach — grant :reach keyword via grants system
          [db blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          db (grants/add-grant db blk-id
                               {:grant/id (random-uuid)
                                :grant/type :keyword
                                :grant/source blk-id
                                :grant/data {:grant/keyword :reach}})
          eligible (combat/get-eligible-blockers db :player-2 atk-id)]
      (is (contains? (set eligible) blk-id)
          "Creature with reach can block a flying attacker"))))


(deftest xantid-swarm-not-blocked-by-non-flying-test
  (testing "Xantid Swarm cannot be blocked by ground creatures"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :xantid-swarm :player-1)
          db (clear-summoning-sickness db atk-id)
          ;; Nimble Mongoose has no flying/reach
          [db _] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          eligible (combat/get-eligible-blockers db :player-2 atk-id)]
      (is (empty? eligible)
          "Ground creature cannot block a flying attacker"))))


;; === G. Edge cases ===

(deftest xantid-swarm-summoning-sick-on-entry-test
  (testing "Xantid Swarm cannot attack the turn it enters"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (true? (creatures/summoning-sick? db obj-id))
          "Should have summoning sickness")
      (is (false? (creatures/can-attack? db obj-id))
          "Should not be able to attack"))))


(deftest xantid-swarm-can-attack-after-summoning-sickness-cleared-test
  (testing "Xantid Swarm can attack after summoning sickness is cleared"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          db (clear-summoning-sickness db obj-id)]
      (is (false? (creatures/summoning-sick? db obj-id))
          "Should not be summoning sick after untap")
      (is (true? (creatures/can-attack? db obj-id))
          "Should be able to attack"))))


(deftest xantid-swarm-restriction-expires-at-cleanup-test
  (testing "Opponent's cannot-cast-spells restriction expires at cleanup phase"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Add restriction directly (simulating trigger resolution)
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-spells}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})
          ;; Restriction should be present before cleanup
          _ (is (= 1 (count (grants/get-player-grants db-restricted :player-2)))
                "Restriction should exist before cleanup")
          ;; Expire grants at cleanup
          db-expired (grants/expire-grants db-restricted 1 :cleanup)]
      (is (= 0 (count (grants/get-player-grants db-expired :player-2)))
          "Restriction should be removed after cleanup"))))


(deftest xantid-swarm-bot-controller-restricts-human-test
  (testing "Bot-controlled Xantid Swarm restricts the human player when it attacks"
    (let [db (th/create-test-db)
          db (th/add-opponent db {:bot-archetype :burn})
          ;; Add Xantid Swarm for the bot (player-2) with direct battlefield placement
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-2)
          ;; Cast resolves and registers triggers in Datascript
          db (zones/move-to-zone* db obj-id :battlefield)
          ;; Register triggers manually for the battlefield object
          ;; (simulating what move-resolved-spell does for cast permanents)
          obj-eid (q/get-object-eid db obj-id)
          p2-eid (q/get-player-eid db :player-2)
          card-triggers (:card/triggers xantid-swarm/card)
          tx (trigger-db/create-triggers-for-card-tx db obj-eid p2-eid card-triggers)
          db (d/db-with db tx)
          db (clear-summoning-sickness db obj-id)
          ;; Dispatch creature-attacked event as player-2 controller
          event (game-events/creature-attacked-event obj-id :player-2)
          db-after (dispatch/dispatch-event db event)
          ;; Resolve the trigger
          trigger-result (resolution/resolve-one-item db-after)
          db-resolved (:db trigger-result)
          ;; Player-1 (human) should have the restriction
          p1-grants (grants/get-player-grants db-resolved :player-1)]
      (is (= 1 (count p1-grants))
          "Human player should have exactly one grant")
      (is (some #(= :cannot-cast-spells (:restriction/type (:grant/data %))) p1-grants)
          "Human player should have :cannot-cast-spells restriction from bot's Xantid Swarm"))))
