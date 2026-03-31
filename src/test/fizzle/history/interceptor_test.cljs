(ns fizzle.history.interceptor-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]))


(defn- make-context
  "Build a mock re-frame context for testing the interceptor.
   pre-db: app-db before event handler ran (goes in coeffects)
   post-db: app-db after event handler ran (goes in effects)
   event: the event vector"
  [pre-db post-db event]
  {:coeffects {:db pre-db
               :event event}
   :effects {:db post-db}})


(defn- run-interceptor
  "Run the :after phase of the history interceptor on a context."
  [context]
  (let [after-fn (:after interceptor/history-interceptor)]
    (after-fn context)))


(defn- make-db-with-history
  "Create an app-db with history initialized and the given game-db."
  [game-db]
  (merge {:game/db game-db} (history/init-history)))


(deftest test-after-skips-non-pending-events
  (testing "Events that do not set :history/pending-entry are not recorded"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)]
      (doseq [event [[:fizzle.history.events/step-to 3]
                     [:fizzle.events.selection/confirm-selection]
                     [:fizzle.events.ui/select-card :obj-1]
                     [:fizzle.events.casting/cast-spell]
                     [:fizzle.events.lands/play-land :obj-1]]]
        (let [context (make-context pre-db post-db event)
              result (run-interceptor context)
              result-db (get-in result [:effects :db])]
          (is (zero? (count (:history/main result-db)))
              (str (first event) " should not create entries without pending-entry")))))))


(deftest test-after-skips-when-no-db-in-effects
  (testing "If no :db in effects (fx handler), interceptor is a no-op"
    (let [pre-db (make-db-with-history :db-old)
          event [:some/fx-event]
          context {:coeffects {:db pre-db :event event}
                   :effects {}}
          result (run-interceptor context)
          result-effects (:effects result)]
      (is (not (contains? result-effects :db))
          "Should not add :db to effects"))))


(deftest test-pending-entry-creates-history
  (testing "pending-entry on post-db creates a history entry and is cleared"
    (let [game-db :db-snap
          pre-db (make-db-with-history :db-old)
          pending-entry {:description "Dark Ritual resolved"
                         :snapshot game-db
                         :event-type :fizzle.events.casting/cast-spell
                         :turn 3
                         :principal :player-1}
          post-db (assoc (make-db-with-history :db-old)
                         :history/pending-entry pending-entry)
          event [:fizzle.events.selection/confirm-selection]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= 1 (count (:history/main result-db)))
          "One entry should be created from pending-entry")
      (is (= "Dark Ritual resolved" (:entry/description entry))
          "Entry description should match pending-entry :description")
      (is (= :player-1 (:entry/principal entry))
          "Entry principal should match pending-entry :principal")
      (is (= game-db (:entry/snapshot entry))
          "Entry snapshot should be pending-entry :snapshot")
      (is (nil? (:history/pending-entry result-db))
          "pending-entry key should be cleared after processing"))))


(deftest test-pending-entry-appends-history
  (testing "pending-entry appends entry at tip"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          pending-entry {:description "Play land"
                         :snapshot new-game-db
                         :event-type :fizzle.events.lands/play-land
                         :turn 1
                         :principal :player-1}
          post-db (assoc (make-db-with-history new-game-db)
                         :history/pending-entry pending-entry)
          event [:fizzle.events.lands/play-land :obj-1]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "One entry should be appended to main")
      (is (= 0 (:history/position result-db))
          "Position should be 0 after first append"))))


