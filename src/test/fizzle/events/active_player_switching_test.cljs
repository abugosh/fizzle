(ns fizzle.events.active-player-switching-test
  "Tests for active player switching at turn boundaries.
   Verifies opponent gets real turns with the same code path as player."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot]
    [fizzle.db.queries :as q]
    [fizzle.events.init :as init]
    [fizzle.events.phases :as phases]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register interceptors for dispatch-sync tests
(interceptor/register!)


(defn- setup-app-db
  "Create a full app-db with two players (player + goldfish bot),
   player stops at main1+main2, starting at main1.
   Both players get library cards so draw-step triggers don't hit game-over."
  ([]
   (setup-app-db {}))
  ([opts]
   (h/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-yield-all
  "Dispatch ::yield-all through re-frame and return the resulting app-db.
   The game director runs synchronously, so a single dispatch-sync is sufficient.
   The director handles bot actions and phase advancement inline."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::priority-flow/yield-all])
  @rf-db/app-db)


;; === start-turn switches active player ===

(deftest start-turn-switches-active-player
  (testing "start-turn alternates active player between player and opponent"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Advance to cleanup so start-turn will work
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          game-db (d/db-with db [[:db/add game-eid :game/phase :cleanup]])
          ;; Start turn (should switch to opponent since player-1 just finished)
          result-db (phases/start-turn game-db :player-1)]
      (is (= :player-2 (q/get-active-player-id result-db))
          "Active player should switch to opponent after player's turn")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Turn should increment to 2"))))


(deftest start-turn-switches-back-to-player
  (testing "start-turn switches active player back to player after opponent's turn"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Set active player to opponent and advance to cleanup
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          game-db (d/db-with db [[:db/add game-eid :game/phase :cleanup]
                                 [:db/add game-eid :game/active-player opp-eid]])
          ;; Start turn (should switch back to player-1)
          result-db (phases/start-turn game-db :player-2)]
      (is (= :player-1 (q/get-active-player-id result-db))
          "Active player should switch back to player-1 after opponent's turn")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Turn should increment"))))


;; === Shared MTG-correct turn numbering ===

(deftest turn-numbering-shared-mtg-correct
  (testing "Turn 1 = player, Turn 2 = opponent, Turn 3 = player"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          game-state (q/get-game-state db)]
      ;; Turn 1 starts with player-1
      (is (= 1 (:game/turn game-state)))
      (is (= :player-1 (q/get-active-player-id db))))))


;; === opponent-draw removed ===

(deftest no-opponent-draw-hack
  (testing "start-turn does not call opponent-draw (removed hack)"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Give opponent a library to draw from
          [db' _] (h/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-2)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db')
          game-db (d/db-with db' [[:db/add game-eid :game/phase :cleanup]])
          opp-hand-before (count (or (q/get-hand game-db :player-2) []))
          ;; Start turn switches to opponent but does NOT draw for them
          result-db (phases/start-turn game-db :player-1)]
      ;; Opponent draw should happen via draw-step trigger on opponent's draw phase,
      ;; not via start-turn. The untap phase-entered event fires, not draw.
      (is (= opp-hand-before (count (or (q/get-hand result-db :player-2) [])))
          "Opponent should NOT draw during start-turn (draw happens via trigger on draw phase)"))))


;; === Opponent gets turn-based triggers ===

(deftest opponent-has-turn-based-triggers
  (testing "opponent has draw-step and untap-step triggers"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Check that opponent has triggers
          opp-eid (q/get-player-eid db :player-2)
          triggers (d/q '[:find [(pull ?t [*]) ...]
                          :in $ ?controller
                          :where [?t :trigger/controller ?controller]]
                        db opp-eid)
          trigger-types (set (map :trigger/type triggers))]
      (is (contains? trigger-types :draw-step)
          "Opponent should have a draw-step trigger")
      (is (contains? trigger-types :untap-step)
          "Opponent should have an untap-step trigger"))))


;; === Full turn cycle integration ===

