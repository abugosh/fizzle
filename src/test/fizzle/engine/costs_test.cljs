(ns fizzle.engine.costs-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.costs :as costs]))


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


;; === :tap cost tests ===

(deftest test-pay-tap-cost-untapped-permanent
  (testing "pay-cost with :tap on untapped permanent taps it and returns db"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:tap true}
          db' (costs/pay-cost db object-id cost)]
      ;; Should return new db (not nil)
      (is (some? db'))
      ;; Permanent should be tapped
      (is (= true (:object/tapped (q/get-object db' object-id)))))))


(deftest test-pay-tap-cost-already-tapped
  (testing "pay-cost with :tap on already tapped permanent returns nil"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 nil true)
          cost {:tap true}
          result (costs/pay-cost db object-id cost)]
      ;; Should return nil (cost cannot be paid)
      (is (nil? result)))))


(deftest test-can-pay-tap-untapped
  (testing "can-pay? :tap returns true for untapped permanent"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:tap true}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-can-pay-tap-already-tapped
  (testing "can-pay? :tap returns false for tapped permanent"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 nil true)
          cost {:tap true}]
      (is (false? (costs/can-pay? db object-id cost))))))


(deftest test-pay-tap-invalid-object-id
  (testing "pay-cost with invalid object-id returns nil, no crash"
    (let [db (init-game-state)
          cost {:tap true}
          result (costs/pay-cost db (random-uuid) cost)]
      (is (nil? result)))))


(deftest test-can-pay-tap-invalid-object-id
  (testing "can-pay? with invalid object-id returns false, no crash"
    (let [db (init-game-state)
          cost {:tap true}]
      (is (false? (costs/can-pay? db (random-uuid) cost))))))


;; === :remove-counter cost tests ===

(deftest test-pay-remove-counter-has-counters
  (testing "pay-cost removes counter from permanent with sufficient counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          cost {:remove-counter {:mining 1}}
          db' (costs/pay-cost db object-id cost)]
      ;; Should return new db
      (is (some? db'))
      ;; Counter should be decremented
      (is (= {:mining 2} (:object/counters (q/get-object db' object-id)))))))


(deftest test-pay-remove-counter-exact-amount
  (testing "pay-cost removes last counter (edge case: counter goes to 0)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          cost {:remove-counter {:mining 1}}
          db' (costs/pay-cost db object-id cost)]
      ;; Should return new db
      (is (some? db'))
      ;; Counter should be 0 (not removed entirely)
      (is (= {:mining 0} (:object/counters (q/get-object db' object-id)))))))


(deftest test-pay-remove-counter-insufficient
  (testing "pay-cost returns nil when insufficient counters"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          cost {:remove-counter {:mining 2}}
          result (costs/pay-cost db object-id cost)]
      ;; Should return nil
      (is (nil? result)))))


(deftest test-pay-remove-counter-no-counters-at-all
  (testing "pay-cost returns nil when permanent has no counters (nil :object/counters)"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:remove-counter {:mining 1}}
          result (costs/pay-cost db object-id cost)]
      ;; Should return nil (no counters to remove)
      (is (nil? result)))))


(deftest test-can-pay-remove-counter-has-counters
  (testing "can-pay? returns true when counters sufficient"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 3})
          cost {:remove-counter {:mining 1}}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-can-pay-remove-counter-insufficient
  (testing "can-pay? returns false when counters insufficient"
    (let [[db object-id] (add-permanent (init-game-state) :player-1 {:mining 1})
          cost {:remove-counter {:mining 2}}]
      (is (false? (costs/can-pay? db object-id cost))))))


;; === Unknown cost type tests ===

(deftest test-pay-cost-unknown-type-returns-nil
  (testing "pay-cost with unknown cost type returns nil"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:sacrifice true}
          result (costs/pay-cost db object-id cost)]
      (is (nil? result)))))


(deftest test-can-pay-unknown-type-returns-false
  (testing "can-pay? with unknown cost type returns false"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:sacrifice true}]
      (is (false? (costs/can-pay? db object-id cost))))))


;; === :pay-life cost tests ===

(deftest test-pay-life-can-pay-with-sufficient-life
  (testing "can-pay? :pay-life returns true when controller has >= required life"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:pay-life 1}]
      ;; Player starts with 20 life
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-pay-life-cannot-pay-with-insufficient-life
  (testing "can-pay? :pay-life returns false when controller has < required life"
    (let [db (init-game-state)
          ;; Set player to 0 life
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/life 0]])
          [db' object-id] (add-permanent @conn :player-1)
          cost {:pay-life 1}]
      (is (false? (costs/can-pay? db' object-id cost))))))


(deftest test-pay-life-can-pay-exact-life
  (testing "can-pay? :pay-life returns true when controller has exactly required life (going to 0 is allowed)"
    (let [db (init-game-state)
          ;; Set player to 1 life
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/life 1]])
          [db' object-id] (add-permanent @conn :player-1)
          cost {:pay-life 1}]
      (is (true? (costs/can-pay? db' object-id cost))))))


(deftest test-pay-life-deducts-correctly
  (testing "pay-cost :pay-life deducts life from controller"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:pay-life 1}
          player-eid (q/get-player-eid db :player-1)
          life-before (:player/life (d/entity db player-eid))
          db' (costs/pay-cost db object-id cost)
          life-after (:player/life (d/entity db' player-eid))]
      ;; Should return new db
      (is (some? db'))
      ;; Life should be decremented by 1
      (is (= (dec life-before) life-after)))))


;; === :sacrifice-self cost tests ===

(deftest test-can-pay-sacrifice-self-on-battlefield
  (testing "can-pay? :sacrifice-self returns true for permanent on battlefield"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:sacrifice-self true}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-can-pay-sacrifice-self-not-on-battlefield
  (testing "can-pay? :sacrifice-self returns false when not on battlefield"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          cost {:sacrifice-self true}]
      ;; Object is in hand, not battlefield
      (is (false? (costs/can-pay? db obj-id cost))))))


(deftest test-pay-sacrifice-self-moves-to-graveyard
  (testing "pay-cost :sacrifice-self moves permanent from battlefield to graveyard"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:sacrifice-self true}
          db' (costs/pay-cost db object-id cost)]
      (is (some? db'))
      (is (= :graveyard (:object/zone (q/get-object db' object-id)))
          "Sacrificed permanent should be in graveyard")
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :battlefield)))
          "Battlefield should be empty"))))


(deftest test-pay-sacrifice-self-invalid-object
  (testing "pay-cost :sacrifice-self returns nil for nonexistent object"
    (let [db (init-game-state)
          result (costs/pay-cost db (random-uuid) {:sacrifice-self true})]
      (is (nil? result)))))


;; === :discard-hand cost tests ===

(deftest test-can-pay-discard-hand-on-battlefield
  (testing "can-pay? :discard-hand returns true when object on battlefield"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:discard-hand true}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-pay-discard-hand-moves-cards-to-graveyard
  (testing "pay-cost :discard-hand moves all hand cards to graveyard"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ;; init-game-state puts 1 Dark Ritual in hand
          hand-before (q/get-hand db :player-1)
          _ (is (= 1 (count hand-before))
                "Precondition: 1 card in hand")
          cost {:discard-hand true}
          db' (costs/pay-cost db object-id cost)]
      (is (some? db'))
      (is (= 0 (count (q/get-hand db' :player-1)))
          "Hand should be empty after discard")
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :graveyard)))
          "Graveyard should have the discarded card"))))


(deftest test-pay-discard-hand-empty-hand-noop
  (testing "pay-cost :discard-hand with empty hand is a no-op (still succeeds)"
    (let [db (init-game-state)
          ;; Move the only card out of hand
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          db (d/db-with db (let [eid (d/q '[:find ?e . :in $ ?oid
                                            :where [?e :object/id ?oid]]
                                          db obj-id)]
                             [[:db/add eid :object/zone :battlefield]]))
          [db perm-id] (add-permanent db :player-1)
          cost {:discard-hand true}
          db' (costs/pay-cost db perm-id cost)]
      (is (some? db')
          "Should succeed even with empty hand"))))


;; === :mana cost tests ===

(deftest test-can-pay-mana-sufficient
  (testing "can-pay? :mana returns true when controller has sufficient mana"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          ;; Player starts with empty pool, add mana
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/mana-pool {:black 2 :white 0 :blue 0
                                                                      :red 0 :green 0 :colorless 0}]])
          db @conn
          cost {:mana {:black 1}}]
      (is (true? (costs/can-pay? db object-id cost))))))


(deftest test-can-pay-mana-insufficient
  (testing "can-pay? :mana returns false when controller lacks mana"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          cost {:mana {:black 1}}]
      ;; Player starts with 0 black mana
      (is (false? (costs/can-pay? db object-id cost))))))


(deftest test-pay-mana-deducts-from-pool
  (testing "pay-cost :mana deducts correct amount from controller's pool"
    (let [[db object-id] (add-permanent (init-game-state) :player-1)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/mana-pool {:black 3 :white 0 :blue 0
                                                                      :red 0 :green 0 :colorless 0}]])
          db @conn
          cost {:mana {:black 2}}
          db' (costs/pay-cost db object-id cost)
          pool-after (q/get-mana-pool db' :player-1)]
      (is (some? db'))
      (is (= 1 (:black pool-after))
          "Should have 1 black mana remaining after paying 2"))))
