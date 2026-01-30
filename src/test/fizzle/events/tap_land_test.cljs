(ns fizzle.events.tap-land-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with land cards and player."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-land-to-battlefield
  "Add a land card to the battlefield for a player.
   Returns [db object-id] tuple."
  [db card-id player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn get-object-tapped
  "Get the tapped state of an object by its ID."
  [db object-id]
  (:object/tapped (q/get-object db object-id)))


;; === tap-permanent tests ===

(deftest test-tap-permanent-sets-tapped-true
  (testing "tap-permanent sets :object/tapped to true on untapped land"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          _ (is (false? (get-object-tapped db' obj-id))
                "Precondition: land starts untapped")
          db'' (game/tap-permanent db' obj-id)]
      (is (true? (get-object-tapped db'' obj-id))
          "Land should be tapped after tap-permanent"))))


;; === activate-mana-ability tests ===

(deftest test-activate-mana-ability-adds-mana-to-pool
  (testing "activate-mana-ability taps land and adds chosen mana color to pool"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db'' (game/activate-mana-ability db' :player-1 obj-id :black)]
      (is (true? (get-object-tapped db'' obj-id))
          "Land should be tapped after activating mana ability")
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Black mana should be added to pool"))))


(deftest test-activate-mana-ability-any-color
  (testing "activate-mana-ability adds any color from 5-color land"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          db'' (game/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added to pool"))))


;; === double-tap prevention tests ===

(deftest test-activate-mana-ability-fails-on-tapped-land
  (testing "activate-mana-ability returns unchanged db when land already tapped"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          ;; First tap produces mana
          db-after-first-tap (game/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 1 (:black (q/get-mana-pool db-after-first-tap :player-1)))
                "Precondition: first tap adds mana")
          _ (is (true? (get-object-tapped db-after-first-tap obj-id))
                "Precondition: land is tapped")
          ;; Second tap should fail (no additional mana)
          db-after-second-tap (game/activate-mana-ability db-after-first-tap :player-1 obj-id :black)]
      (is (= 1 (:black (q/get-mana-pool db-after-second-tap :player-1)))
          "Second tap should NOT add more mana")
      (is (true? (get-object-tapped db-after-second-tap obj-id))
          "Land should still be tapped"))))


;; === City of Brass damage tests ===

(deftest test-city-of-brass-deals-damage-when-tapped
  (testing "City of Brass deals 1 damage to controller when tapped for mana"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Tap for mana - trigger goes on stack
          db-after-tap (game/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 20 (q/get-life-total db-after-tap :player-1))
                "Life unchanged before trigger resolves (trigger on stack)")
          _ (is (= 1 (count (q/get-stack-items db-after-tap)))
                "One trigger should be on the stack")
          ;; Resolve the trigger
          db-after-resolve (game/resolve-top-trigger db-after-tap)]
      (is (= 19 (q/get-life-total db-after-resolve :player-1))
          "Player should lose 1 life after trigger resolves"))))


(deftest test-city-of-brass-cumulative-damage
  (testing "Multiple City of Brass taps deal cumulative damage"
    (let [db (create-test-db)
          ;; Add two City of Brass to battlefield
          [db' obj-id1] (add-land-to-battlefield db :city-of-brass :player-1)
          [db'' obj-id2] (add-land-to-battlefield db' :city-of-brass :player-1)
          initial-life (q/get-life-total db'' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Tap both lands - both triggers go on stack
          db-after-first-tap (game/activate-mana-ability db'' :player-1 obj-id1 :black)
          db-after-second-tap (game/activate-mana-ability db-after-first-tap :player-1 obj-id2 :blue)
          _ (is (= 20 (q/get-life-total db-after-second-tap :player-1))
                "Life unchanged before triggers resolve")
          _ (is (= 2 (count (q/get-stack-items db-after-second-tap)))
                "Two triggers should be on the stack")
          ;; Resolve both triggers
          db-after-first-resolve (game/resolve-top-trigger db-after-second-tap)
          db-after-second-resolve (game/resolve-top-trigger db-after-first-resolve)]
      (is (= 18 (q/get-life-total db-after-second-resolve :player-1))
          "Player should lose 2 life total after resolving both triggers"))))


(deftest test-gemstone-mine-no-damage
  (testing "Gemstone Mine does NOT deal damage when tapped (only City of Brass does)"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :gemstone-mine :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          db'' (game/activate-mana-ability db' :player-1 obj-id :green)]
      (is (= 20 (q/get-life-total db'' :player-1))
          "Player should NOT lose life when tapping Gemstone Mine"))))


;; === untap step tests ===

(deftest test-start-turn-untaps-all-lands
  (testing "start-turn untaps all tapped permanents controlled by the player"
    (let [db (create-test-db)
          ;; Add two lands to battlefield
          [db' obj-id1] (add-land-to-battlefield db :city-of-brass :player-1)
          [db'' obj-id2] (add-land-to-battlefield db' :gemstone-mine :player-1)
          ;; Tap both lands
          db-tapped (-> db''
                        (game/tap-permanent obj-id1)
                        (game/tap-permanent obj-id2))
          _ (is (true? (get-object-tapped db-tapped obj-id1))
                "Precondition: first land is tapped")
          _ (is (true? (get-object-tapped db-tapped obj-id2))
                "Precondition: second land is tapped")
          ;; Start new turn
          db-new-turn (game/start-turn db-tapped :player-1)]
      (is (false? (get-object-tapped db-new-turn obj-id1))
          "First land should be untapped after start-turn")
      (is (false? (get-object-tapped db-new-turn obj-id2))
          "Second land should be untapped after start-turn"))))


;; === Data-driven ability tests ===

(defn get-object-counters
  "Get the counters map of an object by its ID."
  [db object-id]
  (:object/counters (q/get-object db object-id)))


(deftest test-city-of-brass-damage-via-trigger
  (testing "City of Brass damage fires via trigger on the stack"
    ;; This test verifies that City of Brass damage comes from trigger resolution
    ;; on the stack, not from immediate effect in activate-mana-ability.
    ;; The trigger uses the stack per MTG rules - you can respond before damage.
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          ;; Activate mana ability - trigger goes on stack
          db-after-tap (game/activate-mana-ability db' :player-1 obj-id :black)]
      ;; Verify trigger is on stack (damage not yet applied)
      (is (= 20 (q/get-life-total db-after-tap :player-1))
          "Life unchanged - trigger is on stack, can respond before damage")
      (is (seq (q/get-stack-items db-after-tap))
          "Trigger should be on the stack")
      ;; Verify mana was added immediately (before trigger resolves)
      (is (= 1 (:black (q/get-mana-pool db-after-tap :player-1)))
          "Mana is available immediately, even with trigger on stack")
      ;; Resolve trigger - now damage happens
      (let [db-after-resolve (game/resolve-top-trigger db-after-tap)]
        (is (= 19 (q/get-life-total db-after-resolve :player-1))
            "Player loses 1 life after trigger resolves")))))


(deftest test-gemstone-mine-etb-counters
  (testing "Gemstone Mine enters with 3 mining counters"
    (let [db (create-test-db)
          ;; Add Gemstone Mine to hand first
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :gemstone-mine)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])
      ;; Play the land (should trigger ETB)
      (let [db' (game/play-land @conn :player-1 obj-id)]
        ;; Verify land is on battlefield
        (is (= :battlefield (:object/zone (q/get-object db' obj-id)))
            "Gemstone Mine should be on battlefield")
        ;; Verify counters were added
        (is (= {:mining 3} (get-object-counters db' obj-id))
            "Gemstone Mine should enter with 3 mining counters")))))


(deftest test-gemstone-mine-counter-cost
  (testing "Gemstone Mine activation removes mining counter"
    (let [db (create-test-db)
          ;; Add Gemstone Mine directly with counters (simulating after ETB)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :gemstone-mine)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :battlefield
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false
                          :object/counters {:mining 3}}])
      ;; Activate mana ability
      (let [db' (game/activate-mana-ability @conn :player-1 obj-id :blue)]
        ;; Verify mana was added
        (is (= 1 (:blue (q/get-mana-pool db' :player-1)))
            "Blue mana should be added to pool")
        ;; Verify counter was removed
        (is (= {:mining 2} (get-object-counters db' obj-id))
            "Gemstone Mine should have 2 mining counters after activation")))))


(deftest test-gemstone-mine-sacrifice-at-zero
  (testing "Gemstone Mine sacrificed when counters reach 0"
    (let [db (create-test-db)
          ;; Add Gemstone Mine with only 1 counter (final activation)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :gemstone-mine)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :battlefield
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false
                          :object/counters {:mining 1}}])
      ;; Activate mana ability (should deplete final counter)
      (let [db' (game/activate-mana-ability @conn :player-1 obj-id :green)]
        ;; Verify mana was added
        (is (= 1 (:green (q/get-mana-pool db' :player-1)))
            "Green mana should be added to pool")
        ;; Verify object is now in graveyard (sacrificed via SBA)
        (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
            "Gemstone Mine should be in graveyard after last counter removed")))))
