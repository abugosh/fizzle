(ns fizzle.bots.protocol-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot]
    [fizzle.db.queries :as q]
    [fizzle.test-helpers :as h]))


(defn- setup-db-with-bot
  "Create a test db with an opponent that has a bot archetype set."
  [archetype]
  (let [db (-> (h/create-test-db)
               (h/add-opponent))
        p2-eid (q/get-player-eid db :player-2)]
    (d/db-with db [[:db/add p2-eid :player/bot-archetype archetype]])))


;; === get-bot-archetype ===

(deftest get-bot-archetype-nil-for-human-player
  (let [db (h/create-test-db)]
    (is (nil? (bot/get-bot-archetype db :player-1))
        "Human player should have nil bot archetype")))


(deftest get-bot-archetype-nil-for-opponent-without-archetype
  (let [db (-> (h/create-test-db)
               (h/add-opponent))]
    (is (nil? (bot/get-bot-archetype db :player-2))
        "Opponent without bot-archetype should return nil")))


(deftest get-bot-archetype-returns-goldfish
  (let [db (setup-db-with-bot :goldfish)]
    (is (= :goldfish (bot/get-bot-archetype db :player-2)))))


(deftest get-bot-archetype-returns-burn
  (let [db (setup-db-with-bot :burn)]
    (is (= :burn (bot/get-bot-archetype db :player-2)))))


;; === bot-priority-decision ===

(deftest goldfish-returns-pass
  (is (= :pass (bot/bot-priority-decision :goldfish {}))
      "Goldfish bot always passes priority"))


(deftest default-archetype-returns-pass
  (is (= :pass (bot/bot-priority-decision :unknown-archetype {}))
      "Unknown archetype should default to pass"))


(deftest nil-archetype-returns-pass
  (is (= :pass (bot/bot-priority-decision nil {}))
      "nil archetype should default to pass"))
