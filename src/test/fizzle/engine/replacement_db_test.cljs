(ns fizzle.engine.replacement-db-test
  "Unit and integration tests for replacement-effect entity registration and matching.

   Covers:
   A. Registration: build-object-tx creates replacement entities for cards with :card/replacement-effects
   B. Matching: get-replacements-for-event filters by event-type, destination, and :self sigil
   C. Dispatch: zone_change_dispatch/move-to-zone returns :needs-selection for matching replacements
   D. API: move-to-zone-db shim returns plain db

   All tests use synthetic card fixtures — no dependency on Mox Diamond card (task 3)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.objects :as objects]
    [fizzle.engine.replacement-db :as replacement-db]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Fixture helpers
;; =====================================================

(defn- make-synthetic-card-with-replacement
  "Register a synthetic card with :card/replacement-effects in db.
   Returns [db card-eid]."
  [db]
  (let [card-id (random-uuid)
        card-tx {:card/id   card-id
                 :card/name "Synthetic Replacement Card"
                 :card/types [:artifact]
                 :card/replacement-effects
                 [{:replacement/event :zone-change
                   :replacement/match {:match/object :self
                                       :match/to     :battlefield}
                   :replacement/choices
                   [{:choice/label  "Proceed"
                     :choice/action :proceed}
                    {:choice/label      "Redirect to graveyard"
                     :choice/action     :redirect
                     :choice/redirect-to :graveyard}]}]}
        db-with (d/db-with db [card-tx])
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db-with card-id)]
    [db-with card-eid]))


(defn- add-synthetic-object-with-replacement
  "Add a synthetic object with :card/replacement-effects to the given zone.
   Returns [db obj-id]."
  [db owner zone]
  (let [[db card-eid] (make-synthetic-card-with-replacement db)
        card-data (d/pull db
                          [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects]
                          card-eid)
        player-eid (q/get-player-eid db owner)
        obj-id (random-uuid)
        obj-tx (objects/build-object-tx db card-eid card-data zone player-eid 0 :id obj-id)
        db (d/db-with db [obj-tx])]
    [db obj-id]))


(defn- add-synthetic-object-without-replacement
  "Add a synthetic object WITHOUT :card/replacement-effects to the given zone.
   Returns [db obj-id]."
  [db owner zone]
  (let [card-id (random-uuid)
        card-tx {:card/id   card-id
                 :card/name "Synthetic No-Replacement Card"
                 :card/types [:artifact]}
        db (d/db-with db [card-tx])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        card-data (d/pull db
                          [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects]
                          card-eid)
        player-eid (q/get-player-eid db owner)
        obj-id (random-uuid)
        obj-tx (objects/build-object-tx db card-eid card-data zone player-eid 0 :id obj-id)
        db (d/db-with db [obj-tx])]
    [db obj-id]))


;; =====================================================
;; A. Registration tests
;; =====================================================

(deftest test-replacement-entity-created-for-card-with-replacement-effects
  (testing "build-object-tx creates replacement entity when card has :card/replacement-effects"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          replacement-entities (d/q '[:find [(pull ?r [*]) ...]
                                      :in $ ?o
                                      :where [?o :object/replacement-effects ?r]]
                                    db obj-eid)]
      (is (= 1 (count replacement-entities))
          "One replacement entity should be created for card with one replacement effect"))))


(deftest test-no-replacement-entity-for-card-without-replacement-effects
  (testing "build-object-tx does NOT create replacement entity when card lacks :card/replacement-effects"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-without-replacement db :player-1 :hand)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          replacement-entities (d/q '[:find [(pull ?r [*]) ...]
                                      :in $ ?o
                                      :where [?o :object/replacement-effects ?r]]
                                    db obj-eid)]
      (is (= 0 (count replacement-entities))
          "No replacement entity should be created for card without :card/replacement-effects"))))


(deftest test-replacement-entity-linked-via-object-component
  (testing "replacement entity is linked via :object/replacement-effects component attribute"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          ;; Pull specific fields only (avoid [*] to prevent circular ref via :replacement/source-object)
          pulled (d/pull db [{:object/replacement-effects [:replacement/event :replacement/match :replacement/choices]}]
                         obj-eid)
          replacements (:object/replacement-effects pulled)]
      (is (= 1 (count replacements))
          "d/pull via :object/replacement-effects returns the replacement entity")
      (when (= 1 (count replacements))
        (is (= :zone-change (:replacement/event (first replacements)))
            "Replacement entity has correct :replacement/event")))))


