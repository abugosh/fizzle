(ns fizzle.engine.creatures-test
  "Tests for engine/creatures.cljs — effective P/T computation and creature predicates."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; === Helpers ===

(defn- add-creature-to-battlefield
  "Add a creature card to battlefield with proper creature fields.
   Returns [db obj-id]."
  [db card-id owner]
  (let [[db obj-id] (th/add-card-to-zone db card-id :hand owner)
        ;; Move to battlefield through zone transition (will set creature fields)
        db (zones/move-to-zone* db obj-id :battlefield)]
    [db obj-id]))


;; === effective-power / effective-toughness ===

(deftest test-base-power-only
  (testing "Creature with no modifiers returns base P/T"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (= 1 (creatures/effective-power db obj-id))
          "Base power should be 1")
      (is (= 1 (creatures/effective-toughness db obj-id))
          "Base toughness should be 1"))))


(deftest test-non-creature-returns-nil
  (testing "effective-power/toughness returns nil for non-creature"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (nil? (creatures/effective-power db obj-id))
          "Non-creature should return nil for power")
      (is (nil? (creatures/effective-toughness db obj-id))
          "Non-creature should return nil for toughness"))))


(deftest test-plus-counters
  (testing "+1/+1 counter adds to effective P/T"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/counters {:+1/+1 2}]])]
      (is (= 3 (creatures/effective-power db obj-id))
          "Power should be 1 + 2 = 3")
      (is (= 3 (creatures/effective-toughness db obj-id))
          "Toughness should be 1 + 2 = 3"))))


(deftest test-minus-counters
  (testing "-1/-1 counter subtracts from effective P/T"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/counters {:-1/-1 1}]])]
      (is (= 0 (creatures/effective-power db obj-id))
          "Power should be 1 - 1 = 0")
      (is (= 0 (creatures/effective-toughness db obj-id))
          "Toughness should be 1 - 1 = 0"))))


(deftest test-pt-grant
  (testing "Temporary P/T grant adds to effective P/T"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (grants/add-grant db obj-id
                               {:grant/id (random-uuid)
                                :grant/type :pt-modifier
                                :grant/source obj-id
                                :grant/data {:grant/power 3 :grant/toughness 3}})]
      (is (= 4 (creatures/effective-power db obj-id))
          "Power should be 1 + 3 = 4")
      (is (= 4 (creatures/effective-toughness db obj-id))
          "Toughness should be 1 + 3 = 4"))))


(deftest test-multiple-grants-additive
  (testing "Multiple P/T grants stack additively"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (-> db
                 (grants/add-grant obj-id
                                   {:grant/id (random-uuid)
                                    :grant/type :pt-modifier
                                    :grant/source obj-id
                                    :grant/data {:grant/power 1 :grant/toughness 1}})
                 (grants/add-grant obj-id
                                   {:grant/id (random-uuid)
                                    :grant/type :pt-modifier
                                    :grant/source obj-id
                                    :grant/data {:grant/power 2 :grant/toughness 3}}))]
      (is (= 4 (creatures/effective-power db obj-id))
          "Power should be 1 + 1 + 2 = 4")
      (is (= 5 (creatures/effective-toughness db obj-id))
          "Toughness should be 1 + 1 + 3 = 5"))))


(deftest test-self-static-ability
  (testing "Self-static-ability modifier (threshold) adds to P/T"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          ;; Add 7 cards to graveyard for threshold
          [db _] (th/add-cards-to-graveyard db (vec (repeat 7 :dark-ritual)) :player-1)]
      (is (= 3 (creatures/effective-power db obj-id))
          "Power should be 1 + 2 = 3 with threshold")
      (is (= 3 (creatures/effective-toughness db obj-id))
          "Toughness should be 1 + 2 = 3 with threshold"))))


(deftest test-static-condition-not-met
  (testing "Static ability doesn't apply when condition not met"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          ;; Only 6 cards (no threshold)
          [db _] (th/add-cards-to-graveyard db (vec (repeat 6 :dark-ritual)) :player-1)]
      (is (= 1 (creatures/effective-power db obj-id))
          "Power should be 1 without threshold")
      (is (= 1 (creatures/effective-toughness db obj-id))
          "Toughness should be 1 without threshold"))))


(deftest test-nil-damage-treated-as-zero
  (testing "Creature without damage-marked attribute works correctly"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      ;; effective-power should work even if damage-marked isn't set
      (is (= 1 (creatures/effective-power db obj-id))))))


;; === creature? ===

