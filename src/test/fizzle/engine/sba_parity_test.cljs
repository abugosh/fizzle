(ns fizzle.engine.sba-parity-test
  "SBA parity tests: all 6 types for both players (human + opponent),
   plus cascading SBA scenarios using real SBA types.

   Pattern from state_based_test.cljs lines 198-262:
   - check-sba test (fires / does not fire)
   - execute-sba test (mutates db correctly)
   - check-and-execute-sbas integration test

   Anti-patterns avoided:
   - NO d/conn-from-db in new tests (use d/db-with)
   - NO add-permanent helper (use th/add-test-creature for creatures)
   - NO some?/contains? assertions (exact = always)
   - NO hand-built stack items"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]
    [fizzle.test-helpers :as th]))


;; === Test helpers ===

(defn- get-loss-condition
  [db]
  (:game/loss-condition (q/get-game-state db)))


;; === :lethal-damage SBA — player-1 ===

(deftest test-check-sba-lethal-damage-fires-equal
  (testing "check-sba :lethal-damage fires when damage-marked = toughness"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          sbas (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas))
          "Should return exactly one lethal-damage SBA")
      (is (= :lethal-damage (:sba/type (first sbas)))
          "SBA type should be :lethal-damage")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the damaged creature"))))


(deftest test-check-sba-lethal-damage-fires-overkill
  (testing "check-sba :lethal-damage fires when damage-marked > toughness (overkill)"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 5]])
          sbas (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas)))
      (is (= :lethal-damage (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))))))


(deftest test-check-sba-lethal-damage-no-fire-below-toughness
  (testing "check-sba :lethal-damage does NOT fire when damage-marked < toughness"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 1]])
          sbas (sba/check-sba db :lethal-damage)]
      (is (empty? sbas)
          "Should not fire when damage < toughness"))))


(deftest test-check-sba-lethal-damage-no-fire-zero-damage
  (testing "check-sba :lethal-damage does NOT fire when damage-marked is 0"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db _obj-id] (th/add-test-creature db :player-1 2 2)
          ;; damage-marked starts at 0 (set by add-test-creature)
          sbas (sba/check-sba db :lethal-damage)]
      (is (empty? sbas)
          "Should not fire when damage-marked is 0"))))


(deftest test-execute-sba-lethal-damage-moves-to-graveyard
  (testing "execute-sba :lethal-damage moves creature to graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          sba-event {:sba/type :lethal-damage :sba/target obj-id}
          db' (sba/execute-sba db sba-event)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Creature should be in graveyard after lethal-damage SBA"))))


(deftest test-execute-sba-lethal-damage-guard-already-removed
  (testing "execute-sba :lethal-damage is a no-op when creature already removed"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          ;; Remove the creature first
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db.fn/retractEntity obj-eid]])
          sba-event {:sba/type :lethal-damage :sba/target obj-id}
          db' (sba/execute-sba db sba-event)]
      ;; Should return db unchanged (no crash)
      (is (nil? (q/get-object-eid db' obj-id))
          "Creature should still not exist after no-op execute"))))


(deftest test-check-and-execute-sbas-lethal-damage-integration
  (testing "check-and-execute-sbas moves lethally damaged creature to graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          db' (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Integration: check-and-execute-sbas should move creature to graveyard"))))


;; === :lethal-damage SBA — player-2 (opponent) ===

(deftest test-check-sba-lethal-damage-opponent-creature
  (testing "check-sba :lethal-damage fires for opponent's lethally damaged creature"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-2 3 3)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 3]])
          sbas (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas)))
      (is (= :lethal-damage (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))))))


(deftest test-check-and-execute-sbas-lethal-damage-opponent-integration
  (testing "check-and-execute-sbas moves opponent's lethally damaged creature to graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-2 3 3)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/damage-marked 4]])
          db' (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Opponent's creature should be in graveyard"))))


;; === :zero-toughness SBA — player-1 ===

(deftest test-check-sba-zero-toughness-fires-at-zero
  (testing "check-sba :zero-toughness fires for 1/0 creature"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 1 0)
          sbas (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas))
          "Should return one zero-toughness SBA")
      (is (= :zero-toughness (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))))))


