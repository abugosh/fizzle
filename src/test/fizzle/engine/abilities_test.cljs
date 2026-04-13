(ns fizzle.engine.abilities-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]))


;; === Test helpers ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id]
   (add-permanent db player-id nil))
  ([db player-id initial-counters]
   (add-permanent db player-id initial-counters false))
  ([db player-id initial-counters tapped?]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         card-eid (d/q '[:find ?e .
                         :where [?e :card/id :dark-ritual]]
                       @conn)
         object-id (random-uuid)
         base-entity {:object/id object-id
                      :object/card card-eid
                      :object/zone :battlefield
                      :object/owner player-eid
                      :object/controller player-eid
                      :object/tapped tapped?}
         entity (if initial-counters
                  (assoc base-entity :object/counters initial-counters)
                  base-entity)]
     (d/transact! conn [entity])
     [@conn object-id])))


;; === can-activate? tests ===

(deftest test-can-activate-tap-ability
  (testing "can-activate? returns true for untapped permanent with :tap cost"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ability {:ability/cost {:tap true}}]
      (is (true? (abilities/can-activate? db object-id ability))))))


(deftest test-can-activate-already-tapped
  (testing "can-activate? returns false for tapped permanent with :tap cost"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 nil true)
          ability {:ability/cost {:tap true}}]
      (is (false? (abilities/can-activate? db object-id ability))))))


(deftest test-can-activate-counter-ability
  (testing "can-activate? returns true when permanent has sufficient counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          ability {:ability/cost {:remove-counter {:mining 1}}}]
      (is (true? (abilities/can-activate? db object-id ability))))))


(deftest test-can-activate-counter-ability-insufficient
  (testing "can-activate? returns false when insufficient counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 0})
          ability {:ability/cost {:remove-counter {:mining 1}}}]
      (is (false? (abilities/can-activate? db object-id ability))))))


(deftest test-can-activate-nil-cost
  (testing "can-activate? returns true for ability with nil cost (free activation)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ability-nil {:ability/cost nil}
          ability-empty {:ability/cost {}}
          ability-missing {}]
      (is (true? (abilities/can-activate? db object-id ability-nil)))
      (is (true? (abilities/can-activate? db object-id ability-empty)))
      (is (true? (abilities/can-activate? db object-id ability-missing))))))


(deftest test-nil-cost-ability-actually-executes
  (testing "nil-cost ability can-activate? true implies pay-all-costs also succeeds (no NPE)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ;; pay-all-costs with nil/empty cost must return db unchanged (no cost deducted)
          result-nil  (abilities/pay-all-costs db object-id nil)
          result-empty (abilities/pay-all-costs db object-id {})]
      ;; Bug caught: can-activate? returning true but pay-all-costs throwing NPE on nil cost
      ;; would mean activation appears valid but explodes at payment time
      (is (= db result-nil)
          "pay-all-costs with nil cost should return db unchanged — no cost to pay")
      (is (= db result-empty)
          "pay-all-costs with empty cost should return db unchanged — no cost to pay"))))


(deftest test-can-activate-invalid-object-id
  (testing "can-activate? returns false for non-existent permanent"
    (let [db (init-game-state)
          ability {:ability/cost {:tap true}}]
      (is (false? (abilities/can-activate? db (random-uuid) ability))))))


;; === pay-all-costs tests ===