(deftest integration-full-turn-cycle-player-to-opponent-to-player
  (testing "yield-all advances through full cycle: player turn -> opponent turn -> player turn"
    (let [app-db (setup-app-db)
          ;; Give opponent library cards so draw doesn't fail
          game-db (:game/db app-db)
          [game-db' _] (h/add-cards-to-library game-db [:dark-ritual :dark-ritual :dark-ritual] :player-2)
          app-db (assoc app-db :game/db game-db')
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; F6 should advance through player's turn AND opponent's turn, landing on player's next turn
      (is (= :player-1 (q/get-active-player-id result-db))
          "Should end on player's turn after full cycle")
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should be turn 3 (player T1 -> opponent T2 -> player T3)"))))


(deftest integration-yield-advances-through-opponent-turn
  (testing "yield-all from player's last phase advances through opponent's turn to player's next stop"
    (let [app-db (setup-app-db {:stops #{:main1 :main2}})
          game-db (:game/db app-db)
          ;; Give opponent library cards
          [game-db' _] (h/add-cards-to-library game-db [:dark-ritual :dark-ritual :dark-ritual] :player-2)
          ;; Advance to main2 manually
          game-db' (-> game-db'
                       (phases/advance-phase :player-1)   ; main1 -> combat
                       (phases/advance-phase :player-1))   ; combat -> main2
          app-db (assoc app-db :game/db game-db')
          result (dispatch-yield-all app-db)
          result-db (:game/db result)]
      ;; yield-all from main2 should go through end, cleanup, cross turn boundary to opponent,
      ;; auto-advance through opponent's full turn, cross back to player, stop at main1
      (is (= :player-1 (q/get-active-player-id result-db))
          "Should end on player's turn")
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should stop at player's main1 stop"))))


;; === History records opponent turn actions ===

(deftest integration-history-records-opponent-actions
  (testing "opponent turn actions appear as separate history entries via interceptor"
    (let [app-db (setup-app-db {:stops #{:main1 :main2}})
          game-db (:game/db app-db)
          ;; Give opponent library cards so draw triggers
          [game-db' _] (h/add-cards-to-library game-db [:dark-ritual :dark-ritual :dark-ritual] :player-2)
          ;; Advance to main2
          game-db' (-> game-db'
                       (phases/advance-phase :player-1)
                       (phases/advance-phase :player-1))
          app-db (assoc app-db :game/db game-db')
          entries-before (count (history/effective-entries app-db))
          result (dispatch-yield-all app-db)
          entries-after (count (history/effective-entries result))]
      ;; Should have multiple new history entries (one per yield dispatch through opponent's turn)
      (is (> entries-after entries-before)
          "Should have new history entries from opponent turn"))))


;; === Opponent draws during their turn ===

(deftest opponent-draws-card-during-turn
  (testing "opponent draws a card when their turn reaches the draw phase"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          ;; Give opponent library cards so draw has something to draw
          [game-db' _] (h/add-cards-to-library game-db [:dark-ritual :dark-ritual :dark-ritual] :player-2)
          app-db (assoc app-db :game/db game-db')
          opp-library-before (count (q/get-top-n-library (:game/db app-db) :player-2 100))
          result (dispatch-yield-all app-db)
          result-db (:game/db result)
          opp-library-after (count (q/get-top-n-library result-db :player-2 100))]
      ;; After a full cycle (player T1 -> opponent T2 -> player T3),
      ;; opponent drew 1 card on draw step. May have also played a land (-1).
      ;; Library is the reliable indicator since cards only leave library via draw.
      (is (= (dec opp-library-before) opp-library-after)
          (str "Opponent library should decrease by 1 (drew a card). "
               "Before: " opp-library-before " After: " opp-library-after)))))


(deftest advance-phase-fires-draw-for-opponent
  (testing "advance-phase through :draw fires draw-step trigger for :player-2"
    (let [app-db (init/init-game-state {:main-deck [{:card/id :island :count 60}]
                                        :bot-deck (bot/bot-deck :goldfish)})
          game-db (:game/db app-db)
          ;; Set active player to opponent at :upkeep (one step before :draw)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] game-db)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add game-eid :game/active-player opp-eid]
                                      [:db/add game-eid :game/phase :upkeep]
                                      [:db/add game-eid :game/turn 2]])
          opp-hand-before (count (q/get-hand game-db :player-2))
          opp-library-before (count (q/get-top-n-library game-db :player-2 100))
          ;; Advance one phase: upkeep -> draw
          result-db (phases/advance-phase game-db :player-2)
          opp-hand-after (count (q/get-hand result-db :player-2))
          opp-library-after (count (q/get-top-n-library result-db :player-2 100))]
      (is (= :draw (:game/phase (q/get-game-state result-db)))
          "Phase should be :draw")
      (is (= (inc opp-hand-before) opp-hand-after)
          (str "Opponent should draw 1 card. Hand: " opp-hand-before " -> " opp-hand-after))
      (is (= (dec opp-library-before) opp-library-after)
          (str "Library should decrease by 1. Library: " opp-library-before " -> " opp-library-after)))))


(deftest opponent-draws-card-production-init
  (testing "opponent draws using production init-game-state (player-id :player-2)"
    (let [app-db (init/init-game-state {:main-deck [{:card/id :island :count 60}]
                                        :bot-deck (bot/bot-deck :goldfish)})
          ;; Accept the opening hand to get into gameplay
          app-db (assoc app-db :active-screen :game)
          app-db (merge app-db (history/init-history))
          game-db (:game/db app-db)
          opp-library-before (count (q/get-top-n-library game-db :player-2 100))
          result (dispatch-yield-all app-db)
          result-db (:game/db result)
          opp-library-after (count (q/get-top-n-library result-db :player-2 100))]
      (is (= 3 (:game/turn (q/get-game-state result-db)))
          "Should reach turn 3 after full cycle")
      ;; Library is the reliable draw indicator (bot may play a land from hand)
      (is (= (dec opp-library-before) opp-library-after)
          (str "Opponent library should decrease by 1 (drew a card). "
               "Before: " opp-library-before " after: " opp-library-after)))))
