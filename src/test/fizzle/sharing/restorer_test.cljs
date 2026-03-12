(ns fizzle.sharing.restorer-test
  "Tests for snapshot restorer: portable game-state map → Datascript DB.

   The restorer reconstructs a fully playable game state from the decoded
   portable map produced by fizzle.sharing.decoder/decode-snapshot.

   Verification strategy: restore a db, then extract it again and compare
   the extracted map to the original snapshot (round-trip property)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.sharing.decoder :as decoder]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.sharing.restorer :as restorer]
    [fizzle.test-helpers :as th]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-snapshot
  "Extract + encode + decode a live DB to get a portable snapshot map."
  [db]
  (-> db extractor/extract encoder/encode-snapshot decoder/decode-snapshot))


(defn- restore-and-extract
  "Full round-trip: DB → snapshot → restored DB → extracted map."
  [db]
  (-> db make-snapshot restorer/restore-game-state :game/db extractor/extract))


;; ---------------------------------------------------------------------------
;; A. Return shape

(deftest restore-returns-app-db-map-test
  (testing "restore-game-state returns app-db map shape"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          result   (restorer/restore-game-state snapshot)]
      (is (map? result)
          "result should be a map")
      (is (contains? result :game/db)
          "result should have :game/db key")
      (is (contains? result :active-screen)
          "result should have :active-screen key"))))


(deftest restore-active-screen-is-game-test
  (testing ":active-screen is :game (not :opening-hand)"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          result   (restorer/restore-game-state snapshot)]
      (is (= :game (:active-screen result))
          ":active-screen should be :game"))))


(deftest restore-game-db-is-datascript-db-test
  (testing ":game/db is a Datascript DB value"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          result   (restorer/restore-game-state snapshot)]
      (is (d/db? (:game/db result))
          ":game/db should be a Datascript DB"))))


(deftest restore-has-history-keys-test
  (testing "result has empty history keys"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          result   (restorer/restore-game-state snapshot)]
      (is (= [] (:history/main result))
          ":history/main should be []")
      (is (= {} (:history/forks result))
          ":history/forks should be {}")
      (is (nil? (:history/current-branch result))
          ":history/current-branch should be nil")
      (is (= -1 (:history/position result))
          ":history/position should be -1"))))


;; ---------------------------------------------------------------------------
;; B. Players in restored DB

(deftest restore-player-1-exists-test
  (testing "restored DB has :player-1"
    (let [db (-> (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
                 restorer/restore-game-state
                 :game/db)]
      (is (some? (q/get-player-eid db :player-1))
          "player-1 should exist in restored DB"))))


(deftest restore-player-2-exists-test
  (testing "restored DB has :player-2"
    (let [db (-> (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
                 restorer/restore-game-state
                 :game/db)]
      (is (some? (q/get-player-eid db :player-2))
          "player-2 should exist in restored DB"))))


(deftest restore-player-life-test
  (testing "player life total restored correctly"
    (let [db       (-> (th/create-test-db {:life 13}) (th/add-opponent))
          snapshot (make-snapshot db)
          restored (-> snapshot restorer/restore-game-state :game/db)
          p1       (d/pull restored [:player/life] (q/get-player-eid restored :player-1))]
      (is (= 13 (:player/life p1))
          "player-1 life should be 13"))))


(deftest restore-player-storm-count-test
  (testing "player storm count restored correctly"
    (let [db       (-> (th/create-test-db {:storm-count 5}) (th/add-opponent))
          snapshot (make-snapshot db)
          restored (-> snapshot restorer/restore-game-state :game/db)
          p1       (d/pull restored [:player/storm-count] (q/get-player-eid restored :player-1))]
      (is (= 5 (:player/storm-count p1))
          "player-1 storm count should be 5"))))


(deftest restore-player-mana-pool-test
  (testing "player mana pool restored correctly"
    (let [db       (-> (mana/add-mana (th/create-test-db) :player-1 {:black 2 :blue 1})
                       (th/add-opponent))
          snapshot (make-snapshot db)
          restored (-> snapshot restorer/restore-game-state :game/db)
          p1       (d/pull restored [:player/mana-pool] (q/get-player-eid restored :player-1))]
      (is (= 2 (get-in p1 [:player/mana-pool :black]))
          "black mana should be 2")
      (is (= 1 (get-in p1 [:player/mana-pool :blue]))
          "blue mana should be 1"))))


(deftest restore-player-land-plays-test
  (testing "player land-plays-left restored correctly"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          p1-snap  (get-in snapshot [:players :player-1 :player/land-plays-left])
          restored (-> snapshot restorer/restore-game-state :game/db)
          p1       (d/pull restored [:player/land-plays-left] (q/get-player-eid restored :player-1))]
      (is (= p1-snap (:player/land-plays-left p1))
          "land-plays-left should match snapshot"))))


;; ---------------------------------------------------------------------------
;; C. Card definitions in restored DB

