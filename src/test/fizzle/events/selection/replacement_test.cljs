(ns fizzle.events.selection.replacement-test
  "Tests for the :replacement-choice selection pipeline.

   Covers:
   A. sel-spec validation for :replacement-choice selections
   B. build-selection-for-replacement: builds selection from :needs-selection signal
   C. execute-confirmed-selection :proceed path: cost + zone change + marker cleared
   D. execute-confirmed-selection :redirect path: redirect zone + no cost + marker cleared
   E. Infinite loop prevention: replacement entity retracted before zone change
   F. Continuation chain shape: returns {:app-db ...} (ADR-020)

   Pattern B entry: sel-spec/set-pending-selection + sel-core/confirm-selection-impl
   (or direct execute-confirmed-selection call for unit tests).

   Deletion-test standard: deleting src/test/fizzle/cards/** would NOT
   create a coverage gap here. These tests prove defmethod mechanism
   independently of any per-card oracle test."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.objects :as objects]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.replacement :as sel-replacement]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Fixture Helpers
;; =====================================================

(defn- make-synthetic-card-with-replacement
  "Register a synthetic card with :card/replacement-effects in db.
   The replacement matches self moving to battlefield, offering proceed (with lose-life cost)
   or redirect to graveyard.
   Uses :lose-life as cost (non-interactive) so the executor path can be tested directly.
   Returns [db card-eid card-id]."
  [db]
  (let [card-id (random-uuid)
        card-tx {:card/id   card-id
                 :card/name "Synthetic Replacement Artifact"
                 :card/types [:artifact]
                 :card/replacement-effects
                 [{:replacement/event :zone-change
                   :replacement/match {:match/object :self
                                       :match/to     :battlefield}
                   :replacement/choices
                   [{:choice/label  "Proceed: Lose 1 life"
                     :choice/action :proceed
                     :choice/cost   {:effect/type :lose-life :effect/amount 1}}
                    {:choice/label       "Decline: Go to graveyard"
                     :choice/action      :redirect
                     :choice/redirect-to :graveyard}]}]}
        db-with (d/db-with db [card-tx])
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db-with card-id)]
    [db-with card-eid card-id]))


(defn- add-synthetic-object-to-hand
  "Add a synthetic replacement artifact to player's hand.
   Returns [db obj-id replacement-entity-id]."
  [db owner]
  (let [[db card-eid _] (make-synthetic-card-with-replacement db)
        card-data (d/pull db
                          [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects]
                          card-eid)
        player-eid (q/get-player-eid db owner)
        obj-id (random-uuid)
        obj-tx (objects/build-object-tx db card-eid card-data :hand player-eid 0 :id obj-id)
        db (d/db-with db [obj-tx])
        obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
        ;; Pull replacement entity id (should be 1)
        replacement-eid (d/q '[:find ?r .
                               :in $ ?o
                               :where [?o :object/replacement-effects ?r]]
                             db obj-eid)]
    [db obj-id replacement-eid]))


(defn- build-mock-needs-selection
  "Build a mock :needs-selection signal as returned by move-to-zone.
   Arguments:
     replacement-entity - pull'd replacement entity map (with :db/id)
     obj-id             - UUID of the moving object
     from-zone          - source zone keyword
     to-zone            - destination zone keyword"
  [replacement-entity obj-id from-zone to-zone]
  {:input/type          :replacement
   :replacement/entity  replacement-entity
   :replacement/event   {:event/type  :zone-change
                         :event/object obj-id
                         :event/from  from-zone
                         :event/to    to-zone}
   :replacement/object-id obj-id})


(defn- make-card-with-discard-land-replacement
  "Register a synthetic card with a :proceed replacement cost requiring discard-specific.
   Simulates Mox Diamond style: proceed = discard a land (interactive), redirect = go to graveyard."
  [db]
  (let [card-id (random-uuid)
        card-tx {:card/id   card-id
                 :card/name "Synthetic Interactive Replacement Artifact"
                 :card/types [:artifact]
                 :card/replacement-effects
                 [{:replacement/event :zone-change
                   :replacement/match {:match/object :self
                                       :match/to     :battlefield}
                   :replacement/choices
                   [{:choice/label  "Discard a land"
                     :choice/action :proceed
                     :choice/cost   {:effect/type     :discard-specific
                                     :effect/criteria {:match/types #{:land}}
                                     :effect/count    1
                                     :effect/from     :hand}}
                    {:choice/label       "Go to graveyard"
                     :choice/action      :redirect
                     :choice/redirect-to :graveyard}]}]}
        db-with (d/db-with db [card-tx])
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db-with card-id)]
    [db-with card-eid card-id]))


(defn- add-interactive-replacement-object-to-hand
  "Add a synthetic interactive replacement artifact to player's hand.
   Returns [db obj-id replacement-entity-id]."
  [db owner]
  (let [[db card-eid _] (make-card-with-discard-land-replacement db)
        card-data (d/pull db
                          [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects]
                          card-eid)
        player-eid (q/get-player-eid db owner)
        obj-id (random-uuid)
        obj-tx (objects/build-object-tx db card-eid card-data :hand player-eid 0 :id obj-id)
        db (d/db-with db [obj-tx])
        obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
        replacement-eid (d/q '[:find ?r .
                               :in $ ?o
                               :where [?o :object/replacement-effects ?r]]
                             db obj-eid)]
    [db obj-id replacement-eid]))


;; =====================================================
;; A. sel-spec :replacement-choice Validation
;; =====================================================

(deftest test-replacement-choice-spec-validates-required-keys
  (testing "valid :replacement-choice selection passes spec and is stored with mechanism/domain"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [choices [{:choice/label "Proceed" :choice/action :proceed}
                     {:choice/label "Decline" :choice/action :redirect :choice/redirect-to :graveyard}]
            sel {:selection/type             :replacement-choice
                 :selection/mechanism        :binary-choice
                 :selection/domain           :replacement-choice
                 :selection/player-id        :player-1
                 :selection/object-id        (random-uuid)
                 :selection/replacement-entity-id 42
                 :selection/replacement-event {:event/type :zone-change}
                 :selection/choices          choices
                 :selection/select-count     1
                 :selection/selected         #{}
                 :selection/validation       :always
                 :selection/auto-confirm?    false}
            result (sel-spec/set-pending-selection {:game/db nil} sel)
            pending (:game/pending-selection result)]
        ;; ADR-030: builders set mechanism+domain directly; stored selection equals input
        (is (= :binary-choice (:selection/mechanism pending))
            "valid :replacement-choice selection should have :binary-choice mechanism")
        (is (= :replacement-choice (:selection/domain pending))
            "valid :replacement-choice selection should have :replacement-choice domain")
        (is (= sel pending)
            "valid :replacement-choice selection should be stored as-is")))))


(deftest test-replacement-choice-spec-rejects-missing-choices
  (testing ":replacement-choice selection without :selection/choices fails spec"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [sel {:selection/type :replacement-choice
                 :selection/player-id :player-1
                 :selection/object-id (random-uuid)
                 :selection/replacement-entity-id 42
                 :selection/replacement-event {:event/type :zone-change}
                 :selection/select-count 1
                 :selection/selected #{}
                 :selection/validation :always
                 :selection/auto-confirm? false}]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection {:game/db nil} sel))
            "selection without :selection/choices should throw spec failure")))))


(deftest test-replacement-choice-spec-rejects-missing-validation
  (testing ":binary-choice selection without :selection/validation fails spec (ADR-030 mechanism-level req)"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [choices [{:choice/label "Proceed" :choice/action :proceed}]
            ;; :selection/validation is :req at the :binary-choice mechanism level
            ;; (shared across :replacement-choice and :unless-pay domains)
            sel {:selection/type      :replacement-choice
                 :selection/mechanism :binary-choice
                 :selection/domain    :replacement-choice
                 :selection/player-id :player-1
                 :selection/object-id (random-uuid)
                 :selection/replacement-entity-id 42
                 :selection/replacement-event {:event/type :zone-change}
                 :selection/choices choices
                 :selection/select-count 1
                 :selection/selected #{}
                 ;; :selection/validation intentionally omitted — must be :req
                 :selection/auto-confirm? false}]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection {:game/db nil} sel))
            ":binary-choice without :selection/validation should throw spec failure")))))


(deftest test-replacement-choice-spec-rejects-missing-selected
  (testing ":replacement-choice selection without :selection/selected fails spec"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [choices [{:choice/label "Proceed" :choice/action :proceed}
                     {:choice/label "Decline" :choice/action :redirect :choice/redirect-to :graveyard}]
            sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
                 :selection/player-id :player-1
                 :selection/object-id (random-uuid)
                 :selection/replacement-entity-id 42
                 :selection/replacement-event {:event/type :zone-change}
                 :selection/choices choices
                 :selection/select-count 1
                 :selection/validation :always
                 :selection/auto-confirm? false}]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection {:game/db nil} sel))
            "selection without :selection/selected should throw spec failure")))))


;; =====================================================
;; B. build-selection-for-replacement
;; =====================================================

(deftest test-build-selection-type-is-replacement-choice
  (testing "build-selection-for-replacement returns :selection/type :replacement-choice"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          replacement-entity (d/pull db [:db/id :replacement/event :replacement/match :replacement/choices] replacement-eid)
          needs-sel (build-mock-needs-selection replacement-entity obj-id :hand :battlefield)
          result (sel-replacement/build-selection-for-replacement db needs-sel)
          sel (:selection result)]
      (is (= :replacement-choice (:selection/type sel))
          "build-selection-for-replacement should produce :replacement-choice type"))))


(deftest test-build-selection-contains-choices-from-replacement-entity
  (testing "build-selection-for-replacement propagates :replacement/choices from replacement entity"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          replacement-entity (d/pull db [:db/id :replacement/event :replacement/match :replacement/choices] replacement-eid)
          needs-sel (build-mock-needs-selection replacement-entity obj-id :hand :battlefield)
          result (sel-replacement/build-selection-for-replacement db needs-sel)
          sel (:selection result)]
      (is (= 2 (count (:selection/choices sel)))
          "built selection should have 2 choices from the replacement entity")
      (is (some #(= :proceed (:choice/action %)) (:selection/choices sel))
          "choices should include :proceed action")
      (is (some #(= :redirect (:choice/action %)) (:selection/choices sel))
          "choices should include :redirect action"))))


(deftest test-build-selection-stores-event-for-resume
  (testing "build-selection-for-replacement stores in-flight event for resume after confirm"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          replacement-entity (d/pull db [:db/id :replacement/event :replacement/match :replacement/choices] replacement-eid)
          needs-sel (build-mock-needs-selection replacement-entity obj-id :hand :battlefield)
          result (sel-replacement/build-selection-for-replacement db needs-sel)
          sel (:selection result)]
      (is (some? (:selection/replacement-event sel))
          "built selection should store :selection/replacement-event")
      (is (= :zone-change (get-in sel [:selection/replacement-event :event/type]))
          "stored event should have :zone-change type")
      (is (= :battlefield (get-in sel [:selection/replacement-event :event/to]))
          "stored event should have original :to zone"))))


(deftest test-build-selection-stores-replacement-entity-id
  (testing "build-selection-for-replacement stores :selection/replacement-entity-id for retraction"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          replacement-entity (d/pull db [:db/id :replacement/event :replacement/match :replacement/choices] replacement-eid)
          needs-sel (build-mock-needs-selection replacement-entity obj-id :hand :battlefield)
          result (sel-replacement/build-selection-for-replacement db needs-sel)
          sel (:selection result)]
      (is (= replacement-eid (:selection/replacement-entity-id sel))
          "built selection should store the replacement entity EID"))))


(deftest test-build-selection-sets-replacement-pending-on-object
  (testing "build-selection-for-replacement sets :object/replacement-pending true on the object in returned db"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          replacement-entity (d/pull db [:db/id :replacement/event :replacement/match :replacement/choices] replacement-eid)
          needs-sel (build-mock-needs-selection replacement-entity obj-id :hand :battlefield)
          result (sel-replacement/build-selection-for-replacement db needs-sel)
          updated-db (:db result)
          obj-after (q/get-object updated-db obj-id)]
      (is (true? (:object/replacement-pending obj-after))
          "build-selection-for-replacement should set :object/replacement-pending true in returned db"))))


;; =====================================================
;; C. execute-confirmed-selection :proceed path
;; =====================================================

(deftest test-proceed-executes-choice-cost-effects
  (testing ":proceed path executes :choice/cost effect (non-interactive :lose-life cost)"
    (let [db (th/create-test-db {:life 20})
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Lose 1 life"
                          :choice/action :proceed
                          :choice/cost   {:effect/type :lose-life :effect/amount 1}}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)
          player-life (q/get-life-total result-db :player-1)]
      (is (some? result-db)
          ":proceed should return {:db ...}")
      ;; :lose-life 1 should reduce player life by 1
      (is (= 19 player-life)
          "player life should decrease by 1 from :lose-life cost"))))


(deftest test-proceed-commits-original-zone-change
  (testing ":proceed path commits original zone change (object moves to :event/to zone)"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)]
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "object should move to battlefield (:event/to zone) after :proceed"))))


(deftest test-proceed-clears-replacement-pending
  (testing ":proceed path clears :object/replacement-pending marker"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)
          obj-after (q/get-object result-db obj-id)]
      (is (not (:object/replacement-pending obj-after))
          ":object/replacement-pending should be cleared (nil or false) after confirmation"))))


(deftest test-proceed-retracts-replacement-entity-before-zone-change
  (testing ":proceed RETRACTS replacement entity before calling move-to-zone-db (prevents infinite loop)"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)
          ;; Verify replacement entity was retracted
          remaining-replacements (d/q '[:find [?r ...]
                                        :where [?r :replacement/event _]]
                                      result-db)]
      (is (not (contains? (set remaining-replacements) replacement-eid))
          "replacement entity should be retracted from db after confirmation (prevents re-trigger)"))))


(deftest test-proceed-with-no-cost-commits-zone-change
  (testing ":proceed with no :choice/cost still commits zone change"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)]
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "object should move to battlefield even when :proceed has no cost"))))


;; =====================================================
;; D. execute-confirmed-selection :redirect path
;; =====================================================

(deftest test-redirect-moves-to-redirect-zone
  (testing ":redirect path moves object to :choice/redirect-to zone (not original :event/to)"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          redirect-choice {:choice/label       "Decline: Go to graveyard"
                           :choice/action      :redirect
                           :choice/redirect-to :graveyard}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [redirect-choice]
               :selection/select-count 1
               :selection/selected #{redirect-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "object should end up in :choice/redirect-to zone (graveyard), NOT battlefield"))))


(deftest test-redirect-does-not-execute-cost
  (testing ":redirect path does NOT execute :choice/cost — land stays in hand when :redirect chosen over :proceed with discard cost"
    ;; Set up BOTH choices: proceed (with :discard-specific cost) and redirect.
    ;; Select :redirect. Assert the land was NOT discarded.
    ;; This is a meaningful test: the proceed cost is real (would discard the land),
    ;; but choosing redirect must not trigger it.
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-interactive-replacement-object-to-hand db :player-1)
          [db land-id] (th/add-card-to-zone db :forest :hand :player-1)
          ;; The interactive-replacement card has BOTH :proceed (discard land) and :redirect choices.
          ;; We fetch both from the replacement entity.
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          rep-entity (d/pull db [:db/id :replacement/choices] replacement-eid)
          choices (:replacement/choices rep-entity)
          proceed-choice (first (filter #(= :proceed (:choice/action %)) choices))
          redirect-choice (first (filter #(= :redirect (:choice/action %)) choices))
          _ (is (some? proceed-choice) "Precondition: proceed choice with discard cost must exist")
          _ (is (some? redirect-choice) "Precondition: redirect choice must exist")
          _ (is (= :discard-specific (get-in proceed-choice [:choice/cost :effect/type]))
                "Precondition: proceed cost is :discard-specific (would discard the land if chosen)")
          ;; Build selection with BOTH choices present; player selects :redirect
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices choices
               :selection/select-count 1
               :selection/selected #{redirect-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)]
      (is (= :hand (:object/zone (q/get-object result-db land-id)))
          "land card should still be in hand — :redirect choice must not execute proceed's discard cost"))))


(deftest test-redirect-no-infinite-replacement-loop
  (testing ":redirect retracts replacement entity before move-to-zone-db (prevents infinite loop)"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          redirect-choice {:choice/label       "Decline: Go to graveyard"
                           :choice/action      :redirect
                           :choice/redirect-to :graveyard}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [redirect-choice]
               :selection/select-count 1
               :selection/selected #{redirect-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)
          remaining-replacements (d/q '[:find [?r ...]
                                        :where [?r :replacement/event _]]
                                      result-db)]
      (is (not (contains? (set remaining-replacements) replacement-eid))
          "replacement entity should be retracted after :redirect (prevents re-trigger on second move)"))))


(deftest test-redirect-clears-replacement-pending
  (testing ":redirect path clears :object/replacement-pending marker"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          redirect-choice {:choice/label       "Decline: Go to graveyard"
                           :choice/action      :redirect
                           :choice/redirect-to :graveyard}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [redirect-choice]
               :selection/select-count 1
               :selection/selected #{redirect-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)
          obj-after (q/get-object result-db obj-id)]
      (is (not (:object/replacement-pending obj-after))
          ":object/replacement-pending should be cleared on :redirect path"))))


;; =====================================================
;; E. Infinite loop prevention: retract-before-move
;; =====================================================

(deftest test-replacement-entity-retracted-before-second-zone-change
  (testing "after confirmation, the second move-to-zone does not re-trigger replacement"
    ;; This tests the invariant directly: the replacement entity is gone before the move.
    ;; If it were not gone, move-to-zone would return :needs-selection again = infinite loop.
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)
          result-db (:db result)
          ;; After confirmation, calling move-to-zone again on the object should NOT return :needs-selection
          ;; (object is now on battlefield, but let's test with graveyard move since the obj moved)
          second-move (zone-change-dispatch/move-to-zone result-db obj-id :graveyard)]
      (is (not (contains? second-move :needs-selection))
          "second move-to-zone should NOT return :needs-selection after replacement entity is retracted"))))


;; =====================================================
;; F. Continuation chain shape
;; =====================================================

(deftest test-replacement-choice-returns-correct-continuation-shape
  (testing "execute-confirmed-selection :replacement-choice returns {:db ...} shape (ADR-020)"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          result (sel-core/execute-confirmed-selection db sel)]
      (is (map? result)
          "execute-confirmed-selection should return a map")
      (is (contains? result :db)
          "result should contain :db key (ADR-020 continuation shape)")
      (is (not (contains? result :app-db))
          "result should have :db key not :app-db (execute-confirmed-selection convention)"))))


;; =====================================================
;; G. build-selection-for-replacement integration
;; =====================================================

;; =====================================================
;; H. :resume-replacement-zone-change continuation
;; =====================================================

(deftest test-resume-replacement-zone-change-commits-zone-change
  (testing ":resume-replacement-zone-change continuation moves object to original destination"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          ;; In real flow, execute-confirmed-selection retracts the replacement entity first.
          ;; The continuation fires AFTER retraction — simulate that here.
          db (d/db-with db [[:db/retractEntity replacement-eid]])
          continuation {:continuation/type         :resume-replacement-zone-change
                        :continuation/object-id    obj-id
                        :continuation/destination  :battlefield}
          app-db {:game/db db}
          result (sel-core/apply-continuation continuation app-db)
          result-db (:game/db (:app-db result))]
      (is (map? result)
          "apply-continuation should return a map")
      (is (contains? result :app-db)
          "result should have :app-db key (ADR-020 shape)")
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "object should be on battlefield after :resume-replacement-zone-change"))))


(deftest test-resume-replacement-zone-change-clears-pending-marker
  (testing ":resume-replacement-zone-change clears :object/replacement-pending marker"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          ;; Simulate real flow: entity retracted before continuation fires
          db (d/db-with db [[:db/retractEntity replacement-eid]])
          continuation {:continuation/type         :resume-replacement-zone-change
                        :continuation/object-id    obj-id
                        :continuation/destination  :battlefield}
          app-db {:game/db db}
          result (sel-core/apply-continuation continuation app-db)
          result-db (:game/db (:app-db result))
          obj-after (q/get-object result-db obj-id)]
      (is (not (:object/replacement-pending obj-after))
          ":object/replacement-pending should be cleared after :resume-replacement-zone-change"))))


;; =====================================================
;; I. :proceed with interactive cost chains to discard selection
;; =====================================================

(deftest test-proceed-with-interactive-cost-opens-discard-selection
  (testing ":proceed with interactive :discard-specific cost builds a land-discard pending selection"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-interactive-replacement-object-to-hand db :player-1)
          ;; Add a land to hand so there's something to discard (not asserted on directly)
          [db _land-id] (th/add-card-to-zone db :forest :hand :player-1)
          proceed-choice {:choice/label  "Discard a land"
                          :choice/action :proceed
                          :choice/cost   {:effect/type     :discard-specific
                                          :effect/criteria {:match/types #{:land}}
                                          :effect/count    1
                                          :effect/from     :hand}}
          ;; The on-complete continuation is set by build-selection-for-replacement when
          ;; an interactive :proceed cost is detected. We simulate it here directly.
          on-complete {:continuation/type        :build-discard-for-replacement-proceed
                       :continuation/object-id   obj-id
                       :continuation/destination :battlefield
                       :continuation/cost        (:choice/cost proceed-choice)
                       :continuation/player-id   :player-1}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/on-complete on-complete
               :selection/validation :always
               :selection/lifecycle :finalized
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          app-db {:game/db db}
          sel-placed (sel-spec/set-pending-selection app-db sel)
          ;; confirm-selection-impl routes through finalized-path, drains on-complete continuation
          result (sel-core/confirm-selection-impl sel-placed)
          pending-sel (:game/pending-selection result)]
      (is (some? pending-sel)
          "after confirming :proceed with interactive cost, a new pending-selection should appear")
      (is (some? (:selection/on-complete pending-sel))
          "the new discard selection should have :selection/on-complete continuation for zone-change resume"))))


(deftest test-proceed-interactive-cost-then-discard-moves-object-to-destination
  (testing "full chain: :proceed (interactive cost) → land discard → :resume-replacement-zone-change → object enters battlefield"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-interactive-replacement-object-to-hand db :player-1)
          ;; Add a land to hand so there's something to discard
          [db land-id] (th/add-card-to-zone db :forest :hand :player-1)
          proceed-choice {:choice/label  "Discard a land"
                          :choice/action :proceed
                          :choice/cost   {:effect/type     :discard-specific
                                          :effect/criteria {:match/types #{:land}}
                                          :effect/count    1
                                          :effect/from     :hand}}
          ;; The on-complete continuation is set by build-selection-for-replacement
          on-complete {:continuation/type        :build-discard-for-replacement-proceed
                       :continuation/object-id   obj-id
                       :continuation/destination :battlefield
                       :continuation/cost        (:choice/cost proceed-choice)
                       :continuation/player-id   :player-1}
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/on-complete on-complete
               :selection/validation :always
               :selection/lifecycle :finalized
               :selection/auto-confirm? false}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          app-db {:game/db db}
          sel-placed (sel-spec/set-pending-selection app-db sel)
          ;; Step 1: Confirm replacement-choice with :proceed → drains on-complete → builds discard selection
          after-replace (sel-core/confirm-selection-impl sel-placed)
          ;; Step 2: Toggle + confirm land in the discard selection
          after-toggle (sel-core/toggle-selection-impl after-replace land-id)
          after-confirm (sel-core/confirm-selection-impl (:app-db after-toggle))
          final-db (:game/db after-confirm)]
      (is (= :battlefield (:object/zone (q/get-object final-db obj-id)))
          "artifact should be on battlefield after full interactive :proceed chain")
      (is (= :graveyard (:object/zone (q/get-object final-db land-id)))
          "land should be in graveyard after being discarded as cost"))))


(deftest test-proceed-with-dead-object-is-noop
  (testing ":proceed with retracted (dead) object does not crash — returns db cleanly"
    ;; Guard: if obj-eid is nil (object retracted), replacement.cljs checks (if obj-eid ...)
    ;; This test verifies that guard is exercised and no exception is thrown.
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          proceed-choice {:choice/label  "Proceed: Free"
                          :choice/action :proceed}
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          ;; Set replacement-pending THEN retract the object entirely
          db-with-pending (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          db-dead (d/db-with db-with-pending [[:db/retractEntity obj-eid]])
          sel {:selection/type      :replacement-choice
               :selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/player-id :player-1
               :selection/object-id obj-id
               :selection/replacement-entity-id replacement-eid
               :selection/replacement-event {:event/type  :zone-change
                                             :event/object obj-id
                                             :event/from  :hand
                                             :event/to    :battlefield}
               :selection/choices [proceed-choice]
               :selection/select-count 1
               :selection/selected #{proceed-choice}
               :selection/validation :always
               :selection/auto-confirm? false}
          ;; Should NOT throw even though the object is gone
          result (sel-core/execute-confirmed-selection db-dead sel)
          result-db (:db result)]
      (is (map? result)
          "execute-confirmed-selection should return a map even when object is dead (retracted)")
      (is (contains? result :db)
          "result should have :db key")
      ;; Object is gone — get-object returns nil
      (is (nil? (q/get-object result-db obj-id))
          "dead object should remain nil in result db"))))


(deftest test-build-then-confirm-selection-full-cycle
  (testing "full cycle: build-selection-for-replacement → set-pending-selection → confirm-selection-impl"
    (let [db (th/create-test-db)
          [db obj-id replacement-eid] (add-synthetic-object-to-hand db :player-1)
          replacement-entity (d/pull db [:db/id :replacement/event :replacement/match :replacement/choices] replacement-eid)
          needs-sel (build-mock-needs-selection replacement-entity obj-id :hand :battlefield)
          ;; build-selection-for-replacement returns {:db updated-db :selection sel-map}
          build-result (sel-replacement/build-selection-for-replacement db needs-sel)
          updated-db (:db build-result)
          sel (:selection build-result)
          ;; Choose the :proceed action (no cost needed for free proceed)
          proceed-choice (first (filter #(= :proceed (:choice/action %)) (:selection/choices sel)))
          sel-with-choice (assoc sel :selection/selected #{proceed-choice})
          app-db (-> {:game/db updated-db}
                     (sel-spec/set-pending-selection sel-with-choice))
          final-app-db (sel-core/confirm-selection-impl app-db)
          final-game-db (:game/db final-app-db)]
      (is (nil? (:game/pending-selection final-app-db))
          "pending-selection should be cleared after confirm-selection-impl")
      (is (= :battlefield (:object/zone (q/get-object final-game-db obj-id)))
          "object should be on battlefield after full confirm cycle with :proceed"))))
