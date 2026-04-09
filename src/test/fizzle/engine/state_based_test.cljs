(ns fizzle.engine.state-based-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]
    [fizzle.test-helpers :as th]))


;; === Token creation helper (d/db-with only — no mutable conn) ===

(defn- add-test-token
  "Create a token creature in a given zone using d/db-with.
   Returns [db token-obj-id]."
  [db owner zone]
  (let [player-eid (q/get-player-eid db owner)
        card-id    (keyword (str "token-card-" (random-uuid)))
        db         (d/db-with db [{:card/id card-id
                                   :card/name "Test Token"
                                   :card/types #{:creature}
                                   :card/power 1
                                   :card/toughness 1}])
        card-eid   (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        token-id   (random-uuid)
        db         (d/db-with db [{:object/id token-id
                                   :object/card card-eid
                                   :object/zone zone
                                   :object/owner player-eid
                                   :object/controller player-eid
                                   :object/is-token true
                                   :object/tapped false
                                   :object/damage-marked 0}])]
    [db token-id card-eid]))


;; === Test helpers ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id card-data]
   (add-permanent db player-id card-data nil))
  ([db player-id card-data initial-counters]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         ;; Create an inline card definition
         card-id (keyword (str "test-card-" (random-uuid)))
         _ (d/transact! conn [(merge {:card/id card-id
                                      :card/name "Test Card"
                                      :card/types #{:land}}
                                     card-data)])
         card-eid (d/q '[:find ?e .
                         :in $ ?cid
                         :where [?e :card/id ?cid]]
                       @conn card-id)
         object-id (random-uuid)
         base-entity {:object/id object-id
                      :object/card card-eid
                      :object/zone :battlefield
                      :object/owner player-eid
                      :object/controller player-eid
                      :object/tapped false}
         entity (if initial-counters
                  (assoc base-entity :object/counters initial-counters)
                  base-entity)]
     (d/transact! conn [entity])
     [@conn object-id])))


(defn get-object-zone
  "Get the zone of an object by its UUID."
  [db object-id]
  (d/q '[:find ?zone .
         :in $ ?oid
         :where [?e :object/id ?oid]
         [?e :object/zone ?zone]]
       db object-id))


;; === check-sba multimethod tests ===

(deftest test-check-sba-default-returns-empty
  (testing "check-sba :default returns empty seq for unknown SBA types"
    (let [db (init-game-state)
          result (sba/check-sba db :nonexistent-type)]
      (is (empty? result)
          "Default check-sba should return empty seq"))))


