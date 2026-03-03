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
  (testing "When :game/db changes, a history entry is appended"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)
          event [:fizzle.events.game/cast-spell]
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
          event [:fizzle.events.game/cast-spell]
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
                     [:fizzle.events.game/select-card :obj-1]
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
          event [:fizzle.events.game/cast-spell]
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
  (testing "Appended entry has all required fields"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)
          event [:fizzle.events.game/cast-spell]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= new-game-db (:entry/snapshot entry))
          "Snapshot should be the new game-db")
      (is (= :fizzle.events.game/cast-spell (:entry/event-type entry))
          "Event type should be the event keyword")
      (is (= "Cast spell" (:entry/description entry))
          "Description should come from describe-event")
      (is (= 0 (:entry/turn entry))
          "Turn should be 0 for mock game-db (no game state to query)"))))


(deftest test-description-from-describe-event
  (testing "Entry uses description from describe-event when available"
    (let [pre-db (make-db-with-history :db-old)
          post-db (make-db-with-history :db-new)
          event [:fizzle.events.game/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Yield" (:entry/description entry))))))


(deftest test-priority-events-all-have-descriptions
  (testing "All priority events produce named descriptions (not fallback)"
    (doseq [event [[:fizzle.events.game/cast-spell]
                   [:fizzle.events.game/cast-and-yield]
                   [:fizzle.events.game/yield]
                   [:fizzle.events.game/yield-all]
                   ;; start-turn creates its own history entries (not via interceptor)
                   [:fizzle.events.game/play-land :obj-1]
                   [:fizzle.events.game/init-game]
                   [:fizzle.events.abilities/activate-mana-ability :obj-1 :black]
                   [:fizzle.events.abilities/activate-ability :obj-1 0]]]
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
  (testing "Selection confirm events with priority selection types produce descriptions"
    (doseq [selection-type [:cast-time-targeting :x-mana-cost :exile-cards-cost :ability-targeting]]
      (let [pre-db (assoc (make-db-with-history :db-old)
                          :game/pending-selection {:selection/type selection-type})
            post-db (make-db-with-history :db-new)
            event [:fizzle.events.selection/confirm-selection]
            context (make-context pre-db post-db event)
            result (run-interceptor context)
            result-db (get-in result [:effects :db])
            entry (first (:history/main result-db))]
        (is (and (string? (:entry/description entry))
                 (pos? (count (:entry/description entry))))
            (str "confirm-selection with " selection-type " should have a non-empty description"))
        (is (not= "confirm-selection" (:entry/description entry))
            (str "confirm-selection with " selection-type " should not use fallback name"))))))


(deftest test-init-game-creates-first-entry
  (testing "init-game creates the first history entry (position -1 -> 0)"
    (let [pre-db (merge {:game/db nil} (history/init-history))
          new-game-db :db-initial
          post-db (merge {:game/db new-game-db} (history/init-history))
          event [:fizzle.events.game/init-game]
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
          event [:fizzle.events.game/yield]
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
          event [:fizzle.events.game/yield]
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
          event [:fizzle.events.game/yield]
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
  (testing "Cast spell description includes card name with real Datascript db"
    (let [db-before (create-game-db)
          [db-with-card obj-id] (add-card-to-zone db-before :dark-ritual :hand :player-1)
          db-mana (mana/add-mana db-with-card :player-1 {:black 1})
          db-after-cast (rules/cast-spell db-mana :player-1 obj-id)
          pre-db (make-db-with-history db-before)
          post-db (make-db-with-history db-after-cast)
          event [:fizzle.events.game/cast-spell]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Cast Dark Ritual" (:entry/description entry))
          "Should include card name when real db has spell on stack")
      (is (= 1 (:entry/turn entry))
          "Turn should be 1 from real game state"))))


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
          event [:fizzle.events.game/yield]
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
          event [:fizzle.events.game/yield-all]
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
          event [:fizzle.events.game/cast-spell]
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
  (testing "cast-and-yield creates a history entry when game-db changes"
    (let [old-game-db :db-old
          new-game-db :db-new
          pre-db (make-db-with-history old-game-db)
          post-db (make-db-with-history new-game-db)
          event [:fizzle.events.game/cast-and-yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "One entry should be appended for cast-and-yield")
      (is (= "Cast & Yield" (:entry/description (first (:history/main result-db))))
          "Should have Cast & Yield description"))))


(deftest test-cast-and-yield-selection-triggers-entry
  (testing "cast-and-yield creates entry when selection created but game-db unchanged"
    (let [same-db :db-same
          pre-db (make-db-with-history same-db)
          post-db (assoc (make-db-with-history same-db)
                         :game/pending-selection {:selection/type :peek-and-select})
          event [:fizzle.events.game/cast-and-yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])]
      (is (= 1 (count (:history/main result-db)))
          "Entry should be created when cast-and-yield creates a pending-selection"))))


(deftest test-cast-and-yield-description-with-real-db
  (testing "Cast-and-yield description includes card name with real Datascript db"
    (let [db-before (create-game-db)
          [db-with-card obj-id] (add-card-to-zone db-before :dark-ritual :hand :player-1)
          db-mana (mana/add-mana db-with-card :player-1 {:black 1})
          db-after-cast (rules/cast-spell db-mana :player-1 obj-id)
          ;; Simulate full cast-and-yield: spell cast and resolved
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 obj-id)
          ;; pre-db has the spell in hand (before cast), selected-card set
          pre-db (assoc (make-db-with-history db-mana)
                        :game/selected-card obj-id)
          post-db (make-db-with-history db-after-resolve)
          event [:fizzle.events.game/cast-and-yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= "Cast & Yield Dark Ritual" (:entry/description entry))
          "Should include card name for cast-and-yield"))))