(deftest test-creature-predicate
  (testing "creature? returns true for creatures and false for non-creatures"
    (let [db (th/create-test-db)
          [db mongoose-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (true? (creatures/creature? db mongoose-id)))
      (is (false? (creatures/creature? db petal-id))))))


;; === summoning-sick? ===

(deftest test-new-creature-is-sick
  (testing "New creature has summoning sickness"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (true? (creatures/summoning-sick? db obj-id))
          "New creature should be summoning sick"))))


(deftest test-non-creature-not-sick
  (testing "Non-creature is never summoning sick"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (false? (creatures/summoning-sick? db obj-id))
          "Non-creature should not be summoning sick"))))


;; === has-keyword? ===

(deftest test-has-keyword-from-card
  (testing "has-keyword? checks card keywords"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (true? (creatures/has-keyword? db obj-id :shroud))
          "Nimble Mongoose has shroud")
      (is (false? (creatures/has-keyword? db obj-id :haste))
          "Nimble Mongoose does not have haste"))))


(deftest test-has-keyword-from-grant
  (testing "has-keyword? checks granted keywords"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (grants/add-grant db obj-id
                               {:grant/id (random-uuid)
                                :grant/type :keyword
                                :grant/source obj-id
                                :grant/data {:grant/keyword :haste}})]
      (is (true? (creatures/has-keyword? db obj-id :haste))
          "Granted haste should be detected"))))


;; === can-attack? ===

(deftest test-non-creature-cannot-attack
  (testing "Non-creature cannot attack"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (false? (creatures/can-attack? db obj-id))
          "Non-creature should not be able to attack"))))


(deftest test-summoning-sick-cannot-attack
  (testing "Summoning sick creature cannot attack"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (false? (creatures/can-attack? db obj-id))
          "Summoning sick creature should not attack"))))


(deftest test-tapped-cannot-attack
  (testing "Tapped creature cannot attack"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          ;; Clear summoning sickness manually
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])
          ;; Tap the creature
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])]
      (is (false? (creatures/can-attack? db obj-id))
          "Tapped creature should not attack"))))


(deftest test-creature-can-attack-when-eligible
  (testing "Untapped, non-sick creature can attack"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          ;; Clear summoning sickness
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])]
      (is (true? (creatures/can-attack? db obj-id))
          "Eligible creature should be able to attack"))))


(deftest test-haste-overrides-summoning-sickness-for-attack
  (testing "Creature with haste can attack despite summoning sickness"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          db (grants/add-grant db obj-id
                               {:grant/id (random-uuid)
                                :grant/type :keyword
                                :grant/source obj-id
                                :grant/data {:grant/keyword :haste}})]
      (is (true? (creatures/can-attack? db obj-id))
          "Creature with granted haste should attack despite summoning sickness"))))


;; === can-block? ===

(deftest test-non-creature-cannot-block
  (testing "Non-creature cannot block"
    (let [db (th/create-test-db)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          [db attacker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (false? (creatures/can-block? db petal-id attacker-id))
          "Non-creature cannot block"))))


(deftest test-tapped-cannot-block
  (testing "Tapped creature cannot block"
    (let [db (th/create-test-db)
          [db blocker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          [db attacker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          obj-eid (q/get-object-eid db blocker-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])]
      (is (false? (creatures/can-block? db blocker-id attacker-id))
          "Tapped creature cannot block"))))


(deftest test-untapped-creature-can-block
  (testing "Untapped creature can block"
    (let [db (th/create-test-db)
          [db blocker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          [db attacker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)]
      (is (true? (creatures/can-block? db blocker-id attacker-id))
          "Untapped creature should be able to block"))))


;; === Zone transitions ===

(deftest test-creature-entering-bf-gets-pt
  (testing "Creature entering battlefield gets power/toughness/summoning-sick/damage-marked"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          obj (q/get-object db obj-id)]
      (is (= 1 (:object/power obj)) "Should have base power")
      (is (= 1 (:object/toughness obj)) "Should have base toughness")
      (is (true? (:object/summoning-sick obj)) "Should be summoning sick")
      (is (= 0 (:object/damage-marked obj)) "Damage should be 0"))))


(deftest test-creature-entering-bf-sets-pt-from-card-definition
  (testing "Creature entering battlefield gets P/T even if object was created without them
            (production path: objects start without P/T, zone transition must set it)"
    (let [db (th/create-test-db)
          ;; Create a creature object WITHOUT :object/power/:object/toughness
          ;; This simulates the production object creation path where P/T is not pre-set
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db :nimble-mongoose)
          obj-id (random-uuid)
          _ (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :hand
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
          db @conn
          ;; Verify: no P/T on the object yet
          obj-before (q/get-object db obj-id)
          _ (is (nil? (:object/power obj-before)) "Object should NOT have P/T before battlefield")
          ;; Move to battlefield via production zone transition
          db (zones/move-to-zone* db obj-id :battlefield)
          obj (q/get-object db obj-id)]
      ;; P/T must be set from card definition by the zone transition
      (is (= 1 (:object/power obj)) "Zone transition must set power from card definition")
      (is (= 1 (:object/toughness obj)) "Zone transition must set toughness from card definition")
      (is (true? (:object/summoning-sick obj)) "Should be summoning sick")
      (is (= 0 (:object/damage-marked obj)) "Damage should be 0"))))


(deftest test-land-entering-bf-no-creature-fields
  (testing "Land entering battlefield does not get creature fields"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :island :hand :player-1)
          db (zones/move-to-zone* db obj-id :battlefield)
          obj (q/get-object db obj-id)]
      (is (nil? (:object/power obj)) "Land should not have power")
      (is (nil? (:object/toughness obj)) "Land should not have toughness")
      (is (nil? (:object/summoning-sick obj)) "Land should not be summoning sick"))))


