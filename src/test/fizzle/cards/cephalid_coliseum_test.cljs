(ns fizzle.cards.cephalid-coliseum-test
  "Tests for Cephalid Coliseum land card.

   Cephalid Coliseum: Land
   {T}: Add {U}. Cephalid Coliseum deals 1 damage to you.
   Threshold — {U}, {T}, Sacrifice Cephalid Coliseum: Target player draws
   three cards, then discards three cards. Activate only if seven or more
   cards are in your graveyard.

   This tests:
   - Card definition (type, abilities, keywords)
   - Mana ability produces {U} AND deals 1 damage (NOT a trigger)
   - Threshold ability gating (7+ cards in graveyard)
   - Threshold ability costs ({U}, tap, sacrifice)
   - Player targeting for draw effect
   - Discard selection (player chooses 3)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.cephalid-coliseum :as coliseum]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.selection :as selection]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
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


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [db object-id] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn add-cards-to-graveyard
  "Add multiple cards to a player's graveyard.
   Returns [db object-ids] tuple."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        get-card-eid (fn [card-id]
                       (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id))]
    (loop [remaining-cards card-ids
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (get-card-eid card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :graveyard
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false}])
          (recur (rest remaining-cards)
                 (conj object-ids obj-id)))))))


(defn add-cards-to-library
  "Add multiple cards to the top of a player's library.
   Returns [db object-ids] tuple with object-ids in order (first = top of library)."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        get-card-eid (fn [card-id]
                       (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id))]
    (loop [remaining-cards card-ids
           position 0
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (get-card-eid card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining-cards)
                 (inc position)
                 (conj object-ids obj-id)))))))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn get-graveyard-count
  "Get the number of cards in a player's graveyard."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :graveyard)))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db player-id]
  (count (q/get-hand db player-id)))


(defn set-mana-pool
  "Set a player's mana pool to specific values."
  [db player-id mana-pool]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (d/transact! conn [[:db/add player-eid :player/mana-pool mana-pool]])
    @conn))


;; === Card Definition Tests ===

(deftest test-cephalid-coliseum-is-land-type
  (testing "Cephalid Coliseum has :land in types"
    (is (= #{:land} (:card/types coliseum/cephalid-coliseum))
        "Cephalid Coliseum should be a land")))


(deftest test-cephalid-coliseum-has-two-abilities
  (testing "Cephalid Coliseum has exactly 2 abilities"
    (is (= 2 (count (:card/abilities coliseum/cephalid-coliseum)))
        "Cephalid Coliseum should have exactly 2 abilities (mana + threshold)")))


(deftest test-cephalid-coliseum-has-threshold-keyword
  (testing "Cephalid Coliseum has :threshold keyword"
    (is (= #{:threshold} (:card/keywords coliseum/cephalid-coliseum))
        "Cephalid Coliseum should have :threshold keyword")))


;; === Mana Ability Tests ===

(deftest test-coliseum-tap-produces-blue-mana
  (testing "Tapping Cephalid Coliseum produces {U}"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          _ (is (= :battlefield (get-object-zone db' obj-id))
                "Precondition: Coliseum starts on battlefield")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id nil)]
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added to pool"))))


(deftest test-coliseum-tap-deals-one-damage-to-controller
  (testing "Tapping Cephalid Coliseum deals 1 damage to controller"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id nil)]
      (is (= 19 (q/get-life-total db'' :player-1))
          "Player should have taken 1 damage from Coliseum"))))


(deftest test-coliseum-tap-mana-and-damage-happen-together
  (testing "Single activation produces mana AND deals damage (not a trigger)"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          initial-life (q/get-life-total db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          _ (is (= 20 initial-life) "Precondition: life is 20")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id nil)]
      ;; Both should happen from single activation
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added")
      (is (= 19 (q/get-life-total db'' :player-1))
          "Damage should be dealt")
      ;; Verify NO trigger was used (card should have no triggers)
      (is (empty? (:card/triggers coliseum/cephalid-coliseum))
          "Coliseum should NOT have any triggers (damage is part of ability)"))))