(deftest test-pay-all-costs-single
  (testing "pay-all-costs with single :tap cost taps permanent"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          costs {:tap true}
          db' (abilities/pay-all-costs db object-id costs)]
      ;; Permanent should be tapped
      (is (= true (:object/tapped (q/get-object db' object-id)))))))


(deftest test-pay-all-costs-multiple
  (testing "pay-all-costs with :tap and :remove-counter pays both"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          costs {:tap true :remove-counter {:mining 1}}
          db' (abilities/pay-all-costs db object-id costs)]
      ;; Permanent should be tapped
      (is (= true (:object/tapped (q/get-object db' object-id))))
      ;; Counters should be decremented
      (is (= {:mining 2} (:object/counters (q/get-object db' object-id)))))))


(deftest test-pay-all-costs-fails-partial
  (testing "pay-all-costs returns nil if any cost fails, no partial payment"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3} true) ; already tapped
          costs {:tap true :remove-counter {:mining 1}}
          result (abilities/pay-all-costs db object-id costs)]
      ;; Should return nil (tap cost fails)
      (is (nil? result)))))


(deftest test-pay-all-costs-empty
  (testing "pay-all-costs with empty/nil costs returns db unchanged"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          db-nil (abilities/pay-all-costs db object-id nil)
          db-empty (abilities/pay-all-costs db object-id {})]
      ;; Permanent should still be untapped
      (is (= false (:object/tapped (q/get-object db-nil object-id))))
      (is (= false (:object/tapped (q/get-object db-empty object-id)))))))


;; === activate-ability tests ===

(deftest test-activate-tap-ability
  (testing "activate-ability pays :tap cost and applies mana effect"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ability {:ability/cost {:tap true}
                   :ability/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}
          db' (abilities/activate-ability db object-id ability :player-1)]
      ;; Permanent should be tapped
      (is (= true (:object/tapped (q/get-object db' object-id))))
      ;; Player should have 1 black mana
      (is (= 1 (get (q/get-mana-pool db' :player-1) :black 0))))))


(deftest test-activate-tap-already-tapped
  (testing "activate-ability returns nil when cost cannot be paid"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 nil true)
          ability {:ability/cost {:tap true}
                   :ability/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}
          original-mana (q/get-mana-pool db :player-1)
          result (abilities/activate-ability db object-id ability :player-1)]
      ;; Should return nil
      (is (nil? result))
      ;; Mana pool unchanged (checked via original db)
      (is (= original-mana (q/get-mana-pool db :player-1))))))


(deftest test-activate-counter-ability
  (testing "activate-ability removes counter and applies mana effect"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          ability {:ability/cost {:remove-counter {:mining 1}}
                   :ability/effects [{:effect/type :add-mana :effect/mana {:green 1}}]}
          db' (abilities/activate-ability db object-id ability :player-1)]
      ;; Counters should be decremented
      (is (= {:mining 2} (:object/counters (q/get-object db' object-id))))
      ;; Player should have 1 green mana
      (is (= 1 (get (q/get-mana-pool db' :player-1) :green 0))))))


(deftest test-activate-ability-empty-effects
  (testing "activate-ability with no effects still pays costs"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ability {:ability/cost {:tap true}
                   :ability/effects []}
          db' (abilities/activate-ability db object-id ability :player-1)]
      ;; Permanent should be tapped
      (is (= true (:object/tapped (q/get-object db' object-id)))))))


;; =====================================================
;; Corner Case Tests: threshold condition
;; =====================================================

(defn add-cards-to-graveyard
  "Add n cards to player's graveyard. Returns db."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [_ (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :graveyard
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}]))
    @conn))


(deftest test-can-activate-with-threshold-condition-met
  (testing "can-activate? returns true when threshold condition is met (7+ cards in graveyard)"
    (let [db (add-cards-to-graveyard (init-game-state) :player-1 7)
          [db object-id] (add-permanent db :player-1)
          ability {:ability/cost {:tap true}
                   :ability/condition {:condition/type :threshold}
                   :ability/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}]
      (is (true? (abilities/can-activate? db object-id ability :player-1))
          "Should be activatable with threshold met"))))


(deftest test-can-activate-with-threshold-condition-not-met
  (testing "can-activate? returns false when threshold condition is not met (< 7 cards)"
    (let [db (add-cards-to-graveyard (init-game-state) :player-1 5)
          [db object-id] (add-permanent db :player-1)
          ability {:ability/cost {:tap true}
                   :ability/condition {:condition/type :threshold}
                   :ability/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}]
      (is (false? (abilities/can-activate? db object-id ability :player-1))
          "Should NOT be activatable without threshold"))))


(deftest test-can-activate-2-arity-resolves-controller
  (testing "can-activate? 2-arity resolves controller from object and checks condition"
    (let [db (add-cards-to-graveyard (init-game-state) :player-1 7)
          [db object-id] (add-permanent db :player-1)
          ability {:ability/cost {:tap true}
                   :ability/condition {:condition/type :threshold}
                   :ability/effects []}]
      ;; 2-arity: (db, object-id, ability) - should resolve controller internally
      (is (true? (abilities/can-activate? db object-id ability))
          "2-arity should resolve controller and check threshold"))))
