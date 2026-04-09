(ns fizzle.engine.opponent-sba-test
  "Tests for SBA checking on opponent player.
   Validates that all 6 state-based action types fire for the opponent."
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


;; === opponent :life-zero SBA tests ===

(deftest opponent-life-zero-check-fires
  (testing "check-sba :life-zero fires when opponent has life = 0"
    (let [db       (-> (th/create-test-db {:life 20})
                       (th/add-opponent))
          opp-eid  (q/get-player-eid db :player-2)
          db       (d/db-with db [[:db/add opp-eid :player/life 0]])
          sbas     (sba/check-sba db :life-zero)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent at 0 life")
      (is (= :life-zero (:sba/type (first sbas)))
          "SBA type should be :life-zero")
      (is (= :player-2 (:sba/player-id (first sbas)))
          "SBA should identify opponent as the losing player"))))


(deftest opponent-life-zero-execute-sets-loss-condition
  (testing "execute-sba :life-zero sets loss condition with player-1 as winner when opponent dies"
    (let [db       (-> (th/create-test-db {:life 20})
                       (th/add-opponent))
          opp-eid  (q/get-player-eid db :player-2)
          db       (d/db-with db [[:db/add opp-eid :player/life 0]])
          sba-evt  {:sba/type :life-zero :sba/player-id :player-2}
          db'      (sba/execute-sba db sba-evt)
          game     (q/get-game-state db')
          winner-eid (:game/winner game)
          winner-pid (when winner-eid
                       (:player/id (d/pull db' [:player/id] (:db/id winner-eid))))]
      (is (= :life-zero (:game/loss-condition game))
          "Loss condition should be :life-zero")
      (is (= :player-1 winner-pid)
          "Player-1 should be the winner when opponent loses"))))


(deftest opponent-check-and-execute-sbas-life-zero-integration
  (testing "check-and-execute-sbas detects opponent life=0 and sets loss condition"
    (let [db      (-> (th/create-test-db {:life 20})
                      (th/add-opponent))
          opp-eid (q/get-player-eid db :player-2)
          db      (d/db-with db [[:db/add opp-eid :player/life 0]])
          db'     (sba/check-and-execute-sbas db)]
      (is (= :life-zero (get-loss-condition db'))
          "check-and-execute-sbas should detect opponent at 0 life and end game"))))


;; === opponent :lethal-damage SBA tests ===

(deftest opponent-lethal-damage-check-fires
  (testing "check-sba :lethal-damage fires when opponent's creature has lethal damage"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (th/add-test-creature :player-2 3 3))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 3]])
          sbas        (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent creature with lethal damage")
      (is (= :lethal-damage (:sba/type (first sbas)))
          "SBA type should be :lethal-damage")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the opponent's damaged creature"))))


(deftest opponent-lethal-damage-no-fire-below-toughness
  (testing "check-sba :lethal-damage does NOT fire when opponent damage < toughness"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (th/add-test-creature :player-2 3 5))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          sbas        (sba/check-sba db :lethal-damage)]
      (is (empty? (filter #(= obj-id (:sba/target %)) sbas))
          "Should not fire when opponent creature damage < toughness"))))


(deftest opponent-check-and-execute-sbas-lethal-damage-integration
  (testing "check-and-execute-sbas moves opponent's creature to graveyard when damage is lethal"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (th/add-test-creature :player-2 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          db'         (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Opponent's creature should be in graveyard after lethal-damage SBA"))))


;; === opponent :zero-toughness SBA tests ===

(deftest opponent-zero-toughness-check-fires
  (testing "check-sba :zero-toughness fires when opponent has a 0-toughness creature"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (th/add-test-creature :player-2 1 0))
          sbas        (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent's 0-toughness creature")
      (is (= :zero-toughness (:sba/type (first sbas)))
          "SBA type should be :zero-toughness")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the opponent's 0-toughness creature"))))


(deftest opponent-zero-toughness-no-fire-positive
  (testing "check-sba :zero-toughness does NOT fire for opponent creature with toughness > 0"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (th/add-test-creature :player-2 2 3))
          sbas        (sba/check-sba db :zero-toughness)]
      (is (empty? (filter #(= obj-id (:sba/target %)) sbas))
          "Should not fire when opponent creature has positive toughness"))))


(deftest opponent-check-and-execute-sbas-zero-toughness-integration
  (testing "check-and-execute-sbas moves opponent's 0-toughness creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (th/add-test-creature :player-2 2 0))
          db'         (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Opponent's 0-toughness creature should be in graveyard"))))


;; === opponent :token-cleanup SBA tests ===

(defn- add-opponent-token
  "Create a token for the opponent in a given zone using d/db-with.
   Returns [db token-obj-id]."
  [db zone]
  (let [opp-eid  (q/get-player-eid db :player-2)
        card-id  (keyword (str "opp-token-card-" (random-uuid)))
        db       (d/db-with db [{:card/id card-id
                                 :card/name "Opponent Token"
                                 :card/types #{:creature}
                                 :card/power 1
                                 :card/toughness 1}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        token-id (random-uuid)
        db       (d/db-with db [{:object/id token-id
                                 :object/card card-eid
                                 :object/zone zone
                                 :object/owner opp-eid
                                 :object/controller opp-eid
                                 :object/is-token true
                                 :object/tapped false
                                 :object/damage-marked 0}])]
    [db token-id]))


(deftest opponent-token-cleanup-fires-for-graveyard-token
  (testing "check-sba :token-cleanup fires when opponent's token is in graveyard"
    (let [[db token-id] (-> (th/create-test-db {:life 20})
                            (th/add-opponent)
                            (add-opponent-token :graveyard))
          sbas          (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent's graveyard token")
      (is (= :token-cleanup (:sba/type (first sbas)))
          "SBA type should be :token-cleanup")
      (is (= token-id (:sba/target (first sbas)))
          "SBA should target the opponent's graveyard token"))))


(deftest opponent-token-cleanup-no-fire-for-battlefield-token
  (testing "check-sba :token-cleanup does NOT fire for opponent's token on battlefield"
    (let [[db token-id] (-> (th/create-test-db {:life 20})
                            (th/add-opponent)
                            (add-opponent-token :battlefield))
          sbas          (sba/check-sba db :token-cleanup)]
      (is (empty? (filter #(= token-id (:sba/target %)) sbas))
          "Should not fire for opponent's token on battlefield"))))


(deftest opponent-check-and-execute-sbas-token-cleanup-integration
  (testing "check-and-execute-sbas retracts opponent's graveyard token"
    (let [[db token-id] (-> (th/create-test-db {:life 20})
                            (th/add-opponent)
                            (add-opponent-token :graveyard))
          db'           (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object-eid db' token-id))
          "Opponent's graveyard token should be fully retracted"))))


;; === opponent :state-check-trigger SBA tests ===

(defn- add-opponent-creature-with-state-trigger
  "Create an opponent creature with a :card/state-triggers entry on the battlefield.
   Returns [db obj-id]. Uses d/db-with (no mutable conn). Trigger condition: power >= threshold."
  [db power toughness threshold]
  (let [opp-eid  (q/get-player-eid db :player-2)
        card-id  (keyword (str "opp-trigger-creature-" (random-uuid)))
        trigger  {:state/condition   {:condition/type      :power-gte
                                      :condition/threshold threshold}
                  :state/effects     [{:effect/type :sacrifice :effect/target :self}]
                  :state/description (str "Sacrifice when power >= " threshold)}
        db       (d/db-with db [{:card/id             card-id
                                 :card/name           "Opponent Trigger Creature"
                                 :card/types          #{:creature}
                                 :card/power          power
                                 :card/toughness      toughness
                                 :card/state-triggers [trigger]}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        obj-id   (random-uuid)
        db       (d/db-with db [{:object/id            obj-id
                                 :object/card          card-eid
                                 :object/zone          :battlefield
                                 :object/owner         opp-eid
                                 :object/controller    opp-eid
                                 :object/tapped        false
                                 :object/damage-marked 0
                                 :object/power         power
                                 :object/toughness     toughness
                                 :object/summoning-sick true}])]
    [db obj-id]))


(deftest opponent-state-check-trigger-fires-when-condition-met
  (testing "check-sba :state-check-trigger fires for opponent's creature when power >= threshold"
    (let [[db obj-id] (-> (th/create-test-db {:life 20})
                          (th/add-opponent)
                          (add-opponent-creature-with-state-trigger 7 7 7))
          sbas        (sba/check-sba db :state-check-trigger)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent creature with condition met")
      (is (= :state-check-trigger (:sba/type (first sbas)))
          "SBA type should be :state-check-trigger")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the opponent's creature"))))


(deftest opponent-state-check-trigger-no-fire-when-condition-not-met
  (testing "check-sba :state-check-trigger does NOT fire for opponent when power < threshold"
    (let [[db _obj-id] (-> (th/create-test-db {:life 20})
                           (th/add-opponent)
                           (add-opponent-creature-with-state-trigger 3 3 7))
          sbas         (sba/check-sba db :state-check-trigger)]
      (is (empty? sbas)
          "Should not fire when opponent creature power < threshold"))))


(deftest opponent-check-and-execute-sbas-state-check-trigger-integration
  (testing "check-and-execute-sbas puts state-check-trigger on stack for opponent creature"
    (let [[db _obj-id] (-> (th/create-test-db {:life 20})
                           (th/add-opponent)
                           (add-opponent-creature-with-state-trigger 7 7 7))
          db'          (sba/check-and-execute-sbas db)
          stack-items  (d/q '[:find [?e ...] :where [?e :stack-item/type :state-check-trigger]] db')]
      (is (= 1 (count stack-items))
          "Opponent's creature state trigger should be placed on the stack"))))
