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
  "Run both :before and :after phases of the history interceptor on a context."
  [context]
  (let [before-fn (:before interceptor/history-interceptor)
        after-fn (:after interceptor/history-interceptor)
        ctx-after-before (before-fn context)]
    (after-fn ctx-after-before)))


(defn- make-db-with-history
  "Create an app-db with history initialized and the given game-db."
  [game-db]
  (merge {:game/db game-db} (history/init-history)))


(deftest test-before-stores-pre-game-db
  (testing ":before stores :game/db in coeffects as :history/pre-game-db"
    (let [game-db :db-original
          db (make-db-with-history game-db)
          context {:coeffects {:db db :event [:some/event]}
                   :effects {}}
          before-fn (:before interceptor/history-interceptor)
          ctx-after (before-fn context)]
      (is (= game-db (get-in ctx-after [:coeffects :history/pre-game-db]))))))


(deftest test-after-appends-entry-on-game-db-change
  (testing "When :game/db changes, a history entry is appended (inference path)"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)
          event [:fizzle.events.lands/play-land :obj-1]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "One entry should be appended to main")
      (is (= 0 (:history/position result-db))
          "Position should be 0 after first append"))))


(deftest test-after-skips-when-game-db-unchanged
  (testing "When :game/db is identical (same reference), no entry is appended"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          ;; Post-db has same game-db reference
          post-db (make-db-with-history same-db)
          event [:fizzle.events.casting/cast-spell]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (zero? (count (:history/main result-db)))
          "No entry should be appended when game-db unchanged"))))


(deftest test-after-skips-non-priority-events
  (testing "Non-priority events are not recorded even if they change game-db"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)]
      (doseq [event [[:fizzle.history.events/step-to 3]
                     [:fizzle.events.selection/confirm-selection]
                     [:fizzle.events.ui/select-card :obj-1]
                     [:fizzle.events.selection/confirm-tutor-selection]]]
        (let [context (make-context pre-db post-db event)
              result (run-interceptor context)
              result-db (get-in result [:effects :db])]
          (is (zero? (count (:history/main result-db)))
              (str (first event) " should not create entries")))))))


(deftest test-after-auto-forks-when-not-at-tip
  (testing "When position < tip and game-db changes, auto-fork is used"
    (let [;; Set up db with 3 entries on main, position rewound to 1
          db (-> (make-db-with-history :db-0)
                 (history/append-entry (history/make-entry :db-0 :evt-0 "Entry 0" 1))
                 (history/append-entry (history/make-entry :db-1 :evt-1 "Entry 1" 1))
                 (history/append-entry (history/make-entry :db-2 :evt-2 "Entry 2" 1))
                 ;; Rewind to position 1
                 (history/step-to 1))
          new-game-db :db-diverge
          post-db (assoc db :game/db new-game-db)
          event [:fizzle.events.lands/play-land :obj-1]
          context (make-context db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      ;; Should have forked — current-branch should be non-nil
      (is (uuid? (:history/current-branch result-db))
          "Should have auto-forked to a new branch")
      ;; Main should still have 3 entries (unchanged)
      (is (= 3 (count (:history/main result-db)))
          "Main timeline should be preserved"))))


(deftest test-entry-has-correct-fields
  (testing "Appended entry has all required fields (inference path via play-land)"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)
          event [:fizzle.events.lands/play-land :obj-1]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= new-game-db (:entry/snapshot entry))
          "Snapshot should be the new game-db")
      (is (= :fizzle.events.lands/play-land (:entry/event-type entry))
          "Event type should be the event keyword")
      (is (string? (:entry/description entry))
          "Description should be a string")
      (is (= 0 (:entry/turn entry))
          "Turn should be 0 for mock game-db (no game state to query)"))))


