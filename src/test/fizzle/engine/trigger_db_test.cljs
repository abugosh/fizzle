(ns fizzle.engine.trigger-db-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.engine.trigger-db :as trigger-db]))


;; === Test helpers ===

(defn get-player-eid
  "Get player entity ID from db."
  [db]
  (d/q '[:find ?e .
         :where [?e :player/id :player-1]]
       db))


(defn get-object-eid
  "Get first object entity ID from db."
  [db]
  (d/q '[:find ?e .
         :where [?e :object/id _]]
       db))


(defn transact-object
  "Add a test object to the db, returns [new-db object-eid]."
  [db player-eid]
  (let [card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        obj-id (random-uuid)
        conn (d/conn-from-db db)
        _ (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :battlefield
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false}])
        new-db @conn
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     new-db obj-id)]
    [new-db obj-eid]))


;; === create-trigger-tx tests ===

(deftest test-create-trigger-tx-returns-valid-tx-data
  (testing "create-trigger-tx returns a vector of Datascript tx-data maps"
    (let [tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source 42
                :trigger/controller 1
                :trigger/filter {:event/object-id :self}
                :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]})]
      (is (vector? tx))
      (is (pos? (count tx)))
      (let [m (first tx)]
        (is (= :permanent-tapped (:trigger/event-type m)))
        (is (= :becomes-tapped (:trigger/type m)))
        (is (= 42 (:trigger/source m)))
        (is (= 1 (:trigger/controller m)))
        (is (= {:event/object-id :self} (:trigger/filter m)))
        (is (= [{:effect/type :deal-damage :effect/amount 1}] (:trigger/effects m)))))))


(deftest test-create-trigger-tx-defaults-uses-stack-true
  (testing "create-trigger-tx defaults :trigger/uses-stack? to true"
    (let [tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source 42
                :trigger/controller 1})
          m (first tx)]
      (is (true? (:trigger/uses-stack? m))))))


(deftest test-create-trigger-tx-filters-nil-values
  (testing "create-trigger-tx omits nil values from tx-data"
    (let [tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source 42
                :trigger/controller 1
                :trigger/description nil
                :trigger/filter nil})
          m (first tx)]
      (is (nil? (:trigger/description m)))
      (is (nil? (:trigger/filter m))))))


;; === Transacting and querying trigger entities ===

(deftest test-transact-trigger-creates-entity
  (testing "transacting trigger tx-data creates entity with correct attributes"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {:event/object-id :self}
                :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Filter to the card trigger we just added (not turn-based triggers)
          triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= 1 (count triggers)))
      (let [t (first triggers)]
        (is (= :permanent-tapped (:trigger/event-type t)))
        (is (= obj-eid (:db/id (:trigger/source t))))
        (is (= [{:effect/type :deal-damage :effect/amount 1}] (:trigger/effects t)))
        (is (= {:event/object-id :self} (:trigger/filter t)))))))


;; === create-triggers-for-card-tx tests ===

(deftest test-create-triggers-for-card-tx-maps-trigger-type
  (testing "creates triggers with event-type mapped from trigger-type"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Filter to card triggers only (not turn-based)
          triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= 1 (count triggers)))
      (let [t (first triggers)]
        (is (= :permanent-tapped (:trigger/event-type t)))
        (is (= :becomes-tapped (:trigger/type t)))))))


(deftest test-create-triggers-for-card-tx-default-self-filter
  (testing "applies default {:event/object-id :self} filter when no filter specified"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Filter to card triggers only (not turn-based)
          triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= {:event/object-id :self} (:trigger/filter (first triggers)))))))


(deftest test-create-triggers-for-card-tx-custom-filter
  (testing "uses card's filter when specified"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :land-entered
                          :trigger/filter {:exclude-self true}
                          :trigger/effects [{:effect/type :sacrifice-self}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Filter to the card trigger we just added
          triggers (filter #(= :land-entered (:trigger/type %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= {:exclude-self true} (:trigger/filter (first triggers)))))))


(deftest test-create-triggers-for-card-tx-propagates-active-zone
  (testing "creates triggers with :trigger/active-zone when card trigger has it"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :zone-change
                          :trigger/active-zone :graveyard
                          :trigger/filter {}
                          :trigger/effects [{:effect/type :shuffle-from-graveyard-to-library}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          triggers (filter #(= :zone-change (:trigger/event-type %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= 1 (count triggers)))
      (let [t (first triggers)]
        (is (= :graveyard (:trigger/active-zone t))
            "active-zone :graveyard should be propagated from card trigger data")))))


(deftest test-create-triggers-for-card-tx-no-active-zone-absent
  (testing "creates triggers without :trigger/active-zone when card trigger lacks it"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= 1 (count triggers)))
      (is (nil? (:trigger/active-zone (first triggers)))
          "No :trigger/active-zone field should be stored when card trigger omits it"))))


(deftest test-create-triggers-for-card-tx-links-object
  (testing "trigger entities are linked to source object via :object/triggers"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          obj-triggers (:object/triggers (d/entity new-db obj-eid))]
      (is (= 1 (count obj-triggers))))))


