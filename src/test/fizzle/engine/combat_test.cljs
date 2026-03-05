(ns fizzle.engine.combat-test
  "Tests for combat system: stack items, declare attackers, phase skipping."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.combat :as combat]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.effects.tokens]
    [fizzle.engine.resolution :as resolution]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.zones :as zones]
    [fizzle.events.game :as game]
    [fizzle.events.phases :as phases]
    [fizzle.test-helpers :as th]))


;; === Helpers ===

(defn- add-creature-to-battlefield
  "Add a creature card to battlefield with proper creature fields."
  [db card-id owner]
  (let [[db obj-id] (th/add-card-to-zone db card-id :hand owner)
        db (zones/move-to-zone db obj-id :battlefield)]
    [db obj-id]))


(defn- clear-summoning-sickness
  "Clear summoning sickness from a creature."
  [db obj-id]
  (let [obj-eid (q/get-object-eid db obj-id)]
    (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])))


(defn- set-phase
  "Set the game phase."
  [db phase]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/phase phase]])))


(def beast-token-effect
  {:effect/type :create-token
   :effect/token {:token/name "Beast"
                  :token/power 4
                  :token/toughness 4
                  :token/types #{:creature}
                  :token/subtypes #{:beast}
                  :token/colors #{:green}}})


;; === has-creatures-on-battlefield? ===

(deftest test-no-creatures-on-battlefield
  (testing "No creatures returns false"
    (let [db (th/create-test-db)
          db (th/add-opponent db)]
      (is (false? (combat/has-creatures-on-battlefield? db))
          "Should be false with no creatures"))))


(deftest test-creatures-on-battlefield
  (testing "Creature on battlefield returns true"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (true? (combat/has-creatures-on-battlefield? db))
          "Should be true with creature"))))


(deftest test-token-counts-as-creature
  (testing "Token creature on battlefield returns true"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          db (effects/execute-effect db :player-1 beast-token-effect)]
      (is (true? (combat/has-creatures-on-battlefield? db))
          "Should be true with token creature"))))


;; === get-eligible-attackers ===

(deftest test-eligible-attackers-not-summoning-sick
  (testing "Eligible attackers excludes summoning sick creatures"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (empty? (combat/get-eligible-attackers db :player-1))
          "Summoning sick creature should not be eligible"))))


(deftest test-eligible-attackers-after-clearing-sickness
  (testing "Creature is eligible after summoning sickness cleared"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)]
      (is (= 1 (count (combat/get-eligible-attackers db :player-1)))
          "Non-sick creature should be eligible")
      (is (= obj-id (first (combat/get-eligible-attackers db :player-1)))))))


(deftest test-tapped-creature-not-eligible
  (testing "Tapped creature is not eligible to attack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])]
      (is (empty? (combat/get-eligible-attackers db :player-1))
          "Tapped creature should not be eligible"))))


;; === tap-and-mark-attackers ===

(deftest test-tap-and-mark-attackers
  (testing "Selected attackers get tapped and marked attacking"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          db (combat/tap-and-mark-attackers db [obj-id])
          obj (q/get-object db obj-id)]
      (is (true? (:object/tapped obj))
          "Attacker should be tapped")
      (is (true? (:object/attacking obj))
          "Attacker should be marked attacking"))))


;; === begin-combat ===

(deftest test-begin-combat-pushes-stack-items
  (testing "begin-combat pushes :declare-attackers on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (combat/begin-combat db :player-1)
          top (stack/get-top-stack-item db)]
      (is (some? top)
          "Stack should have an item")
      (is (= :declare-attackers (:stack-item/type top))
          "Top should be :declare-attackers"))))


(deftest test-begin-combat-no-creatures-returns-db-unchanged
  (testing "begin-combat with no creatures returns db unchanged"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          db-before db
          db-after (combat/begin-combat db :player-1)]
      (is (= db-before db-after)
          "Should return db unchanged when no creatures"))))


;; === Phase skipping ===

(deftest test-combat-phase-skipped-when-no-creatures
  (testing "Advancing from main1 skips combat to main2 when no creatures"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          db (set-phase db :main1)
          db (phases/advance-phase db :player-1)
          game-state (q/get-game-state db)]
      (is (= :main2 (:game/phase game-state))
          "Should skip to main2 when no creatures"))))


(deftest test-combat-phase-entered-when-creatures-exist
  (testing "Advancing from main1 enters combat when creatures exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (set-phase db :main1)
          db (phases/advance-phase db :player-1)
          game-state (q/get-game-state db)]
      (is (= :combat (:game/phase game-state))
          "Should enter combat when creatures exist"))))


;; === Resolution ===

