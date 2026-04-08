(ns fizzle.engine.triggers-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.triggers :as triggers]))


;; =====================================================
;; Corner Case Tests: draw-step trigger
;; =====================================================

(defn set-turn
  "Set the game turn number. Returns updated db."
  [db turn-number]
  (let [game-eid (d/q '[:find ?e .
                        :where [?e :game/id _]]
                      db)]
    (d/db-with db [[:db/add game-eid :game/turn turn-number]])))


(defn add-cards-to-library
  "Add n cards to library with positions. Returns db."
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


(deftest test-resolve-trigger-draw-step-turn-1-skips
  (testing "Draw step on turn 1 does not draw a card (MTG play/draw rule)"
    (let [db (-> (init-game-state)
                 (add-cards-to-library :player-1 5))
          hand-before (count (q/get-hand db :player-1))
          trigger {:trigger/type :draw-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)
          hand-after (count (q/get-hand db' :player-1))]
      (is (= hand-before hand-after)
          "Should not draw on turn 1"))))


(deftest test-resolve-trigger-draw-step-turn-2-draws
  (testing "Draw step on turn 2 draws one card"
    (let [db (-> (init-game-state)
                 (set-turn 2)
                 (add-cards-to-library :player-1 5))
          hand-before (count (q/get-hand db :player-1))
          trigger {:trigger/type :draw-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)
          hand-after (count (q/get-hand db' :player-1))]
      (is (= (inc hand-before) hand-after)
          "Should draw exactly 1 card on turn 2"))))


;; =====================================================
;; Corner Case Tests: untap-step trigger
;; =====================================================

(defn add-tapped-permanent
  "Add a tapped permanent to the battlefield. Returns [db object-id]."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped true}])
    [@conn obj-id]))


(deftest test-resolve-trigger-untap-step
  (testing "Untap step untaps all tapped permanents"
    (let [db (init-game-state)
          [db obj-1] (add-tapped-permanent db :player-1)
          [db obj-2] (add-tapped-permanent db :player-1)
          ;; Verify tapped
          _ (is (true? (:object/tapped (q/get-object db obj-1))))
          _ (is (true? (:object/tapped (q/get-object db obj-2))))
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)]
      (is (false? (:object/tapped (q/get-object db' obj-1)))
          "First permanent should be untapped")
      (is (false? (:object/tapped (q/get-object db' obj-2)))
          "Second permanent should be untapped"))))


(deftest test-resolve-trigger-untap-step-no-tapped-noop
  (testing "Untap step with no tapped permanents is a no-op"
    (let [db (init-game-state)
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)]
      (is (= db db')
          "DB should be unchanged when no permanents to untap"))))


;; =====================================================
;; Corner Case Tests: create-spell-copy
;; =====================================================

(deftest test-create-spell-copy-returns-valid-object
  (testing "create-spell-copy creates a copy with is-copy flag on stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-with-copy (triggers/create-spell-copy db-cast obj-id :player-1)
          copy-objs (d/q '[:find [(pull ?e [* {:object/card [*]}]) ...]
                           :where [?e :object/is-copy true]]
                         db-with-copy)
          copy (first copy-objs)]
      (is (= 1 (count copy-objs))
          "Should create exactly one copy")
      (is (true? (:object/is-copy copy))
          "Copy should have :object/is-copy true")
      (is (= :stack (:object/zone copy))
          "Copy should be on stack")
      (is (= :dark-ritual (get-in copy [:object/card :card/id]))
          "Copy should reference same card as original")
      ;; Copy should have a stack-item
      (let [copy-eid (:db/id copy)
            si (stack/get-stack-item-by-object-ref db-with-copy copy-eid)]
        (is (= :storm-copy (:stack-item/type si))
            "Stack-item type should be :storm-copy")
        (is (true? (:stack-item/is-copy si))
            "Stack-item should be marked as copy")))))


(deftest test-create-spell-copy-inherits-stack-item-targets
  (testing "create-spell-copy propagates :stack-item/targets from original to copy"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; Store targets on the original spell's stack-item
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast obj-id)
          original-si (stack/get-stack-item-by-object-ref db-cast obj-eid)
          db-with-targets (d/db-with db-cast
                                     [[:db/add (:db/id original-si)
                                       :stack-item/targets {:player :player-2}]])
          ;; Create the copy
          db-with-copy (triggers/create-spell-copy db-with-targets obj-id :player-1)
          ;; Find the copy's stack-item
          copy-objs (d/q '[:find [(pull ?e [:db/id :object/id]) ...]
                           :where [?e :object/is-copy true]]
                         db-with-copy)
          copy (first copy-objs)
          copy-si (stack/get-stack-item-by-object-ref db-with-copy (:db/id copy))]
      (is (= {:player :player-2} (:stack-item/targets copy-si))
          "Copy's stack-item should inherit targets from original"))))


(deftest test-create-spell-copy-no-targets-no-crash
  (testing "create-spell-copy works fine when original has no targets"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-with-copy (triggers/create-spell-copy db-cast obj-id :player-1)
          copy-objs (d/q '[:find [(pull ?e [:db/id :object/id]) ...]
                           :where [?e :object/is-copy true]]
                         db-with-copy)
          copy (first copy-objs)
          copy-si (stack/get-stack-item-by-object-ref db-with-copy (:db/id copy))]
      (is (nil? (:stack-item/targets copy-si))
          "Copy should have no targets when original has none"))))


