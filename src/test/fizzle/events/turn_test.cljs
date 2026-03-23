(ns fizzle.events.turn-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.phases :as phases]))


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
          db' (phases/advance-phase db :player-1)]
      (is (= :main1 (get-phase db')) "phase should not advance")
      (is (not (mana-pool-empty? db' :player-1)) "mana pool should not be cleared"))))


(deftest test-advance-phase-blocked-by-spell-on-stack
  (testing "advance-phase no-ops when spell is on the stack"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-spell-to-stack :player-1))
          db' (phases/advance-phase db :player-1)]
      (is (= :main1 (get-phase db')) "phase should not advance"))))


(deftest test-advance-phase-blocked-by-both-triggers-and-spells
  (testing "advance-phase no-ops when both triggers and spells are on the stack"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-stack-item-to-stack)
                 (add-spell-to-stack :player-1))
          db' (phases/advance-phase db :player-1)]
      (is (= :main1 (get-phase db')) "phase should not advance"))))


(deftest test-start-turn-blocked-by-stack-items
  (testing "start-turn no-ops when stack has items"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-turn 1)
                 (add-stack-item-to-stack))
          db' (phases/start-turn db :player-1)]
      (is (= :cleanup (get-phase db')) "phase should stay at cleanup")
      (is (= 1 (get-turn db')) "turn should not increment"))))


;; === advance-phase tests ===

(deftest test-advance-phase-from-main1-skips-combat-without-creatures
  (testing "advance-phase from main1 skips combat to main2 when no creatures"
    (let [db (-> (init-game-state)
                 (set-phase :main1))
          db' (phases/advance-phase db :player-1)]
      (is (= :main2 (get-phase db'))))))


(deftest test-advance-phase-from-combat-to-main2
  (testing "advance-phase from combat goes to main2"
    (let [db (-> (init-game-state)
                 (set-phase :combat))
          db' (phases/advance-phase db :player-1)]
      (is (= :main2 (get-phase db'))))))


(deftest test-advance-phase-from-cleanup-stays-cleanup
  (testing "advance-phase from cleanup stays at cleanup (requires explicit start-turn)"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup))
          db' (phases/advance-phase db :player-1)]
      ;; CRITICAL: Must NOT auto-advance to untap - need explicit start-turn
      (is (= :cleanup (get-phase db'))))))


(deftest test-advance-phase-clears-mana-pool
  (testing "advance-phase clears player's mana pool"
    (let [db (-> (init-game-state)
                 (set-phase :main1)
                 (add-mana :player-1 {:black 3 :blue 2}))
          db' (phases/advance-phase db :player-1)]
      (is (mana-pool-empty? db' :player-1)))))


;; === start-turn tests ===

(deftest test-start-turn-increments-turn-number
  (testing "start-turn increments turn from 1 to 2"
    (let [db (-> (init-game-state)
                 (set-turn 1)
                 (set-phase :cleanup))
          db' (phases/start-turn db :player-1)]
      (is (= 2 (get-turn db'))))))


(deftest test-start-turn-resets-storm-count
  (testing "start-turn resets storm count to 0"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-storm-count :player-1 5))
          db' (phases/start-turn db :player-1)]
      (is (= 0 (q/get-storm-count db' :player-1))))))


(deftest test-start-turn-resets-land-plays-to-one
  (testing "start-turn resets land-plays-left to 1"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-land-plays :player-1 0))
          db' (phases/start-turn db :player-1)]
      (is (= 1 (get-land-plays db' :player-1))))))


(deftest test-start-turn-sets-phase-to-untap
  (testing "start-turn sets phase to :untap"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup))
          db' (phases/start-turn db :player-1)]
      (is (= :untap (get-phase db'))))))


(deftest test-start-turn-clears-mana-pool
  (testing "start-turn clears player's mana pool"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (add-mana :player-1 {:red 2 :green 1}))
          db' (phases/start-turn db :player-1)]
      (is (mana-pool-empty? db' :player-1)))))


;; === Full phase sequence test ===

(deftest test-full-phase-sequence-no-creatures
  (testing "phases advance with combat skipped when no creatures: untap → upkeep → draw → main1 → main2 → end → cleanup"
    (let [db (-> (init-game-state)
                 (set-phase :untap))
          ;; Advance through all phases (combat skipped — no creatures)
          after-upkeep (phases/advance-phase db :player-1)
          after-draw (phases/advance-phase after-upkeep :player-1)
          after-main1 (phases/advance-phase after-draw :player-1)
          ;; main1 -> main2 (combat skipped)
          after-main2 (phases/advance-phase after-main1 :player-1)
          after-end (phases/advance-phase after-main2 :player-1)
          after-cleanup (phases/advance-phase after-end :player-1)]
      (is (= :upkeep (get-phase after-upkeep)))
      (is (= :draw (get-phase after-draw)))
      (is (= :main1 (get-phase after-main1)))
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
          result (cleanup/begin-cleanup db :player-1)]
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
          result (cleanup/begin-cleanup db :player-1)]
      (is (nil? (:pending-selection result))
          "hand at exactly max should not trigger discard"))))


(deftest test-cleanup-over-max-creates-selection
  (testing "With hand > max, pending selection created with correct count"
    (let [;; init-game-state has 1 card, add 8 more = 9 total
          db (-> (init-game-state)
                 (add-cards-to-hand :player-1 8)
                 (set-phase :cleanup)
                 (set-turn 1))
          result (cleanup/begin-cleanup db :player-1)
          selection (:pending-selection result)]
      (is (= 2 (:selection/select-count selection))
          "should discard 2 cards (9 - 7)")
      (is (= :hand (:selection/zone selection))
          "should select from hand")
      (is (= :discard (:selection/type selection))
          "should have discard type with cleanup? flag")
      (is (true? (:selection/cleanup? selection))
          "should be marked as cleanup discard")
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
          result (cleanup/begin-cleanup db :player-1)
          selection (:pending-selection result)]
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
          result (cleanup/complete-cleanup-discard db :player-1 to-discard)
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
          result (cleanup/complete-cleanup-discard db :player-1 to-discard)]
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
          result (cleanup/complete-cleanup-discard db :player-1 to-discard)]
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
          result (cleanup/begin-cleanup db :player-1)]
      (is (= :discard (:selection/type (:pending-selection result)))
          "selection should be for cleanup discard")
      (let [cleanup-db (:db result)
            after-advance (phases/advance-phase cleanup-db :player-1)]
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
          result (cleanup/maybe-continue-cleanup app-db)]
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
          result (cleanup/maybe-continue-cleanup app-db)]
      ;; Should NOT re-run begin-cleanup while stack has items
      (is (= 1 (count (grants/get-player-grants (:game/db result) :player-1)))
          "grants should NOT be expired - stack still has items"))))


(deftest test-cleanup-repeat-does-not-trigger-at-other-phases
  (testing "maybe-continue-cleanup is a no-op during non-cleanup phases"
    (let [game-db (-> (init-game-state)
                      (set-phase :main1)
                      (set-turn 1)
                      (add-expiring-player-grant :player-1 1 :cleanup))
          app-db (make-app-db game-db)
          result (cleanup/maybe-continue-cleanup app-db)]
      (is (= game-db (:game/db result))
          "game-db should be unchanged during non-cleanup phase")
      (is (= 1 (count (grants/get-player-grants (:game/db result) :player-1)))
          "grants should NOT be expired during non-cleanup phase"))))


(deftest test-cleanup-repeat-rechecks-hand-size
  (testing "Cleanup repeat rechecks hand size and prompts discard if over max"
    (let [;; Cleanup phase, hand at 9 cards (over max of 7)
          game-db (-> (init-game-state)
                      (add-cards-to-hand :player-1 8)  ; 9 total
                      (set-phase :cleanup)
                      (set-turn 1))
          app-db (make-app-db game-db)
          result (cleanup/maybe-continue-cleanup app-db)]
      ;; Should re-run begin-cleanup which creates selection for discard
      (is (= 2 (:selection/select-count (:game/pending-selection result)))
          "should need to discard 2 cards (9 - 7)"))))


;; === Opponent draw step helpers ===

(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])
    @conn))


(defn add-library-cards
  "Add N cards to a player's library with sequential positions."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)]
    (d/transact! conn (vec (for [i (range n)]
                             {:object/id (random-uuid)
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/position i
                              :object/tapped false})))
    @conn))


(defn count-zone
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (q/get-objects-in-zone db player-id zone)))


(defn get-loss-condition
  "Get the game loss condition."
  [db]
  (:game/loss-condition (q/get-game-state db)))


(defn get-winner
  "Get the winner player-id from game state."
  [db]
  (let [game (q/get-game-state db)
        winner-ref (:game/winner game)]
    (when winner-ref
      (d/q '[:find ?pid .
             :in $ ?eid
             :where [?eid :player/id ?pid]]
           db (:db/id winner-ref)))))


;; === Opponent draw step tests ===
;; Opponent draws via draw-step trigger on their own draw phase.
;; start-turn switches active player to opponent, advance-phase to draw fires trigger.

(deftest test-opponent-draws-card-on-draw-phase
  (testing "Opponent draws a card when their draw phase is entered via trigger"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-library-cards :player-2 5))
          ;; Add draw-step trigger for opponent
          conn (d/conn-from-db db)
          opp-eid (q/get-player-eid @conn :player-2)
          _ (d/transact! conn (fizzle.engine.turn-based/create-turn-based-triggers-tx opp-eid :player-2))
          db (d/db-with @conn [])
          ;; Set up: switch to opponent's turn at untap
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/phase :upkeep]
                            [:db/add game-eid :game/turn 2]])
          lib-before (count-zone db :player-2 :library)
          hand-before (count-zone db :player-2 :hand)
          ;; Advance to draw phase — triggers opponent draw
          db' (phases/advance-phase db :player-2)]
      (is (= (dec lib-before) (count-zone db' :player-2 :library))
          "opponent library should shrink by 1")
      (is (= (inc hand-before) (count-zone db' :player-2 :hand))
          "opponent hand should grow by 1"))))


(deftest test-opponent-empty-library-draw-sets-loss
  (testing "Opponent drawing from empty library on their draw phase sets loss condition"
    (let [db (-> (init-game-state)
                 (add-opponent))
          ;; Add draw-step trigger for opponent (no library cards)
          conn (d/conn-from-db db)
          opp-eid (q/get-player-eid @conn :player-2)
          _ (d/transact! conn (fizzle.engine.turn-based/create-turn-based-triggers-tx opp-eid :player-2))
          db @conn
          ;; Set up opponent's turn at upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/phase :upkeep]
                            [:db/add game-eid :game/turn 2]])
          ;; Advance to draw — triggers draw from empty library
          db' (-> (phases/advance-phase db :player-2)
                  (sba/check-and-execute-sbas))]
      (is (= :empty-library (get-loss-condition db'))
          "loss condition should be :empty-library")
      (is (= :player-1 (get-winner db'))
          "player should win when opponent draws from empty library"))))


(deftest test-opponent-partial-library-draws-until-empty
  (testing "Opponent with 1 card draws it, next turn draw fails and triggers loss"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (add-library-cards :player-2 1))
          ;; Add draw-step trigger for opponent
          conn (d/conn-from-db db)
          opp-eid (q/get-player-eid @conn :player-2)
          _ (d/transact! conn (fizzle.engine.turn-based/create-turn-based-triggers-tx opp-eid :player-2))
          db @conn
          ;; First opponent draw phase: draws last card
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/phase :upkeep]
                            [:db/add game-eid :game/turn 2]])
          db-after-draw (phases/advance-phase db :player-2)]
      (is (= 0 (count-zone db-after-draw :player-2 :library))
          "opponent library should be empty after drawing last card")
      (is (nil? (get-loss-condition db-after-draw))
          "no loss condition yet — drawing last card is fine")
      ;; Second opponent draw phase: draws from empty library → loss
      (let [db-at-upkeep (d/db-with db-after-draw
                                    [[:db/add game-eid :game/phase :upkeep]
                                     [:db/add game-eid :game/turn 4]])
            db-after-draw-2 (-> (phases/advance-phase db-at-upkeep :player-2)
                                (sba/check-and-execute-sbas))]
        (is (= :empty-library (get-loss-condition db-after-draw-2))
            "loss condition should fire on failed draw")
        (is (= :player-1 (get-winner db-after-draw-2))
            "player should win when opponent draws from empty library")))))


(deftest test-existing-start-turn-unaffected-without-opponent
  (testing "start-turn still works normally when no opponent exists"
    (let [db (-> (init-game-state)
                 (set-phase :cleanup)
                 (set-turn 1))
          db' (phases/start-turn db :player-1)]
      (is (= 2 (get-turn db'))
          "turn should increment normally")
      (is (= :untap (get-phase db'))
          "phase should be :untap"))))