;; === create-game-rule-trigger-tx tests ===

(deftest test-create-game-rule-trigger-tx-always-active
  (testing "game-rule trigger has :trigger/always-active? true"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          tx (trigger-db/create-game-rule-trigger-tx
               {:trigger/type :draw-step
                :trigger/event-type :phase-entered
                :trigger/filter {:event/phase :draw}
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Filter to the game-rule trigger we just added (draw-step with :draw phase filter)
          triggers (filter #(= {:event/phase :draw} (:trigger/filter %))
                           (trigger-db/get-all-triggers new-db))]
      (is (= 1 (count triggers)))
      (let [t (first triggers)]
        (is (true? (:trigger/always-active? t)))
        (is (false? (:trigger/uses-stack? t)))))))


(deftest test-create-game-rule-trigger-tx-no-source
  (testing "game-rule trigger has no :trigger/source"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          tx (trigger-db/create-game-rule-trigger-tx
               {:trigger/type :draw-step
                :trigger/event-type :phase-entered
                :trigger/filter {:event/phase :draw}
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          triggers (trigger-db/get-all-triggers new-db)]
      (is (nil? (:trigger/source (first triggers)))))))


;; === get-triggers-for-event tests ===

(deftest test-get-triggers-for-event-matches-event-type
  (testing "returns matching triggers by event-type"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx1 (trigger-db/create-trigger-tx
                {:trigger/type :becomes-tapped
                 :trigger/event-type :permanent-tapped
                 :trigger/source obj-eid
                 :trigger/controller player-eid})
          tx2 (trigger-db/create-trigger-tx
                {:trigger/type :draw-step
                 :trigger/event-type :phase-entered
                 :trigger/source obj-eid
                 :trigger/controller player-eid})
          tx3 (trigger-db/create-trigger-tx
                {:trigger/type :untap-step
                 :trigger/event-type :phase-entered
                 :trigger/source obj-eid
                 :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn (vec (concat tx1 tx2 tx3)))
          new-db @conn
          result (trigger-db/get-triggers-for-event new-db {:event/type :phase-entered})]
      (is (= 2 (count result)))
      (is (every? #(= :phase-entered (:trigger/event-type %)) result)))))


(deftest test-get-triggers-for-event-no-matches-returns-empty
  (testing "returns empty seq when no triggers match"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source obj-eid
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          result (trigger-db/get-triggers-for-event new-db {:event/type :phase-entered})]
      (is (empty? result)))))


(deftest test-get-triggers-for-event-empty-db-returns-empty
  (testing "returns empty seq when no triggers exist"
    (let [db (init-game-state)
          result (trigger-db/get-triggers-for-event db {:event/type :phase-entered})]
      (is (empty? result)))))


;; === Filter matching via get-triggers-for-event ===

(deftest test-filter-matching-no-filter-matches-all
  (testing "trigger with no filter matches any event of type"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source obj-eid
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :permanent-tapped :event/object-id 999})]
      (is (= 1 (count result))))))


(deftest test-filter-matching-self-resolves-to-source
  (testing ":self in filter resolves to trigger source"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :becomes-tapped
                :trigger/event-type :permanent-tapped
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {:event/object-id :self}})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn]
      ;; Matching: event object-id = trigger source
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :permanent-tapped
                                :event/object-id obj-eid}))))
      ;; Not matching: different object-id
      (is (= 0 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :permanent-tapped
                                :event/object-id 999})))))))


(deftest test-filter-matching-exclude-self
  (testing ":exclude-self prevents self-triggering"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :land-entered
                :trigger/event-type :land-entered
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {:exclude-self true}})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn]
      ;; Self: does NOT match
      (is (= 0 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :land-entered
                                :event/object-id obj-eid}))))
      ;; Other: matches
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :land-entered
                                :event/object-id 999})))))))


