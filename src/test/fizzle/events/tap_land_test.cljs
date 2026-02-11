(ns fizzle.events.tap-land-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.state-based :as state-based]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.abilities :as ability-events]
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
    ;; Transact game state and game-rule triggers
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}])
      (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid)))
    @conn))


(defn add-land-to-battlefield
  "Add a land card to the battlefield for a player.
   Also creates Datascript trigger entities for cards with triggers.
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
    ;; Create Datascript trigger entities for this card
    (let [result-db @conn
          card (d/pull result-db '[*] card-eid)]
      (when (seq (:card/triggers card))
        (let [obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                           result-db obj-id)
              tx (trigger-db/create-triggers-for-card-tx result-db obj-eid player-eid (:card/triggers card))]
          (d/transact! conn tx))))
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
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      (is (true? (get-object-tapped db'' obj-id))
          "Land should be tapped after activating mana ability")
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Black mana should be added to pool"))))


(deftest test-activate-mana-ability-any-color
  (testing "activate-mana-ability adds any color from 5-color land"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added to pool"))))


;; === double-tap prevention tests ===

(deftest test-activate-mana-ability-fails-on-tapped-land
  (testing "activate-mana-ability returns unchanged db when land already tapped"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :city-of-brass :player-1)
          ;; First tap produces mana
          db-after-first-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 1 (:black (q/get-mana-pool db-after-first-tap :player-1)))
                "Precondition: first tap adds mana")
          _ (is (true? (get-object-tapped db-after-first-tap obj-id))
                "Precondition: land is tapped")
          ;; Second tap should fail (no additional mana)
          db-after-second-tap (ability-events/activate-mana-ability db-after-first-tap :player-1 obj-id :black)]
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
          db-after-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)
          _ (is (= 20 (q/get-life-total db-after-tap :player-1))
                "Life unchanged before trigger resolves (trigger on stack)")
          _ (is (= 1 (count (stack/get-all-stack-items db-after-tap)))
                "One trigger should be on the stack")
          ;; Resolve the trigger
          db-after-resolve (game/resolve-top-of-stack db-after-tap :player-1)]
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
          db-after-first-tap (ability-events/activate-mana-ability db'' :player-1 obj-id1 :black)
          db-after-second-tap (ability-events/activate-mana-ability db-after-first-tap :player-1 obj-id2 :blue)
          _ (is (= 20 (q/get-life-total db-after-second-tap :player-1))
                "Life unchanged before triggers resolve")
          _ (is (= 2 (count (stack/get-all-stack-items db-after-second-tap)))
                "Two triggers should be on the stack")
          ;; Resolve both triggers
          db-after-first-resolve (game/resolve-top-of-stack db-after-second-tap :player-1)
          db-after-second-resolve (game/resolve-top-of-stack db-after-first-resolve :player-1)]
      (is (= 18 (q/get-life-total db-after-second-resolve :player-1))
          "Player should lose 2 life total after resolving both triggers"))))


