(ns fizzle.events.turn-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.stack :as stack]
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


(defn add-stack-item-to-stack
  "Add a stack-item to the stack for testing."
  [db]
  (stack/create-stack-item db
                           {:stack-item/type :test
                            :stack-item/controller :player-1}))


(defn add-spell-to-stack
  "Add a spell object to the stack zone for testing."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        order (stack/get-next-stack-order db)]
    (d/transact! conn [{:object/id (str "test-spell-" order)
                        :object/owner player-eid
                        :object/zone :stack
                        :object/position order}])
    @conn))


;; === Stack guard tests ===

(deftest test-advance-phase-blocked-by-trigger-on-stack
  (testing "advance-phase no-ops when trigger is on the stack"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-mana :player-1 {:black 3})
                 (add-stack-item-to-stack))
          db' (game/advance-phase db :player-1)]
      (is (= :main1 (get-phase db')) "phase should not advance")
      (is (not (mana-pool-empty? db' :player-1)) "mana pool should not be cleared"))))


(deftest test-advance-phase-blocked-by-spell-on-stack
  (testing "advance-phase no-ops when spell is on the stack"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-spell-to-stack :player-1))
          db' (game/advance-phase db :player-1)]
      (is (= :main1 (get-phase db')) "phase should not advance"))))


(deftest test-advance-phase-blocked-by-both-triggers-and-spells
  (testing "advance-phase no-ops when both triggers and spells are on the stack"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-stack-item-to-stack)
                 (add-spell-to-stack :player-1))
          db' (game/advance-phase db :player-1)]
      (is (= :main1 (get-phase db')) "phase should not advance"))))


(deftest test-start-turn-blocked-by-stack-items
  (testing "start-turn no-ops when stack has items"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-turn 1)
                 (add-stack-item-to-stack))
          db' (game/start-turn db :player-1)]
      (is (= :cleanup (get-phase db')) "phase should stay at cleanup")
      (is (= 1 (get-turn db')) "turn should not increment"))))


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


;; === Cleanup discard helpers ===

(defn add-cards-to-hand
  "Add N Dark Ritual objects to a player's hand for testing.
   Returns updated db."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid @conn player-id)
        card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] @conn)]
    (d/transact! conn
                 (vec (for [_ (range n)]
                        {:object/id (random-uuid)
                         :object/card card-eid
                         :object/zone :hand
                         :object/owner player-eid
                         :object/controller player-eid
                         :object/tapped false})))
    @conn))


(defn set-max-hand-size
  "Set the max hand size for a player."
  [db player-id max-size]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid @conn player-id)]
    (d/transact! conn [[:db/add player-eid :player/max-hand-size max-size]])
    @conn))


(defn add-expiring-player-grant
  "Add a grant to a player that expires at the given turn/phase."
  [db player-id turn phase]
  (grants/add-player-grant db player-id
                           {:grant/id (random-uuid)
                            :grant/type :test-grant
                            :grant/expires {:expires/turn turn :expires/phase phase}}))


;; === Cleanup discard tests ===

(deftest test-cleanup-no-discard-expires-grants
  (testing "With hand <= max, begin-cleanup expires grants without selection"
    (let [;; init-game-state creates 1 Dark Ritual in hand (well under 7)
          db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-turn 1)
                 (add-expiring-player-grant :player-1 1 :cleanup))
          result (game/begin-cleanup db :player-1)]
      (is (nil? (:pending-selection result))
          "should not create pending selection")
      (is (empty? (grants/get-player-grants (:db result) :player-1))
          "grants should be expired"))))


(deftest test-cleanup-hand-at-max-no-discard
  (testing "With hand exactly at max (7), no selection created"
    (let [;; init-game-state has 1 card, add 6 more = 7 total
          db (-> (init-game-state)
                 (add-cards-to-hand :player-1 6)
                 (set-phase :cleanup)
                 (set-turn 1))
          result (game/begin-cleanup db :player-1)]
      (is (nil? (:pending-selection result))
          "hand at exactly max should not trigger discard"))))


(deftest test-cleanup-over-max-creates-selection
  (testing "With hand > max, pending selection created with correct count"
    (let [;; init-game-state has 1 card, add 8 more = 9 total
          db (-> (init-game-state)
                 (add-cards-to-hand :player-1 8)
                 (set-phase :cleanup)
                 (set-turn 1))
          result (game/begin-cleanup db :player-1)
          selection (:pending-selection result)]
      (is (some? selection)
          "should create pending selection")
      (is (= 2 (:selection/select-count selection))
          "should discard 2 cards (9 - 7)")
      (is (= :hand (:selection/zone selection))
          "should select from hand")
      (is (= :cleanup-discard (:selection/effect-type selection))
          "should have cleanup-discard effect type")
      (is (= :player-1 (:selection/player-id selection))
          "should be for player-1"))))


(deftest test-cleanup-selection-uses-max-hand-size-field
  (testing "Selection count reads from :player/max-hand-size, not hardcoded 7"
    (let [;; 6 cards in hand, max hand size = 5 → discard 1
          db (-> (init-game-state)
                 (add-cards-to-hand :player-1 5)  ; 6 total
                 (set-max-hand-size :player-1 5)
                 (set-phase :cleanup)
                 (set-turn 1))
          result (game/begin-cleanup db :player-1)
          selection (:pending-selection result)]
      (is (some? selection)
          "should create selection when over custom max")
      (is (= 1 (:selection/select-count selection))
          "should discard 1 card (6 - 5)"))))


(deftest test-cleanup-confirm-discards-to-graveyard
  (testing "Confirming cleanup discard moves selected cards to graveyard"
    (let [;; 9 cards in hand (1 original + 8 added), need to discard 2
          db (-> (init-game-state)
                 (add-cards-to-hand :player-1 8)
                 (set-phase :cleanup)
                 (set-turn 1))
          hand (q/get-hand db :player-1)
          ;; Pick first 2 cards to discard
          to-discard (set (map :object/id (take 2 hand)))
          result (game/complete-cleanup-discard db :player-1 to-discard)
          new-hand (q/get-hand (:db result) :player-1)
          graveyard (q/get-objects-in-zone (:db result) :player-1 :graveyard)]
      (is (= 7 (count new-hand))
          "hand should have 7 cards after discarding 2")
      (is (= 2 (count graveyard))
          "graveyard should have 2 discarded cards")
      (is (every? #(contains? to-discard (:object/id %)) graveyard)
          "discarded cards should be in graveyard"))))


(deftest test-cleanup-confirm-expires-grants
  (testing "After confirming discard, grants are expired"
    (let [db (-> (init-game-state)
                 (add-cards-to-hand :player-1 8)  ; 9 total
                 (set-phase :cleanup)
                 (set-turn 1)
                 (add-expiring-player-grant :player-1 1 :cleanup))
          hand (q/get-hand db :player-1)
          to-discard (set (map :object/id (take 2 hand)))
          result (game/complete-cleanup-discard db :player-1 to-discard)]
      (is (empty? (grants/get-player-grants (:db result) :player-1))
          "grants should be expired after cleanup discard"))))


(deftest test-cleanup-confirm-no-spell-cleanup
  (testing "Cleanup discard does not try to clean up a spell (no spell-id)"
    (let [db (-> (init-game-state)
                 (add-cards-to-hand :player-1 8)
                 (set-phase :cleanup)
                 (set-turn 1))
          hand (q/get-hand db :player-1)
          to-discard (set (map :object/id (take 2 hand)))
          result (game/complete-cleanup-discard db :player-1 to-discard)]
      (is (= 7 (count (q/get-hand (:db result) :player-1)))
          "hand should have 7 cards after discarding 2")
      (is (= 2 (count (q/get-objects-in-zone (:db result) :player-1 :graveyard)))
          "discarded cards should be in graveyard"))))


(deftest test-cleanup-advance-blocked-during-selection
  (testing "Cannot advance phase when cleanup discard selection is pending"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-turn 1)
                 (add-cards-to-hand :player-1 8))
          result (game/begin-cleanup db :player-1)]
      (is (some? (:pending-selection result))
          "begin-cleanup should create a pending selection for discard")
      (is (= :cleanup-discard (:selection/effect-type (:pending-selection result)))
          "selection should be for cleanup discard")
      (let [cleanup-db (:db result)
            after-advance (game/advance-phase cleanup-db :player-1)]
        (is (= :cleanup (get-phase after-advance))
            "should stay at cleanup, cannot advance past it")))))


;; === Cleanup repeat tests ===

(defn make-app-db
  "Build a minimal app-db shape for testing maybe-continue-cleanup.
   Takes a game-db (Datascript) and optional pending-selection."
  ([game-db]
   {:game/db game-db})
  ([game-db pending-selection]
   {:game/db game-db
    :game/pending-selection pending-selection}))


(deftest test-cleanup-repeat-after-trigger-resolves
  (testing "After last trigger resolves during cleanup, begin-cleanup re-runs"
    (let [;; Set up cleanup phase with empty stack, hand under max
          game-db (-> (init-game-state)
                      (set-phase :cleanup)
                      (set-turn 1)
                      (add-expiring-player-grant :player-1 1 :cleanup))
          app-db (make-app-db game-db)
          result (game/maybe-continue-cleanup app-db)]
      ;; begin-cleanup should have run and expired the grant
      (is (empty? (grants/get-player-grants (:game/db result) :player-1))
          "grants should be expired after cleanup re-run"))))


(deftest test-cleanup-no-repeat-when-stack-not-empty
  (testing "Cleanup restart does NOT fire when stack still has items"
    (let [;; Cleanup phase with trigger still on stack
          game-db (-> (init-game-state)
                      (set-phase :cleanup)
                      (set-turn 1)
                      (add-expiring-player-grant :player-1 1 :cleanup)
                      (add-stack-item-to-stack))
          app-db (make-app-db game-db)
          result (game/maybe-continue-cleanup app-db)]
      ;; Should NOT re-run begin-cleanup while stack has items
      (is (seq (grants/get-player-grants (:game/db result) :player-1))
          "grants should NOT be expired - stack still has items"))))


(deftest test-cleanup-repeat-does-not-trigger-at-other-phases
  (testing "maybe-continue-cleanup is a no-op during non-cleanup phases"
    (let [game-db (-> (init-game-state)
                      (set-phase :main1)
                      (set-turn 1)
                      (add-expiring-player-grant :player-1 1 :cleanup))
          app-db (make-app-db game-db)
          result (game/maybe-continue-cleanup app-db)]
      (is (= game-db (:game/db result))
          "game-db should be unchanged during non-cleanup phase")
      (is (seq (grants/get-player-grants (:game/db result) :player-1))
          "grants should NOT be expired during non-cleanup phase"))))


(deftest test-cleanup-repeat-rechecks-hand-size
  (testing "Cleanup repeat rechecks hand size and prompts discard if over max"
    (let [;; Cleanup phase, hand at 9 cards (over max of 7)
          game-db (-> (init-game-state)
                      (add-cards-to-hand :player-1 8)  ; 9 total
                      (set-phase :cleanup)
                      (set-turn 1))
          app-db (make-app-db game-db)
          result (game/maybe-continue-cleanup app-db)]
      ;; Should re-run begin-cleanup which creates selection for discard
      (is (some? (:game/pending-selection result))
          "should create pending selection for discard")
      (is (= 2 (:selection/select-count (:game/pending-selection result)))
          "should need to discard 2 cards (9 - 7)"))))