(deftest restore-card-definitions-present-test
  (testing "all card definitions are present in restored DB"
    (let [db (-> (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
                 restorer/restore-game-state
                 :game/db)
          dark-ritual-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)]
      (is (some? dark-ritual-eid)
          ":dark-ritual card definition should be present in restored DB"))))


(deftest restore-card-count-matches-registry-test
  (testing "restored DB has all cards from registry"
    (let [db         (-> (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
                         restorer/restore-game-state
                         :game/db)
          card-count (d/q '[:find (count ?e) . :where [?e :card/id _]] db)]
      (is (= (count cards/all-cards) card-count)
          "number of card definitions should match registry"))))


;; ---------------------------------------------------------------------------
;; D. Zone contents in restored DB

(deftest restore-hand-cards-test
  (testing "hand cards restored with correct card-id"
    (let [db       (th/create-test-db)
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          db-with-opp (th/add-opponent db'')
          out      (restore-and-extract db-with-opp)
          hand     (get-in out [:players :player-1 :hand])]
      (is (= 2 (count hand))
          "hand should have 2 cards")
      (is (= #{:dark-ritual :brain-freeze} (set (map :card/id hand)))
          "hand should contain dark-ritual and brain-freeze"))))


(deftest restore-graveyard-cards-test
  (testing "graveyard cards restored correctly"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :lotus-petal :graveyard :player-1)
          out      (restore-and-extract db')]
      (is (= 1 (count (get-in out [:players :player-1 :graveyard])))
          "graveyard should have 1 card")
      (is (= :lotus-petal (:card/id (first (get-in out [:players :player-1 :graveyard]))))
          "graveyard card should be lotus-petal"))))


(deftest restore-library-order-test
  (testing "library card order preserved by position"
    (let [db      (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-cards-to-library db [:dark-ritual :brain-freeze :lotus-petal] :player-1)
          out     (restore-and-extract db')]
      (is (= [:dark-ritual :brain-freeze :lotus-petal]
             (map :card/id (get-in out [:players :player-1 :library])))
          "library order should be preserved"))))


(deftest restore-battlefield-tapped-state-test
  (testing "battlefield permanent tapped state restored"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          out      (restore-and-extract db')]
      (let [bf (get-in out [:players :player-1 :battlefield])]
        (is (= 1 (count bf))
            "battlefield should have 1 permanent")
        (is (= :city-of-brass (:card/id (first bf)))
            "battlefield permanent should be city-of-brass")
        (is (false? (:object/tapped (first bf)))
            "permanent should not be tapped")))))


(deftest restore-battlefield-counters-test
  (testing "battlefield permanent counters restored"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          ;; Inject counters into the snapshot manually
          snapshot     (make-snapshot db')
          snapshot-ctr (update-in snapshot [:players :player-1 :battlefield 0]
                                  assoc :object/counters {:mining 3})
          out      (-> snapshot-ctr restorer/restore-game-state :game/db extractor/extract)]
      (let [bf (get-in out [:players :player-1 :battlefield])]
        (is (= {:mining 3} (:object/counters (first bf)))
            "counters should be restored")))))


(deftest restore-exile-cards-test
  (testing "exile zone cards restored"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :lotus-petal :exile :player-1)
          out      (restore-and-extract db')]
      (is (= [:lotus-petal]
             (map :card/id (get-in out [:players :player-1 :exile])))
          "exile should have lotus-petal"))))


;; ---------------------------------------------------------------------------
;; E. Game state in restored DB

(deftest restore-game-turn-test
  (testing "game turn restored correctly"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          restored (-> snapshot restorer/restore-game-state :game/db)
          game     (d/pull restored [:game/turn] [:game/id :game-1])]
      (is (= (:game/turn snapshot) (:game/turn game))
          "game turn should match snapshot"))))


(deftest restore-game-phase-test
  (testing "game phase restored correctly"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          restored (-> snapshot restorer/restore-game-state :game/db)
          game     (d/pull restored [:game/phase] [:game/id :game-1])]
      (is (= (:game/phase snapshot) (:game/phase game))
          "game phase should match snapshot"))))


(deftest restore-active-player-ref-test
  (testing "game active-player is a valid entity ref in restored DB"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          restored (-> snapshot restorer/restore-game-state :game/db)
          game     (d/pull restored [{:game/active-player [:player/id]}] [:game/id :game-1])
          active   (get-in game [:game/active-player :player/id])]
      (is (= (:game/active-player snapshot) active)
          "active-player should match snapshot player-id"))))


(deftest restore-priority-ref-test
  (testing "game priority is a valid entity ref in restored DB"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          restored (-> snapshot restorer/restore-game-state :game/db)
          game     (d/pull restored [{:game/priority [:player/id]}] [:game/id :game-1])
          prio     (get-in game [:game/priority :player/id])]
      (is (= (:game/priority snapshot) prio)
          "priority player should match snapshot"))))