(deftest test-gemstone-mine-no-damage
  (testing "Gemstone Mine does NOT deal damage when tapped (only City of Brass does)"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :gemstone-mine :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :green)]
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
          db-after-tap (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      ;; Verify trigger is on stack (damage not yet applied)
      (is (= 20 (q/get-life-total db-after-tap :player-1))
          "Life unchanged - trigger is on stack, can respond before damage")
      (is (seq (stack/get-all-stack-items db-after-tap))
          "Trigger should be on the stack")
      ;; Verify mana was added immediately (before trigger resolves)
      (is (= 1 (:black (q/get-mana-pool db-after-tap :player-1)))
          "Mana is available immediately, even with trigger on stack")
      ;; Resolve trigger - now damage happens
      (let [db-after-resolve (game/resolve-top-of-stack db-after-tap :player-1)]
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
      (let [db' (ability-events/activate-mana-ability @conn :player-1 obj-id :blue)]
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
      (let [db' (ability-events/activate-mana-ability @conn :player-1 obj-id :green)]
        ;; Verify mana was added
        (is (= 1 (:green (q/get-mana-pool db' :player-1)))
            "Green mana should be added to pool")
        ;; Verify object is now in graveyard (sacrificed via SBA)
        (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
            "Gemstone Mine should be in graveyard after last counter removed")))))


;; === Stack LIFO ordering tests ===

(deftest test-spell-resolves-before-earlier-trigger
  (testing "Spell cast AFTER tapping City of Brass resolves BEFORE the trigger (LIFO)"
    ;; Scenario: 1) Tap City of Brass (trigger on stack), 2) Cast Dark Ritual
    ;; Stack order: [Dark Ritual (top), City of Brass trigger (bottom)]
    ;; Resolution: Dark Ritual first, then City of Brass damage
    (let [db (create-test-db)
          ;; Add City of Brass to battlefield
          [db' cob-id] (add-land-to-battlefield db :city-of-brass :player-1)
          ;; Create conn from db' (which has City of Brass)
          conn (d/conn-from-db db')
          player-eid (q/get-player-eid db' :player-1)
          ;; Add Dark Ritual to hand
          ritual-eid (d/q '[:find ?e .
                            :in $ ?cid
                            :where [?e :card/id ?cid]]
                          db' :dark-ritual)
          ritual-obj-id (random-uuid)
          _ (d/transact! conn [{:object/id ritual-obj-id
                                :object/card ritual-eid
                                :object/zone :hand
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
          db-with-ritual @conn
          initial-life (q/get-life-total db-with-ritual :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")

          ;; Step 1: Tap City of Brass for black mana - trigger goes on stack
          db-after-tap (ability-events/activate-mana-ability db-with-ritual :player-1 cob-id :black)
          _ (is (= 1 (:black (q/get-mana-pool db-after-tap :player-1)))
                "Should have 1 black mana from City of Brass")
          _ (is (= 1 (count (stack/get-all-stack-items db-after-tap)))
                "City of Brass trigger on stack")
          _ (is (= 20 (q/get-life-total db-after-tap :player-1))
                "Life unchanged - trigger not yet resolved")

          ;; Step 2: Cast Dark Ritual (costs B, produces BBB) - spell goes on stack
          db-after-cast (rules/cast-spell db-after-tap :player-1 ritual-obj-id)
          _ (is (= :stack (:object/zone (q/get-object db-after-cast ritual-obj-id)))
                "Dark Ritual on stack")
          _ (is (= 0 (:black (q/get-mana-pool db-after-cast :player-1)))
                "Black mana spent on casting Dark Ritual")

          ;; Stack now has: Dark Ritual (top), City of Brass trigger (bottom)
          ;; Step 3: Resolve top of stack - should be Dark Ritual (LIFO)
          db-after-first-resolve (game/resolve-top-of-stack db-after-cast :player-1)
          _ (is (= 20 (q/get-life-total db-after-first-resolve :player-1))
                "Life STILL unchanged - Dark Ritual resolved first, not the trigger")
          _ (is (= 3 (:black (q/get-mana-pool db-after-first-resolve :player-1)))
                "Dark Ritual produced 3 black mana")
          _ (is (= :graveyard (:object/zone (q/get-object db-after-first-resolve ritual-obj-id)))
                "Dark Ritual in graveyard after resolution")
          _ (is (= 1 (count (stack/get-all-stack-items db-after-first-resolve)))
                "City of Brass trigger still on stack")

          ;; Step 4: Resolve top of stack again - now City of Brass trigger
          db-after-second-resolve (game/resolve-top-of-stack db-after-first-resolve :player-1)]
      (is (= 19 (q/get-life-total db-after-second-resolve :player-1))
          "NOW player loses 1 life from City of Brass trigger")
      (is (= 0 (count (stack/get-all-stack-items db-after-second-resolve)))
          "Stack is empty"))))


;; === Gemstone Mine sacrifice behavior tests ===

(deftest test-gemstone-mine-no-sacrifice-on-manual-counter-removal
  (testing "Gemstone Mine does NOT sacrifice when counters removed by non-ability means"
    ;; This tests the key rules fix: sacrifice is part of the mana ability,
    ;; NOT a state-based action. If counters are removed by external effect,
    ;; Gemstone Mine should remain on the battlefield.
    (let [db (create-test-db)
          ;; Add Gemstone Mine with 3 counters
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
      ;; Directly set counters to 0 (simulating external effect, NOT mana ability)
      (let [obj-eid (d/q '[:find ?e .
                           :in $ ?oid
                           :where [?e :object/id ?oid]]
                         @conn obj-id)]
        (d/transact! conn [[:db/add obj-eid :object/counters {:mining 0}]]))
      ;; Now run state-based actions (the old implementation would sacrifice here)
      (let [db-after-sba (state-based/check-and-execute-sbas @conn)]
        ;; Gemstone Mine should STILL be on battlefield - no SBA sacrifice
        (is (= :battlefield (:object/zone (q/get-object db-after-sba obj-id)))
            "Gemstone Mine should NOT sacrifice when counters removed externally")))))


(deftest test-gemstone-mine-sacrifices-on-last-counter
  (testing "Gemstone Mine sacrifices when mana ability activates AND counters = 0"
    ;; This verifies the rules-correct behavior: sacrifice happens as part
    ;; of the mana ability resolution when the last counter is removed.
    (let [db (create-test-db)
          ;; Add Gemstone Mine with exactly 1 counter (final activation)
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
      ;; Activate mana ability (removes last counter AND should sacrifice)
      (let [db' (ability-events/activate-mana-ability @conn :player-1 obj-id :green)]
        ;; Mana should be added
        (is (= 1 (:green (q/get-mana-pool db' :player-1)))
            "Green mana should be added to pool")
        ;; Gemstone Mine should be in graveyard (sacrificed as part of ability)
        (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
            "Gemstone Mine should sacrifice as part of mana ability resolution")))))


(deftest test-mana-ability-executes-ability-effects
  (testing "activate-mana-ability processes :ability/effects after adding mana"
    ;; This test verifies that activate-mana-ability executes :ability/effects.
    ;; We create a custom test card with a mana ability that has a gain life effect.
    (let [db (create-test-db)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          ;; Create a test land with mana ability that has :ability/effects
          test-card-id (keyword (str "test-land-" (random-uuid)))
          _ (d/transact! conn [{:card/id test-card-id
                                :card/name "Test Land with Effect"
                                :card/types #{:land}
                                :card/abilities [{:ability/type :mana
                                                  :ability/cost {:tap true}
                                                  :ability/produces {:green 1}
                                                  :ability/effects [{:effect/type :gain-life
                                                                     :effect/amount 1}]}]}])
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn test-card-id)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :battlefield
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])
      (let [initial-life (q/get-life-total @conn :player-1)
            _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
            db' (ability-events/activate-mana-ability @conn :player-1 obj-id :green)]
        ;; Mana should be added
        (is (= 1 (:green (q/get-mana-pool db' :player-1)))
            "Green mana should be added to pool")
        ;; Effect should be executed (gain 1 life)
        (is (= 21 (q/get-life-total db' :player-1))
            "Player should gain 1 life from ability effect")))))


;; === Mana ability corner case tests ===

(deftest test-mana-ability-wrong-color-request
  (testing "Requesting wrong color from basic land produces the land's actual color"
    ;; Swamp only produces black. Requesting blue should still produce black
    ;; (the mana-color param is only relevant for :any mana)
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :swamp :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          ;; Request blue from a Swamp (which only produces black)
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      ;; Swamp should produce black, not blue (ignores color request for fixed mana)
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Swamp produces black even when blue requested")
      (is (= 0 (:blue (q/get-mana-pool db'' :player-1)))
          "No blue mana produced")
      (is (true? (get-object-tapped db'' obj-id))
          "Swamp should be tapped after activation"))))


(deftest test-colorless-mana-from-colored-only-land
  (testing "Requesting colorless from a basic land produces the land's actual color"
    ;; Island only produces blue. Requesting colorless should produce blue
    ;; (only lands with :any or :colorless in produces can produce colorless)
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :island :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          _ (is (= 0 (:colorless initial-pool)) "Precondition: colorless mana is 0")
          ;; Request colorless from an Island (which only produces blue)
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :colorless)]
      ;; Island should produce blue, not colorless
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Island produces blue even when colorless requested")
      (is (= 0 (:colorless (q/get-mana-pool db'' :player-1)))
          "No colorless mana produced")
      (is (true? (get-object-tapped db'' obj-id))
          "Island should be tapped after activation"))))


(deftest test-mana-ability-atomic-resolution
  (testing "Mana abilities resolve atomically (no stack interruption possible)"
    ;; MTG Rules: Mana abilities don't use the stack; they resolve immediately.
    ;; This test verifies that Gemstone Mine sacrifice happens as part of
    ;; the mana ability resolution - there's no window to interrupt.
    ;;
    ;; Corner case "object-destroyed-between-cost-and-effect" is N/A because:
    ;; 1. Mana abilities don't use the stack
    ;; 2. Cost payment and mana production are atomic
    ;; 3. Sacrifice (if any) happens at the END of ability resolution
    ;;
    ;; We verify this by showing Gemstone Mine produces mana BEFORE being sacrificed.
    (let [db (create-test-db)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :gemstone-mine)
          obj-id (random-uuid)]
      ;; Add Gemstone Mine with 1 counter (final activation)
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :battlefield
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false
                          :object/counters {:mining 1}}])
      ;; Activate - this pays tap+counter cost, produces mana, THEN sacrifices
      (let [db' (ability-events/activate-mana-ability @conn :player-1 obj-id :red)]
        ;; Mana was produced (ability worked before sacrifice)
        (is (= 1 (:red (q/get-mana-pool db' :player-1)))
            "Red mana produced before sacrifice")
        ;; Object is now in graveyard (sacrificed as part of ability)
        (is (= :graveyard (:object/zone (q/get-object db' obj-id)))
            "Gemstone Mine sacrificed after mana produced")
        ;; Key insight: there's no window where mana was NOT produced but
        ;; object was already sacrificed - the resolution is atomic
        ))))
