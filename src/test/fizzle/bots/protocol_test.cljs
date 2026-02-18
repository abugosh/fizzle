(ns fizzle.bots.protocol-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot]
    [fizzle.cards.lightning-bolt :as lightning-bolt]
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


;; === Burn Bot Tests ===

(deftest burn-priority-decision-returns-cast-when-bolt-and-mountain
  (testing "burn bot returns cast action when bolt in hand and untapped Mountain"
    (let [db (h/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (h/add-opponent @conn)
          [db obj-id] (h/add-card-to-zone db :lightning-bolt :hand :player-2)
          [db _mtn-id] (h/add-card-to-zone db :mountain :battlefield :player-2)
          decision (bot/bot-priority-decision :burn {:db db :player-id :player-2})]
      (is (map? decision)
          "Should return an action map, not :pass")
      (is (= :cast-spell (:action decision))
          "Action should be :cast-spell")
      (is (= obj-id (:object-id decision))
          "Should target the bolt in hand")
      (is (= :player-1 (:target decision))
          "Should dynamically resolve opponent as target"))))


(deftest burn-priority-decision-returns-pass-without-untapped-lands
  (testing "burn bot passes when no untapped red sources"
    (let [db (h/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (h/add-opponent @conn)
          [db _] (h/add-card-to-zone db :lightning-bolt :hand :player-2)
          decision (bot/bot-priority-decision :burn {:db db :player-id :player-2})]
      (is (= :pass decision)
          "Should pass when no untapped lands to produce red"))))


(deftest burn-priority-decision-returns-pass-without-bolt
  (testing "burn bot passes when no bolt in hand"
    (let [db (h/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db (h/add-opponent @conn)
          [db _] (h/add-card-to-zone db :mountain :battlefield :player-2)
          decision (bot/bot-priority-decision :burn {:db db :player-id :player-2})]
      (is (= :pass decision)
          "Should pass when no bolt in hand"))))


(deftest burn-priority-decision-returns-pass-with-empty-context
  (testing "burn bot passes when context is empty"
    (is (= :pass (bot/bot-priority-decision :burn {}))
        "Should pass with empty context")))


(deftest burn-priority-decision-returns-pass-without-opponent
  (testing "burn bot passes when no opponent exists (goldfish mode)"
    (let [db (h/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/lightning-bolt])
          db @conn
          [db _] (h/add-card-to-zone db :lightning-bolt :hand :player-1)
          [db _] (h/add-card-to-zone db :mountain :battlefield :player-1)
          decision (bot/bot-priority-decision :burn {:db db :player-id :player-1})]
      (is (= :pass decision)
          "Should pass when no other player exists to target"))))


(deftest burn-phase-action-main1-returns-play-land
  (let [db (h/create-test-db)]
    (is (= {:action :play-land}
           (bot/bot-phase-action :burn :main1 db :player-2))
        "Burn bot should play a land on main1")))


(deftest burn-phase-action-other-phases-return-pass
  (doseq [phase [:untap :upkeep :draw :combat :main2 :end :cleanup]]
    (let [db (h/create-test-db)]
      (is (= {:action :pass}
             (bot/bot-phase-action :burn phase db :player-2))
          (str "Burn bot should pass on " (name phase))))))


(deftest burn-deck-has-60-cards
  (let [deck (bot/bot-deck :burn)
        total (reduce + 0 (map :count deck))]
    (is (= 60 total)
        "Burn deck should have 60 cards")))


(deftest burn-deck-is-mountains-and-bolts
  (let [deck (bot/bot-deck :burn)
        card-ids (set (map :card/id deck))]
    (is (= #{:mountain :lightning-bolt} card-ids)
        "Burn deck should contain only Mountains and Lightning Bolts")))


(deftest burn-deck-has-20-mountains-40-bolts
  (let [deck (bot/bot-deck :burn)
        by-id (into {} (map (fn [e] [(:card/id e) (:count e)])) deck)]
    (is (= 20 (:mountain by-id))
        "Should have 20 Mountains")
    (is (= 40 (:lightning-bolt by-id))
        "Should have 40 Lightning Bolts")))