(deftest test-creature-leaving-bf-clears-fields
  (testing "Creature leaving battlefield resets P/T to base values and retracts combat-only fields"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          ;; Verify creature fields are set on battlefield
          _ (is (= 1 (:object/power (q/get-object db obj-id))))
          ;; Move to graveyard
          db (zones/move-to-zone* db obj-id :graveyard)
          obj (q/get-object db obj-id)]
      ;; P/T reset to card base values (not retracted — creatures have P/T in all zones)
      (is (= 1 (:object/power obj)) "Power should reset to base value")
      (is (= 1 (:object/toughness obj)) "Toughness should reset to base value")
      ;; Combat-only attrs retracted
      (is (nil? (:object/summoning-sick obj)) "Summoning-sick should be retracted")
      (is (nil? (:object/damage-marked obj)) "Damage should be retracted"))))


;; === Shroud targeting restriction ===

(deftest test-shroud-prevents-targeting
  (testing "Shroud prevents creature from being a valid target"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db mongoose-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          target-req {:target/id :test-target
                      :target/type :object
                      :target/zone :battlefield
                      :target/controller :any
                      :target/criteria {}
                      :target/required true}
          valid-targets (targeting/find-valid-targets db :player-2 target-req)]
      (is (not (some #{mongoose-id} valid-targets))
          "Shroud creature should not appear in valid targets"))))


;; === Defender ===

(deftest test-defender-cannot-attack
  (testing "Creature with defender keyword cannot attack"
    (let [db (th/create-test-db)
          [db obj-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          obj-eid (q/get-object-eid db obj-id)
          ;; Clear summoning sickness
          db (d/db-with db [[:db/retract obj-eid :object/summoning-sick true]])
          ;; Add defender keyword via grant
          db (grants/add-grant db obj-id
                               {:grant/id (random-uuid)
                                :grant/type :keyword
                                :grant/source obj-id
                                :grant/data {:grant/keyword :defender}})]
      (is (false? (creatures/can-attack? db obj-id))
          "Creature with defender cannot attack"))))


;; === Flying/reach blocking ===

(deftest test-flying-blocked-only-by-flying-or-reach
  (testing "Flying attacker can only be blocked by flying or reach"
    (let [db (th/create-test-db)
          [db attacker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          [db blocker-id] (add-creature-to-battlefield db :nimble-mongoose :player-1)
          ;; Grant flying to attacker
          db (grants/add-grant db attacker-id
                               {:grant/id (random-uuid)
                                :grant/type :keyword
                                :grant/source attacker-id
                                :grant/data {:grant/keyword :flying}})]
      ;; Blocker without flying or reach cannot block flying attacker
      (is (false? (creatures/can-block? db blocker-id attacker-id))
          "Non-flying/reach creature cannot block flying attacker")
      ;; Grant reach to blocker
      (let [db (grants/add-grant db blocker-id
                                 {:grant/id (random-uuid)
                                  :grant/type :keyword
                                  :grant/source blocker-id
                                  :grant/data {:grant/keyword :reach}})]
        (is (true? (creatures/can-block? db blocker-id attacker-id))
            "Reach creature can block flying attacker")))))