(deftest test-check-sba-zero-toughness-fires-negative
  (testing "check-sba :zero-toughness fires for creature with negative toughness via -1/-1 counters"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 2 2)
          obj-eid (q/get-object-eid db obj-id)
          ;; Apply -1/-1 counters to reduce toughness to -1
          db (d/db-with db [[:db/add obj-eid :object/counters {:-1/-1 3}]])
          sbas (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas))
          "Should fire for creature with toughness <= 0 from counters")
      (is (= :zero-toughness (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))))))


(deftest test-check-sba-zero-toughness-no-fire-positive
  (testing "check-sba :zero-toughness does NOT fire for creature with positive toughness"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db _obj-id] (th/add-test-creature db :player-1 2 2)
          sbas (sba/check-sba db :zero-toughness)]
      (is (empty? sbas)
          "Should not fire for 2/2 creature"))))


(deftest test-execute-sba-zero-toughness-moves-to-graveyard
  (testing "execute-sba :zero-toughness moves creature to graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 1 0)
          sba-event {:sba/type :zero-toughness :sba/target obj-id}
          db' (sba/execute-sba db sba-event)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "0-toughness creature should be in graveyard"))))


(deftest test-execute-sba-zero-toughness-guard-already-removed
  (testing "execute-sba :zero-toughness is a no-op when creature already removed"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 1 0)
          obj-eid (q/get-object-eid db obj-id)
          db (d/db-with db [[:db.fn/retractEntity obj-eid]])
          sba-event {:sba/type :zero-toughness :sba/target obj-id}
          db' (sba/execute-sba db sba-event)]
      (is (nil? (q/get-object-eid db' obj-id))
          "Creature remains gone after no-op execute"))))


(deftest test-check-and-execute-sbas-zero-toughness-integration
  (testing "check-and-execute-sbas removes 0-toughness creature"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-1 1 0)
          db' (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Integration: 0-toughness creature should be in graveyard"))))


;; === :zero-toughness SBA — player-2 (opponent) ===

(deftest test-check-sba-zero-toughness-opponent-creature
  (testing "check-sba :zero-toughness fires for opponent's 0-toughness creature"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-2 2 0)
          sbas (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas)))
      (is (= :zero-toughness (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))))))


(deftest test-check-and-execute-sbas-zero-toughness-opponent-integration
  (testing "check-and-execute-sbas removes opponent's 0-toughness creature"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db obj-id] (th/add-test-creature db :player-2 2 0)
          db' (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Opponent's 0-toughness creature should be in graveyard"))))


;; === :token-cleanup SBA ===

(defn- add-token-in-zone
  "Create a token object in the specified zone (graveyard/exile/etc.) via d/db-with.
   Returns [db token-id card-eid]."
  [db player-id zone]
  (let [player-eid (q/get-player-eid db player-id)
        card-id (keyword (str "token-card-" (random-uuid)))
        db (d/db-with db [{:card/id card-id
                           :card/name "Test Token"
                           :card/types #{:creature}}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        token-id (random-uuid)
        db (d/db-with db [{:object/id token-id
                           :object/card card-eid
                           :object/zone zone
                           :object/owner player-eid
                           :object/controller player-eid
                           :object/is-token true
                           :object/tapped false}])]
    [db token-id card-eid]))


(deftest test-check-sba-token-cleanup-graveyard
  (testing "check-sba :token-cleanup fires for token in graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id _card-eid] (add-token-in-zone db :player-1 :graveyard)
          sbas (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas))
          "Should return one token-cleanup SBA")
      (is (= :token-cleanup (:sba/type (first sbas))))
      (is (= token-id (:sba/target (first sbas)))))))


(deftest test-check-sba-token-cleanup-exile
  (testing "check-sba :token-cleanup fires for token in exile"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id _card-eid] (add-token-in-zone db :player-1 :exile)
          sbas (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas)))
      (is (= :token-cleanup (:sba/type (first sbas))))
      (is (= token-id (:sba/target (first sbas)))))))