(deftest test-create-spell-copy-nonexistent-source
  (testing "create-spell-copy with nonexistent source returns db unchanged"
    (let [db (init-game-state)
          fake-id (random-uuid)
          db' (triggers/create-spell-copy db fake-id :player-1)]
      (is (= db db')
          "Should return db unchanged for nonexistent source"))))


;; =====================================================
;; trigger-type->event-type mapping tests
;; =====================================================

(deftest test-trigger-type-event-type-becomes-tapped
  (testing ":becomes-tapped maps to :permanent-tapped"
    (is (= :permanent-tapped (trigger-db/trigger-type->event-type :becomes-tapped)))))


(deftest test-trigger-type-event-type-land-entered
  (testing ":land-entered maps to :land-entered"
    (is (= :land-entered (trigger-db/trigger-type->event-type :land-entered)))))


(deftest test-trigger-type-event-type-creature-attacks
  (testing ":creature-attacks maps to :creature-attacked"
    (is (= :creature-attacked (trigger-db/trigger-type->event-type :creature-attacks)))))


(deftest test-trigger-type-event-type-enters-battlefield
  (testing ":enters-battlefield maps to :permanent-entered"
    (is (= :permanent-entered (trigger-db/trigger-type->event-type :enters-battlefield)))))


(deftest test-trigger-type-event-type-default-identity
  (testing "unknown trigger type maps to itself (identity)"
    (is (= :some-unknown-type (trigger-db/trigger-type->event-type :some-unknown-type)))))


;; =====================================================
;; ETB trigger tests (enters-battlefield / permanent-entered)
;; =====================================================

(defn- add-test-object-db-with
  "Add a test object to db using d/db-with. Returns [db obj-eid]."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        obj-id (random-uuid)
        db' (d/db-with db [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :battlefield
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db' obj-id)]
    [db' obj-eid]))


(deftest test-etb-trigger-created-and-queryable
  (testing "ETB trigger entity is created and returned by get-triggers-for-event"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :enters-battlefield
                          :trigger/effects [{:effect/type :draw :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)
          etb-triggers (trigger-db/get-triggers-for-event
                         db' {:event/type :permanent-entered
                              :event/object-id obj-eid})]
      (is (= 1 (count etb-triggers)))
      (let [t (first etb-triggers)]
        (is (= :permanent-entered (:trigger/event-type t)))
        (is (= :enters-battlefield (:trigger/type t)))
        (is (= [{:effect/type :draw :effect/amount 1}] (:trigger/effects t)))))))


(deftest test-etb-trigger-does-not-fire-for-other-objects
  (testing "ETB trigger with :self filter does not fire for different object"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :enters-battlefield
                          :trigger/effects [{:effect/type :draw :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)
          other-obj-eid 9999]
      ;; Trigger with default :self filter should not match other objects
      (is (= 0 (count (trigger-db/get-triggers-for-event
                        db' {:event/type :permanent-entered
                             :event/object-id other-obj-eid})))))))


(deftest test-etb-trigger-effects-stored-correctly
  (testing "ETB trigger stores effects data from card definition"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          effects [{:effect/type :draw :effect/amount 2}
                   {:effect/type :add-mana :effect/mana {:blue 1}}]
          card-triggers [{:trigger/type :enters-battlefield
                          :trigger/effects effects}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)
          triggers (trigger-db/get-triggers-for-event
                     db' {:event/type :permanent-entered
                          :event/object-id obj-eid})]
      (is (= effects (:trigger/effects (first triggers)))))))