(deftest test-filter-matching-exact-value
  (testing "exact value match in filter"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          tx (trigger-db/create-game-rule-trigger-tx
               {:trigger/type :draw-step
                :trigger/event-type :phase-entered
                :trigger/filter {:event/phase :draw}
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn]
      ;; Matches
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :phase-entered
                                :event/phase :draw}))))
      ;; Does not match
      (is (= 0 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :phase-entered
                                :event/phase :upkeep})))))))


(deftest test-filter-matching-multiple-conditions
  (testing "all filter conditions must match"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          tx (trigger-db/create-game-rule-trigger-tx
               {:trigger/type :draw-step
                :trigger/event-type :phase-entered
                :trigger/filter {:event/phase :draw :event/turn 2}
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn]
      ;; Both match
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :phase-entered
                                :event/phase :draw
                                :event/turn 2}))))
      ;; Partial match (wrong turn)
      (is (= 0 (count (trigger-db/get-triggers-for-event
                        new-db {:event/type :phase-entered
                                :event/phase :draw
                                :event/turn 1})))))))


;; === Always-active triggers ===

(deftest test-always-active-trigger-returned
  (testing "always-active triggers are returned regardless of source"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          tx (trigger-db/create-game-rule-trigger-tx
               {:trigger/type :draw-step
                :trigger/event-type :phase-entered
                :trigger/filter {:event/phase :draw}
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :phase-entered
                           :event/phase :draw})]
      (is (= 1 (count result)))
      (is (true? (:trigger/always-active? (first result)))))))


;; === Component cascade ===

(deftest test-component-cascade-retracts-triggers
  (testing "retracting source object auto-retracts linked triggers"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :deal-damage :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          db-with-triggers @conn
          ;; Count only card triggers (linked to an object source — not turn-based)
          card-trigger-count (count (filter :trigger/source (trigger-db/get-all-triggers db-with-triggers)))]
      ;; Verify card trigger exists (turn-based triggers are also present but not counted here)
      (is (= 1 card-trigger-count))
      ;; Retract the source object
      (d/transact! conn [[:db.fn/retractEntity obj-eid]])
      (let [db-after-retract @conn
            card-trigger-count-after (count (filter :trigger/source (trigger-db/get-all-triggers db-after-retract)))]
        ;; Card trigger should be auto-retracted via component cascade
        (is (= 0 card-trigger-count-after))))))


;; === Observer zone check via get-triggers-for-event ===

(defn transact-object-in-zone
  "Add a test object to the db in the specified zone, returns [new-db object-eid]."
  [db player-eid zone]
  (let [card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        obj-id (random-uuid)
        conn (d/conn-from-db db)
        _ (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone zone
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false}])
        new-db @conn
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     new-db obj-id)]
    [new-db obj-eid]))


(deftest test-observer-trigger-source-in-hand-does-not-fire
  (testing "observer trigger with source in :hand does NOT fire for :land-entered event"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          ;; Source object is in :hand (not battlefield)
          [db obj-eid] (transact-object-in-zone db player-eid :hand)
          ;; Observer trigger: no :event/object-id :self in filter or match
          ;; This is the observer pattern (like CoT watching for any land-entered)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :land-entered
                :trigger/event-type :land-entered
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {:exclude-self true}})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Dispatch :land-entered with a different object — observer should NOT fire
          ;; because source is in :hand, not :battlefield
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :land-entered
                           :event/object-id 9999})]
      (is (= 0 (count result))
          "Observer trigger with source in :hand should not fire"))))


(deftest test-self-scoped-trigger-source-in-library-fires
  (testing "self-scoped trigger fires when source is in :library (zone check skipped)"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          ;; Source object is in :library (like GB waiting to be milled)
          [db obj-eid] (transact-object-in-zone db player-eid :library)
          obj-uuid (d/q '[:find ?oid .
                          :in $ ?e
                          :where [?e :object/id ?oid]]
                        db obj-eid)
          ;; Self-scoped: trigger/match has :event/object-id :self
          tx (trigger-db/create-trigger-tx
               {:trigger/type :zone-change
                :trigger/event-type :zone-change
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {}
                :trigger/match {:event/from-zone :library
                                :event/to-zone :graveyard
                                :event/object-id :self}})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Dispatch :zone-change with event/object-id = this object's UUID
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :zone-change
                           :event/from-zone :library
                           :event/to-zone :graveyard
                           :event/object-id obj-uuid})]
      (is (= 1 (count result))
          "Self-scoped trigger should fire despite source being in :library"))))


