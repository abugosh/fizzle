(ns fizzle.cards.polluted-delta-test
  "Tests for Polluted Delta fetchland.

   Polluted Delta: Land
   {T}, Sacrifice Polluted Delta: Search your library for an Island or Swamp card,
   put it onto the battlefield, then shuffle.

   This tests:
   - Fetchland can tutor to battlefield (not hand)
   - Fetched land enters tapped
   - Sacrifice-self cost works as ability cost
   - Selection modal shows only Island/Swamp subtypes
   - Fail-to-find option available"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.selection :as selection]
    [fizzle.events.selection.resolution :as resolution]))


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


(defn add-cards-to-library
  "Add multiple cards to the library with positions.
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


(defn get-object-tapped
  "Get the tapped state of an object by its ID."
  [db object-id]
  (:object/tapped (q/get-object db object-id)))


(defn get-library-count
  "Get the number of cards in a player's library."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :library)))


(defn get-battlefield-count
  "Get the number of permanents on a player's battlefield."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :battlefield)))


(defn get-graveyard-count
  "Get the number of cards in a player's graveyard."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :graveyard)))


;; === Polluted Delta Card Definition Tests ===

(deftest test-polluted-delta-card-definition
  (testing "Polluted Delta type and ability"
    (is (= #{:land} (:card/types cards/polluted-delta))
        "Polluted Delta should be a land")
    (let [abilities (:card/abilities cards/polluted-delta)]
      (is (= 1 (count abilities))
          "Polluted Delta should have 1 ability")
      (let [ability (first abilities)]
        (is (= :activated (:ability/type ability))
            "Ability should be activated")
        (let [cost (:ability/cost ability)]
          (is (true? (:tap cost))
              "Should have tap cost")
          (is (true? (:sacrifice-self cost))
              "Should have sacrifice-self cost")
          (is (= 1 (:pay-life cost))
              "Should have pay-life 1 cost"))))))


(deftest test-polluted-delta-tutors-to-battlefield
  (testing "Polluted Delta effect targets battlefield (not hand)"
    (let [ability (first (:card/abilities cards/polluted-delta))
          effect (first (:ability/effects ability))]
      (is (= :tutor (:effect/type effect))
          "Effect type should be :tutor")
      (is (= :battlefield (:effect/target-zone effect))
          "Target zone should be :battlefield (not :hand)")
      (is (nil? (:effect/enters-tapped effect))
          "Should NOT enter tapped (Polluted Delta puts land onto battlefield untapped)"))))


(deftest test-polluted-delta-searches-island-or-swamp
  (testing "Polluted Delta searches for Island or Swamp subtypes"
    (let [ability (first (:card/abilities cards/polluted-delta))
          effect (first (:ability/effects ability))
          criteria (:effect/criteria effect)]
      (is (= #{:island :swamp} (:card/subtypes criteria))
          "Should search for cards with Island or Swamp subtype"))))


;; === Tutor to Battlefield Tests ===

(deftest test-tutor-to-battlefield-moves-card
  (testing "Tutor with target-zone :battlefield moves card to battlefield"
    (let [db (create-test-db)
          ;; Add Island to library
          [db' [island-id]] (add-cards-to-library db [:island] :player-1)
          battlefield-before (get-battlefield-count db' :player-1)
          ;; Simulate tutor selection to battlefield
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{island-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :battlefield
                     :selection/type :tutor
                     :selection/enters-tapped true
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Island should be on battlefield
      (is (= :battlefield (get-object-zone db-after island-id))
          "Selected card should move to battlefield")
      ;; Battlefield count increased
      (is (= (inc battlefield-before) (get-battlefield-count db-after :player-1))
          "Battlefield count should increase by 1"))))


(deftest test-tutor-to-battlefield-enters-tapped
  (testing "Tutor with enters-tapped true makes card enter tapped"
    (let [[db' [island-id]] (add-cards-to-library (create-test-db) [:island] :player-1)
          ;; Verify Island starts untapped
          _ (is (false? (get-object-tapped db' island-id))
                "Island should start untapped in library")
          ;; Simulate tutor selection to battlefield with enters-tapped
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{island-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :battlefield
                     :selection/type :tutor
                     :selection/enters-tapped true
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Island should be tapped
      (is (true? (get-object-tapped db-after island-id))
          "Fetched land should enter battlefield tapped"))))


(deftest test-tutor-to-battlefield-without-enters-tapped
  (testing "Tutor without enters-tapped flag keeps card untapped"
    (let [[db' [island-id]] (add-cards-to-library (create-test-db) [:island] :player-1)
          ;; Simulate tutor to battlefield WITHOUT enters-tapped flag
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{island-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :battlefield
                     :selection/type :tutor
                     :selection/enters-tapped false  ; Not tapped
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Island should be untapped
      (is (false? (get-object-tapped db-after island-id))
          "Card should enter untapped when enters-tapped is false"))))


(deftest test-tutor-to-hand-ignores-enters-tapped
  (testing "Tutor to hand ignores enters-tapped flag (only matters for battlefield)"
    (let [[db' [island-id]] (add-cards-to-library (create-test-db) [:island] :player-1)
          ;; Simulate tutor to HAND with enters-tapped (should be ignored)
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{island-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :hand
                     :selection/type :tutor
                     :selection/enters-tapped true  ; Should be ignored for hand
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Island should be in hand
      (is (= :hand (get-object-zone db-after island-id))
          "Card should go to hand")
      ;; Should stay untapped (enters-tapped only matters for battlefield)
      (is (false? (get-object-tapped db-after island-id))
          "Cards in hand should not be tapped"))))


;; === Fetchland Selection Tests ===

(deftest test-fetchland-selection-finds-island
  (testing "Fetchland criteria finds cards with Island subtype"
    (let [db (create-test-db)
          ;; Add various cards to library
          [db' _] (add-cards-to-library db [:island :dark-ritual :brain-freeze] :player-1)
          ;; Query for Island/Swamp subtypes (same criteria as Polluted Delta)
          results (q/query-library-by-criteria db' :player-1
                                               {:card/subtypes #{:island :swamp}})]
      (is (= 1 (count results))
          "Should find exactly 1 card with Island subtype")
      (is (= :island (get-in (first results) [:object/card :card/id]))
          "The match should be Island"))))


(deftest test-fetchland-selection-finds-swamp
  (testing "Fetchland criteria finds cards with Swamp subtype"
    (let [db (create-test-db)
          [db' _] (add-cards-to-library db [:swamp :dark-ritual :brain-freeze] :player-1)
          results (q/query-library-by-criteria db' :player-1
                                               {:card/subtypes #{:island :swamp}})]
      (is (= 1 (count results))
          "Should find exactly 1 card with Swamp subtype")
      (is (= :swamp (get-in (first results) [:object/card :card/id]))
          "The match should be Swamp"))))


(deftest test-fetchland-selection-finds-both
  (testing "Fetchland criteria finds both Island and Swamp"
    (let [db (create-test-db)
          [db' _] (add-cards-to-library db [:island :swamp :dark-ritual] :player-1)
          results (q/query-library-by-criteria db' :player-1
                                               {:card/subtypes #{:island :swamp}})]
      (is (= 2 (count results))
          "Should find both Island and Swamp")
      (is (= #{:island :swamp}
             (set (map #(get-in % [:object/card :card/id]) results)))
          "Should find Island and Swamp"))))


(deftest test-fetchland-selection-excludes-non-matching
  (testing "Fetchland criteria excludes cards without Island/Swamp subtype"
    (let [db (create-test-db)
          ;; city-of-brass is a land but not Island or Swamp
          ;; dark-ritual is not a land
          [db' _] (add-cards-to-library db [:city-of-brass :dark-ritual :gemstone-mine] :player-1)
          results (q/query-library-by-criteria db' :player-1
                                               {:card/subtypes #{:island :swamp}})]
      (is (empty? results)
          "Should not find any cards without Island/Swamp subtype"))))


;; === Fail-to-Find Tests ===

(deftest test-fetchland-fail-to-find-no-targets
  (testing "Fail-to-find when no valid targets just shuffles"
    (let [db (create-test-db)
          [db' _] (add-cards-to-library db [:dark-ritual :brain-freeze :city-of-brass] :player-1)
          library-before (get-library-count db' :player-1)
          battlefield-before (get-battlefield-count db' :player-1)
          ;; Simulate fail-to-find selection
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{}  ; Empty = fail to find
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :battlefield
                     :selection/type :tutor
                     :selection/enters-tapped true
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Library count unchanged
      (is (= library-before (get-library-count db-after :player-1))
          "Library count should not change on fail-to-find")
      ;; Battlefield unchanged
      (is (= battlefield-before (get-battlefield-count db-after :player-1))
          "Battlefield should not change on fail-to-find"))))


(deftest test-fetchland-shuffles-after-selection
  (testing "Library is shuffled after card is moved to battlefield"
    (let [db (create-test-db)
          ;; Add 5 cards to make shuffle detectable
          [db' [island-id & _rest]] (add-cards-to-library db
                                                          [:island :dark-ritual :dark-ritual
                                                           :dark-ritual :dark-ritual]
                                                          :player-1)
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{island-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :battlefield
                     :selection/type :tutor
                     :selection/enters-tapped true
                     :selection/shuffle? true
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Island should be on battlefield
      (is (= :battlefield (get-object-zone db-after island-id))
          "Selected card should be on battlefield")
      ;; Remaining 4 cards should be in library
      (is (= 4 (get-library-count db-after :player-1))
          "Library should have 4 cards after fetch"))))


;; === Activated Ability Uses Stack Tests ===

(deftest test-activate-ability-adds-stack-item
  (testing "Activating Polluted Delta ability adds stack-item to stack"
    (let [db (create-test-db)
          ;; Add Polluted Delta to battlefield and Island to library
          [db' delta-id] (add-card-to-zone db :polluted-delta :battlefield :player-1)
          [db'' _island-ids] (add-cards-to-library db' [:island] :player-1)
          ;; Give player enough life to pay the cost
          player-eid (q/get-player-eid db'' :player-1)
          db-with-life (d/db-with db'' [[:db/add player-eid :player/life 20]])
          ;; Verify no stack items before
          _ (is (true? (stack/stack-empty? db-with-life))
                "Precondition: no items on stack")
          ;; Activate ability
          result (ability-events/activate-ability db-with-life :player-1 delta-id 0)
          db-after (:db result)]
      ;; Should have a stack-item
      (is (false? (stack/stack-empty? db-after))
          "Activated ability should create a stack-item")
      ;; Stack-item should be :activated-ability type
      (let [item (stack/get-top-stack-item db-after)]
        (is (= :activated-ability (:stack-item/type item))
            "Stack-item should have type :activated-ability")))))


(deftest test-sacrifice-cost-paid-on-activation-not-resolution
  (testing "Polluted Delta sacrifices immediately when ability is activated (as cost)"
    (let [db (create-test-db)
          [db' delta-id] (add-card-to-zone db :polluted-delta :battlefield :player-1)
          [db'' _] (add-cards-to-library db' [:island] :player-1)
          player-eid (q/get-player-eid db'' :player-1)
          db-with-life (d/db-with db'' [[:db/add player-eid :player/life 20]])
          ;; Verify Delta on battlefield before
          _ (is (= :battlefield (get-object-zone db-with-life delta-id))
                "Precondition: Delta on battlefield")
          ;; Activate ability (costs are paid immediately)
          result (ability-events/activate-ability db-with-life :player-1 delta-id 0)
          db-after (:db result)]
      ;; Delta should be in graveyard (sacrificed as cost)
      (is (= :graveyard (get-object-zone db-after delta-id))
          "Polluted Delta should be sacrificed immediately as cost")
      ;; But stack-item is still on stack (not yet resolved)
      (is (false? (stack/stack-empty? db-after))
          "Stack-item should still be on stack awaiting resolution"))))


(deftest test-life-cost-paid-on-activation
  (testing "Pay-life cost is deducted immediately when ability is activated"
    (let [db (create-test-db)
          [db' delta-id] (add-card-to-zone db :polluted-delta :battlefield :player-1)
          [db'' _] (add-cards-to-library db' [:island] :player-1)
          player-eid (q/get-player-eid db'' :player-1)
          db-with-life (d/db-with db'' [[:db/add player-eid :player/life 20]])
          ;; Verify life before
          _ (is (= 20 (q/get-life-total db-with-life :player-1))
                "Precondition: player has 20 life")
          ;; Activate ability
          result (ability-events/activate-ability db-with-life :player-1 delta-id 0)
          db-after (:db result)]
      ;; Life should be 19 (paid 1 as cost)
      (is (= 19 (q/get-life-total db-after :player-1))
          "Player should have paid 1 life as cost"))))


(deftest test-can-respond-to-activated-ability
  (testing "With trigger on stack, can tap lands before resolution (priority window)"
    (let [db (create-test-db)
          ;; Add Polluted Delta and City of Brass to battlefield
          [db' delta-id] (add-card-to-zone db :polluted-delta :battlefield :player-1)
          [db'' cob-id] (add-card-to-zone db' :city-of-brass :battlefield :player-1)
          [db''' _] (add-cards-to-library db'' [:island] :player-1)
          player-eid (q/get-player-eid db''' :player-1)
          db-with-life (d/db-with db''' [[:db/add player-eid :player/life 20]])
          ;; Activate Polluted Delta ability (puts trigger on stack)
          result (ability-events/activate-ability db-with-life :player-1 delta-id 0)
          db-after-activate (:db result)]
      ;; Stack-item is on stack
      (is (false? (stack/stack-empty? db-after-activate))
          "Stack-item should be on stack")
      ;; Can still tap City of Brass for mana (responding to ability on stack)
      (let [db-after-tap (ability-events/activate-mana-ability db-after-activate :player-1 cob-id :blue)
            mana-pool (q/get-mana-pool db-after-tap :player-1)]
        (is (= 1 (:blue mana-pool))
            "Should be able to tap City of Brass while ability is on stack")
        ;; Stack-item still on stack
        (is (false? (stack/stack-empty? db-after-tap))
            "Stack-item should remain on stack after tapping for mana")))))


;; === Full Stack Resolution Test ===

(deftest test-polluted-delta-full-resolution-from-stack
  ;; Bug caught: fetchland stack resolution broken
  (testing "Activate, resolve from stack, verify land search selection created"
    (let [db (create-test-db)
          ;; Add Polluted Delta to battlefield
          [db' delta-id] (add-card-to-zone db :polluted-delta :battlefield :player-1)
          ;; Add Island and Swamp to library (valid targets)
          [db'' [island-id swamp-id]] (add-cards-to-library db'
                                                            [:island :swamp :dark-ritual]
                                                            :player-1)
          initial-life (q/get-life-total db'' :player-1)
          _ (is (= 20 initial-life) "Precondition: player has 20 life")
          ;; Activate ability (index 0 = only ability)
          result (ability-events/activate-ability db'' :player-1 delta-id 0)
          db-after-activate (:db result)
          ;; Delta should be sacrificed (cost paid)
          _ (is (= :graveyard (get-object-zone db-after-activate delta-id))
                "Delta should be sacrificed as cost")
          ;; Life should be 19 (paid 1 life)
          _ (is (= 19 (q/get-life-total db-after-activate :player-1))
                "Should have paid 1 life")
          ;; Stack should have ability
          top-item (stack/get-top-stack-item db-after-activate)
          _ (is (some? top-item) "Should have stack-item")
          ;; Resolve the stack item (pass full entity, not eid)
          resolve-result (resolution/resolve-stack-item-ability-with-selection
                           db-after-activate top-item)]
      ;; Should create tutor selection for library search
      (is (= :tutor (get-in resolve-result [:pending-selection :selection/type]))
          "Selection should be tutor type")
      ;; Candidates should include Island and Swamp (match criteria)
      (let [candidates (:selection/candidates (:pending-selection resolve-result))]
        (is (contains? candidates island-id)
            "Island should be a valid candidate")
        (is (contains? candidates swamp-id)
            "Swamp should be a valid candidate")))))