(deftest restore-human-player-id-test
  (testing ":game/human-player-id restored correctly"
    (let [snapshot (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
          restored (-> snapshot restorer/restore-game-state :game/db)
          game     (d/pull restored [:game/human-player-id] [:game/id :game-1])]
      (is (= :player-1 (:game/human-player-id game))
          "human-player-id should be :player-1"))))


;; ---------------------------------------------------------------------------
;; F. Turn-based triggers in restored DB

(deftest restore-turn-based-triggers-present-test
  (testing "turn-based triggers created for both players"
    (let [db (-> (make-snapshot (-> (th/create-test-db) (th/add-opponent)))
                 restorer/restore-game-state
                 :game/db)
          triggers (d/q '[:find [?type ...]
                          :where [?e :trigger/event-type :phase-entered]
                          [?e :trigger/type ?type]]
                        db)]
      (is (seq triggers)
          "should have phase-entered triggers")
      (is (some #{:draw-step} triggers)
          "should have :draw-step trigger")
      (is (some #{:untap-step} triggers)
          "should have :untap-step trigger"))))


;; ---------------------------------------------------------------------------
;; G. Battlefield triggers in restored DB

(deftest restore-battlefield-triggers-for-card-test
  (testing "triggers created for battlefield permanents with :card/triggers"
    (let [;; City of Brass has a :becomes-tapped trigger
          db       (-> (th/create-test-db) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          snapshot (make-snapshot db')
          restored (-> snapshot restorer/restore-game-state :game/db)
          triggers (d/q '[:find [?type ...]
                          :where [?e :trigger/event-type _]
                          [?e :trigger/type ?type]]
                        restored)]
      (is (some #{:becomes-tapped} triggers)
          "city-of-brass :becomes-tapped trigger should be present in restored DB"))))


;; ---------------------------------------------------------------------------
;; H. Stack items in restored DB

(deftest restore-stack-items-test
  (testing "stack items restored when snapshot has non-empty stack"
    (let [db       (th/create-test-db)
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m     (mana/add-mana db' :player-1 {:black 1})
          hand     (q/get-hand db-m :player-1)
          db-cast  (rules/cast-spell db-m :player-1 (:object/id (first hand)))
          db-with-opp (th/add-opponent db-cast)
          snapshot (make-snapshot db-with-opp)
          restored (-> snapshot restorer/restore-game-state :game/db)
          stack    (d/q '[:find [(pull ?e [*]) ...]
                          :where [?e :stack-item/position _]]
                        restored)]
      (is (pos? (count stack))
          "restored DB should have stack items"))))


(deftest restore-stack-item-type-test
  (testing "stack item type restored correctly"
    (let [db       (th/create-test-db)
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m     (mana/add-mana db' :player-1 {:black 1})
          hand     (q/get-hand db-m :player-1)
          db-cast  (rules/cast-spell db-m :player-1 (:object/id (first hand)))
          db-with-opp (th/add-opponent db-cast)
          out      (restore-and-extract db-with-opp)
          stack    (:stack out)]
      (is (pos? (count stack))
          "stack should not be empty")
      (is (= :spell (:stack-item/type (first stack)))
          "stack item type should be :spell"))))


;; ---------------------------------------------------------------------------
;; I. Playability — can-cast? works from restored state

(deftest restore-can-cast-from-restored-state-test
  (testing "player can cast spells from restored game state"
    (let [db       (th/create-test-db)
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m     (mana/add-mana db' :player-1 {:black 1})
          db-with-opp (th/add-opponent db-m)
          snapshot (make-snapshot db-with-opp)
          restored (-> snapshot restorer/restore-game-state :game/db)
          hand     (q/get-hand restored :player-1)]
      (is (= 1 (count hand))
          "hand should have 1 card")
      (is (rules/can-cast? restored :player-1 (:object/id (first hand)))
          "should be able to cast dark-ritual from restored state"))))


;; ---------------------------------------------------------------------------
;; J. Round-trip fidelity

(deftest round-trip-full-state-test
  (testing "full round-trip preserves all zones for both players"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          [db1 _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db2 _]  (th/add-card-to-zone db1 :brain-freeze :graveyard :player-1)
          [db3 _]  (th/add-card-to-zone db2 :lotus-petal :battlefield :player-1)
          [db4 _]  (th/add-card-to-zone db3 :counterspell :hand :player-2)
          out      (restore-and-extract db4)]
      (is (= [:dark-ritual]
             (map :card/id (get-in out [:players :player-1 :hand])))
          "player-1 hand preserved")
      (is (= [:brain-freeze]
             (map :card/id (get-in out [:players :player-1 :graveyard])))
          "player-1 graveyard preserved")
      (is (= [:lotus-petal]
             (map :card/id (get-in out [:players :player-1 :battlefield])))
          "player-1 battlefield preserved")
      (is (= [:counterspell]
             (map :card/id (get-in out [:players :player-2 :hand])))
          "player-2 hand preserved"))))