(deftest test-observer-trigger-source-on-battlefield-fires
  (testing "observer trigger with source on :battlefield fires (CoT regression guard)"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          ;; Source object is on :battlefield (normal case)
          [db obj-eid] (transact-object-in-zone db player-eid :battlefield)
          ;; Observer trigger: no :event/object-id :self
          tx (trigger-db/create-trigger-tx
               {:trigger/type :land-entered
                :trigger/event-type :land-entered
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {:exclude-self true}})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Dispatch :land-entered with different object — observer SHOULD fire
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :land-entered
                           :event/object-id 9999})]
      (is (= 1 (count result))
          "Observer trigger with source on :battlefield should fire"))))


(deftest test-game-rule-trigger-always-fires-despite-no-source
  (testing "game-rule trigger (:trigger/always-active? true, no source) fires after zone check added"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          ;; Use a unique test filter that no existing game-rule trigger has
          tx (trigger-db/create-game-rule-trigger-tx
               {:trigger/type :draw-step
                :trigger/event-type :phase-entered
                :trigger/filter {:event/phase :test-zone-check-phase}
                :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :phase-entered
                           :event/phase :test-zone-check-phase})]
      (is (= 1 (count result))
          "Game-rule trigger should fire (no source = zone check skipped)")
      (is (true? (:trigger/always-active? (first result)))
          "Should be marked always-active"))))


(deftest test-observer-trigger-ceased-to-exist-does-not-fire-no-crash
  (testing "observer trigger whose source was retracted: trigger is cascade-retracted, 0 results, no crash"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object-in-zone db player-eid :battlefield)
          ;; Component-linked trigger (production path via create-triggers-for-card-tx)
          card-triggers [{:trigger/type :land-entered
                          :trigger/filter {:exclude-self true}
                          :trigger/effects [{:effect/type :sacrifice
                                             :effect/target :self}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          ;; Retract the source object — cascade-retracts the component trigger
          _ (d/transact! conn [[:db.fn/retractEntity obj-eid]])
          db-after-retract @conn
          ;; get-triggers-for-event should return 0 (trigger is gone) and not crash
          result (trigger-db/get-triggers-for-event
                   db-after-retract {:event/type :land-entered
                                     :event/object-id 9999})]
      (is (= 0 (count result))
          "Trigger with cascade-retracted source does not fire and does not crash"))))


(deftest test-observer-trigger-default-active-zone-is-battlefield
  (testing "observer trigger with no :trigger/active-zone defaults to :battlefield"
    (let [db (init-game-state)
          player-eid (get-player-eid db)
          ;; Source on battlefield, no :trigger/active-zone set
          [db obj-eid] (transact-object-in-zone db player-eid :battlefield)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :land-entered
                :trigger/event-type :land-entered
                :trigger/source obj-eid
                :trigger/controller player-eid
                :trigger/filter {:exclude-self true}})
          conn (d/conn-from-db db)
          _ (d/transact! conn tx)
          new-db @conn
          ;; Should fire because source is on battlefield (default active-zone)
          result (trigger-db/get-triggers-for-event
                   new-db {:event/type :land-entered
                           :event/object-id 9999})]
      (is (= 1 (count result))
          "Trigger with no active-zone field and source on :battlefield should fire")
      (is (nil? (:trigger/active-zone (first result)))
          "No :trigger/active-zone field should be stored for this trigger"))))


;; === get-all-triggers ===

(deftest test-get-all-triggers-returns-all
  (testing "get-all-triggers returns all trigger entities"
    (let [db (init-game-state)
          before-count (count (trigger-db/get-all-triggers db))
          player-eid (get-player-eid db)
          [db obj-eid] (transact-object db player-eid)
          tx1 (trigger-db/create-trigger-tx
                {:trigger/type :becomes-tapped
                 :trigger/event-type :permanent-tapped
                 :trigger/source obj-eid
                 :trigger/controller player-eid})
          tx2 (trigger-db/create-game-rule-trigger-tx
                {:trigger/type :draw-step
                 :trigger/event-type :phase-entered
                 :trigger/filter {:event/phase :draw}
                 :trigger/controller player-eid})
          conn (d/conn-from-db db)
          _ (d/transact! conn (vec (concat tx1 tx2)))
          new-db @conn]
      ;; get-all-triggers returns all triggers including the 2 we added
      (is (= (+ before-count 2) (count (trigger-db/get-all-triggers new-db)))))))
