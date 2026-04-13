(ns fizzle.history.principal-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.history.core :as history]))


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