;; =====================================================
;; Mana-ability / becomes-tapped trigger tests
;; =====================================================

(deftest test-becomes-tapped-trigger-event-matching
  (testing "becomes-tapped trigger matches :permanent-tapped event for source object"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)]
      ;; Matching: self tap event
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        db' {:event/type :permanent-tapped
                             :event/object-id obj-eid}))))
      ;; Not matching: different object tap
      (is (= 0 (count (trigger-db/get-triggers-for-event
                        db' {:event/type :permanent-tapped
                             :event/object-id 9999})))))))


(deftest test-becomes-tapped-trigger-type-and-event-type
  (testing "becomes-tapped trigger stores both :trigger/type and :trigger/event-type"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)
          triggers (trigger-db/get-triggers-for-event
                     db' {:event/type :permanent-tapped
                          :event/object-id obj-eid})]
      (is (= :becomes-tapped (:trigger/type (first triggers))))
      (is (= :permanent-tapped (:trigger/event-type (first triggers)))))))


(deftest test-resolve-trigger-default-noop-for-etb
  (testing "resolve-trigger :default is a no-op for unregistered trigger types like :enters-battlefield"
    (let [db (init-game-state)
          trigger {:trigger/type :enters-battlefield
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)]
      (is (= db db')
          "resolve-trigger with no defmethod returns db unchanged"))))


;; =====================================================
;; Creature-attacks trigger tests
;; =====================================================

(deftest test-creature-attacks-trigger-event-matching
  (testing "creature-attacks trigger matches :creature-attacked event for source creature"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :creature-attacks
                          :trigger/effects [{:effect/type :draw :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)]
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        db' {:event/type :creature-attacked
                             :event/object-id obj-eid}))))
      (is (= :creature-attacks (:trigger/type (first (trigger-db/get-triggers-for-event
                                                       db' {:event/type :creature-attacked
                                                            :event/object-id obj-eid}))))))))


;; =====================================================
;; matches-filter? edge cases (via get-triggers-for-event)
;; =====================================================

(deftest test-matches-filter-no-filter-matches-any-event-object
  (testing "trigger with no :trigger/filter key matches any event object-id"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          tx (trigger-db/create-trigger-tx
               {:trigger/type :enters-battlefield
                :trigger/event-type :permanent-entered
                :trigger/source obj-eid
                :trigger/controller player-eid})
          db' (d/db-with db tx)]
      ;; No filter: matches any object-id
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        db' {:event/type :permanent-entered
                             :event/object-id 1234}))))
      (is (= 1 (count (trigger-db/get-triggers-for-event
                        db' {:event/type :permanent-entered
                             :event/object-id 9999})))))))


(deftest test-matches-filter-self-controller-resolved
  (testing ":self-controller in filter is resolved to the player keyword at registration time"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :creature-attacks
                          :trigger/filter {:event/controller :self-controller}
                          :trigger/effects [{:effect/type :draw :effect/amount 1}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)
          triggers (trigger-db/get-triggers-for-event
                     db' {:event/type :creature-attacked
                          :event/controller :player-1})]
      ;; :self-controller resolved to :player-1 at registration
      (is (= 1 (count triggers)))
      (is (= :player-1 (get-in (first triggers) [:trigger/filter :event/controller]))))))


(deftest test-create-triggers-for-card-tx-multiple-trigger-types
  (testing "card with multiple trigger types creates separate trigger entities"
    (let [db (init-game-state)
          player-eid (q/get-player-eid db :player-1)
          [db obj-eid] (add-test-object-db-with db :player-1)
          card-triggers [{:trigger/type :enters-battlefield
                          :trigger/effects [{:effect/type :draw :effect/amount 1}]}
                         {:trigger/type :becomes-tapped
                          :trigger/effects [{:effect/type :add-mana :effect/mana {:blue 1}}]}]
          tx (trigger-db/create-triggers-for-card-tx db obj-eid player-eid card-triggers)
          db' (d/db-with db tx)
          etb (trigger-db/get-triggers-for-event
                db' {:event/type :permanent-entered
                     :event/object-id obj-eid})
          tapped (trigger-db/get-triggers-for-event
                   db' {:event/type :permanent-tapped
                        :event/object-id obj-eid})]
      (is (= 1 (count etb)))
      (is (= 1 (count tapped))))))
