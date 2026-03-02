(ns fizzle.engine.state-based-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]
    [fizzle.test-helpers :as th]))


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
