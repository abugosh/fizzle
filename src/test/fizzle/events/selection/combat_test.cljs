(ns fizzle.events.selection.combat-test
  "Pattern B (multimethod production-path slice) tests for
   events/selection/combat.cljs defmethods.

   Covers all 3 defmethod registrations:

   execute-confirmed-selection (2):
     :select-attackers  - heavy executor: taps creatures, removes declare-attackers
                          stack item, creates combat-damage + declare-blockers stack
                          items, dispatches creature-attacked triggers.
                          Empty-selection branch: removes stack-item only.
     :assign-blockers   - marks selected creatures as blocking the current attacker.

   build-chain-selection (1):
     :assign-blockers   - chains to next blocker selection when :remaining-attackers
                          non-empty; returns nil when all attackers processed.

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test.

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or via th/confirm-selection for :selected-field selections).

   Note: combat builders (build-attacker-selection, build-blocker-selection) are
   regular Clojure functions, NOT defmethods. Calling them directly IS the
   production API — this is not a bypass."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.selection.combat :as sel-combat]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Setup Helpers
;; =====================================================

(defn- setup-db
  "Create a minimal game-db with player-1 (and optional player-2)."
  ([]
   (setup-db {}))
  ([opts]
   (let [db (th/create-test-db {})]
     (if (:add-opponent? opts)
       (th/add-opponent db)
       db))))


(defn- add-ready-creature
  "Add a test creature on the battlefield without summoning sickness.
   Returns [db obj-id].
   Power/toughness default to 2/2 if not specified."
  ([db owner]
   (add-ready-creature db owner 2 2))
  ([db owner power toughness]
   (let [[db1 obj-id] (th/add-test-creature db owner power toughness)
         obj-eid (q/get-object-eid db1 obj-id)
         db2 (d/db-with db1 [[:db/retract obj-eid :object/summoning-sick true]])]
     [db2 obj-id])))


(defn- create-declare-attackers-item
  "Create a :declare-attackers stack-item for the given controller.
   Returns [db stack-item-eid]."
  [db controller]
  (let [db1 (stack/create-stack-item db {:stack-item/type :declare-attackers
                                         :stack-item/controller controller
                                         :stack-item/description "Declare Attackers"})
        si (stack/get-top-stack-item db1)]
    [db1 (:db/id si)]))


(defn- get-all-stack-item-types
  "Return a set of all :stack-item/type values currently on the stack."
  [db]
  (set (d/q '[:find [?t ...]
              :where [?e :stack-item/type ?t]]
            db)))


(defn- count-stack-items
  "Count total stack items in db."
  [db]
  (count (d/q '[:find [?e ...]
                :where [?e :stack-item/position _]]
              db)))


;; =====================================================
;; execute-confirmed-selection :select-attackers
;; Heavy executor: taps attackers, removes declare-attackers stack item,
;; creates combat-damage + declare-blockers stack items, dispatches triggers.
;; =====================================================

