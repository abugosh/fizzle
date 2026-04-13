(ns fizzle.engine.trigger-db-integration-test
  "Integration tests verifying Datascript trigger entities are created
   and that dispatch reads from Datascript."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.init :as game-init]
    [fizzle.events.lands :as lands]
    [fizzle.events.setup :as setup]
    [fizzle.test-helpers :as th]))


;; === Card trigger Datascript entity tests ===

(deftest test-card-triggers-in-datascript-after-play-land
  (testing "City of Brass trigger entities exist in Datascript after playing the land"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-after (lands/play-land db' :player-1 obj-id)
          ;; Query Datascript for trigger entities
          triggers (trigger-db/get-all-triggers db-after)
          cob-triggers (filter #(= :permanent-tapped (:trigger/event-type %)) triggers)]
      (is (= 1 (count cob-triggers))
          "Exactly one :permanent-tapped trigger entity should exist in Datascript")
      (when (seq cob-triggers)
        (let [t (first cob-triggers)]
          (is (= :becomes-tapped (:trigger/type t))
              "Trigger type should be :becomes-tapped")
          (is (= {:event/object-id :self} (:trigger/filter t))
              "Default filter should be {:event/object-id :self}"))))))


;; === Game-rule trigger Datascript entity tests ===

(deftest test-game-rule-triggers-in-datascript-after-init
  (testing "Game-rule triggers (draw, untap) exist in Datascript after game init"
    (let [app-db (game-init/init-game-state
                   {:main-deck (:deck/main setup/iggy-pop-decklist)})
          game-db (:game/db app-db)
          triggers (trigger-db/get-all-triggers game-db)
          always-active (filter :trigger/always-active? triggers)]
      (is (= 4 (count always-active))
          "Four always-active game-rule triggers should exist (draw + untap per player)")
      (let [draw-triggers (filter #(= :draw-step (:trigger/type %)) always-active)
            untap-triggers (filter #(= :untap-step (:trigger/type %)) always-active)]
        (is (= 2 (count draw-triggers))
            "Two draw-step triggers (one per player)")
        (is (= 2 (count untap-triggers))
            "Two untap-step triggers (one per player)")
        (when (seq draw-triggers)
          (is (= :draw (:event/phase (:trigger/filter (first draw-triggers))))
              "Draw trigger filter should match draw phase")
          (is (= :player-1 (:event/player (:trigger/filter (first draw-triggers))))
              "Draw trigger filter should include player-1"))
        (when (seq untap-triggers)
          (is (= :untap (:event/phase (:trigger/filter (first untap-triggers))))
              "Untap trigger filter should match untap phase")
          (is (= :player-1 (:event/player (:trigger/filter (first untap-triggers))))
              "Untap trigger filter should include player-1"))))))


;; === Object-trigger component ref tests ===

(deftest test-card-triggers-linked-to-object
  (testing "Trigger entities are linked to source object via :object/triggers"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-after (lands/play-land db' :player-1 obj-id)
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-after obj-id)
          obj-triggers (:object/triggers (d/entity db-after obj-eid))]
      (is (= 1 (count obj-triggers))
          "Object should have one linked trigger entity"))))


;; === Dispatch via Datascript path tests ===

(deftest test-dispatch-uses-datascript-triggers
  (testing "dispatch-event fires trigger from Datascript"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          ;; Play land creates Datascript triggers
          db-after-play (lands/play-land db' :player-1 obj-id)
          ;; Dispatch permanent-tapped event — should find trigger in Datascript
          event (game-events/permanent-tapped-event obj-id :player-1)
          db-after-dispatch (dispatch/dispatch-event db-after-play event)
          items (q/get-all-stack-items db-after-dispatch)]
      (is (= 1 (count items))
          "Dispatch should find trigger in Datascript")
      (when (seq items)
        (is (= :permanent-tapped (:stack-item/type (first items)))
            "Stack item should be :permanent-tapped type")))))


;; === HIGH corner case: retractEntity cascade for real card leaving battlefield ===

(deftest test-city-of-brass-triggers-retracted-on-leave-battlefield
  (testing "City of Brass :object/triggers AND trigger-db entries are retracted when CoB leaves battlefield"
    ;; Bug caught: if zones/move-to-zone* (the chokepoint for trigger retraction) does not
    ;; correctly retract triggers via :db.fn/retractEntity, then a CoB that bounces back to
    ;; hand would retain its :becomes-tapped trigger in Datascript. On re-entry to battlefield
    ;; a second trigger would be registered, causing double-firing.
    ;; Test verifies BOTH: :object/triggers on the object AND trigger-db/get-all-triggers count.
    (let [db (th/create-test-db)
          [db cob-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          ;; Play CoB — registers :becomes-tapped trigger in Datascript
          db-on-bf (lands/play-land db :player-1 cob-id)
          cob-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db-on-bf cob-id)
          ;; Count trigger-db entries BEFORE leaving battlefield
          triggers-before (trigger-db/get-all-triggers db-on-bf)
          obj-triggers-before (:object/triggers (d/entity db-on-bf cob-eid))
          ;; Move CoB to hand (simulates bounce — retractEntity should fire)
          db-bounced (zone-change-dispatch/move-to-zone db-on-bf cob-id :hand)
          ;; Count trigger-db entries AFTER leaving battlefield
          triggers-after (trigger-db/get-all-triggers db-bounced)
          obj-triggers-after (:object/triggers (d/entity db-bounced cob-eid))]
      ;; Preconditions
      (is (= 1 (count obj-triggers-before))
          "Precondition: CoB has 1 :object/triggers entry while on battlefield")
      (is (pos? (count triggers-before))
          "Precondition: trigger-db has entries while CoB is on battlefield")
      ;; Post-conditions — cascade must have fired
      (is (= 0 (count (or obj-triggers-after #{})))
          "After leaving battlefield: CoB :object/triggers must be retracted (count = 0)")
      (is (< (count triggers-after) (count triggers-before))
          "After leaving battlefield: trigger-db/get-all-triggers count must decrease (cascade fired)"))))
