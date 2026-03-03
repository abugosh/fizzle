(ns fizzle.history.principal-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.priority :as priority]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]))


;; === make-entry principal tests ===

(deftest test-make-entry-with-principal
  (testing "make-entry with principal includes :entry/principal"
    (let [entry (history/make-entry :db-0 :cast-spell "Cast Dark Ritual" 1 :player-1)]
      (is (= :player-1 (:entry/principal entry))))))


(deftest test-make-entry-without-principal
  (testing "make-entry with nil principal omits :entry/principal key"
    (let [entry (history/make-entry :db-0 :cast-spell "Cast Dark Ritual" 1 nil)]
      (is (not (contains? entry :entry/principal))
          "Entry should not contain :entry/principal when nil"))))


;; === queries/get-player-id tests ===

(deftest test-get-player-id
  (testing "get-player-id returns player-id keyword for a player entity"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn [{:player/id :player-1
                                :player/name "Player"
                                :player/life 20
                                :player/mana-pool {}
                                :player/storm-count 0
                                :player/land-plays-left 1}])
          db @conn
          eid (queries/get-player-eid db :player-1)]
      (is (= :player-1 (queries/get-player-id db eid))))))


(deftest test-get-player-id-nil-for-invalid
  (testing "get-player-id returns nil for non-player entity"
    (let [conn (d/create-conn schema)
          db @conn]
      (is (nil? (queries/get-player-id db 99999))))))


;; === determine-principal tests ===

(defn- create-two-player-game-db
  "Create a Datascript db with two players and a game state.
   Returns the db with priority on player-1."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Human"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}
                       {:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/is-opponent true
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [p1-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player p1-eid
                          :game/priority p1-eid
                          :game/human-player-id :player-1}])
      @conn)))


(defn- make-db-with-history
  "Create an app-db with history initialized and the given game-db."
  [game-db]
  (merge {:game/db game-db} (history/init-history)))


(defn- make-context
  "Build a mock re-frame context for testing the interceptor."
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


(deftest test-determine-principal-cast-spell-human
  (testing "cast-spell by human returns human player-id"
    (let [game-db (create-two-player-game-db)
          pre-db (make-db-with-history game-db)
          ;; Simulate game-db change
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [[:db/add (queries/get-player-eid game-db :player-1)
                                :player/storm-count 1]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.game/cast-spell]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-1 (:entry/principal entry))))))


(deftest test-determine-principal-cast-spell-bot
  (testing "cast-spell with bot player-id opts returns bot player-id"
    (let [game-db (create-two-player-game-db)
          pre-db (make-db-with-history game-db)
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [[:db/add (queries/get-player-eid game-db :player-2)
                                :player/storm-count 1]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.game/cast-spell {:player-id :player-2}]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-2 (:entry/principal entry))))))


(deftest test-determine-principal-yield
  (testing "yield returns priority holder player-id"
    (let [game-db (create-two-player-game-db)
          ;; Transfer priority to player-2
          game-db' (priority/transfer-priority game-db
                                               (priority/get-priority-holder-eid game-db))
          pre-db (make-db-with-history game-db')
          ;; Simulate phase advancement
          conn (d/conn-from-db game-db')
          game-eid (d/q '[:find ?e . :where [?e :game/id :game-1]] game-db')
          _ (d/transact! conn [[:db/add game-eid :game/phase :combat]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.game/yield]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-2 (:entry/principal entry))))))


(deftest test-determine-principal-activate-mana-human
  (testing "activate-mana-ability without explicit player-id returns human"
    (let [game-db (create-two-player-game-db)
          pre-db (make-db-with-history game-db)
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [[:db/add (queries/get-player-eid game-db :player-1)
                                :player/storm-count 1]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.abilities/activate-mana-ability :obj-1 :black]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-1 (:entry/principal entry))))))


(deftest test-determine-principal-activate-mana-bot
  (testing "activate-mana-ability with explicit player-id returns that player-id"
    (let [game-db (create-two-player-game-db)
          pre-db (make-db-with-history game-db)
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [[:db/add (queries/get-player-eid game-db :player-2)
                                :player/storm-count 1]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.abilities/activate-mana-ability :obj-1 :black :player-2]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-2 (:entry/principal entry))))))


(deftest test-determine-principal-play-land
  (testing "play-land with explicit player-id returns that player-id"
    (let [game-db (create-two-player-game-db)
          pre-db (make-db-with-history game-db)
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [[:db/add (queries/get-player-eid game-db :player-2)
                                :player/storm-count 1]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.game/play-land :obj-1 :player-2]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-2 (:entry/principal entry))))))


(deftest test-determine-principal-init-game
  (testing "init-game returns nil principal (system event)"
    (let [game-db (create-two-player-game-db)
          pre-db (merge {:game/db nil} (history/init-history))
          post-db (make-db-with-history game-db)
          event [:fizzle.events.game/init-game]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (not (contains? entry :entry/principal))
          "init-game should have nil principal (omitted from entry)"))))


(deftest test-determine-principal-confirm-selection
  (testing "confirm-selection uses selection's player-id"
    (let [game-db (create-two-player-game-db)
          pre-db (assoc (make-db-with-history game-db)
                        :game/pending-selection {:selection/type :cast-time-targeting
                                                 :selection/player-id :player-2})
          conn (d/conn-from-db game-db)
          _ (d/transact! conn [[:db/add (queries/get-player-eid game-db :player-2)
                                :player/storm-count 1]])
          post-db (make-db-with-history @conn)
          event [:fizzle.events.selection/confirm-selection]
          context (make-context pre-db post-db event)
          result (run-interceptor context)
          result-db (get-in result [:effects :db])
          entry (first (:history/main result-db))]
      (is (= :player-2 (:entry/principal entry))))))