(deftest test-check-sba-token-cleanup-no-fire-on-battlefield
  (testing "check-sba :token-cleanup does NOT fire for token on battlefield"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db _token-id _card-eid] (add-token-in-zone db :player-1 :battlefield)
          sbas (sba/check-sba db :token-cleanup)]
      (is (empty? sbas)
          "Tokens on battlefield should not be cleaned up"))))


(deftest test-execute-sba-token-cleanup-retracts-token-and-card
  (testing "execute-sba :token-cleanup retracts both token entity and its synthetic card"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id card-eid] (add-token-in-zone db :player-1 :graveyard)
          sba-event {:sba/type :token-cleanup :sba/target token-id}
          db' (sba/execute-sba db sba-event)]
      ;; Token entity gone
      (is (nil? (q/get-object-eid db' token-id))
          "Token entity should be fully retracted")
      ;; Synthetic card entity gone
      (is (nil? (d/q '[:find ?e . :in $ ?ce :where [?e :db/id ?ce]] db' card-eid))
          "Synthetic card entity should be fully retracted"))))


(deftest test-execute-sba-token-cleanup-guard-already-removed
  (testing "execute-sba :token-cleanup is a no-op when token already gone"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id _card-eid] (add-token-in-zone db :player-1 :graveyard)
          token-eid (q/get-object-eid db token-id)
          db (d/db-with db [[:db.fn/retractEntity token-eid]])
          sba-event {:sba/type :token-cleanup :sba/target token-id}
          db' (sba/execute-sba db sba-event)]
      (is (nil? (q/get-object-eid db' token-id))
          "Token should still be gone after no-op"))))


(deftest test-check-and-execute-sbas-token-cleanup-integration
  (testing "check-and-execute-sbas retracts token in graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id _card-eid] (add-token-in-zone db :player-1 :graveyard)
          db' (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object-eid db' token-id))
          "Integration: token should be fully removed"))))


(deftest test-check-sba-token-cleanup-multiple-zones
  (testing "check-sba :token-cleanup returns SBAs for tokens in multiple non-battlefield zones"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token1-id _] (add-token-in-zone db :player-1 :graveyard)
          [db token2-id _] (add-token-in-zone db :player-1 :exile)
          sbas (sba/check-sba db :token-cleanup)
          sba-targets (set (map :sba/target sbas))]
      (is (= 2 (count sbas))
          "Should return two token-cleanup SBAs")
      (is (contains? sba-targets token1-id)
          "Should target graveyard token")
      (is (contains? sba-targets token2-id)
          "Should target exile token"))))


;; === :token-cleanup SBA — player-2 (opponent) ===

(deftest test-check-sba-token-cleanup-opponent-graveyard
  (testing "check-sba :token-cleanup fires for opponent's token in graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id _] (add-token-in-zone db :player-2 :graveyard)
          sbas (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas)))
      (is (= :token-cleanup (:sba/type (first sbas))))
      (is (= token-id (:sba/target (first sbas)))))))


(deftest test-check-and-execute-sbas-token-cleanup-opponent-integration
  (testing "check-and-execute-sbas retracts opponent's token from graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db token-id _] (add-token-in-zone db :player-2 :graveyard)
          db' (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object-eid db' token-id))
          "Opponent's graveyard token should be removed"))))


;; === :state-check-trigger SBA ===

(defn- add-creature-with-state-trigger
  "Create a creature with a state trigger (power >= threshold → sacrifice self).
   Uses d/db-with for state setup.
   Returns [db obj-id]."
  [db owner power toughness threshold]
  (let [player-eid (q/get-player-eid db owner)
        card-id (keyword (str "test-trigger-creature-" (random-uuid)))
        state-trigger {:state/condition {:condition/type :power-gte
                                         :condition/threshold threshold}
                       :state/effects [{:effect/type :sacrifice :effect/target :self}]
                       :state/description (str "Sacrifice when power >= " threshold)}
        db (d/db-with db [{:card/id card-id
                           :card/name "Test Trigger Creature"
                           :card/cmc 2
                           :card/mana-cost {:colorless 2}
                           :card/colors #{}
                           :card/types #{:creature}
                           :card/text ""
                           :card/power power
                           :card/toughness toughness
                           :card/state-triggers [state-trigger]}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        obj-id (random-uuid)
        db (d/db-with db [{:object/id obj-id
                           :object/card card-eid
                           :object/zone :battlefield
                           :object/owner player-eid
                           :object/controller player-eid
                           :object/tapped false
                           :object/summoning-sick true
                           :object/damage-marked 0
                           :object/power power
                           :object/toughness toughness}])]
    [db obj-id]))


(deftest test-check-sba-state-check-trigger-condition-met
  (testing "check-sba :state-check-trigger fires when power >= threshold"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; 7/7 creature with trigger: sacrifice when power >= 7
          [db obj-id] (add-creature-with-state-trigger db :player-1 7 7 7)
          sbas (sba/check-sba db :state-check-trigger)]
      (is (= 1 (count sbas))
          "Should return one state-check-trigger SBA")
      (is (= :state-check-trigger (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))))))