(deftest test-description-from-describe-event
  (testing "Entry uses description from describe-event when available"
    (let [pre-db (make-db-with-history :db-old)
          post-db (make-db-with-history :db-new)
          event [:fizzle.events.priority-flow/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Yield" (:entry/description entry))))))


(deftest test-priority-events-all-have-descriptions
  (testing "Remaining inference-path events produce named descriptions"
    (doseq [event [;; yield/yield-all use pending-entry from handlers but still pass through priority-events
                   ;; init-game, play-land, cycle-card, activate-mana-ability use inference path
                   [:fizzle.events.priority-flow/yield]
                   [:fizzle.events.priority-flow/yield-all]
                   [:fizzle.events.lands/play-land :obj-1]
                   [:fizzle.events.init/init-game]
                   [:fizzle.events.abilities/activate-mana-ability :obj-1 :black]]]
      (let [pre-db (make-db-with-history :db-old)
            post-db (make-db-with-history :db-new)
            context (make-context pre-db post-db event)
            result (run-interceptor context)
            result-db (get-in result [:effects :db])
            entry (first (:history/main result-db))]
        (is (and (string? (:entry/description entry))
                 (pos? (count (:entry/description entry))))
            (str (first event) " should have a non-empty description"))
        (is (not= (name (first event)) (:entry/description entry))
            (str (first event) " should not use fallback name")))))
  (testing "cast-spell, cast-and-yield, activate-ability no longer use inference — they use pending-entry"
    (doseq [event [[:fizzle.events.casting/cast-spell]
                   [:fizzle.events.priority-flow/cast-and-yield]
                   [:fizzle.events.abilities/activate-ability :obj-1 0]]]
      (let [pre-db (make-db-with-history :db-old)
            post-db (make-db-with-history :db-new)
            context (make-context pre-db post-db event)
            result (run-interceptor context)
            result-db (get-in result [:effects :db])]
        ;; Without pending-entry set by handler, no entry is created via inference
        (is (zero? (count (:history/main result-db)))
            (str (first event) " should NOT create entry via inference path (uses pending-entry)")))))
  (testing "confirm-selection no longer creates entries via priority-selection-types"
    (doseq [selection-type [:cast-time-targeting :x-mana-cost :exile-cards-cost :ability-targeting]]
      (let [pre-db (assoc (make-db-with-history :db-old)
                          :game/pending-selection {:selection/type selection-type})
            post-db (make-db-with-history :db-new)
            event [:fizzle.events.selection/confirm-selection]
            context (make-context pre-db post-db event)
            result (run-interceptor context)
            result-db (get-in result [:effects :db])]
        ;; confirm-selection does NOT create entries via interceptor inference anymore
        (is (zero? (count (:history/main result-db)))
            (str "confirm-selection with " selection-type " no longer creates entries via inference"))))))


(deftest test-init-game-creates-first-entry
  (testing "init-game creates the first history entry (position -1 -> 0)"
    (let [pre-db (merge {:game/db nil} (history/init-history))
          new-game-db :db-initial
          post-db (merge {:game/db new-game-db} (history/init-history))
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


(deftest test-after-creates-entry-on-pending-selection-creation
  (testing "When pending-selection is created but game-db is unchanged, entry is still created"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          ;; Post-db has same game-db but gained a pending-selection
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :peek-and-select})
          event [:fizzle.events.priority-flow/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "Entry should be created when yield creates a pending-selection"))))


(deftest test-after-skips-when-selection-already-existed
  (testing "When pending-selection already existed before, unchanged game-db means no entry"
    (let [same-db :db-same
          ;; Pre-db already had a pending-selection
          pre-db (assoc (make-db-with-history same-db)
                        :game/pending-selection {:selection/type :discard})
          ;; Post-db still has a pending-selection (same or different)
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :discard})
          event [:fizzle.events.priority-flow/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (zero? (count (:history/main result-db)))
          "No entry when selection already existed and game-db unchanged"))))


(deftest test-after-uses-pre-game-db-snapshot-for-selection-created
  (testing "When entry is created via selection-creation, snapshot uses pre-game-db"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :peek-and-select})
          event [:fizzle.events.priority-flow/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= same-db (:entry/snapshot entry))
          "Snapshot should be pre-game-db when game-db was unchanged"))))


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


(deftest test-yield-description-with-real-db
  (testing "Yield description with real Datascript db"
    (let [db-before (create-game-db)
          ;; Simulate a game-db change from yielding
          conn (d/conn-from-db db-before)
          game-eid (d/q '[:find ?e . :where [?e :game/id :game-1]] db-before)
          _ (d/transact! conn [[:db/add game-eid :game/phase :combat]])
          db-after @conn
          pre-db (make-db-with-history db-before)
          post-db (make-db-with-history db-after)
          event [:fizzle.events.priority-flow/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Yield \u2192 Combat" (:entry/description entry))
          "Should have Yield description with target phase"))))


(deftest test-yield-all-description-with-real-db
  (testing "Yield-all description with real Datascript db"
    (let [db-before (create-game-db)
          conn (d/conn-from-db db-before)
          game-eid (d/q '[:find ?e . :where [?e :game/id :game-1]] db-before)
          _ (d/transact! conn [[:db/add game-eid :game/phase :main2]])
          db-after @conn
          pre-db (make-db-with-history db-before)
          post-db (make-db-with-history db-after)
          event [:fizzle.events.priority-flow/yield-all]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Yield All \u2192 Main 2" (:entry/description entry))
          "Should have Yield All description with target phase"))))


(deftest test-cast-spell-selection-created-does-not-create-entry
  (testing "cast-spell that only creates a selection (no game-db change) should NOT create entry"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          ;; cast-spell created a pending-selection but game-db unchanged
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :exile-cards-cost}
                         :game/selected-card :some-spell)
          event [:fizzle.events.casting/cast-spell]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (zero? (count (:history/main result-db)))
          "No entry when cast-spell only creates selection — confirm-selection handles it")))
  (testing "Same for activate-ability creating a selection"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :ability-targeting})
          event [:fizzle.events.abilities/activate-ability :obj-1 0]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (zero? (count (:history/main result-db)))
          "No entry when activate-ability only creates selection"))))


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


(deftest test-cast-and-yield-selection-defers-to-confirm
  (testing "cast-and-yield does NOT create entry when selection created — defers to confirm-selection"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :cast-time-targeting
                                                  :selection/on-complete
                                                  {:continuation/type :resolve-one-and-stop}})
          event [:fizzle.events.priority-flow/cast-and-yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 0 (count (:history/main result-db)))
          "No entry — confirm-selection will create the single Cast & Yield entry"))))


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
          ;; Use a non-priority event so ONLY the pending-entry mechanism fires
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


(deftest test-pending-entry-auto-forks
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
