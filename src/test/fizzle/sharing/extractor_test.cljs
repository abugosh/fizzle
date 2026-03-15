(ns fizzle.sharing.extractor-test
  "Tests for game state extractor — Datascript DB → portable map.

   The extractor converts the live Datascript DB into a plain Clojure map
   suitable for serialization. Card references use :card/id keywords.
   Library cards are in position order (index 0 = top).
   All object state fields are preserved."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.test-helpers :as th]))


;; === A. Top-level shape ===

(deftest extract-returns-map-test
  (testing "extract returns a plain map"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (map? out)
          "extract should return a map"))))


(deftest extract-has-required-keys-test
  (testing "extracted map has all required top-level keys"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (contains? out :game/turn)         ":game/turn")
      (is (contains? out :game/phase)        ":game/phase")
      (is (contains? out :game/active-player) ":game/active-player")
      (is (contains? out :game/priority)     ":game/priority")
      (is (contains? out :players)           ":players"))))


;; === B. Game state fields ===

(deftest extract-game-state-values-test
  (testing "game state fields have correct values"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (= 1 (:game/turn out))
          "turn should be 1")
      (is (= :main1 (:game/phase out))
          "phase should be :main1")
      (is (= :player-1 (:game/active-player out))
          "active-player should be :player-1 keyword (not eid)")
      (is (= :player-1 (:game/priority out))
          "priority should be :player-1 keyword (not eid)"))))


;; === C. Players map ===

(deftest extract-players-contains-player-1-test
  (testing "players map contains :player-1 key"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (contains? (:players out) :player-1)
          "players should have :player-1"))))


(deftest extract-player-has-required-fields-test
  (testing "each player entry has all required fields"
    (let [db     (-> (th/create-test-db) (th/add-opponent))
          out    (extractor/extract db)
          p1     (get-in out [:players :player-1])
          p2     (get-in out [:players :player-2])]
      (doseq [p [p1 p2]]
        (is (contains? p :player/life)          ":player/life")
        (is (contains? p :player/mana-pool)     ":player/mana-pool")
        (is (contains? p :player/storm-count)   ":player/storm-count")
        (is (contains? p :player/land-plays-left) ":player/land-plays-left")
        (is (contains? p :player/max-hand-size) ":player/max-hand-size")
        (is (contains? p :player/grants)        ":player/grants")
        (is (contains? p :hand)                 ":hand zone")
        (is (contains? p :library)              ":library zone")
        (is (contains? p :graveyard)            ":graveyard zone")
        (is (contains? p :battlefield)          ":battlefield zone")
        (is (contains? p :exile)                ":exile zone")))))


(deftest extract-player-life-correct-test
  (testing "player life total is extracted correctly"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (= 20 (get-in out [:players :player-1 :player/life]))
          "player-1 life should be 20"))))


(deftest extract-player-mana-pool-correct-test
  (testing "player mana pool is extracted as a map"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (map? (get-in out [:players :player-1 :player/mana-pool]))
          "mana pool should be a map"))))


(deftest extract-player-grants-empty-by-default-test
  (testing "player grants default to empty vector when no grants exist"
    (let [db  (th/create-test-db)
          out (extractor/extract db)]
      (is (= [] (get-in out [:players :player-1 :player/grants]))
          "grants should default to []"))))


;; === D. Zone contents — card refs use :card/id keywords ===

(deftest extract-hand-card-uses-card-id-test
  (testing "hand cards reference :card/id keyword, not entity id"
    (let [[db _] (th/add-card-to-zone (th/create-test-db) :dark-ritual :hand :player-1)
          out    (extractor/extract db)
          hand   (get-in out [:players :player-1 :hand])]
      (is (= 1 (count hand))
          "should have 1 card in hand")
      (let [card-ref (:card/id (first hand))]
        (is (= :dark-ritual card-ref)
            "card reference should be :dark-ritual keyword")))))


(deftest extract-graveyard-card-uses-card-id-test
  (testing "graveyard cards reference :card/id keyword"
    (let [[db _] (th/add-card-to-zone (th/create-test-db) :brain-freeze :graveyard :player-1)
          out    (extractor/extract db)
          gy     (get-in out [:players :player-1 :graveyard])]
      (is (= 1 (count gy)))
      (is (= :brain-freeze (:card/id (first gy)))))))


