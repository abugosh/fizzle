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


;; === bot-phase-action ===

(deftest goldfish-phase-action-main1-returns-play-land
  (let [db (h/create-test-db)]
    (is (= {:action :play-land}
           (bot/bot-phase-action :goldfish :main1 db :player-1))
        "Goldfish should want to play a land on main1")))


(deftest goldfish-phase-action-other-phases-return-pass
  (doseq [phase [:untap :upkeep :draw :combat :main2 :end :cleanup]]
    (let [db (h/create-test-db)]
      (is (= {:action :pass}
             (bot/bot-phase-action :goldfish phase db :player-1))
          (str "Goldfish should pass on " (name phase))))))


(deftest default-phase-action-returns-pass
  (let [db (h/create-test-db)]
    (is (= {:action :pass}
           (bot/bot-phase-action :unknown-archetype :main1 db :player-1))
        "Unknown archetype should pass on all phases")))


;; === bot-deck ===

(deftest goldfish-deck-has-60-cards
  (let [deck (bot/bot-deck :goldfish)
        total (reduce + 0 (map :count deck))]
    (is (= 60 total)
        "Goldfish deck should have 60 cards")))


(deftest goldfish-deck-is-all-basic-lands
  (let [deck (bot/bot-deck :goldfish)
        card-ids (set (map :card/id deck))]
    (is (= #{:plains :island :swamp :mountain :forest} card-ids)
        "Goldfish deck should contain all 5 basic land types")))


(deftest goldfish-deck-is-evenly-distributed
  (let [deck (bot/bot-deck :goldfish)]
    (doseq [{:keys [card/id count]} deck]
      (is (= 12 count)
          (str (name id) " should have 12 copies")))))


(deftest default-deck-returns-60-cards
  (let [deck (bot/bot-deck :unknown)
        total (reduce + 0 (map :count deck))]
    (is (= 60 total)
        "Default deck should have 60 cards")))