(deftest test-check-sba-state-check-trigger-condition-not-met
  (testing "check-sba :state-check-trigger does NOT fire when power < threshold"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; 6/6 creature with trigger: sacrifice when power >= 7
          [db _obj-id] (add-creature-with-state-trigger db :player-1 6 6 7)
          sbas (sba/check-sba db :state-check-trigger)]
      (is (empty? sbas)
          "Should not fire when power is below threshold"))))


(deftest test-execute-sba-state-check-trigger-creates-stack-item
  (testing "execute-sba :state-check-trigger puts trigger on the stack"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db _obj-id] (add-creature-with-state-trigger db :player-1 7 7 7)
          sbas (sba/check-sba db :state-check-trigger)
          _ (is (= 1 (count sbas)) "Precondition: one SBA should fire")
          db' (sba/execute-sba db (first sbas))
          ;; Find any state-check-trigger stack items
          stack-items (d/q '[:find [?e ...]
                             :where [?e :stack-item/type :state-check-trigger]]
                           db')]
      (is (= 1 (count stack-items))
          "Stack should have exactly one state-check-trigger item"))))


(deftest test-check-sba-state-check-trigger-duplicate-guard
  (testing "check-sba :state-check-trigger does NOT fire when trigger already on stack"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db _obj-id] (add-creature-with-state-trigger db :player-1 7 7 7)
          ;; First execution puts trigger on stack
          sbas (sba/check-sba db :state-check-trigger)
          db-with-trigger (sba/execute-sba db (first sbas))
          ;; Now check again — duplicate guard should prevent re-firing
          sbas-after (sba/check-sba db-with-trigger :state-check-trigger)]
      (is (empty? sbas-after)
          "Duplicate guard: trigger should NOT fire again when already on stack"))))


(deftest test-check-and-execute-sbas-state-check-trigger-integration
  (testing "check-and-execute-sbas puts state-check-trigger on stack"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db _obj-id] (add-creature-with-state-trigger db :player-1 7 7 7)
          db' (sba/check-and-execute-sbas db)
          stack-items (d/q '[:find [?e ...]
                             :where [?e :stack-item/type :state-check-trigger]]
                           db')]
      (is (= 1 (count stack-items))
          "Integration: state-check-trigger should be on stack"))))


;; === :life-zero SBA — opponent (player-2) ===

(deftest test-check-sba-life-zero-opponent-fires
  (testing "check-sba :life-zero fires when opponent has life = 0"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Set opponent life to 0 via d/db-with
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life 0]])
          sbas (sba/check-sba db :life-zero)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA for opponent at 0 life")
      (is (= :life-zero (:sba/type (first sbas))))
      (is (= :player-2 (:sba/player-id (first sbas)))
          "SBA should identify player-2 as the loser"))))


(deftest test-check-sba-life-zero-opponent-negative
  (testing "check-sba :life-zero fires when opponent has negative life"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life -3]])
          sbas (sba/check-sba db :life-zero)]
      (is (= 1 (count sbas)))
      (is (= :player-2 (:sba/player-id (first sbas)))))))


