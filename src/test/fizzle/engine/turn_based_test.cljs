(ns fizzle.engine.turn-based-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :refer [dark-ritual]]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.turn-based :as turn-based]))


;; === Test helpers ===

(def empty-mana-pool
  {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0})


(defn init-test-game
  "Create a test game state with a player, library, and turn-based triggers.
   Returns immutable db value."
  []
  (let [conn (d/create-conn schema)]
    ;; Card definition
    (d/transact! conn [dark-ritual])
    ;; Player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool empty-mana-pool
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)
          card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] @conn)]
      ;; Create library with 10 cards
      (d/transact! conn
                   (vec (for [i (range 10)]
                          {:object/id (random-uuid)
                           :object/card card-eid
                           :object/zone :library
                           :object/owner player-eid
                           :object/controller player-eid
                           :object/tapped false
                           :object/position i})))
      ;; Game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}])
      ;; Turn-based triggers in Datascript
      (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid :player-1)))
    @conn))


(defn set-turn
  "Set the game turn number."
  [db turn]
  (let [conn (d/conn-from-db db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] @conn)]
    (d/transact! conn [[:db/add game-eid :game/turn turn]])
    @conn))


(defn get-hand-count
  "Get number of cards in player's hand."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (count (d/q '[:find ?e
                  :in $ ?owner
                  :where [?e :object/owner ?owner]
                  [?e :object/zone :hand]]
                db player-eid))))


(defn get-library-count
  "Get number of cards in player's library."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (count (d/q '[:find ?e
                  :in $ ?owner
                  :where [?e :object/owner ?owner]
                  [?e :object/zone :library]]
                db player-eid))))


(defn add-tapped-permanent
  "Add a tapped permanent to the battlefield."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] @conn)]
    (d/transact! conn [{:object/id (random-uuid)
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped true}])
    @conn))


(defn get-tapped-permanents
  "Get all tapped permanents controlled by player."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (d/q '[:find ?e
           :in $ ?controller
           :where [?e :object/controller ?controller]
           [?e :object/zone :battlefield]
           [?e :object/tapped true]]
         db player-eid)))


(defn get-loss-condition
  "Get the game loss condition if any."
  [db]
  (:game/loss-condition (q/get-game-state db)))


(defn clear-library
  "Remove all cards from player's library."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        library-eids (d/q '[:find [?e ...]
                            :in $ ?owner
                            :where [?e :object/owner ?owner]
                            [?e :object/zone :library]]
                          @conn player-eid)]
    (d/transact! conn (mapv (fn [eid] [:db/retractEntity eid]) library-eids))
    @conn))


;; === Registration tests ===

(deftest test-register-turn-based-actions
  (testing "init-test-game creates draw and untap triggers in Datascript"
    (let [db (init-test-game)
          all-triggers (trigger-db/get-all-triggers db)
          trigger-types (set (map :trigger/type all-triggers))]
      (is (contains? trigger-types :draw-step))
      (is (contains? trigger-types :untap-step)))))