(deftest test-declare-attackers-resolution-returns-needs-attackers
  (testing ":declare-attackers resolution returns :needs-attackers with eligible list"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          stack-item {:stack-item/type :declare-attackers
                      :stack-item/controller :player-1}
          result (resolution/resolve-stack-item db stack-item)]
      (is (true? (:needs-attackers result))
          "Should return :needs-attackers")
      (is (= [obj-id] (:eligible-attackers result))
          "Should include eligible attackers"))))


(deftest test-declare-attackers-no-eligible-returns-db
  (testing ":declare-attackers with no eligible attackers returns {:db db}"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Creature exists but is summoning sick
          [db _] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          stack-item {:stack-item/type :declare-attackers
                      :stack-item/controller :player-1}
          result (resolution/resolve-stack-item db stack-item)]
      (is (nil? (:needs-attackers result))
          "Should not need attackers when none eligible")
      (is (some? (:db result))
          "Should return {:db db}"))))


(deftest test-declare-blockers-resolution-stub
  (testing ":declare-blockers resolution is a no-op stub for now"
    (let [db (th/create-test-db)
          stack-item {:stack-item/type :declare-blockers
                      :stack-item/controller :player-1}
          result (resolution/resolve-stack-item db stack-item)]
      (is (some? (:db result))
          "Should return {:db db}"))))


(deftest test-combat-damage-resolution-stub
  (testing ":combat-damage resolution is a no-op stub for now"
    (let [db (th/create-test-db)
          stack-item {:stack-item/type :combat-damage
                      :stack-item/controller :player-1}
          result (resolution/resolve-stack-item db stack-item)]
      (is (some? (:db result))
          "Should return {:db db}"))))


;; === Clear attacking flags ===

(deftest test-clear-combat-state
  (testing "clear-combat-state removes attacking/blocking flags"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          db (combat/tap-and-mark-attackers db [obj-id])
          ;; Verify attacking
          _ (is (true? (:object/attacking (q/get-object db obj-id))))
          ;; Clear combat state
          db (combat/clear-combat-state db)
          obj (q/get-object db obj-id)]
      (is (nil? (:object/attacking obj))
          "Attacking flag should be cleared"))))


;; === Attacker Selection Flow (via resolve-one-item) ===

(deftest test-resolve-one-item-returns-attacker-selection-for-human
  (testing "resolve-one-item returns pending-selection for human player"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-1)
          result (game/resolve-one-item db)]
      (is (some? (:pending-selection result))
          "Should return pending-selection for attacker selection")
      (is (= :select-attackers (:selection/type (:pending-selection result)))
          "Selection type should be :select-attackers")
      (is (= #{obj-id} (set (:selection/valid-targets (:pending-selection result))))
          "Valid targets should include eligible attackers"))))


(deftest test-attacker-selection-confirm-taps-and-marks
  (testing "Confirming attacker selection taps and marks attackers"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-1)
          result (game/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{obj-id})
          db-after (:db confirmed)
          obj (q/get-object db-after obj-id)]
      (is (true? (:object/tapped obj))
          "Attacker should be tapped after selection confirmed")
      (is (true? (:object/attacking obj))
          "Attacker should be marked attacking after selection confirmed"))))


(deftest test-attacker-selection-confirm-empty-skips
  (testing "Confirming with no attackers selected skips remaining combat"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-1)
          result (game/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{})
          db-after (:db confirmed)
          obj (q/get-object db-after obj-id)]
      (is (not (:object/tapped obj))
          "Creature should NOT be tapped if not selected as attacker")
      (is (nil? (:object/attacking obj))
          "Creature should NOT be marked attacking"))))


(deftest test-attacker-selection-pushes-blockers-and-damage
  (testing "Confirming attackers pushes declare-blockers and combat-damage stack items"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-1)
          result (game/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{obj-id})
          db-after (:db confirmed)
          stack-items (q/get-all-stack-items db-after)]
      (is (= 2 (count stack-items))
          "Stack should have 2 items: declare-blockers and combat-damage")
      (let [types (set (map :stack-item/type stack-items))]
        (is (contains? types :declare-blockers)
            "Stack should contain :declare-blockers")
        (is (contains? types :combat-damage)
            "Stack should contain :combat-damage")))))


(deftest test-attacker-selection-no-attackers-no-stack-items
  (testing "Confirming with no attackers does not push blockers/damage"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db _obj-id)
          db (combat/begin-combat db :player-1)
          result (game/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{})
          db-after (:db confirmed)
          stack-items (q/get-all-stack-items db-after)]
      (is (empty? stack-items)
          "Stack should be empty when no attackers selected"))))


;; === Bot Attacker Selection ===

(deftest test-bot-attackers-auto-selected
  (testing "Bot controller auto-selects all eligible attackers"
    (let [db (th/create-test-db)
          db (th/add-opponent db {:bot-archetype :burn})
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          db (clear-summoning-sickness db obj-id)
          db (combat/begin-combat db :player-2)
          result (game/resolve-one-item db)]
      (is (nil? (:pending-selection result))
          "Bot should auto-select attackers, no pending-selection")
      (let [db-after (:db result)
            obj (q/get-object db-after obj-id)]
        (is (true? (:object/tapped obj))
            "Bot's creature should be tapped")
        (is (true? (:object/attacking obj))
            "Bot's creature should be marked attacking")))))