(deftest extract-empty-zones-are-vectors-test
  (testing "empty zones are represented as empty vectors"
    (let [db  (th/create-test-db)
          out (extractor/extract db)
          p1  (get-in out [:players :player-1])]
      (is (= [] (:hand p1))      "hand should be []")
      (is (= [] (:library p1))   "library should be []")
      (is (= [] (:graveyard p1)) "graveyard should be []")
      (is (= [] (:battlefield p1)) "battlefield should be []")
      (is (= [] (:exile p1))     "exile should be []"))))


;; === E. Library ordering ===

(deftest extract-library-in-position-order-test
  (testing "library cards are ordered by position (index 0 = top)"
    (let [[db _] (th/add-cards-to-library
                   (th/create-test-db)
                   [:dark-ritual :brain-freeze :lotus-petal]
                   :player-1)
          out    (extractor/extract db)
          lib    (get-in out [:players :player-1 :library])]
      (is (= 3 (count lib))
          "library should have 3 cards")
      (is (= :dark-ritual   (:card/id (nth lib 0)))
          "position 0 = top of library")
      (is (= :brain-freeze  (:card/id (nth lib 1)))
          "position 1 = second card")
      (is (= :lotus-petal   (:card/id (nth lib 2)))
          "position 2 = third card"))))


;; === F. Battlefield state — all fields preserved ===

(deftest extract-battlefield-non-creature-test
  (testing "non-creature battlefield card has tapped and zone"
    (let [[db _] (th/add-card-to-zone (th/create-test-db) :city-of-brass :battlefield :player-1)
          out    (extractor/extract db)
          bf     (get-in out [:players :player-1 :battlefield])]
      (is (= 1 (count bf)))
      (let [obj (first bf)]
        (is (= :city-of-brass (:card/id obj)))
        (is (contains? obj :object/tapped))
        (is (false? (:object/tapped obj)))))))


(deftest extract-battlefield-creature-full-state-test
  (testing "creature on battlefield includes all state fields"
    (let [[db _] (th/add-card-to-zone (th/create-test-db) :nimble-mongoose :battlefield :player-1)
          out    (extractor/extract db)
          bf     (get-in out [:players :player-1 :battlefield])
          obj    (first bf)]
      (is (= :nimble-mongoose (:card/id obj)))
      (is (contains? obj :object/tapped)           ":object/tapped")
      (is (contains? obj :object/summoning-sick)   ":object/summoning-sick")
      (is (contains? obj :object/power)            ":object/power")
      (is (contains? obj :object/toughness)        ":object/toughness")
      (is (contains? obj :object/damage-marked)    ":object/damage-marked")
      (is (contains? obj :object/counters)         ":object/counters"))))


(deftest extract-battlefield-no-datascript-internals-test
  (testing "extracted objects do not contain :db/id or raw entity refs"
    (let [[db _] (th/add-card-to-zone (th/create-test-db) :city-of-brass :battlefield :player-1)
          out    (extractor/extract db)
          obj    (first (get-in out [:players :player-1 :battlefield]))]
      (is (not (contains? obj :db/id))
          "should not contain :db/id")
      ;; :object/owner and :object/controller should be :player/id keywords, not eids
      (is (keyword? (:object/owner obj))
          ":object/owner should be a keyword like :player-1")
      (is (keyword? (:object/controller obj))
          ":object/controller should be a keyword like :player-1"))))


;; === G. Two-player extraction ===

(deftest extract-two-players-test
  (testing "both players appear in :players map"
    (let [db  (-> (th/create-test-db) (th/add-opponent))
          out (extractor/extract db)]
      (is (= 2 (count (:players out)))
          "should have 2 players")
      (is (contains? (:players out) :player-1) ":player-1 present")
      (is (contains? (:players out) :player-2) ":player-2 present"))))


(deftest extract-two-players-independent-zones-test
  (testing "each player's zones are independent"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-2)
          out  (extractor/extract db'')
          p1   (get-in out [:players :player-1])
          p2   (get-in out [:players :player-2])]
      (is (= 1 (count (:hand p1))) "player-1 has 1 card in hand")
      (is (= 1 (count (:hand p2))) "player-2 has 1 card in hand")
      (is (= :dark-ritual  (:card/id (first (:hand p1)))) "player-1 has dark-ritual")
      (is (= :brain-freeze (:card/id (first (:hand p2)))) "player-2 has brain-freeze"))))