(deftest test-replacement-entity-cascade-retract
  (testing "retractEntity on object also retracts replacement entities (component cascade)"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          ;; Verify replacement entity exists before retraction
          replacements-before (d/q '[:find [(pull ?r [*]) ...]
                                     :in $ ?o
                                     :where [?o :object/replacement-effects ?r]]
                                   db obj-eid)
          _ (is (= 1 (count replacements-before)) "Precondition: replacement entity exists")
          ;; Retract the object
          db-after (d/db-with db [[:db/retractEntity obj-eid]])
          ;; Query replacement entities — should be empty now
          all-replacements (d/q '[:find [(pull ?r [*]) ...]
                                  :where [?r :replacement/event _]]
                                db-after)]
      (is (= 0 (count all-replacements))
          "Replacement entities cascade-retract when parent object is retracted"))))


(deftest test-card-with-both-triggers-and-replacements
  (testing "card with both :card/triggers AND :card/replacement-effects creates both entity types"
    (let [db (th/create-test-db)
          card-id (random-uuid)
          card-tx {:card/id   card-id
                   :card/name "Card With Both"
                   :card/types [:artifact]
                   :card/triggers
                   [{:trigger/type    :zone-change
                     :trigger/effects [{:effect/type :draw :effect/amount 1}]}]
                   :card/replacement-effects
                   [{:replacement/event :zone-change
                     :replacement/match {:match/object :self :match/to :battlefield}
                     :replacement/choices [{:choice/action :proceed}]}]}
          db (d/db-with db [card-tx])
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
          card-data (d/pull db
                            [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects]
                            card-eid)
          player-eid (q/get-player-eid db :player-1)
          obj-id (random-uuid)
          obj-tx (objects/build-object-tx db card-eid card-data :hand player-eid 0 :id obj-id)
          db (d/db-with db [obj-tx])
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          triggers (d/q '[:find [(pull ?t [*]) ...]
                          :in $ ?o
                          :where [?o :object/triggers ?t]]
                        db obj-eid)
          replacements (d/q '[:find [(pull ?r [*]) ...]
                              :in $ ?o
                              :where [?o :object/replacement-effects ?r]]
                            db obj-eid)]
      (is (= 1 (count triggers))
          "Trigger entity created for card with :card/triggers")
      (is (= 1 (count replacements))
          "Replacement entity created for card with :card/replacement-effects")
      (is (not= (first triggers) (first replacements))
          "Trigger and replacement entities are distinct"))))


(deftest test-empty-replacement-effects-vector
  (testing "empty :card/replacement-effects vector creates no replacement entity"
    (let [db (th/create-test-db)
          card-id (random-uuid)
          card-tx {:card/id   card-id
                   :card/name "Card With Empty Replacements"
                   :card/types [:artifact]
                   :card/replacement-effects []}
          db (d/db-with db [card-tx])
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
          card-data (d/pull db
                            [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects]
                            card-eid)
          player-eid (q/get-player-eid db :player-1)
          obj-id (random-uuid)
          obj-tx (objects/build-object-tx db card-eid card-data :hand player-eid 0 :id obj-id)
          db (d/db-with db [obj-tx])
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          replacements (d/q '[:find [(pull ?r [*]) ...]
                              :in $ ?o
                              :where [?o :object/replacement-effects ?r]]
                            db obj-eid)]
      (is (= 0 (count replacements))
          "Empty :card/replacement-effects vector creates no replacement entity"))))


;; =====================================================
;; B. Matching tests
;; =====================================================

(deftest test-get-replacements-matching-self-zone-change-to-battlefield
  (testing "get-replacements-for-event returns replacement matching self-ETB"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          event {:event/type      :zone-change
                 :event/object-id obj-id
                 :event/from-zone :hand
                 :event/to-zone   :battlefield}
          matches (replacement-db/get-replacements-for-event db obj-id event)]
      (is (= 1 (count matches))
          "One replacement should match self-ETB event")
      (when (= 1 (count matches))
        (is (= :zone-change (:replacement/event (first matches)))
            "Matching replacement has :replacement/event :zone-change")))))