(deftest select-attackers-executor-happy-path
  (testing ":select-attackers executor taps creatures and creates combat stack items"
    (let [db (setup-db {:add-opponent? true})
          ;; Two ready creatures for player-1
          [db c1-id] (add-ready-creature db :player-1 2 2)
          [db c2-id] (add-ready-creature db :player-1 3 3)
          [db si-eid] (create-declare-attackers-item db :player-1)
          ;; Build attacker selection via production builder
          sel (sel-combat/build-attacker-selection [c1-id c2-id] :player-1 si-eid)
          ;; Route through chokepoint
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Player picks both creatures
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{c1-id c2-id})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)]
      ;; Both creatures should be tapped
      (is (true? (:object/tapped (q/get-object db' c1-id)))
          "Creature 1 should be tapped after attacking")
      (is (true? (:object/tapped (q/get-object db' c2-id)))
          "Creature 2 should be tapped after attacking")
      ;; Both creatures should be marked as attacking
      (is (true? (:object/attacking (q/get-object db' c1-id)))
          "Creature 1 should be marked as attacking")
      (is (true? (:object/attacking (q/get-object db' c2-id)))
          "Creature 2 should be marked as attacking")
      ;; :declare-attackers stack item should be removed
      (is (not (contains? (get-all-stack-item-types db') :declare-attackers))
          ":declare-attackers stack item should be removed after executor runs")
      ;; :combat-damage and :declare-blockers stack items should be created
      (is (contains? (get-all-stack-item-types db') :combat-damage)
          ":combat-damage stack item should be created")
      (is (contains? (get-all-stack-item-types db') :declare-blockers)
          ":declare-blockers stack item should be created")
      ;; Exactly 2 stack items remain (combat-damage + declare-blockers)
      (is (= 2 (count-stack-items db'))
          "Exactly 2 stack items should remain after declaring attackers"))))


(deftest select-attackers-executor-empty-selection-branch
  (testing ":select-attackers executor with empty selection removes only the declare-attackers item"
    (let [db (setup-db {:add-opponent? true})
          [db c1-id] (add-ready-creature db :player-1 2 2)
          [db si-eid] (create-declare-attackers-item db :player-1)
          sel (sel-combat/build-attacker-selection [c1-id] :player-1 si-eid)
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Player picks NO creatures (passes on attacks)
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)]
      ;; :declare-attackers stack item should be removed
      (is (not (contains? (get-all-stack-item-types db') :declare-attackers))
          ":declare-attackers stack item should be removed even with empty attacker selection")
      ;; NO combat stack items should be created (no combat happened)
      (is (not (contains? (get-all-stack-item-types db') :combat-damage))
          ":combat-damage should NOT be created when no attackers declared")
      (is (not (contains? (get-all-stack-item-types db') :declare-blockers))
          ":declare-blockers should NOT be created when no attackers declared")
      ;; Stack should be empty
      (is (= 0 (count-stack-items db'))
          "Stack should be empty after passing on attacks")
      ;; Creature should NOT be tapped
      (is (not (:object/tapped (q/get-object db' c1-id)))
          "Creature should not be tapped when not selected as attacker"))))


;; =====================================================
;; execute-confirmed-selection :assign-blockers
;; Marks selected blockers as blocking the current attacker.
;; =====================================================

(deftest assign-blockers-executor-marks-blockers-on-attacker
  (testing ":assign-blockers executor marks selected creatures as blocking the attacker"
    (let [db (setup-db {:add-opponent? true})
          ;; Player-1 has an attacker (already attacking/tapped)
          [db c1-id] (add-ready-creature db :player-1 2 2)
          c1-eid (q/get-object-eid db c1-id)
          db (d/db-with db [[:db/add c1-eid :object/attacking true]
                            [:db/add c1-eid :object/tapped true]])
          ;; Player-2 has a blocker (not tapped)
          [db blocker-id] (add-ready-creature db :player-2 1 1)
          ;; Create declare-blockers stack item
          [db si-eid] (create-declare-attackers-item db :player-1)
          ;; Build blocker selection via production builder
          sel (sel-combat/build-blocker-selection db [c1-id] :player-2 si-eid)
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Player-2 picks the blocker
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{blocker-id})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)
          blocker-obj (q/get-object db' blocker-id)]
      ;; The blocking reference should point to the attacker
      (let [atk-eid (q/get-object-eid db' c1-id)]
        (is (= atk-eid (:object/blocking blocker-obj))
            ":object/blocking should reference the attacker's entity id")))))


(deftest assign-blockers-executor-empty-selection-no-blockers-assigned
  (testing ":assign-blockers executor with empty selection assigns no blockers"
    (let [db (setup-db {:add-opponent? true})
          [db c1-id] (add-ready-creature db :player-1 2 2)
          c1-eid (q/get-object-eid db c1-id)
          db (d/db-with db [[:db/add c1-eid :object/attacking true]])
          [db blocker-id] (add-ready-creature db :player-2 1 1)
          [db si-eid] (create-declare-attackers-item db :player-1)
          sel (sel-combat/build-blocker-selection db [c1-id] :player-2 si-eid)
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Player-2 picks no blockers
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{})
          result (sel-core/confirm-selection-impl app-db')
          db' (:game/db result)
          blocker-obj (q/get-object db' blocker-id)]
      ;; Blocker should have no :object/blocking attribute
      (is (nil? (:object/blocking blocker-obj))
          "Blocker should not have :object/blocking set when no blockers declared"))))


;; =====================================================
;; build-chain-selection :assign-blockers
;; Returns next blocker selection when :remaining-attackers non-empty.
;; Returns nil when all attackers processed.
;; =====================================================

(deftest assign-blockers-chain-builder-produces-next-selection-when-remaining
  (testing "build-chain-selection :assign-blockers produces next selection when remaining-attackers non-empty"
    (let [db (setup-db {:add-opponent? true})
          [db c1-id] (add-ready-creature db :player-1 2 2)
          [db c2-id] (add-ready-creature db :player-1 3 3)
          c1-eid (q/get-object-eid db c1-id)
          c2-eid (q/get-object-eid db c2-id)
          db (d/db-with db [[:db/add c1-eid :object/attacking true]
                            [:db/add c2-eid :object/attacking true]])
          [db _blocker-id] (add-ready-creature db :player-2 1 1)
          [db si-eid] (create-declare-attackers-item db :player-1)
          ;; Build blocker selection for both attackers — c1 is current, c2 is remaining
          sel (sel-combat/build-blocker-selection db [c1-id c2-id] :player-2 si-eid)
          app-db (sel-spec/set-pending-selection {:game/db db} sel)
          ;; Confirm with no blockers for c1 — chain builder should produce selection for c2
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{})
          result (sel-core/confirm-selection-impl app-db')
          chained-sel (:game/pending-selection result)]
      ;; Chain builder should return a new :assign-blockers selection
      (is (= :assign-blockers (:selection/domain chained-sel))
          "Chain builder should produce :assign-blockers selection for next attacker")
      ;; The next current-attacker should be c2 (the previously-remaining one)
      (is (= c2-id (:selection/current-attacker chained-sel))
          "Chain builder should advance to next attacker (c2)")
      ;; remaining-attackers should now be empty
      (is (empty? (:selection/remaining-attackers chained-sel))
          "Chain builder should have empty remaining-attackers after advancing to last attacker")
      ;; Player should be the defender
      (is (= :player-2 (:selection/player-id chained-sel))
          "Chain builder should preserve :player-2 as the blocking player")
      ;; stack-item-eid should carry through
      (is (= si-eid (:selection/stack-item-eid chained-sel))
          "Chain builder should preserve stack-item-eid through the chain"))))


(deftest assign-blockers-chain-builder-returns-nil-when-no-remaining
  (testing "build-chain-selection :assign-blockers returns nil when no remaining-attackers"
    (let [db (setup-db {:add-opponent? true})
          [db c1-id] (add-ready-creature db :player-1 2 2)
          c1-eid (q/get-object-eid db c1-id)
          db (d/db-with db [[:db/add c1-eid :object/attacking true]])
          [db si-eid] (create-declare-attackers-item db :player-1)
          ;; Build selection with only 1 attacker — no remaining after it
          sel (sel-combat/build-blocker-selection db [c1-id] :player-2 si-eid)
          ;; Verify remaining-attackers is empty
          _ (is (empty? (:selection/remaining-attackers sel))
                "Precondition: remaining-attackers should be empty for single attacker")
          ;; Call build-chain-selection directly to test the return value
          next-sel (sel-core/build-chain-selection db sel)]
      ;; Chain builder should return nil — no more attackers
      (is (nil? next-sel)
          "build-chain-selection should return nil when :remaining-attackers is empty"))))


;; =====================================================
;; Full round-trip: declare-attackers → assign-blockers chain
;; Tests all 3 defmethods in sequence as they execute in production.
;; =====================================================

(deftest combat-full-round-trip-two-attackers-two-blocker-steps
  (testing "Full 3-defmethod round-trip: select-attackers → assign-blockers × 2"
    (let [db (setup-db {:add-opponent? true})
          ;; Two attackers for player-1
          [db atk1-id] (add-ready-creature db :player-1 2 2)
          [db atk2-id] (add-ready-creature db :player-1 1 1)
          ;; One potential blocker for player-2
          [db blocker-id] (add-ready-creature db :player-2 2 2)
          ;; Create declare-attackers stack item (as begin-combat would)
          [db si-eid] (create-declare-attackers-item db :player-1)

          ;; === Step 1: :select-attackers executor ===
          atk-sel (sel-combat/build-attacker-selection [atk1-id atk2-id] :player-1 si-eid)
          app-db (sel-spec/set-pending-selection {:game/db db} atk-sel)
          app-db' (update app-db :game/pending-selection assoc :selection/selected #{atk1-id atk2-id})
          result1 (sel-core/confirm-selection-impl app-db')
          db1 (:game/db result1)]

      ;; Verify step 1 effects
      (is (true? (:object/attacking (q/get-object db1 atk1-id)))
          "Round-trip: atk1 should be attacking after step 1")
      (is (true? (:object/attacking (q/get-object db1 atk2-id)))
          "Round-trip: atk2 should be attacking after step 1")
      (is (contains? (get-all-stack-item-types db1) :declare-blockers)
          "Round-trip: :declare-blockers stack item should exist after step 1")

      ;; Get the declare-blockers stack item eid for blocker selections
      (let [db-si (first (filter #(= :declare-blockers (:stack-item/type %))
                                 (map #(d/pull db1 [:db/id :stack-item/type] %)
                                      (d/q '[:find [?e ...] :where [?e :stack-item/position _]] db1))))
            blockers-si-eid (:db/id db-si)

            ;; === Step 2: :assign-blockers executor for atk1 ===
            ;; Build blocker selection for first attacker (atk1), with atk2 as remaining
            blk-sel1 (sel-combat/build-blocker-selection db1 [atk1-id atk2-id] :player-2 blockers-si-eid)
            app-db2 (sel-spec/set-pending-selection {:game/db db1} blk-sel1)
            ;; player-2 blocks atk1 with their creature
            app-db2' (update app-db2 :game/pending-selection assoc :selection/selected #{blocker-id})
            result2 (sel-core/confirm-selection-impl app-db2')
            db2 (:game/db result2)
            chained-sel (:game/pending-selection result2)]

        ;; Verify step 2 effects: blocker assigned to atk1
        (is (= (q/get-object-eid db2 atk1-id)
               (:object/blocking (q/get-object db2 blocker-id)))
            "Round-trip: blocker should reference atk1 as the blocked attacker")

        ;; chain builder produced step 3 selection for atk2
        (is (= :assign-blockers (:selection/domain chained-sel))
            "Round-trip: chain builder should produce :assign-blockers for atk2")
        (is (= atk2-id (:selection/current-attacker chained-sel))
            "Round-trip: next selection should have atk2 as current-attacker")
        (is (empty? (:selection/remaining-attackers chained-sel))
            "Round-trip: no more remaining-attackers after step 3 selection produced")

        ;; === Step 3: :assign-blockers executor for atk2 (no blockers left) ===
        (let [app-db3 (sel-spec/set-pending-selection {:game/db db2} chained-sel)
              app-db3' (update app-db3 :game/pending-selection assoc :selection/selected #{})
              result3 (sel-core/confirm-selection-impl app-db3')
              db3 (:game/db result3)]
          ;; No new blockers — state unchanged for atk2
          (is (nil? (:object/blocking (q/get-object db3 atk2-id)))
              "Round-trip: atk2 should be unblocked")
          ;; Chain builder returns nil — no more selections needed
          (is (nil? (:game/pending-selection result3))
              "Round-trip: no more pending selections after last attacker processed"))))))