(deftest test-coliseum-cannot-tap-when-already-tapped
  (testing "Already tapped Coliseum cannot activate mana ability"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Tap the land manually
          conn (d/conn-from-db db')
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db' obj-id)
          _ (d/transact! conn [[:db/add obj-eid :object/tapped true]])
          db-tapped @conn
          initial-pool (q/get-mana-pool db-tapped :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          ;; Try to activate mana ability on tapped land
          db'' (ability-events/activate-mana-ability db-tapped :player-1 obj-id nil)]
      (is (= 0 (:blue (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (land was already tapped)"))))


;; === Threshold Ability Tests ===

(deftest test-coliseum-threshold-blocked-with-six-cards-in-graveyard
  (testing "Threshold ability cannot be activated with only 6 cards in graveyard"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add exactly 6 cards to graveyard (below threshold)
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp]
                                           :player-1)
          ;; Add blue mana to pool (so mana cost is payable)
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          _ (is (= 6 (get-graveyard-count db''' :player-1))
                "Precondition: exactly 6 cards in graveyard")
          ;; Get the threshold ability (second ability)
          threshold-ability (second (:card/abilities coliseum/cephalid-coliseum))
          can-activate? (abilities/can-activate? db''' obj-id threshold-ability :player-1)]
      (is (not can-activate?)
          "Threshold ability should NOT be activatable with only 6 cards in graveyard"))))


(deftest test-coliseum-threshold-activatable-with-seven-cards-in-graveyard
  (testing "Threshold ability can be activated with 7+ cards in graveyard"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add exactly 7 cards to graveyard (at threshold)
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana to pool
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          _ (is (= 7 (get-graveyard-count db''' :player-1))
                "Precondition: exactly 7 cards in graveyard")
          ;; Get the threshold ability (second ability)
          threshold-ability (second (:card/abilities coliseum/cephalid-coliseum))
          can-activate? (abilities/can-activate? db''' obj-id threshold-ability :player-1)]
      (is can-activate?
          "Threshold ability should be activatable with 7 cards in graveyard"))))


(deftest test-coliseum-threshold-requires-blue-mana
  (testing "Threshold ability requires {U} in mana pool"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard (at threshold)
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Mana pool is empty (no blue mana)
          _ (is (= 0 (:blue (q/get-mana-pool db'' :player-1)))
                "Precondition: no blue mana in pool")
          ;; Get the threshold ability (second ability)
          threshold-ability (second (:card/abilities coliseum/cephalid-coliseum))
          can-activate? (abilities/can-activate? db'' obj-id threshold-ability :player-1)]
      (is (not can-activate?)
          "Threshold ability should NOT be activatable without {U} in pool"))))


(deftest test-coliseum-threshold-sacrifices-land
  (testing "Activating threshold ability sacrifices the land (after target confirmation)"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana and cards to library for drawing
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          [db'''' _] (add-cards-to-library db''' [:island :island :island] :player-1)
          _ (is (= :battlefield (get-object-zone db'''' obj-id))
                "Precondition: Coliseum on battlefield")
          ;; Activate threshold ability (index 1 = second ability)
          ;; Now returns pending selection for targeting
          result (ability-events/activate-ability db'''' :player-1 obj-id 1)
          selection (:pending-selection result)
          ;; Coliseum should still be on battlefield (costs not paid yet)
          _ (is (= :battlefield (get-object-zone (:db result) obj-id))
                "Coliseum should still be on battlefield before target confirmed")
          ;; Confirm target selection (targeting self)
          selection-with-target (assoc selection :selection/selected-target :player-1)
          final-result (ability-events/confirm-ability-target (:db result) selection-with-target)]
      ;; After confirmation, land should be in graveyard (sacrifice paid as cost)
      (is (= :graveyard (get-object-zone (:db final-result) obj-id))
          "Coliseum should be in graveyard after target confirmation"))))


(deftest test-coliseum-threshold-draws-three-cards
  (testing "Threshold ability has draw 3 effect"
    ;; This test verifies the card definition has correct draw effect
    ;; Actual drawing happens on stack resolution, which requires player targeting
    (let [threshold-ability (second (:card/abilities coliseum/cephalid-coliseum))
          draw-effect (first (:ability/effects threshold-ability))]
      (is (= :draw (:effect/type draw-effect))
          "First effect should be draw")
      (is (= 3 (:effect/amount draw-effect))
          "Should draw 3 cards")
      (is (= :any-player (:effect/target draw-effect))
          "Should allow targeting any player"))))


(deftest test-coliseum-threshold-target-discards-three
  (testing "Threshold ability has discard 3 effect with player selection"
    ;; This test verifies the card definition has correct discard effect
    (let [threshold-ability (second (:card/abilities coliseum/cephalid-coliseum))
          discard-effect (second (:ability/effects threshold-ability))]
      (is (= :discard (:effect/type discard-effect))
          "Second effect should be discard")
      (is (= 3 (:effect/count discard-effect))
          "Should discard 3 cards")
      (is (= :player (:effect/selection discard-effect))
          "Should require player selection for which cards to discard"))))


;; === Edge Case Tests ===

(deftest test-coliseum-threshold-targeting-self
  (testing "Threshold ability activation pauses for targeting, then puts ability on stack"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana and cards to library
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          [db'''' _] (add-cards-to-library db''' [:island :island :island :swamp] :player-1)
          ;; Activate threshold ability (index 1 = second ability)
          ;; Now returns pending selection for targeting
          result (ability-events/activate-ability db'''' :player-1 obj-id 1)
          selection (:pending-selection result)
          ;; Stack should be empty (waiting for target)
          _ (is (true? (stack/stack-empty? (:db result)))
                "Stack should be empty before target selected")
          ;; Confirm target selection (targeting self)
          selection-with-target (assoc selection :selection/selected-target :player-1)
          final-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          ;; Check that ability was put on stack after target confirmation
          top-item (stack/get-top-stack-item (:db final-result))]
      ;; Should have a stack-item on stack
      (is (some? top-item)
          "Should have stack-item on stack after target confirmed")
      (is (= :activated-ability (:stack-item/type top-item))
          "Stack item should be an activated ability"))))


(deftest test-coliseum-threshold-targeting-opponent
  (testing "Threshold ability can target opponent for draw/discard"
    ;; Note: This test validates the :effect/target :any-player works
    ;; The actual opponent handling depends on game state having opponent
    (let [effect (second (:card/abilities coliseum/cephalid-coliseum))
          draw-effect (first (:ability/effects effect))]
      (is (= :any-player (:effect/target draw-effect))
          "Draw effect should allow targeting any player"))))


(deftest test-coliseum-in-graveyard-cannot-activate
  (testing "Coliseum in graveyard cannot activate either ability"
    (let [db (create-test-db)
          ;; Add Coliseum to graveyard (not battlefield)
          [db' obj-id] (add-card-to-zone db :cephalid-coliseum :graveyard :player-1)
          _ (is (= :graveyard (get-object-zone db' obj-id))
                "Precondition: Coliseum is in graveyard")
          initial-pool (q/get-mana-pool db' :player-1)
          initial-life (q/get-life-total db' :player-1)
          ;; Try to activate mana ability from graveyard
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id nil)]
      ;; Nothing should happen
      (is (= (:blue initial-pool) (:blue (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added from graveyard")
      (is (= initial-life (q/get-life-total db'' :player-1))
          "Life should NOT change from graveyard activation")
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Coliseum should remain in graveyard"))))


;; === Cast-Time Ability Targeting Tests ===

(deftest test-threshold-ability-has-targeting-requirement
  (testing "Threshold ability has :ability/targeting for player targeting"
    (let [threshold-ability (second (:card/abilities coliseum/cephalid-coliseum))
          targeting-reqs (targeting/get-targeting-requirements threshold-ability)]
      (is (= 1 (count targeting-reqs))
          "Threshold ability should have exactly 1 targeting requirement")
      (is (= :player (:target/type (first targeting-reqs)))
          "Target type should be :player")
      (is (= :player (:target/id (first targeting-reqs)))
          "Target id should be :player")
      (is (true? (:target/required (first targeting-reqs)))
          "Target should be required"))))


(deftest test-threshold-activation-prompts-for-target
  (testing "Activating threshold ability returns pending selection instead of immediate stack"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          ;; Activate threshold ability
          result (ability-events/activate-ability db''' :player-1 obj-id 1)
          selection (:pending-selection result)]
      ;; Should return pending selection
      (is (some? selection)
          "Should have pending selection for target")
      (is (= :ability-targeting (:selection/type selection))
          "Selection type should be :ability-targeting")
      (is (= :player-1 (:selection/player-id selection))
          "Selection should track activating player")
      (is (= obj-id (:selection/object-id selection))
          "Selection should track source object")
      (is (= 1 (:selection/ability-index selection))
          "Selection should track ability index")
      ;; Stack should be empty (waiting for target)
      (is (true? (stack/stack-empty? (:db result)))
          "Stack should be empty before target confirmed")
      ;; Costs should NOT be paid yet
      (is (= :battlefield (get-object-zone (:db result) obj-id))
          "Land should still be on battlefield (not sacrificed yet)"))))


(deftest test-threshold-target-stored-in-trigger
  (testing "Confirmed ability target is stored in trigger context"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana and cards to library
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          [db'''' _] (add-cards-to-library db''' [:island :island :island] :player-1)
          ;; Activate and get selection
          result (ability-events/activate-ability db'''' :player-1 obj-id 1)
          selection (:pending-selection result)
          ;; Confirm with target
          selection-with-target (assoc selection :selection/selected-target :player-1)
          final-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          ;; Get the stack-item from stack
          item (stack/get-top-stack-item (:db final-result))
          item-targets (:stack-item/targets item)]
      ;; Should have stored target
      (is (= {:player :player-1} item-targets)
          "Stack-item should contain stored target"))))


(deftest test-threshold-activation-cancelled-preserves-state
  (testing "Cancelled target selection does not activate ability"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          initial-mana (q/get-mana-pool db''' :player-1)
          ;; Activate and get selection
          result (ability-events/activate-ability db''' :player-1 obj-id 1)
          selection (:pending-selection result)
          ;; Cancel by confirming with nil target
          selection-cancelled (assoc selection :selection/selected-target nil)
          final-result (ability-events/confirm-ability-target (:db result) selection-cancelled)]
      ;; Land should still be on battlefield (not sacrificed)
      (is (= :battlefield (get-object-zone (:db final-result) obj-id))
          "Land should still be on battlefield after cancellation")
      ;; Mana pool should be unchanged
      (is (= initial-mana (q/get-mana-pool (:db final-result) :player-1))
          "Mana pool should be unchanged after cancellation")
      ;; Stack should be empty
      (is (true? (stack/stack-empty? (:db final-result)))
          "Stack should be empty after cancellation"))))


;; === Threshold Resolution Integration Test ===

(deftest test-coliseum-threshold-resolution-draws-and-discards
  ;; Bug caught: threshold ability resolution broken
  (testing "Activate threshold, confirm target, resolve stack item - draw 3, discard pending"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :cephalid-coliseum :player-1)
          ;; Add 7 cards to graveyard for threshold
          [db'' _] (add-cards-to-graveyard db'
                                           [:dark-ritual :dark-ritual :cabal-ritual
                                            :brain-freeze :island :swamp :lotus-petal]
                                           :player-1)
          ;; Add blue mana and 5 cards to library for drawing
          db''' (set-mana-pool db'' :player-1 {:white 0 :blue 1 :black 0
                                               :red 0 :green 0 :colorless 0})
          [db'''' _lib-ids] (add-cards-to-library db''' [:island :swamp :dark-ritual
                                                         :cabal-ritual :brain-freeze]
                                                  :player-1)
          initial-hand (get-hand-count db'''' :player-1)
          _ (is (= 0 initial-hand) "Precondition: hand is empty")
          ;; Activate threshold ability (index 1)
          result (ability-events/activate-ability db'''' :player-1 obj-id 1)
          selection (:pending-selection result)
          _ (is (some? selection) "Should have pending target selection")
          ;; Confirm target (self)
          selection-with-target (assoc selection :selection/selected-target :player-1)
          final-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          db-after-confirm (:db final-result)
          ;; Stack should have the ability
          top-item (stack/get-top-stack-item db-after-confirm)
          _ (is (some? top-item) "Should have stack-item on stack")
          ;; Resolve the stack item ability (pass full entity, not eid)
          resolve-result (selection/resolve-stack-item-ability-with-selection
                           db-after-confirm top-item)]
      ;; After resolution, player should have drawn 3 cards
      (is (= 3 (get-hand-count (:db resolve-result) :player-1))
          "Player should have drawn 3 cards from threshold ability")
      ;; Should have pending discard selection (discard 3)
      (is (some? (:pending-selection resolve-result))
          "Should have pending discard selection for 3 cards")
      (is (= :discard (get-in resolve-result [:pending-selection :selection/effect-type]))
          "Pending selection should be for discard")
      (is (= 3 (get-in resolve-result [:pending-selection :selection/select-count]))
          "Should require discarding 3 cards"))))