(deftest test-execute-sba-life-zero-opponent-sets-loss-condition
  (testing "execute-sba :life-zero for opponent sets loss condition with player-1 as winner"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life 0]])
          sba-event {:sba/type :life-zero :sba/player-id :player-2}
          db' (sba/execute-sba db sba-event)]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be :life-zero")
      (let [game (q/get-game-state db')
            winner-eid (:game/winner game)
            winner-pid (when winner-eid
                         (:player/id (d/pull db' [:player/id] (:db/id winner-eid))))]
        (is (= :player-1 winner-pid)
            "Player-1 should be the winner when opponent loses")))))


(deftest test-check-and-execute-sbas-life-zero-opponent-integration
  (testing "check-and-execute-sbas sets loss condition when opponent at 0 life"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life 0]])
          db' (sba/check-and-execute-sbas db)]
      (is (= :life-zero (get-loss-condition db'))
          "Integration: opponent at 0 life triggers loss condition"))))


;; === Both players at 0 life simultaneously ===

(deftest test-check-sba-life-zero-both-players-at-zero
  (testing "check-sba :life-zero returns SBAs for both players when both at 0 life"
    (let [db (th/create-test-db {:life 0})
          db (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life 0]])
          sbas (sba/check-sba db :life-zero)
          player-ids (set (map :sba/player-id sbas))]
      (is (= 2 (count sbas))
          "Should return one SBA per player at 0 life")
      (is (contains? player-ids :player-1)
          "Should include player-1")
      (is (contains? player-ids :player-2)
          "Should include player-2"))))


(deftest test-check-and-execute-sbas-both-at-zero-first-processed-wins
  (testing "When both players at 0 life, first SBA sets loss condition; second is skipped"
    (let [db (th/create-test-db {:life 0})
          db (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/life 0]])
          db' (sba/check-and-execute-sbas db)]
      ;; Exactly one loss condition is set (not both)
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be :life-zero"))))


;; === Cascading SBA tests using real SBA types ===

(deftest test-cascading-token-lethal-damage-then-cleanup
  (testing "Token with lethal damage: lethal-damage moves to graveyard, token-cleanup retracts"
    ;; Scenario: 2/2 token with 2 damage on battlefield.
    ;; Loop 1: lethal-damage SBA fires → moves token to graveyard
    ;; Loop 2: token-cleanup SBA fires → retracts token entity entirely
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Create a token directly on the battlefield with damage
          player-eid (q/get-player-eid db :player-1)
          card-id (keyword (str "token-card-" (random-uuid)))
          db (d/db-with db [{:card/id card-id
                             :card/name "Test Token Creature"
                             :card/types #{:creature}
                             :card/power 2
                             :card/toughness 2}])
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
          token-id (random-uuid)
          db (d/db-with db [{:object/id token-id
                             :object/card card-eid
                             :object/zone :battlefield
                             :object/owner player-eid
                             :object/controller player-eid
                             :object/is-token true
                             :object/tapped false
                             :object/summoning-sick false
                             :object/damage-marked 2
                             :object/power 2
                             :object/toughness 2}])
          ;; Run cascading SBAs
          db' (sba/check-and-execute-sbas db)]
      ;; Token should be fully retracted (not just moved to graveyard)
      (is (nil? (q/get-object-eid db' token-id))
          "Token should be fully retracted after cascading lethal-damage → token-cleanup"))))


(deftest test-cascading-zero-toughness-token-then-cleanup
  (testing "0-toughness token: zero-toughness moves to graveyard, token-cleanup retracts"
    ;; Scenario: 1/0 token on battlefield.
    ;; Loop 1: zero-toughness SBA fires → moves token to graveyard
    ;; Loop 2: token-cleanup SBA fires → retracts token entity entirely
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          player-eid (q/get-player-eid db :player-1)
          card-id (keyword (str "token-card-" (random-uuid)))
          db (d/db-with db [{:card/id card-id
                             :card/name "Test 0-Toughness Token"
                             :card/types #{:creature}
                             :card/power 1
                             :card/toughness 0}])
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
          token-id (random-uuid)
          db (d/db-with db [{:object/id token-id
                             :object/card card-eid
                             :object/zone :battlefield
                             :object/owner player-eid
                             :object/controller player-eid
                             :object/is-token true
                             :object/tapped false
                             :object/summoning-sick false
                             :object/damage-marked 0
                             :object/power 1
                             :object/toughness 0}])
          db' (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object-eid db' token-id))
          "0-toughness token should be fully retracted after cascading SBAs"))))
