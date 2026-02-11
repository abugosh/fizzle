(ns fizzle.engine.trigger-db-integration-test
  "Integration tests verifying Datascript trigger entities are created
   alongside atom registration (dual-write), and that dispatch reads
   from Datascript."
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.game :as game]))


;; === Test fixtures ===

(defn reset-registry
  "Clear trigger registry before and after each test."
  [f]
  (registry/clear-registry!)
  (turn-based/register-turn-based-actions!)
  (f)
  (registry/clear-registry!))


(use-fixtures :each reset-registry)


;; === Test helpers ===

(defn create-test-db
  "Create a game state with City of Brass card definition and player."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-card-to-hand
  "Add a card to the player's hand. Returns [db object-id]."
  [db card-id player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :hand
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


;; === Card trigger Datascript entity tests ===

(deftest test-card-triggers-in-datascript-after-play-land
  (testing "City of Brass trigger entities exist in Datascript after playing the land"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-hand db :city-of-brass :player-1)
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
                   {:main-deck (:deck/main cards/iggy-pop-decklist)
                    :clock-turns 4})
          game-db (:game/db app-db)
          triggers (trigger-db/get-all-triggers game-db)
          always-active (filter :trigger/always-active? triggers)]
      (is (= 2 (count always-active))
          "Two always-active game-rule triggers should exist (draw + untap)")
      (let [draw-triggers (filter #(= :draw-step (:trigger/type %)) always-active)
            untap-triggers (filter #(= :untap-step (:trigger/type %)) always-active)]
        (is (= 1 (count draw-triggers))
            "One draw-step trigger")
        (is (= 1 (count untap-triggers))
            "One untap-step trigger")
        (when (seq draw-triggers)
          (is (= {:event/phase :draw} (:trigger/filter (first draw-triggers)))
              "Draw trigger filter should match draw phase"))
        (when (seq untap-triggers)
          (is (= {:event/phase :untap} (:trigger/filter (first untap-triggers)))
              "Untap trigger filter should match untap phase"))))))


;; === Object-trigger component ref tests ===

(deftest test-card-triggers-linked-to-object
  (testing "Trigger entities are linked to source object via :object/triggers"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-hand db :city-of-brass :player-1)
          db-after (game/play-land db' :player-1 obj-id)
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-after obj-id)
          obj-triggers (:object/triggers (d/entity db-after obj-eid))]
      (is (= 1 (count obj-triggers))
          "Object should have one linked trigger entity"))))


;; === Dual-write verification ===

(deftest test-both-atom-and-datascript-triggers-exist
  (testing "After play-land, both atom registry and Datascript have triggers"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-hand db :city-of-brass :player-1)
          db-after (game/play-land db' :player-1 obj-id)
          ;; Atom registry triggers
          atom-triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                                (registry/get-all-triggers))
          ;; Datascript triggers
          ds-triggers (filter #(= :permanent-tapped (:trigger/event-type %))
                              (trigger-db/get-all-triggers db-after))]
      (is (= 1 (count atom-triggers))
          "Atom registry should have one :permanent-tapped trigger")
      (is (= 1 (count ds-triggers))
          "Datascript should have one :permanent-tapped trigger entity"))))


;; === Dispatch via Datascript path tests ===

(deftest test-dispatch-uses-datascript-triggers
  (testing "dispatch-event fires trigger from Datascript even with empty atom registry"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-hand db :city-of-brass :player-1)
          ;; Play land creates Datascript triggers (dual-write also registers atom)
          db-after-play (game/play-land db' :player-1 obj-id)
          ;; Clear atom to isolate Datascript path
          _ (registry/clear-registry!)
          ;; Dispatch permanent-tapped event — should find trigger in Datascript
          event (game-events/permanent-tapped-event obj-id :player-1)
          db-after-dispatch (dispatch/dispatch-event db-after-play event)
          items (stack/get-all-stack-items db-after-dispatch)]
      (is (= 1 (count items))
          "Dispatch should find trigger in Datascript even with empty atom registry")
      (when (seq items)
        (is (= :permanent-tapped (:stack-item/type (first items)))
            "Stack item should be :permanent-tapped type")))))
