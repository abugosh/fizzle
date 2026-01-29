(ns fizzle.events.turn-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn set-phase
  "Set the game phase in the database."
  [db phase]
  (let [conn (d/conn-from-db db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] @conn)]
    (d/transact! conn [[:db/add game-eid :game/phase phase]])
    @conn))


(defn set-turn
  "Set the game turn number in the database."
  [db turn]
  (let [conn (d/conn-from-db db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] @conn)]
    (d/transact! conn [[:db/add game-eid :game/turn turn]])
    @conn))


(defn set-storm-count
  "Set the storm count for a player."
  [db player-id count]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (d/transact! conn [[:db/add player-eid :player/storm-count count]])
    @conn))


(defn set-land-plays
  "Set the land plays remaining for a player."
  [db player-id plays]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (d/transact! conn [[:db/add player-eid :player/land-plays-left plays]])
    @conn))


(defn add-mana
  "Add mana to a player's pool."
  [db player-id mana]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        current-pool (q/get-mana-pool db player-id)
        new-pool (merge-with + current-pool mana)]
    (d/transact! conn [[:db/add player-eid :player/mana-pool new-pool]])
    @conn))


(defn get-phase
  "Get the current game phase."
  [db]
  (:game/phase (q/get-game-state db)))


(defn get-turn
  "Get the current turn number."
  [db]
  (:game/turn (q/get-game-state db)))


(defn get-land-plays
  "Get the land plays remaining for a player."
  [db player-id]
  (d/q '[:find ?plays .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/land-plays-left ?plays]]
       db player-id))


(defn mana-pool-empty?
  "Check if a player's mana pool has all zeros."
  [db player-id]
  (let [pool (q/get-mana-pool db player-id)]
    (every? zero? (vals pool))))


;; === advance-phase tests ===

(deftest test-advance-phase-from-main1-to-combat
  (testing "advance-phase from main1 goes to combat"
    (let [db (-> (init-game-state)
                 (set-phase :main1))
          db' (game/advance-phase db :player-1)]
      (is (= :combat (get-phase db'))))))


(deftest test-advance-phase-from-combat-to-main2
  (testing "advance-phase from combat goes to main2"
    (let [db (-> (init-game-state)
                 (set-phase :combat))
          db' (game/advance-phase db :player-1)]
      (is (= :main2 (get-phase db'))))))


(deftest test-advance-phase-from-cleanup-stays-cleanup
  (testing "advance-phase from cleanup stays at cleanup (requires explicit start-turn)"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup))
          db' (game/advance-phase db :player-1)]
      ;; CRITICAL: Must NOT auto-advance to untap - need explicit start-turn
      (is (= :cleanup (get-phase db'))))))


(deftest test-advance-phase-clears-mana-pool
  (testing "advance-phase clears player's mana pool"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-mana :player-1 {:black 3 :blue 2}))
          db' (game/advance-phase db :player-1)]
      (is (mana-pool-empty? db' :player-1)))))


;; === start-turn tests ===

(deftest test-start-turn-increments-turn-number
  (testing "start-turn increments turn from 1 to 2"
    (let [db (-> (init-game-state)
                 (set-turn 1)
                 (set-phase :cleanup))
          db' (game/start-turn db :player-1)]
      (is (= 2 (get-turn db'))))))


(deftest test-start-turn-resets-storm-count
  (testing "start-turn resets storm count to 0"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-storm-count :player-1 5))
          db' (game/start-turn db :player-1)]
      (is (= 0 (q/get-storm-count db' :player-1))))))


(deftest test-start-turn-resets-land-plays-to-one
  (testing "start-turn resets land-plays-left to 1"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-land-plays :player-1 0))
          db' (game/start-turn db :player-1)]
      (is (= 1 (get-land-plays db' :player-1))))))


(deftest test-start-turn-sets-phase-to-untap
  (testing "start-turn sets phase to :untap"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup))
          db' (game/start-turn db :player-1)]
      (is (= :untap (get-phase db'))))))


(deftest test-start-turn-clears-mana-pool
  (testing "start-turn clears player's mana pool"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (add-mana :player-1 {:red 2 :green 1}))
          db' (game/start-turn db :player-1)]
      (is (mana-pool-empty? db' :player-1)))))


;; === Full phase sequence test ===

(deftest test-full-phase-sequence
  (testing "phases advance in correct order: untap → upkeep → draw → main1 → combat → main2 → end → cleanup"
    (let [db (-> (init-game-state)
                 (set-phase :untap))
          ;; Advance through all phases
          after-upkeep (game/advance-phase db :player-1)
          after-draw (game/advance-phase after-upkeep :player-1)
          after-main1 (game/advance-phase after-draw :player-1)
          after-combat (game/advance-phase after-main1 :player-1)
          after-main2 (game/advance-phase after-combat :player-1)
          after-end (game/advance-phase after-main2 :player-1)
          after-cleanup (game/advance-phase after-end :player-1)]
      (is (= :upkeep (get-phase after-upkeep)))
      (is (= :draw (get-phase after-draw)))
      (is (= :main1 (get-phase after-main1)))
      (is (= :combat (get-phase after-combat)))
      (is (= :main2 (get-phase after-main2)))
      (is (= :end (get-phase after-end)))
      (is (= :cleanup (get-phase after-cleanup))))))