(deftest test-check-sba-defmethod-discovered
  (testing "registered check-sba defmethod is discovered by check-all-sbas"
    (let [sentinel {:sba/type :test-custom :sba/target :player-1}]
      (defmethod sba/check-sba :test-custom-sba [_db _type] [sentinel])
      (let [results (vec (sba/check-all-sbas (init-game-state)))]
        (remove-method sba/check-sba :test-custom-sba)
        (is (= 1 (count (filter #(= sentinel %) results)))
            "check-all-sbas should include exactly one result from registered defmethod")
        (is (= :test-custom (:sba/type (first (filter #(= sentinel %) results))))
            "Discovered SBA should have the correct :sba/type")
        (is (= :player-1 (:sba/target (first (filter #(= sentinel %) results))))
            "Discovered SBA should have the correct :sba/target")))))


;; === check-all-sbas tests ===

(deftest test-check-all-sbas-empty
  (testing "returns empty when no SBAs apply"
    ;; Create db with permanent that has counters > 0
    (let [[db _object-id] (add-permanent
                            (init-game-state)
                            :player-1
                            {:card/abilities [{:ability/cost {:remove-counter {:mining 1}}}]}
                            {:mining 3})
          sbas (sba/check-all-sbas db)]
      (is (empty? (filter #(= :zero-counters (:sba/type %)) sbas))
          "No zero-counters SBA when counters > 0"))))


;; === zero-counters SBA tests ===
;; NOTE: These tests were removed in favor of ability-based sacrifice.
;; The zero-counters SBA was replaced with :sacrifice effect in mana abilities.
;; See test-gemstone-mine-sacrifices-on-last-counter in tap_land_test.cljs for new behavior.


(deftest test-non-zero-counters-ignored
  (testing "permanents with counters > 0 not sacrificed"
    (let [[db obj-id] (add-permanent
                        (init-game-state)
                        :player-1
                        {:card/abilities [{:ability/cost {:remove-counter {:mining 1}}}]}
                        {:mining 1})
          db-after (sba/check-and-execute-sbas db)
          zone-after (get-object-zone db-after obj-id)]
      (is (= :battlefield zone-after) "Object should still be on battlefield"))))


(deftest test-no-counter-ability-ignored
  (testing "permanents without remove-counter cost are not sacrificed even with 0 counters"
    (let [[db obj-id] (add-permanent
                        (init-game-state)
                        :player-1
                        {:card/abilities [{:ability/cost {:tap true}}]}  ; tap only, no counter cost
                        {:mining 0})  ; has 0 counters but ability doesn't use them
          db-after (sba/check-and-execute-sbas db)
          zone-after (get-object-zone db-after obj-id)]
      (is (= :battlefield zone-after) "Object should still be on battlefield"))))


;; === execute-sba :default tests ===

(deftest test-execute-sba-default-returns-db-unchanged
  (testing "execute-sba :default returns db unchanged for unknown SBA types"
    (let [db (init-game-state)
          sba {:sba/type :unknown-type :sba/target :some-target}
          db' (sba/execute-sba db sba)]
      (is (= db db')
          "Default execute-sba should return db unchanged"))))


;; === Cascading SBAs tests ===

(deftest test-cascading-sbas
  (testing "check-and-execute-sbas loops until no more SBAs fire"
    ;; We register two SBAs that cascade:
    ;; SBA-A fires first iteration, removes itself, but creates condition for SBA-B
    ;; SBA-B fires second iteration
    ;; No production SBAs exist yet, so runtime defmethod registration is required.
    (let [execution-order (atom [])
          ;; Track which SBAs have fired to prevent infinite loops
          a-fired (atom false)
          b-fired (atom false)]
      ;; Register SBA checks via defmethod
      (defmethod sba/check-sba :test-cascade-a-check [_db _type]
        (if @a-fired
          []
          [{:sba/type :test-cascade-a :sba/target :player-1}]))
      (defmethod sba/check-sba :test-cascade-b-check [_db _type]
        (if (and @a-fired (not @b-fired))
          [{:sba/type :test-cascade-b :sba/target :player-1}]
          []))
      ;; Register SBA executors
      (defmethod sba/execute-sba :test-cascade-a [db _sba]
        (reset! a-fired true)
        (swap! execution-order conj :a)
        db)
      (defmethod sba/execute-sba :test-cascade-b [db _sba]
        (reset! b-fired true)
        (swap! execution-order conj :b)
        db)
      ;; Execute cascading SBAs
      (let [db (init-game-state)
            db' (sba/check-and-execute-sbas db)]
        ;; Both SBAs should have fired
        (is (true? @a-fired) "SBA-A should have fired")
        (is (true? @b-fired) "SBA-B should have fired (cascading)")
        (is (= 2 (count @execution-order)) "Should have executed exactly 2 SBAs")
        (is (= :a (first @execution-order)) "SBA-A should fire first")
        (is (= :b (second @execution-order)) "SBA-B should fire second (cascading)")
        ;; Function should return a valid db (not nil)
        (is (some? db') "Should return a valid db after cascading SBAs"))
      ;; Clean up
      (remove-method sba/check-sba :test-cascade-a-check)
      (remove-method sba/check-sba :test-cascade-b-check)
      (remove-method sba/execute-sba :test-cascade-a)
      (remove-method sba/execute-sba :test-cascade-b))))


;; === :life-zero SBA tests ===

(defn- get-loss-condition
  "Get the loss condition from game state."
  [db]
  (:game/loss-condition (q/get-game-state db)))


(defn- set-drew-from-empty
  "Set the :player/drew-from-empty flag on a player. Returns updated db."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)]
    (d/db-with db [[:db/add player-eid :player/drew-from-empty true]])))


(deftest test-check-sba-life-zero-fires-at-zero
  (testing "check-sba :life-zero returns SBA event when player has life = 0"
    (let [db (-> (th/create-test-db {:life 0})
                 (th/add-opponent))
          sbas (sba/check-sba db :life-zero)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event")
      (is (= :life-zero (:sba/type (first sbas)))
          "SBA type should be :life-zero")
      (is (= :player-1 (:sba/player-id (first sbas)))
          "SBA should identify the player at 0 life"))))


(deftest test-check-sba-life-zero-fires-at-negative
  (testing "check-sba :life-zero returns SBA event when player has negative life"
    (let [db (-> (th/create-test-db {:life -5})
                 (th/add-opponent))
          sbas (sba/check-sba db :life-zero)]
      (is (= 1 (count sbas)))
      (is (= :player-1 (:sba/player-id (first sbas)))))))


(deftest test-check-sba-life-zero-no-fire-positive
  (testing "check-sba :life-zero returns [] when all players have life > 0"
    (let [db (-> (th/create-test-db {:life 20})
                 (th/add-opponent))
          sbas (sba/check-sba db :life-zero)]
      (is (empty? sbas)
          "Should return empty when all players have positive life"))))


(deftest test-check-sba-life-zero-skips-when-game-over
  (testing "check-sba :life-zero returns [] when game already has loss condition"
    (let [db (th/create-test-db {:life 0})
          db (th/add-opponent db)
          ;; Set loss condition manually
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/loss-condition :life-zero]])
          sbas (sba/check-sba db :life-zero)]
      (is (empty? sbas)
          "Should not fire when game already has a loss condition"))))


(deftest test-execute-sba-life-zero-sets-loss-condition
  (testing "execute-sba :life-zero sets :game/loss-condition and :game/winner"
    (let [db (-> (th/create-test-db {:life 0})
                 (th/add-opponent))
          sba-event {:sba/type :life-zero :sba/player-id :player-1}
          db' (sba/execute-sba db sba-event)]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be :life-zero")
      (let [game (q/get-game-state db')
            winner-eid (:game/winner game)
            winner-pid (when winner-eid
                         (:player/id (d/pull db' [:player/id] (:db/id winner-eid))))]
        (is (= :player-2 winner-pid)
            "Winner should be the other player")))))


(deftest test-check-and-execute-sbas-life-zero-integration
  (testing "check-and-execute-sbas detects life=0 and sets loss condition"
    (let [db (-> (th/create-test-db {:life 0})
                 (th/add-opponent))
          db' (sba/check-and-execute-sbas db)]
      (is (= :life-zero (get-loss-condition db'))
          "check-and-execute-sbas should detect and apply life-zero SBA"))))


;; === :empty-library SBA tests ===

(deftest test-check-sba-empty-library-fires-with-flag
  (testing "check-sba :empty-library returns SBA event when player has drew-from-empty flag"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent)
                 (set-drew-from-empty :player-1))
          sbas (sba/check-sba db :empty-library)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event")
      (is (= :empty-library (:sba/type (first sbas)))
          "SBA type should be :empty-library")
      (is (= :player-1 (:sba/player-id (first sbas)))
          "SBA should identify the player who drew from empty"))))


(deftest test-check-sba-empty-library-no-flag
  (testing "check-sba :empty-library returns [] when no player has the flag"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          sbas (sba/check-sba db :empty-library)]
      (is (empty? sbas)
          "Should return empty when no player has drew-from-empty flag"))))


(deftest test-check-sba-empty-library-skips-when-game-over
  (testing "check-sba :empty-library returns [] when game already has loss condition"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent)
                 (set-drew-from-empty :player-1))
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/loss-condition :empty-library]])
          sbas (sba/check-sba db :empty-library)]
      (is (empty? sbas)
          "Should not fire when game already has a loss condition"))))


(deftest test-execute-sba-empty-library-sets-loss-and-clears-flag
  (testing "execute-sba :empty-library sets loss condition and clears flag"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent)
                 (set-drew-from-empty :player-1))
          sba-event {:sba/type :empty-library :sba/player-id :player-1}
          db' (sba/execute-sba db sba-event)]
      (is (= :empty-library (get-loss-condition db'))
          "Loss condition should be :empty-library")
      ;; Flag should be cleared
      (let [player-eid (q/get-player-eid db' :player-1)
            flag (d/q '[:find ?f .
                        :in $ ?e
                        :where [?e :player/drew-from-empty ?f]]
                      db' player-eid)]
        (is (not flag)
            "drew-from-empty flag should be cleared after SBA executes")))))


(deftest test-check-and-execute-sbas-empty-library-integration
  (testing "check-and-execute-sbas detects drew-from-empty flag and sets loss condition"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent)
                 (set-drew-from-empty :player-1))
          db' (sba/check-and-execute-sbas db)]
      (is (= :empty-library (get-loss-condition db'))
          "check-and-execute-sbas should detect and apply empty-library SBA"))))


;; === set-loss-condition tests ===

(deftest test-set-loss-condition-sets-winner
  (testing "set-loss-condition identifies the winner as the other player"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          db' (sba/set-loss-condition db :life-zero :player-1)
          game (q/get-game-state db')
          winner-eid (:game/winner game)
          winner-pid (when winner-eid
                       (:player/id (d/pull db' [:player/id] (:db/id winner-eid))))]
      (is (= :life-zero (:game/loss-condition game)))
      (is (= :player-2 winner-pid)))))


(deftest test-set-loss-condition-without-opponent
  (testing "set-loss-condition works with single player (no crash)"
    (let [db (th/create-test-db)
          db' (sba/set-loss-condition db :life-zero :player-1)]
      (is (= :life-zero (get-loss-condition db'))
          "Loss condition should be set even without opponent"))))


;; === Cost-based life loss triggers SBA ===

(deftest test-pay-life-cost-to-zero-triggers-sba
  (testing "pay-life cost reducing life to 0 triggers :life-zero SBA"
    ;; This is the bug that motivated the SBA epic: pay-life costs
    ;; had no inline loss check, so paying life to 0 didn't end the game.
    ;; Now the SBA system detects it.
    (let [db (-> (th/create-test-db {:life 1})
                 (th/add-opponent))
          ;; Simulate pay-life cost: directly deduct life (as costs.cljs does)
          player-eid (q/get-player-eid db :player-1)
          db-after-cost (d/db-with db [[:db/add player-eid :player/life 0]])
          ;; SBA detects life <= 0
          db' (sba/check-and-execute-sbas db-after-cost)]
      (is (= 0 (q/get-life-total db' :player-1))
          "Life should be 0 after cost payment")
      (is (= :life-zero (get-loss-condition db'))
          "SBA should detect life-zero from cost payment")
      (let [game (q/get-game-state db')
            winner-eid (:game/winner game)
            winner-pid (when winner-eid
                         (:player/id (d/pull db' [:player/id] (:db/id winner-eid))))]
        (is (= :player-2 winner-pid)
            "Opponent should win when player pays life to 0")))))


(deftest test-pay-x-life-cost-to-zero-triggers-sba
  (testing "pay-x-life cost (Necrologia) reducing life to 0 triggers :life-zero SBA"
    ;; Necrologia: pay X life as additional cost. If X >= life total, game should end.
    (let [db (-> (th/create-test-db {:life 5})
                 (th/add-opponent))
          ;; Simulate pay-x-life cost: deduct exactly player's life total
          player-eid (q/get-player-eid db :player-1)
          db-after-cost (d/db-with db [[:db/add player-eid :player/life 0]])
          ;; SBA detects life <= 0
          db' (sba/check-and-execute-sbas db-after-cost)]
      (is (= 0 (q/get-life-total db' :player-1))
          "Life should be 0 after paying X life")
      (is (= :life-zero (get-loss-condition db'))
          "SBA should detect life-zero from pay-x-life cost"))))


(deftest test-pay-x-life-to-negative-triggers-sba
  (testing "pay-x-life reducing life below 0 triggers :life-zero SBA"
    (let [db (-> (th/create-test-db {:life 5})
                 (th/add-opponent))
          ;; Simulate paying 10 life when only at 5
          player-eid (q/get-player-eid db :player-1)
          db-after-cost (d/db-with db [[:db/add player-eid :player/life -5]])
          db' (sba/check-and-execute-sbas db-after-cost)]
      (is (= -5 (q/get-life-total db' :player-1)))
      (is (= :life-zero (get-loss-condition db'))))))


;; === Extensibility test ===

(deftest test-new-sba-type-works-with-just-defmethod
  (testing "A new SBA type can be added with only defmethod registration"
    ;; This proves the epic's extensibility requirement: no call-site changes needed
    (let [executed (atom false)]
      ;; Register a new SBA type at runtime
      (defmethod sba/check-sba :test-extensibility
        [db _type]
        (let [game (q/get-game-state db)]
          (if (:game/loss-condition game)
            []
            ;; Fire when player-1 has exactly 42 life (arbitrary condition)
            (let [life (q/get-life-total db :player-1)]
              (if (= 42 life)
                [{:sba/type :test-extensibility :sba/player-id :player-1}]
                [])))))
      (defmethod sba/execute-sba :test-extensibility
        [db sba]
        (reset! executed true)
        (sba/set-loss-condition db :test-custom-loss (:sba/player-id sba)))
      ;; Test: SBA does NOT fire at normal life
      (let [db (th/create-test-db {:life 20})
            db' (sba/check-and-execute-sbas db)]
        (is (nil? (get-loss-condition db'))
            "SBA should not fire at life 20")
        (is (false? @executed)))
      ;; Test: SBA fires at life 42
      (let [db (th/create-test-db {:life 42})
            db' (sba/check-and-execute-sbas db)]
        (is (= :test-custom-loss (get-loss-condition db'))
            "Custom SBA should set custom loss condition")
        (is (true? @executed)
            "Custom SBA executor should have been called"))
      ;; Clean up
      (remove-method sba/check-sba :test-extensibility)
      (remove-method sba/execute-sba :test-extensibility))))


;; === :lethal-damage SBA tests ===

(deftest test-check-sba-lethal-damage-fires-at-equal
  (testing "check-sba :lethal-damage fires when damage-marked == toughness"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          sbas        (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event")
      (is (= :lethal-damage (:sba/type (first sbas)))
          "SBA type should be :lethal-damage")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the damaged creature"))))


(deftest test-check-sba-lethal-damage-fires-at-overkill
  (testing "check-sba :lethal-damage fires when damage-marked > toughness (overkill)"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 5]])
          sbas        (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas)))
      (is (= :lethal-damage (:sba/type (first sbas)))))))


(deftest test-check-sba-lethal-damage-no-fire-below-toughness
  (testing "check-sba :lethal-damage does NOT fire when damage < toughness"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 4))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          sbas        (sba/check-sba db :lethal-damage)]
      (is (empty? (filter #(= obj-id (:sba/target %)) sbas))
          "Should not fire when damage < toughness"))))


(deftest test-check-sba-lethal-damage-no-fire-zero-damage
  (testing "check-sba :lethal-damage does NOT fire when damage-marked is 0"
    (let [[db _obj-id] (-> (th/create-test-db)
                           (th/add-opponent)
                           (th/add-test-creature :player-1 2 2))
          ;; damage-marked is already 0 from add-test-creature
          sbas (sba/check-sba db :lethal-damage)]
      (is (empty? sbas)
          "Should not fire when damage-marked is 0"))))


(deftest test-execute-sba-lethal-damage-moves-to-graveyard
  (testing "execute-sba :lethal-damage moves creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          sba-event   {:sba/type :lethal-damage :sba/target obj-id}
          db'         (sba/execute-sba db sba-event)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Creature should be in graveyard after lethal-damage SBA"))))


(deftest test-execute-sba-lethal-damage-noop-when-already-removed
  (testing "execute-sba :lethal-damage no-ops when creature already gone"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          ;; Retract creature manually before executing SBA
          db          (d/db-with db [[:db.fn/retractEntity obj-eid]])
          sba-event   {:sba/type :lethal-damage :sba/target obj-id}
          db'         (sba/execute-sba db sba-event)]
      (is (nil? (q/get-object-eid db' obj-id))
          "Object should not exist, execute-sba should no-op"))))


(deftest test-check-and-execute-sbas-lethal-damage-integration
  (testing "check-and-execute-sbas detects lethal damage and moves creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 3 3))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 3]])
          db'         (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "check-and-execute-sbas should detect and apply lethal-damage SBA"))))


(deftest test-check-sba-lethal-damage-opponent-creature
  (testing "check-sba :lethal-damage fires for opponent's creature"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-2 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 3]])
          sbas        (sba/check-sba db :lethal-damage)]
      (is (= 1 (count sbas)))
      (is (= :lethal-damage (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should fire for opponent's creature"))))


(deftest test-check-and-execute-sbas-lethal-damage-opponent-integration
  (testing "check-and-execute-sbas moves opponent creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-2 2 2))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db/add obj-eid :object/damage-marked 2]])
          db'         (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Opponent's creature should be in graveyard"))))


;; === :zero-toughness SBA tests ===

(deftest test-check-sba-zero-toughness-fires-at-zero
  (testing "check-sba :zero-toughness fires when creature has 0 toughness"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 1 0))
          sbas        (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event")
      (is (= :zero-toughness (:sba/type (first sbas)))
          "SBA type should be :zero-toughness")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the 0-toughness creature"))))


(deftest test-check-sba-zero-toughness-fires-negative
  (testing "check-sba :zero-toughness fires when creature toughness is negative"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 1 -1))
          sbas        (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas)))
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should fire for negative toughness"))))


(deftest test-check-sba-zero-toughness-no-fire-positive
  (testing "check-sba :zero-toughness does NOT fire for creature with toughness > 0"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 2))
          sbas        (sba/check-sba db :zero-toughness)]
      (is (empty? (filter #(= obj-id (:sba/target %)) sbas))
          "Should not fire when toughness > 0"))))


(deftest test-execute-sba-zero-toughness-moves-to-graveyard
  (testing "execute-sba :zero-toughness moves creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 1 0))
          sba-event   {:sba/type :zero-toughness :sba/target obj-id}
          db'         (sba/execute-sba db sba-event)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Creature should be in graveyard after zero-toughness SBA"))))


(deftest test-execute-sba-zero-toughness-noop-when-already-removed
  (testing "execute-sba :zero-toughness no-ops when creature already gone"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 1 0))
          obj-eid     (q/get-object-eid db obj-id)
          db          (d/db-with db [[:db.fn/retractEntity obj-eid]])
          sba-event   {:sba/type :zero-toughness :sba/target obj-id}
          db'         (sba/execute-sba db sba-event)]
      (is (nil? (q/get-object-eid db' obj-id))
          "Object should not exist, execute-sba should no-op"))))


(deftest test-check-and-execute-sbas-zero-toughness-integration
  (testing "check-and-execute-sbas detects 0-toughness and moves creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-1 2 0))
          db'         (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "check-and-execute-sbas should detect and apply zero-toughness SBA"))))


(deftest test-check-sba-zero-toughness-opponent-creature
  (testing "check-sba :zero-toughness fires for opponent's 0-toughness creature"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-2 1 0))
          sbas        (sba/check-sba db :zero-toughness)]
      (is (= 1 (count sbas)))
      (is (= :zero-toughness (:sba/type (first sbas))))
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should fire for opponent's 0-toughness creature"))))


(deftest test-check-and-execute-sbas-zero-toughness-opponent-integration
  (testing "check-and-execute-sbas moves opponent's 0-toughness creature to graveyard"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (th/add-test-creature :player-2 1 0))
          db'         (sba/check-and-execute-sbas db)]
      (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
          "Opponent's 0-toughness creature should move to graveyard"))))


;; === :token-cleanup SBA tests ===

(deftest test-check-sba-token-cleanup-fires-for-graveyard-token
  (testing "check-sba :token-cleanup fires when token is in graveyard"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-1 :graveyard))
          sbas            (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event")
      (is (= :token-cleanup (:sba/type (first sbas)))
          "SBA type should be :token-cleanup")
      (is (= token-id (:sba/target (first sbas)))
          "SBA should target the graveyard token"))))


(deftest test-check-sba-token-cleanup-fires-for-exile-token
  (testing "check-sba :token-cleanup fires when token is in exile"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-1 :exile))
          sbas            (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas)))
      (is (= :token-cleanup (:sba/type (first sbas))))
      (is (= token-id (:sba/target (first sbas)))
          "SBA should fire for exiled token"))))


(deftest test-check-sba-token-cleanup-no-fire-for-battlefield-token
  (testing "check-sba :token-cleanup does NOT fire for token on battlefield"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-1 :battlefield))
          sbas            (sba/check-sba db :token-cleanup)]
      (is (empty? (filter #(= token-id (:sba/target %)) sbas))
          "Should not fire for token on battlefield"))))


(deftest test-execute-sba-token-cleanup-retracts-token-and-card
  (testing "execute-sba :token-cleanup retracts both token entity and synthetic card entity"
    (let [[db token-id card-eid] (-> (th/create-test-db)
                                     (th/add-opponent)
                                     (add-test-token :player-1 :graveyard))
          sba-event              {:sba/type :token-cleanup :sba/target token-id}
          db'                    (sba/execute-sba db sba-event)]
      ;; Token object should be gone
      (is (nil? (q/get-object-eid db' token-id))
          "Token entity should be retracted")
      ;; Synthetic card entity should also be gone
      (is (nil? (d/q '[:find ?e . :in $ ?ceid :where [?e :db/id ?ceid]] db' card-eid))
          "Synthetic card entity should also be retracted"))))


(deftest test-execute-sba-token-cleanup-noop-when-already-removed
  (testing "execute-sba :token-cleanup no-ops when token already gone"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-1 :graveyard))
          token-eid       (q/get-object-eid db token-id)
          db              (d/db-with db [[:db.fn/retractEntity token-eid]])
          sba-event       {:sba/type :token-cleanup :sba/target token-id}
          db'             (sba/execute-sba db sba-event)]
      (is (nil? (q/get-object-eid db' token-id))
          "Token should not exist, execute-sba should no-op"))))


(deftest test-check-and-execute-sbas-token-cleanup-integration
  (testing "check-and-execute-sbas retracts graveyard token"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-1 :graveyard))
          db'             (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object-eid db' token-id))
          "check-and-execute-sbas should retract graveyard token"))))


(deftest test-check-sba-token-cleanup-opponent-token
  (testing "check-sba :token-cleanup fires for opponent's graveyard token"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-2 :graveyard))
          sbas            (sba/check-sba db :token-cleanup)]
      (is (= 1 (count sbas)))
      (is (= token-id (:sba/target (first sbas)))
          "SBA should fire for opponent's graveyard token"))))


(deftest test-check-and-execute-sbas-token-cleanup-opponent-integration
  (testing "check-and-execute-sbas retracts opponent's graveyard token"
    (let [[db token-id _] (-> (th/create-test-db)
                              (th/add-opponent)
                              (add-test-token :player-2 :graveyard))
          db'             (sba/check-and-execute-sbas db)]
      (is (nil? (q/get-object-eid db' token-id))
          "Opponent's graveyard token should be retracted"))))


;; === :state-check-trigger SBA tests ===

(defn- add-creature-with-state-trigger
  "Create a creature with a :card/state-triggers entry on the battlefield.
   Returns [db obj-id]. Uses d/db-with (no mutable conn). Trigger condition: power >= threshold."
  [db owner power toughness threshold]
  (let [player-eid (q/get-player-eid db owner)
        card-id    (keyword (str "trigger-creature-" (random-uuid)))
        trigger    {:state/condition   {:condition/type      :power-gte
                                        :condition/threshold threshold}
                    :state/effects     [{:effect/type :sacrifice :effect/target :self}]
                    :state/description (str "Sacrifice when power >= " threshold)}
        db         (d/db-with db [{:card/id             card-id
                                   :card/name           "Trigger Creature"
                                   :card/types          #{:creature}
                                   :card/power          power
                                   :card/toughness      toughness
                                   :card/state-triggers [trigger]}])
        card-eid   (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        obj-id     (random-uuid)
        db         (d/db-with db [{:object/id            obj-id
                                   :object/card          card-eid
                                   :object/zone          :battlefield
                                   :object/owner         player-eid
                                   :object/controller    player-eid
                                   :object/tapped        false
                                   :object/damage-marked 0
                                   :object/power         power
                                   :object/toughness     toughness
                                   :object/summoning-sick true}])]
    [db obj-id]))


(deftest test-check-sba-state-check-trigger-fires-when-condition-met
  (testing "check-sba :state-check-trigger fires when power >= threshold"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (add-creature-with-state-trigger :player-1 7 7 7))
          sbas        (sba/check-sba db :state-check-trigger)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event")
      (is (= :state-check-trigger (:sba/type (first sbas)))
          "SBA type should be :state-check-trigger")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the creature whose condition is met"))))


(deftest test-check-sba-state-check-trigger-no-fire-when-condition-not-met
  (testing "check-sba :state-check-trigger does NOT fire when power < threshold"
    (let [[db _obj-id] (-> (th/create-test-db)
                           (th/add-opponent)
                           (add-creature-with-state-trigger :player-1 3 3 7))
          sbas         (sba/check-sba db :state-check-trigger)]
      (is (empty? sbas)
          "Should not fire when power < threshold"))))


(deftest test-execute-sba-state-check-trigger-creates-stack-item
  (testing "execute-sba :state-check-trigger creates a stack-item on the stack"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (add-creature-with-state-trigger :player-1 7 7 7))
          sbas        (sba/check-sba db :state-check-trigger)
          db'         (sba/execute-sba db (first sbas))
          stack-items (d/q '[:find [?e ...] :where [?e :stack-item/type :state-check-trigger]] db')]
      (is (= 1 (count stack-items))
          "Should create exactly one stack-item")
      (let [item (d/pull db' [:stack-item/type :stack-item/source] (first stack-items))]
        (is (= :state-check-trigger (:stack-item/type item))
            "Stack item should have correct type")
        (is (= obj-id (:stack-item/source item))
            "Stack item should reference the source creature")))))


(deftest test-check-sba-state-check-trigger-duplicate-guard
  (testing "check-sba :state-check-trigger does NOT fire again when trigger already on stack"
    (let [[db _obj-id] (-> (th/create-test-db)
                           (th/add-opponent)
                           (add-creature-with-state-trigger :player-1 7 7 7))
          ;; First: fire the trigger and execute it (puts it on the stack)
          sbas        (sba/check-sba db :state-check-trigger)
          db-with-st  (sba/execute-sba db (first sbas))
          ;; Now check again — duplicate guard should prevent re-firing
          sbas-2      (sba/check-sba db-with-st :state-check-trigger)]
      (is (empty? sbas-2)
          "Duplicate guard should prevent trigger from firing again when already on stack"))))


(deftest test-check-and-execute-sbas-state-check-trigger-integration
  (testing "check-and-execute-sbas puts state-check-trigger on the stack"
    (let [[db _obj-id] (-> (th/create-test-db)
                           (th/add-opponent)
                           (add-creature-with-state-trigger :player-1 7 7 7))
          db'          (sba/check-and-execute-sbas db)
          stack-items  (d/q '[:find [?e ...] :where [?e :stack-item/type :state-check-trigger]] db')]
      (is (= 1 (count stack-items))
          "check-and-execute-sbas should add state-check-trigger to stack"))))


(deftest test-check-sba-state-check-trigger-fires-for-opponent-creature
  (testing "check-sba :state-check-trigger fires for opponent's creature when condition is met"
    (let [[db obj-id] (-> (th/create-test-db)
                          (th/add-opponent)
                          (add-creature-with-state-trigger :player-2 7 7 7))
          sbas        (sba/check-sba db :state-check-trigger)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent creature")
      (is (= :state-check-trigger (:sba/type (first sbas)))
          "SBA type should be :state-check-trigger")
      (is (= obj-id (:sba/target (first sbas)))
          "SBA should target the opponent's creature"))))


(deftest test-check-and-execute-sbas-state-check-trigger-opponent-integration
  (testing "check-and-execute-sbas puts state-check-trigger on stack for opponent creature"
    (let [[db _obj-id] (-> (th/create-test-db)
                           (th/add-opponent)
                           (add-creature-with-state-trigger :player-2 7 7 7))
          db'          (sba/check-and-execute-sbas db)
          stack-items  (d/q '[:find [?e ...] :where [?e :stack-item/type :state-check-trigger]] db')]
      (is (= 1 (count stack-items))
          "Opponent's creature state trigger should appear on stack"))))


;; === :life-zero SBA opponent tests ===

(deftest test-check-sba-life-zero-fires-for-opponent
  (testing "check-sba :life-zero returns SBA event when opponent has life = 0"
    (let [db   (th/create-test-db)
          ;; Add opponent with 0 life via d/db-with after adding them
          db   (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db   (d/db-with db [[:db/add opp-eid :player/life 0]])
          sbas (sba/check-sba db :life-zero)]
      (is (= 1 (count sbas))
          "Should return exactly one SBA event for opponent at 0 life")
      (is (= :life-zero (:sba/type (first sbas)))
          "SBA type should be :life-zero")
      (is (= :player-2 (:sba/player-id (first sbas)))
          "SBA should identify the opponent as the player at 0 life"))))


(deftest test-check-and-execute-sbas-life-zero-opponent-integration
  (testing "check-and-execute-sbas detects opponent life=0 and sets loss condition with player-1 as winner"
    (let [db      (th/create-test-db)
          db      (th/add-opponent db)
          opp-eid (q/get-player-eid db :player-2)
          db      (d/db-with db [[:db/add opp-eid :player/life 0]])
          db'     (sba/check-and-execute-sbas db)
          game    (q/get-game-state db')
          winner-eid (:game/winner game)
          winner-pid (when winner-eid
                       (:player/id (d/pull db' [:player/id] (:db/id winner-eid))))]
      (is (= :life-zero (get-loss-condition db'))
          "check-and-execute-sbas should detect opponent life-zero")
      (is (= :player-1 winner-pid)
          "Player-1 should win when opponent reaches 0 life"))))


;; === Cascading SBA tests (real SBA types) ===

(deftest test-cascading-lethal-damage-then-token-cleanup
  (testing "lethal-damage on token → token moves to graveyard → token-cleanup retracts it"
    ;; First loop: :lethal-damage fires, moves token to graveyard.
    ;; Second loop: :token-cleanup fires, retracts token from graveyard.
    (let [[db token-id _card-eid] (-> (th/create-test-db)
                                      (th/add-opponent)
                                      ;; Create token directly on battlefield via add-test-token
                                      (add-test-token :player-1 :battlefield))
          ;; Give the token enough damage to die
          token-eid               (q/get-object-eid db token-id)
          ;; First we need to mark it as a 1/1 (add-test-token creates 1/1)
          ;; then give it 1 damage = lethal
          db                      (d/db-with db [[:db/add token-eid :object/damage-marked 1]
                                                 [:db/add token-eid :object/power 1]
                                                 [:db/add token-eid :object/toughness 1]])
          ;; Run full SBA loop
          db'                     (sba/check-and-execute-sbas db)]
      ;; Token should be fully gone (not just in graveyard — token-cleanup retracts it)
      (is (nil? (q/get-object-eid db' token-id))
          "Token should be fully retracted after lethal-damage + token-cleanup cascade"))))


(deftest test-cascading-both-players-lethal-damage
  (testing "lethal-damage SBA fires for both players' creatures simultaneously"
    (let [[db p1-id] (-> (th/create-test-db)
                         (th/add-opponent)
                         (th/add-test-creature :player-1 2 2))
          p1-eid     (q/get-object-eid db p1-id)
          [db p2-id] (th/add-test-creature db :player-2 3 3)
          p2-eid     (q/get-object-eid db p2-id)
          ;; Both creatures take lethal damage
          db         (d/db-with db [[:db/add p1-eid :object/damage-marked 2]
                                    [:db/add p2-eid :object/damage-marked 3]])
          sbas       (sba/check-sba db :lethal-damage)]
      (is (= 2 (count sbas))
          "Both creatures should trigger lethal-damage SBA")
      (let [targets (set (map :sba/target sbas))]
        (is (= #{p1-id p2-id} targets)
            "Both creature IDs should appear as SBA targets"))
      ;; Integration: both go to graveyard
      (let [db' (sba/check-and-execute-sbas db)]
        (is (= :graveyard (:object/zone (q/get-object db' p1-id)))
            "Player-1's creature should be in graveyard")
        (is (= :graveyard (:object/zone (q/get-object db' p2-id)))
            "Player-2's creature should be in graveyard")))))
