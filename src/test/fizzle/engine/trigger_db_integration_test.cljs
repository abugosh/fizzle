(ns fizzle.engine.trigger-db-integration-test
  "Integration tests verifying Datascript trigger entities are created
   and that dispatch reads from Datascript."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === Card trigger Datascript entity tests ===

(deftest test-card-triggers-in-datascript-after-play-land
  (testing "City of Brass trigger entities exist in Datascript after playing the land"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-after (game/play-land db' :player-1 obj-id)
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
    (let [app-db (game/init-game-state
                   {:main-deck (:deck/main cards/iggy-pop-decklist)})
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
          (is (some? (:event/player (:trigger/filter (first draw-triggers))))
              "Draw trigger filter should include player"))
        (when (seq untap-triggers)
          (is (= :untap (:event/phase (:trigger/filter (first untap-triggers))))
              "Untap trigger filter should match untap phase")
          (is (some? (:event/player (:trigger/filter (first untap-triggers))))
              "Untap trigger filter should include player"))))))


;; === Object-trigger component ref tests ===

(deftest test-card-triggers-linked-to-object
  (testing "Trigger entities are linked to source object via :object/triggers"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-after (game/play-land db' :player-1 obj-id)
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
          db-after-play (game/play-land db' :player-1 obj-id)
          ;; Dispatch permanent-tapped event — should find trigger in Datascript
          event (game-events/permanent-tapped-event obj-id :player-1)
          db-after-dispatch (dispatch/dispatch-event db-after-play event)
          items (q/get-all-stack-items db-after-dispatch)]
      (is (= 1 (count items))
          "Dispatch should find trigger in Datascript")
      (when (seq items)
        (is (= :permanent-tapped (:stack-item/type (first items)))
            "Stack item should be :permanent-tapped type")))))
