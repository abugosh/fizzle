(ns fizzle.engine.zones-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.zones :as zones]))


;; === move-to-zone tests ===

(deftest move-to-zone-hand-to-stack-test
  (testing "move-to-zone moves object from hand to stack"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :stack)]
      (is (= :stack (:object/zone (q/get-object db' object-id))))
      (is (empty? (q/get-hand db' :player-1)))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))))))


(deftest move-to-zone-stack-to-graveyard-test
  (testing "move-to-zone moves object from stack to graveyard"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (-> db
                  (zones/move-to-zone object-id :stack)
                  (zones/move-to-zone object-id :graveyard))]
      (is (= :graveyard (:object/zone (q/get-object db' object-id))))
      (is (empty? (q/get-objects-in-zone db' :player-1 :stack)))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :graveyard)))))))


(deftest move-to-zone-preserves-other-attributes-test
  (testing "move-to-zone only changes zone, not owner/controller/card"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          original-obj (q/get-object db object-id)
          db' (zones/move-to-zone db object-id :stack)
          moved-obj (q/get-object db' object-id)]
      (is (= (:object/owner original-obj) (:object/owner moved-obj)))
      (is (= (:object/controller original-obj) (:object/controller moved-obj)))
      (is (= (:object/card original-obj) (:object/card moved-obj))))))


(deftest move-to-zone-same-zone-no-op-test
  (testing "move-to-zone to same zone is a no-op"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :hand)]
      (is (= db db') "Same-zone move should be exact no-op")
      (is (= :hand (:object/zone (q/get-object db' object-id))))
      (is (= 1 (count (q/get-hand db' :player-1)))))))


(deftest move-to-zone-battlefield-test
  (testing "move-to-zone can move to battlefield"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :battlefield)]
      (is (= :battlefield (:object/zone (q/get-object db' object-id))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :battlefield)))))))


(deftest move-to-zone-exile-test
  (testing "move-to-zone can move to exile"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/move-to-zone db object-id :exile)]
      (is (= :exile (:object/zone (q/get-object db' object-id))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :exile)))))))


(deftest move-to-zone-entering-battlefield-resets-tapped-test
  (testing "permanents enter the battlefield untapped regardless of prior tapped state"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          ;; Move to battlefield, then tap it
          db' (zones/move-to-zone db object-id :battlefield)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db' object-id)
          db-tapped (d/db-with db' [[:db/add obj-eid :object/tapped true]])
          _ (is (true? (:object/tapped (q/get-object db-tapped object-id)))
                "Precondition: object is tapped on battlefield")
          ;; Move to graveyard (should reset tapped)
          db-gy (zones/move-to-zone db-tapped object-id :graveyard)
          _ (is (false? (:object/tapped (q/get-object db-gy object-id)))
                "Precondition: tapped reset when leaving battlefield")
          ;; Manually set tapped true in graveyard (simulating stale state)
          obj-eid2 (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                        db-gy object-id)
          db-gy-tapped (d/db-with db-gy [[:db/add obj-eid2 :object/tapped true]])
          _ (is (true? (:object/tapped (q/get-object db-gy-tapped object-id)))
                "Precondition: object has tapped=true in graveyard")
          ;; Move back to battlefield — should enter untapped
          db-back (zones/move-to-zone db-gy-tapped object-id :battlefield)]
      (is (= :battlefield (:object/zone (q/get-object db-back object-id))))
      (is (false? (:object/tapped (q/get-object db-back object-id)))
          "Permanent should enter battlefield untapped (MTG rule 110.6)"))))


;; === remove-object tests ===

(deftest remove-object-removes-from-db-test
  (testing "remove-object retracts entity so it no longer exists in db"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db' (zones/remove-object db object-id)]
      (is (nil? (q/get-object db' object-id))
          "Object should no longer exist after removal")
      (is (empty? (q/get-hand db' :player-1))
          "Hand should be empty after removing its only card"))))


(deftest remove-object-nonexistent-returns-unchanged-test
  (testing "remove-object with nonexistent ID returns db unchanged"
    (let [db (init-game-state)
          db' (zones/remove-object db (random-uuid))]
      (is (= db db') "DB should be identical when removing nonexistent object"))))


(deftest remove-object-nil-id-returns-unchanged-test
  (testing "remove-object with nil ID returns db unchanged"
    (let [db (init-game-state)
          db' (zones/remove-object db nil)]
      (is (= db db') "DB should be identical when removing nil object-id"))))


(deftest remove-object-double-remove-is-noop-test
  (testing "removing same object twice is idempotent"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))
          db-after-first (zones/remove-object db object-id)
          db-after-second (zones/remove-object db-after-first object-id)]
      (is (= db-after-first db-after-second)
          "Second removal should be a no-op"))))


(deftest remove-object-other-objects-unaffected-test
  (testing "removing one object does not affect other objects"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          first-obj-id (:object/id (first hand))
          ;; Add a second object to hand
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :dark-ritual)
          second-obj-id (random-uuid)
          _ (d/transact! conn [{:object/id second-obj-id
                                :object/card card-eid
                                :object/zone :hand
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
          db-with-two @conn
          ;; Remove only the first object
          db' (zones/remove-object db-with-two first-obj-id)]
      (is (nil? (q/get-object db' first-obj-id))
          "First object should be removed")
      (is (= :hand (:object/zone (q/get-object db' second-obj-id)))
          "Second object should still be in hand"))))


;; =====================================================
;; Corner Case Tests: shuffle-library
;; =====================================================

(defn add-cards-to-library
  "Add n cards to a player's library with sequential positions.
   Returns db with cards added."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [i (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :library
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false
                          :object/position i}]))
    @conn))


(defn get-library-positions
  "Get sorted vector of positions for all cards in player's library."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (->> (d/q '[:find ?pos
                :in $ ?owner
                :where [?e :object/owner ?owner]
                [?e :object/zone :library]
                [?e :object/position ?pos]]
              db player-eid)
         (map first)
         sort
         vec)))


(deftest test-shuffle-library-preserves-card-count
  (testing "shuffle-library preserves number of cards in library"
    (let [db (add-cards-to-library (init-game-state) :player-1 10)
          lib-before (q/get-objects-in-zone db :player-1 :library)
          db-after (zones/shuffle-library db :player-1)
          lib-after (q/get-objects-in-zone db-after :player-1 :library)]
      (is (= (count lib-before) (count lib-after))
          "Card count should be identical after shuffle")
      (is (= 10 (count lib-after))
          "Should have exactly 10 cards"))))


(deftest test-shuffle-library-all-positions-assigned
  (testing "shuffle-library assigns contiguous positions 0..n-1"
    (let [db (add-cards-to-library (init-game-state) :player-1 5)
          db-after (zones/shuffle-library db :player-1)
          positions (get-library-positions db-after :player-1)]
      (is (= [0 1 2 3 4] positions)
          "Positions should be contiguous 0..4 after shuffle"))))


(deftest test-shuffle-library-empty-noop
  (testing "shuffle-library on empty library returns db unchanged"
    (let [db (init-game-state)
          db' (zones/shuffle-library db :player-1)]
      (is (= db db')
          "Empty library shuffle should be no-op"))))


(deftest test-shuffle-library-single-card
  (testing "shuffle-library on single card library keeps position 0"
    (let [db (add-cards-to-library (init-game-state) :player-1 1)
          db-after (zones/shuffle-library db :player-1)
          positions (get-library-positions db-after :player-1)]
      (is (= [0] positions)
          "Single card should stay at position 0"))))


;; =====================================================
;; Corner Case Tests: trigger unregistration
;; =====================================================

(deftest test-move-from-battlefield-unregisters-triggers
  (testing "move-to-zone from battlefield unregisters triggers for that source"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          ;; Move to battlefield first
          db-bf (zones/move-to-zone db obj-id :battlefield)
          ;; Register a trigger for this object
          trigger {:trigger/id :test-trigger-1
                   :trigger/type :test-type
                   :trigger/event-type :permanent-tapped
                   :trigger/source obj-id
                   :trigger/controller :player-1}
          _ (registry/register-trigger! trigger)
          ;; Verify trigger is registered
          _ (is (= 1 (count (registry/get-triggers-for-event {:event/type :permanent-tapped})))
                "Precondition: trigger should be registered")
          ;; Move off battlefield to graveyard
          db-gy (zones/move-to-zone db-bf obj-id :graveyard)]
      ;; Trigger should be unregistered
      (is (= 0 (count (registry/get-triggers-for-event {:event/type :permanent-tapped})))
          "Trigger should be unregistered when source leaves battlefield")
      ;; Object should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db-gy obj-id)))
          "Object should be in graveyard")
      ;; Clean up
      (registry/clear-registry!))))


(deftest test-move-non-battlefield-doesnt-unregister
  (testing "move-to-zone from non-battlefield zone does NOT unregister triggers"
    (registry/clear-registry!)
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          ;; Register a trigger for a different source (battlefield object)
          other-id (random-uuid)
          trigger {:trigger/id :test-trigger-2
                   :trigger/type :test-type
                   :trigger/event-type :permanent-tapped
                   :trigger/source other-id
                   :trigger/controller :player-1}
          _ (registry/register-trigger! trigger)
          ;; Move hand card to graveyard (not from battlefield)
          _db-gy (zones/move-to-zone db obj-id :graveyard)]
      ;; Trigger for other source should still be registered
      (is (= 1 (count (registry/get-triggers-for-event {:event/type :permanent-tapped})))
          "Trigger for other source should survive non-battlefield zone change")
      (registry/clear-registry!))))