(deftest test-get-replacements-no-match-wrong-destination
  (testing "get-replacements-for-event returns empty when destination doesn't match"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          ;; Replacement matches :to :battlefield, but event is :to :graveyard
          event {:event/type      :zone-change
                 :event/object-id obj-id
                 :event/from-zone :hand
                 :event/to-zone   :graveyard}
          matches (replacement-db/get-replacements-for-event db obj-id event)]
      (is (= 0 (count matches))
          "Replacement should NOT match: destination is :graveyard, not :battlefield"))))


(deftest test-get-replacements-no-match-wrong-object
  (testing "get-replacements-for-event returns empty when :self doesn't match queried object"
    (let [db (th/create-test-db)
          [db obj-id-1] (add-synthetic-object-with-replacement db :player-1 :hand)
          [db _obj-id-2] (add-synthetic-object-without-replacement db :player-1 :hand)
          ;; Query with a different object UUID than the one the replacement is registered on
          other-uuid (random-uuid)
          event {:event/type      :zone-change
                 :event/object-id other-uuid
                 :event/from-zone :hand
                 :event/to-zone   :battlefield}
          matches (replacement-db/get-replacements-for-event db obj-id-1 event)]
      (is (= 0 (count matches))
          "Replacement should NOT match: :self resolved to obj-id-1, event has different object"))))


(deftest test-get-replacements-no-match-wrong-event-type
  (testing "get-replacements-for-event returns empty when event type doesn't match"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          ;; Replacement is :zone-change, but event is :permanent-tapped
          event {:event/type      :permanent-tapped
                 :event/object-id obj-id}
          matches (replacement-db/get-replacements-for-event db obj-id event)]
      (is (= 0 (count matches))
          "Replacement should NOT match: event type :permanent-tapped != :zone-change"))))


;; =====================================================
;; C. Dispatch integration tests
;; =====================================================

(deftest test-move-to-zone-returns-needs-selection-for-matching-replacement
  (testing "move-to-zone returns :needs-selection for object with self-ETB replacement"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          result (zone-change-dispatch/move-to-zone db obj-id :battlefield)]
      (is (map? result) "move-to-zone returns a map")
      (is (contains? result :db) "Result contains :db key")
      (is (contains? result :needs-selection) "Result contains :needs-selection when replacement matches")
      (when (contains? result :needs-selection)
        (is (= :replacement (get-in result [:needs-selection :input/type]))
            ":needs-selection has :input/type :replacement")))))


(deftest test-move-to-zone-needs-selection-contains-required-keys
  (testing ":needs-selection map from move-to-zone contains all keys required by task 2"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          result (zone-change-dispatch/move-to-zone db obj-id :battlefield)
          ns-map (:needs-selection result)]
      (is (some? ns-map) "Precondition: :needs-selection is present")
      (when ns-map
        (is (= :replacement (:input/type ns-map))
            ":needs-selection has :input/type :replacement")
        (is (map? (:replacement/entity ns-map))
            ":needs-selection has :replacement/entity (the replacement entity map)")
        (is (map? (:replacement/event ns-map))
            ":needs-selection has :replacement/event (the in-flight event map)")
        (is (some? (:replacement/object-id ns-map))
            ":needs-selection has :replacement/object-id (the UUID of moving object)")
        (when (map? (:replacement/event ns-map))
          (is (= :zone-change (get-in ns-map [:replacement/event :event/type]))
              ":replacement/event has :event/type :zone-change")
          (is (= obj-id (get-in ns-map [:replacement/event :event/object]))
              ":replacement/event has :event/object = the moving object UUID")
          (is (= :hand (get-in ns-map [:replacement/event :event/from]))
              ":replacement/event has :event/from = :hand")
          (is (= :battlefield (get-in ns-map [:replacement/event :event/to]))
              ":replacement/event has :event/to = :battlefield"))))))


(deftest test-move-to-zone-returns-db-for-no-replacement
  (testing "move-to-zone returns {:db db'} (no :needs-selection) when no replacement applies"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-without-replacement db :player-1 :hand)
          result (zone-change-dispatch/move-to-zone db obj-id :battlefield)]
      (is (map? result) "move-to-zone returns a map even when no replacement")
      (is (contains? result :db) "Result contains :db key")
      (is (not (contains? result :needs-selection))
          "Result does NOT contain :needs-selection when no replacement applies")
      ;; Verify the zone change actually committed
      (let [obj-after (q/get-object (:db result) obj-id)]
        (is (= :battlefield (:object/zone obj-after))
            "Object moved to :battlefield when no replacement applies")))))


