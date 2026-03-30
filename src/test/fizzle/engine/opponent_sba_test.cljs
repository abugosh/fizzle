(ns fizzle.engine.opponent-sba-test
  "Tests for SBA checking on opponent player.
   Validates that state-based actions fire for the bot opponent
   when drawing from empty library."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.director :as director]
    [fizzle.events.phases :as phases]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(defn- clear-library
  "Remove all cards from a player's library."
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


(defn- get-loss-condition
  [db]
  (:game/loss-condition (q/get-game-state db)))


(deftest opponent-draw-empty-library-sets-flag
  (testing "opponent drawing from empty library sets drew-from-empty flag"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, draw phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          ;; Advance from upkeep to draw
          db-after-advance (phases/advance-phase db :player-2)
          ;; Check flag
          flag (d/q '[:find ?f .
                      :where [?e :player/id :player-2]
                      [?e :player/drew-from-empty ?f]]
                    db-after-advance)]
      (is (true? flag)
          "Opponent should have drew-from-empty flag after drawing from empty library"))))


(deftest opponent-sba-detects-empty-library-draw
  (testing "SBA engine detects opponent drew-from-empty flag"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, draw phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          ;; Advance from upkeep to draw
          db-after-advance (phases/advance-phase db :player-2)
          ;; Run SBA checks
          db-after-sba (sba/check-and-execute-sbas db-after-advance)]
      (is (= :empty-library (get-loss-condition db-after-sba))
          "SBA should detect opponent's empty library draw and set loss condition"))))


(deftest opponent-sba-via-director
  (testing "director advances bot turn through draw step and SBA detects empty library"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, upkeep phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :upkeep]])
          app-db (merge (history/init-history) {:game/db db})
          ;; Run director: bot turn at upkeep → both pass → advance to draw (empty library)
          ;; Director inline-runs SBAs after each step, so loss condition set automatically
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      ;; SBA should detect the opponent drew from empty library (run inline in director)
      (is (= :empty-library (get-loss-condition result-db))
          "SBA should detect opponent's empty library draw during director run"))))


(deftest director-iterates-through-bot-draw-with-sba
  (testing "Director running bot turn from untap detects empty library via inline SBA"
    (let [db (-> (th/create-test-db {:stops #{}})
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Clear opponent's library
          db (clear-library db :player-2)
          ;; Set game to opponent's turn, untap phase, turn 2
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/priority opp-eid]
                            [:db/add game-eid :game/turn 2]
                            [:db/add game-eid :game/phase :untap]])
          app-db (merge (history/init-history) {:game/db db})
          ;; Director runs inline from untap → draw (empty library) → SBAs fire → game-over
          result (director/run-to-decision app-db {:yield-all? false})
          result-db (:game/db (:app-db result))]
      ;; Director's inline SBAs should detect loss during the run
      (is (= :game-over (:reason result))
          "Director should return :game-over after SBA detects empty library draw")
      (is (= :empty-library (get-loss-condition result-db))
          "Loss condition should be :empty-library"))))