(deftest test-draw-trigger-has-correct-filter
  (testing "draw trigger has filter for draw phase and player"
    (let [db (init-test-game)
          all-triggers (trigger-db/get-all-triggers db)
          draw-trigger (first (filter #(= :draw-step (:trigger/type %)) all-triggers))]
      (is (= {:event/phase :draw :event/player :player-1} (:trigger/filter draw-trigger))))))


(deftest test-untap-trigger-has-correct-filter
  (testing "untap trigger has filter for untap phase and player"
    (let [db (init-test-game)
          all-triggers (trigger-db/get-all-triggers db)
          untap-trigger (first (filter #(= :untap-step (:trigger/type %)) all-triggers))]
      (is (= {:event/phase :untap :event/player :player-1} (:trigger/filter untap-trigger))))))


(deftest test-turn-based-triggers-dont-use-stack
  (testing "turn-based actions execute immediately (uses-stack? false)"
    (let [db (init-test-game)
          all-triggers (trigger-db/get-all-triggers db)]
      (is (every? #(false? (:trigger/uses-stack? %)) all-triggers)))))


(deftest test-cleanup-grants-handled-by-advance-phase
  (testing "cleanup grant expiration is handled by advance-phase handler, not auto-trigger"
    (let [db (init-test-game)
          all-triggers (trigger-db/get-all-triggers db)
          cleanup-trigger (first (filter #(= :cleanup-expire-grants (:trigger/type %)) all-triggers))]
      (is (nil? cleanup-trigger)
          "Cleanup expire-grants trigger should NOT be registered (handled by advance-phase)"))))


;; === Draw step tests ===

(deftest test-draw-step-skips-turn-1
  (testing "draw step doesn't draw on turn 1 (play/draw rule)"
    (let [db (-> (init-test-game)
                 (set-turn 1))
          initial-hand (get-hand-count db :player-1)
          event (game-events/phase-entered-event :draw 1 :player-1)
          db' (dispatch/dispatch-event db event)]
      (is (= initial-hand (get-hand-count db' :player-1))
          "Hand size should not change on turn 1"))))


(deftest test-draw-step-draws-card-turn-2
  (testing "draw step draws one card on turn 2+"
    (let [db (-> (init-test-game)
                 (set-turn 2))
          initial-hand (get-hand-count db :player-1)
          initial-library (get-library-count db :player-1)
          event (game-events/phase-entered-event :draw 2 :player-1)
          db' (dispatch/dispatch-event db event)]
      (is (= (inc initial-hand) (get-hand-count db' :player-1))
          "Hand size should increase by 1")
      (is (= (dec initial-library) (get-library-count db' :player-1))
          "Library size should decrease by 1"))))


(deftest test-draw-step-draws-card-turn-10
  (testing "draw step draws one card on later turns"
    (let [db (-> (init-test-game)
                 (set-turn 10))
          initial-hand (get-hand-count db :player-1)
          event (game-events/phase-entered-event :draw 10 :player-1)
          db' (dispatch/dispatch-event db event)]
      (is (= (inc initial-hand) (get-hand-count db' :player-1))))))


(deftest test-draw-step-empty-library-sets-loss-condition
  (testing "draw step with empty library sets loss condition"
    (let [db (-> (init-test-game)
                 (set-turn 2)
                 (clear-library :player-1))
          event (game-events/phase-entered-event :draw 2 :player-1)
          db' (dispatch/dispatch-event db event)]
      (is (= :empty-library (get-loss-condition db'))
          "Loss condition should be set when drawing from empty library"))))


(deftest test-draw-step-doesnt-fire-on-other-phases
  (testing "draw trigger doesn't fire on non-draw phases"
    (let [db (-> (init-test-game)
                 (set-turn 2))
          initial-hand (get-hand-count db :player-1)]
      ;; Test upkeep phase
      (let [upkeep-event (game-events/phase-entered-event :upkeep 2 :player-1)
            db-upkeep (dispatch/dispatch-event db upkeep-event)]
        (is (= initial-hand (get-hand-count db-upkeep :player-1))
            "Hand should not change on upkeep"))
      ;; Test main1 phase
      (let [main1-event (game-events/phase-entered-event :main1 2 :player-1)
            db-main1 (dispatch/dispatch-event db main1-event)]
        (is (= initial-hand (get-hand-count db-main1 :player-1))
            "Hand should not change on main1")))))


;; === Untap step tests ===

(deftest test-untap-step-untaps-all-permanents
  (testing "untap step untaps all tapped permanents"
    (let [db (-> (init-test-game)
                 (add-tapped-permanent :player-1)
                 (add-tapped-permanent :player-1)
                 (add-tapped-permanent :player-1))
          tapped-before (get-tapped-permanents db :player-1)
          _ (is (= 3 (count tapped-before)) "Should have 3 tapped permanents before")
          event (game-events/phase-entered-event :untap 1 :player-1)
          db' (dispatch/dispatch-event db event)
          tapped-after (get-tapped-permanents db' :player-1)]
      (is (= 0 (count tapped-after))
          "All permanents should be untapped after untap step"))))


(deftest test-untap-step-empty-battlefield
  (testing "untap step handles empty battlefield gracefully"
    (let [db (init-test-game)
          tapped-before (get-tapped-permanents db :player-1)
          _ (is (= 0 (count tapped-before)) "Should have no permanents")
          event (game-events/phase-entered-event :untap 1 :player-1)
          db' (dispatch/dispatch-event db event)]
      ;; Should not throw, db should be unchanged
      (is (some? db') "Should return valid db"))))


(deftest test-untap-step-only-untaps-controlled-permanents
  (testing "untap step only untaps permanents controlled by active player"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn [dark-ritual])
          _ (d/transact! conn [{:player/id :player-1
                                :player/name "Player"
                                :player/life 20
                                :player/mana-pool empty-mana-pool}
                               {:player/id :opponent
                                :player/name "Opponent"
                                :player/life 20
                                :player/mana-pool empty-mana-pool}])
          p1-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)
          opp-eid (d/q '[:find ?e . :where [?e :player/id :opponent]] @conn)
          card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] @conn)
          _ (d/transact! conn [{:game/id :game-1
                                :game/turn 1
                                :game/phase :untap
                                :game/active-player p1-eid
                                :game/priority p1-eid}])
          ;; Turn-based triggers for player 1
          _ (d/transact! conn (turn-based/create-turn-based-triggers-tx p1-eid :player-1))
          ;; Add tapped permanent for player 1
          _ (d/transact! conn [{:object/id (random-uuid)
                                :object/card card-eid
                                :object/zone :battlefield
                                :object/owner p1-eid
                                :object/controller p1-eid
                                :object/tapped true}])
          ;; Add tapped permanent for opponent
          _ (d/transact! conn [{:object/id (random-uuid)
                                :object/card card-eid
                                :object/zone :battlefield
                                :object/owner opp-eid
                                :object/controller opp-eid
                                :object/tapped true}])
          db @conn
          p1-tapped-before (get-tapped-permanents db :player-1)
          opp-tapped-before (get-tapped-permanents db :opponent)
          _ (is (= 1 (count p1-tapped-before)))
          _ (is (= 1 (count opp-tapped-before)))
          event (game-events/phase-entered-event :untap 1 :player-1)
          db' (dispatch/dispatch-event db event)
          p1-tapped-after (get-tapped-permanents db' :player-1)
          opp-tapped-after (get-tapped-permanents db' :opponent)]
      (is (= 0 (count p1-tapped-after))
          "Player 1's permanents should be untapped")
      (is (= 1 (count opp-tapped-after))
          "Opponent's permanents should still be tapped"))))


(deftest test-untap-step-doesnt-fire-on-other-phases
  (testing "untap trigger doesn't fire on non-untap phases"
    (let [db (-> (init-test-game)
                 (add-tapped-permanent :player-1))
          tapped-before (get-tapped-permanents db :player-1)
          _ (is (= 1 (count tapped-before)))]
      ;; Test draw phase
      (let [draw-event (game-events/phase-entered-event :draw 1 :player-1)
            db-draw (dispatch/dispatch-event db draw-event)
            tapped-draw (get-tapped-permanents db-draw :player-1)]
        (is (= 1 (count tapped-draw))
            "Permanents should still be tapped after draw phase"))
      ;; Test main1 phase
      (let [main1-event (game-events/phase-entered-event :main1 1 :player-1)
            db-main1 (dispatch/dispatch-event db main1-event)
            tapped-main1 (get-tapped-permanents db-main1 :player-1)]
        (is (= 1 (count tapped-main1))
            "Permanents should still be tapped after main1 phase")))))


;; === Integration tests ===

(deftest test-advance-multiple-phases-fires-each-event
  (testing "each phase entry produces independent event dispatch"
    (let [db (-> (init-test-game)
                 (set-turn 2)
                 (add-tapped-permanent :player-1))
          initial-hand (get-hand-count db :player-1)
          ;; Simulate untap phase
          db-untap (dispatch/dispatch-event db
                                            (game-events/phase-entered-event :untap 2 :player-1))
          ;; Verify untap happened
          tapped-after-untap (get-tapped-permanents db-untap :player-1)
          _ (is (= 0 (count tapped-after-untap)) "Permanent should be untapped")
          ;; Simulate upkeep (no effect)
          db-upkeep (dispatch/dispatch-event db-untap
                                             (game-events/phase-entered-event :upkeep 2 :player-1))
          ;; Simulate draw phase
          db-draw (dispatch/dispatch-event db-upkeep
                                           (game-events/phase-entered-event :draw 2 :player-1))]
      (is (= (inc initial-hand) (get-hand-count db-draw :player-1))
          "Should have drawn a card during draw phase"))))