(deftest test-move-to-zone-returns-db-for-non-matching-replacement
  (testing "move-to-zone returns {:db db'} when replacement matches different destination"
    (let [db (th/create-test-db)
          ;; Object has replacement matching :to :battlefield, but we move it to :graveyard
          [db obj-id] (add-synthetic-object-with-replacement db :player-1 :hand)
          result (zone-change-dispatch/move-to-zone db obj-id :graveyard)]
      (is (map? result) "move-to-zone returns a map")
      (is (not (contains? result :needs-selection))
          "No :needs-selection when moving to :graveyard (replacement only matches :battlefield)")
      (let [obj-after (q/get-object (:db result) obj-id)]
        (is (= :graveyard (:object/zone obj-after))
            "Object moved to :graveyard when replacement doesn't match")))))


(deftest test-existing-trigger-dispatch-unchanged-by-replacement-check
  (testing "trigger dispatch still works correctly after replacement-check code added"
    ;; Use an existing card with a known trigger (dark-ritual has no triggers, use city-of-brass)
    ;; Actually use a manual trigger setup to be deterministic
    (let [db (th/create-test-db)
          ;; Add an object without replacement effects
          [db obj-id] (add-synthetic-object-without-replacement db :player-1 :library)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          player-eid (q/get-player-eid db :player-1)
          ;; Manually add a zone-change trigger to the object
          trigger-tx {:db/id obj-eid
                      :object/triggers
                      [{:trigger/type       :zone-change
                        :trigger/event-type :zone-change
                        :trigger/source     obj-eid
                        :trigger/controller player-eid
                        :trigger/match      {:event/from-zone :library
                                             :event/to-zone   :graveyard
                                             :event/object-id :self}
                        :trigger/effects    [{:effect/type :draw :effect/amount 0}]
                        :trigger/uses-stack? true}]}
          db (d/db-with db [trigger-tx])
          initial-stack (d/q '[:find [(pull ?e [*]) ...]
                               :where [?e :stack-item/position _]]
                             db)
          ;; Move object library→graveyard — should fire trigger
          result (zone-change-dispatch/move-to-zone db obj-id :graveyard)
          db-after (:db result)
          stack-after (d/q '[:find [(pull ?e [*]) ...]
                             :where [?e :stack-item/position _]]
                           db-after)]
      (is (= 0 (count initial-stack)) "Precondition: no stack items initially")
      (is (= 1 (count stack-after)) "Zone-change trigger still fires after replacement-check code added"))))


;; =====================================================
;; D. API regression tests
;; =====================================================

(deftest test-move-to-zone-db-shim-returns-plain-db
  (testing "move-to-zone-db shim returns plain db, not a tagged map"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-without-replacement db :player-1 :hand)
          result (zone-change-dispatch/move-to-zone-db db obj-id :graveyard)]
      ;; A Datascript db does NOT have a :db key; the tagged map {:db db'} does.
      ;; This distinguishes the shim return from the tagged return of move-to-zone.
      (is (not (contains? result :db))
          "move-to-zone-db returns plain Datascript db (no :db key), not a {:db ...} tagged map")
      (is (some? result)
          "move-to-zone-db returns non-nil result")
      ;; Verify the object moved
      (let [obj-after (q/get-object result obj-id)]
        (is (= :graveyard (:object/zone obj-after))
            "Object actually moved to :graveyard via move-to-zone-db shim")))))


(deftest test-move-to-zone-tagged-return-has-valid-db
  (testing "move-to-zone tagged return has a valid Datascript db at :db key"
    (let [db (th/create-test-db)
          [db obj-id] (add-synthetic-object-without-replacement db :player-1 :hand)
          result (zone-change-dispatch/move-to-zone db obj-id :graveyard)
          result-db (:db result)]
      (is (map? result) "move-to-zone returns a map")
      (is (some? result-db) ":db key is non-nil")
      ;; Can query the result db normally
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "Result :db contains the moved object in :graveyard"))))