(deftest test-pending-entry-auto-forks-when-not-at-tip
  (testing "pending-entry auto-forks when position is not at tip"
    (let [db (-> (make-db-with-history :db-0)
                 (history/append-entry (history/make-entry :db-0 :evt-0 "Entry 0" 1))
                 (history/append-entry (history/make-entry :db-1 :evt-1 "Entry 1" 1))
                 ;; Rewind to position 0 (not at tip)
                 (history/step-to 0))
          pending-entry {:description "New action"
                         :snapshot :db-fork
                         :event-type :fizzle.events.casting/cast-spell
                         :turn 1
                         :principal :player-1}
          post-db (assoc db :history/pending-entry pending-entry)
          event [:fizzle.events.selection/confirm-selection]
          context (make-context db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (uuid? (:history/current-branch result-db))
          "Should have auto-forked to a new branch")
      (is (= 2 (count (:history/main result-db)))
          "Main timeline should be preserved with 2 entries")
      (is (nil? (:history/pending-entry result-db))
          "pending-entry key should be cleared after processing"))))


(deftest test-pending-entry-with-position-negative-one
  (testing "pending-entry appends even when position is -1 (initial state)"
    (let [pre-db (merge {:game/db nil} (history/init-history))
          new-game-db :db-initial
          pending-entry {:description "Game started"
                         :snapshot new-game-db
                         :event-type :fizzle.events.init/init-game
                         :turn 0
                         :principal nil}
          post-db (assoc (merge {:game/db new-game-db} (history/init-history))
                         :history/pending-entry pending-entry)
          event [:fizzle.events.init/init-game]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "First entry should be appended")
      (is (= 0 (:history/position result-db))
          "Position should be 0 after first entry")
      (is (= "Game started" (:entry/description (first (:history/main result-db))))
          "Should have a description"))))


;; === Description generation tests with real Datascript dbs ===

(defn- create-game-db
  "Create a real Datascript game-db for testing descriptions."
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


(defn- add-card-to-zone
  "Add a card object to a zone. Returns [db object-id]."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (queries/get-player-eid db player-id)
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(deftest test-cast-spell-description-with-real-db
  (testing "Cast spell entry created when pending-entry set by handler (pending-entry mechanism)"
    (let [db-before (create-game-db)
          [db-with-card obj-id] (add-card-to-zone db-before :dark-ritual :hand :player-1)
          db-mana (mana/add-mana db-with-card :player-1 {:black 1})
          db-after-cast (rules/cast-spell db-mana :player-1 obj-id)
          ;; Simulate what cast-spell-handler would set: pending-entry on app-db
          pending-entry {:description "Cast Dark Ritual"
                         :snapshot db-after-cast
                         :event-type :fizzle.events.casting/cast-spell
                         :turn 1
                         :principal :player-1}
          pre-db (make-db-with-history db-before)
          post-db (assoc (make-db-with-history db-after-cast)
                         :history/pending-entry pending-entry)
          event [:fizzle.events.casting/cast-spell]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Cast Dark Ritual" (:entry/description entry))
          "Should include card name from pending-entry")
      (is (= 1 (:entry/turn entry))
          "Turn should be 1 from pending-entry")
      (is (nil? (:history/pending-entry result-db))
          "pending-entry should be cleared after processing"))))


(deftest test-cast-and-yield-creates-history-entry
  (testing "cast-and-yield creates a history entry via pending-entry mechanism"
    (let [game-db :db-new
          pending-entry {:description "Cast & Yield"
                         :snapshot game-db
                         :event-type :fizzle.events.priority-flow/cast-and-yield
                         :turn 0
                         :principal :player-1}
          pre-db (make-db-with-history :db-old)
          post-db (assoc (make-db-with-history game-db)
                         :history/pending-entry pending-entry)
          event [:fizzle.events.priority-flow/cast-and-yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "One entry should be appended for cast-and-yield")
      (is (= "Cast & Yield" (:entry/description (first (:history/main result-db))))
          "Should have Cast & Yield description"))))


(deftest test-cast-and-yield-description-with-real-db
  (testing "Cast-and-yield description includes card name via pending-entry mechanism"
    (let [db-before (create-game-db)
          [db-with-card obj-id] (add-card-to-zone db-before :dark-ritual :hand :player-1)
          db-mana (mana/add-mana db-with-card :player-1 {:black 1})
          db-after-cast (rules/cast-spell db-mana :player-1 obj-id)
          ;; Simulate full cast-and-yield: spell cast and resolved
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 obj-id)
          ;; Simulate pending-entry set by cast-and-yield-handler
          pending-entry {:description "Cast & Yield Dark Ritual"
                         :snapshot db-after-resolve
                         :event-type :fizzle.events.priority-flow/cast-and-yield
                         :turn 1
                         :principal :player-1}
          pre-db (assoc (make-db-with-history db-mana)
                        :game/selected-card obj-id)
          post-db (assoc (make-db-with-history db-after-resolve)
                         :history/pending-entry pending-entry)
          event [:fizzle.events.priority-flow/cast-and-yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Cast & Yield Dark Ritual" (:entry/description entry))
          "Should include card name from pending-entry"))))