;; === get-eligible-blockers ===

(deftest test-get-eligible-blockers-basic
  (testing "Untapped creatures can block"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk-id)
          [db blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)]
      (is (= [blk-id] (combat/get-eligible-blockers db :player-2 atk-id))))))


(deftest test-tapped-creature-cannot-block
  (testing "Tapped creature cannot block"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk-id)
          [db blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          obj-eid (q/get-object-eid db blk-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])]
      (is (empty? (combat/get-eligible-blockers db :player-2 atk-id))))))


(deftest test-already-blocking-creature-not-eligible
  (testing "Creature already assigned as blocker is not eligible for another attacker"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk1-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk1-id)
          [db _atk2-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          [db blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          blk-eid (q/get-object-eid db blk-id)
          atk1-eid (q/get-object-eid db atk1-id)
          db (d/db-with db [[:db/add blk-eid :object/blocking atk1-eid]])]
      (is (empty? (combat/get-eligible-blockers db :player-2 _atk2-id))))))


(deftest test-declare-blockers-resolution-returns-selection
  (testing ":declare-blockers resolution returns blocker selection"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk-id)
          db (combat/tap-and-mark-attackers db [atk-id])
          [db _blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          db (stack/create-stack-item db {:stack-item/type :declare-blockers
                                          :stack-item/controller :player-1
                                          :stack-item/description "Declare Blockers"})
          result (game/resolve-one-item db)]
      (is (some? (:pending-selection result)))
      (is (= :assign-blockers (:selection/type (:pending-selection result)))))))


(deftest test-declare-blockers-no-attackers-skips
  (testing ":declare-blockers with no attackers returns db unchanged"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          stack-item {:stack-item/type :declare-blockers
                      :stack-item/controller :player-1}
          result (resolution/resolve-stack-item db stack-item)]
      (is (nil? (:needs-blockers result)))
      (is (some? (:db result))))))


(deftest test-blocker-confirm-marks-blocking
  (testing "Confirming blocker assignment marks creature as blocking attacker"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk-id)
          db (combat/tap-and-mark-attackers db [atk-id])
          [db blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          db (stack/create-stack-item db {:stack-item/type :declare-blockers
                                          :stack-item/controller :player-1
                                          :stack-item/description "Declare Blockers"})
          result (game/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{blk-id})
          db-after (:db confirmed)
          blk-obj (q/get-object db-after blk-id)
          atk-eid (q/get-object-eid db-after atk-id)]
      (is (= atk-eid (:object/blocking blk-obj))))))


(deftest test-blocker-confirm-empty-skips
  (testing "Confirming with no blockers leaves attacker unblocked"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk-id)
          db (combat/tap-and-mark-attackers db [atk-id])
          [db blk-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          db (stack/create-stack-item db {:stack-item/type :declare-blockers
                                          :stack-item/controller :player-1
                                          :stack-item/description "Declare Blockers"})
          result (game/resolve-one-item db)
          selection (:pending-selection result)
          confirmed (th/confirm-selection (:db result) selection #{})
          db-after (:db confirmed)
          blk-obj (q/get-object db-after blk-id)]
      (is (nil? (:object/blocking blk-obj))))))


(deftest test-blocker-chaining-multiple-attackers
  (testing "Serial blocker assignment chains through multiple attackers"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db atk1-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk1-id)
          [db atk2-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (clear-summoning-sickness db atk2-id)
          db (combat/tap-and-mark-attackers db [atk1-id atk2-id])
          [db blk1-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          [db blk2-id] (add-creature-to-battlefield db :nimble-mongoose :player-2)
          db (stack/create-stack-item db {:stack-item/type :declare-blockers
                                          :stack-item/controller :player-1
                                          :stack-item/description "Declare Blockers"})
          result (game/resolve-one-item db)
          sel1 (:pending-selection result)
          _ (is (= :assign-blockers (:selection/type sel1)))
          confirmed1 (th/confirm-selection (:db result) sel1 #{blk1-id})
          sel2 (:pending-selection confirmed1)]
      (is (some? sel2))
      (is (= :assign-blockers (:selection/type sel2)))
      (let [confirmed2 (th/confirm-selection (:db confirmed1) sel2 #{blk2-id})
            db-after (:db confirmed2)]
        (is (nil? (:pending-selection confirmed2)))
        (let [blk1-obj (q/get-object db-after blk1-id)
              blk2-obj (q/get-object db-after blk2-id)]
          (is (some? (:object/blocking blk1-obj)))
          (is (some? (:object/blocking blk2-obj))))))))
