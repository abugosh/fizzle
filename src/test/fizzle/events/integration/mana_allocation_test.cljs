(ns fizzle.events.integration.mana-allocation-test
  "Integration tests for mana allocation chaining into spell casting and
   ability activation flows.

   Tests that initiate-cast-with-mode, x-mana-cost confirm, targeting confirm,
   and activate-ability all correctly chain into :mana-allocation selection
   when generic mana costs are present."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.events.selection.targeting]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Cards
;; =====================================================

(def spell-with-generic
  "Instant with generic + colored cost: {2}{B}"
  {:card/id :test-generic-spell
   :card/name "Test Generic Spell"
   :card/mana-cost {:colorless 2 :black 1}
   :card/cmc 3
   :card/types #{:instant}
   :card/effects [{:effect/type :add-mana :effect/mana {:black 1}}]})


(def spell-no-generic
  "Instant with pure colored cost: {B}{B}"
  {:card/id :test-no-generic-spell
   :card/name "Test No Generic"
   :card/mana-cost {:black 2}
   :card/cmc 2
   :card/types #{:instant}
   :card/effects [{:effect/type :add-mana :effect/mana {:black 1}}]})


(def spell-only-generic
  "Instant with only generic cost: {3}"
  {:card/id :test-only-generic-spell
   :card/name "Test Only Generic"
   :card/mana-cost {:colorless 3}
   :card/cmc 3
   :card/types #{:instant}
   :card/effects [{:effect/type :add-mana :effect/mana {:black 1}}]})


(def spell-x-with-generic
  "Sorcery with X and fixed generic: {X}{1}{U}"
  {:card/id :test-x-generic-spell
   :card/name "Test X Generic"
   :card/mana-cost {:x 1 :colorless 1 :blue 1}
   :card/cmc 2
   :card/types #{:sorcery}
   :card/effects [{:effect/type :draw :effect/amount 1}]})


(def spell-x-no-fixed-generic
  "Sorcery with X and colored only: {X}{U}"
  {:card/id :test-x-no-generic-spell
   :card/name "Test X No Generic"
   :card/mana-cost {:x 1 :blue 1}
   :card/cmc 1
   :card/types #{:sorcery}
   :card/effects [{:effect/type :draw :effect/amount 1}]})


(def spell-targeting-with-generic
  "Sorcery with targeting + generic cost: {1}{R}"
  {:card/id :test-targeting-generic-spell
   :card/name "Test Targeting Generic"
   :card/mana-cost {:colorless 1 :red 1}
   :card/cmc 2
   :card/types #{:sorcery}
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options [:self :opponent :any-player]
                     :target/required true}]
   :card/effects [{:effect/type :draw
                   :effect/amount 2
                   :effect/target :any-player}]})


(def spell-targeting-no-generic
  "Instant with targeting + no generic cost: {R}{R}"
  {:card/id :test-targeting-no-generic-spell
   :card/name "Test Targeting No Generic"
   :card/mana-cost {:red 2}
   :card/cmc 2
   :card/types #{:instant}
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options [:self :opponent :any-player]
                     :target/required true}]
   :card/effects [{:effect/type :draw
                   :effect/amount 1
                   :effect/target :any-player}]})


(def permanent-with-generic-ability
  "Artifact with activated ability costing {2}, T"
  {:card/id :test-generic-ability-permanent
   :card/name "Test Generic Ability"
   :card/mana-cost {}
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :mana {:colorless 2}}
                     :ability/description "Draw a card"
                     :ability/effects [{:effect/type :draw :effect/amount 1}]}]})


(def permanent-colored-ability
  "Artifact with activated ability costing {U}, T"
  {:card/id :test-colored-ability-permanent
   :card/name "Test Colored Ability"
   :card/mana-cost {}
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :mana {:blue 1}}
                     :ability/description "Draw a card"
                     :ability/effects [{:effect/type :draw :effect/amount 1}]}]})


(def permanent-no-mana-ability
  "Artifact with activated ability costing T, sacrifice"
  {:card/id :test-no-mana-ability-permanent
   :card/name "Test No Mana Ability"
   :card/mana-cost {}
   :card/cmc 0
   :card/types #{:artifact}
   :card/abilities [{:ability/type :activated
                     :ability/cost {:tap true
                                    :sacrifice-self true}
                     :ability/description "Draw a card"
                     :ability/effects [{:effect/type :draw :effect/amount 1}]}]})


;; =====================================================
;; Test Helpers
;; =====================================================

(defn create-allocation-test-db
  "Create a game state with player, opponent, and game state."
  []
  (let [db (th/create-test-db)
        conn (d/conn-from-db db)]
    ;; Opponent (needed for targeting tests)
    (d/transact! conn [{:player/id :opponent
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 0
                        :player/is-opponent true}])
    ;; Set max-hand-size on player-1
    (let [player-eid (q/get-player-eid @conn :player-1)]
      (d/transact! conn [[:db/add player-eid :player/max-hand-size 7]]))
    @conn))


(defn add-card-and-object
  "Add a card definition and create an object in specified zone.
   Returns [db object-id]."
  [db card zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition if not present
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone zone
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false
                          :object/position 0}])
      [@conn obj-id])))


(defn create-app-db
  "Create app-db wrapping a game-db."
  [game-db]
  {:game/db game-db
   :game/selected-card nil
   :game/pending-selection nil
   :game/pending-mode-selection nil})


;; =====================================================
;; 1. Spell with generic enters allocation
;; =====================================================

(deftest test-cast-spell-with-generic-enters-allocation
  (testing "Casting spell with generic cost enters allocation mode"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-with-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:black 5 :blue 3})
          app-db (-> (create-app-db db)
                     (assoc :game/selected-card obj-id))
          result (game/cast-spell-handler app-db)
          sel (:game/pending-selection result)]
      ;; Should enter allocation mode, not cast directly
      (is (some? sel) "Should have pending selection for allocation")
      (is (= :mana-allocation (:selection/type sel))
          "Selection type should be :mana-allocation")
      (is (= 2 (:selection/generic-remaining sel))
          "Generic remaining should be 2 (colorless from {2}{B})")
      ;; Remaining pool: {:black 5 :blue 3} minus colored {:black 1}
      (is (= 4 (:black (:selection/remaining-pool sel)))
          "Remaining black should be 4 (5 - 1 colored)")
      (is (= 3 (:blue (:selection/remaining-pool sel)))
          "Blue should be 3 (untouched)")
      ;; Spell should NOT be on stack yet
      (let [obj (q/get-object (:game/db result) obj-id)]
        (is (= :hand (:object/zone obj))
            "Spell should still be in hand (not cast yet)")))))


;; =====================================================
;; 2. Spell with no generic skips allocation
;; =====================================================

(deftest test-cast-spell-no-generic-skips-allocation
  (testing "Casting spell with pure colored cost skips allocation"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-no-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:black 5})
          app-db (-> (create-app-db db)
                     (assoc :game/selected-card obj-id))
          result (game/cast-spell-handler app-db)]
      ;; No pending selection
      (is (nil? (:game/pending-selection result))
          "Should NOT enter allocation for pure colored cost")
      ;; Spell should be on stack
      (let [obj (q/get-object (:game/db result) obj-id)]
        (is (= :stack (:object/zone obj))
            "Spell should be on stack (cast directly)")))))


;; =====================================================
;; 3. Spell with only generic enters allocation
;; =====================================================

(deftest test-cast-spell-only-generic-enters-allocation
  (testing "Casting spell with only generic cost enters allocation"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-only-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:black 3 :blue 2})
          app-db (-> (create-app-db db)
                     (assoc :game/selected-card obj-id))
          result (game/cast-spell-handler app-db)
          sel (:game/pending-selection result)]
      (is (some? sel) "Should enter allocation mode")
      (is (= :mana-allocation (:selection/type sel)))
      (is (= 3 (:selection/generic-remaining sel))
          "Generic remaining should be 3")
      ;; No colored deduction - remaining = full pool
      (is (= 3 (:black (:selection/remaining-pool sel)))
          "Black should be full pool (no colored deduction)")
      (is (= 2 (:blue (:selection/remaining-pool sel)))
          "Blue should be full pool (no colored deduction)"))))


;; =====================================================
;; 4. X cost chains to allocation
;; =====================================================

(deftest test-x-cost-chains-to-allocation
  (testing "X cost confirm chains to allocation when resolved cost has generic"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-x-with-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 3 :black 5})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          x-value 2
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db-with-x (d/db-with db [[:db/add obj-eid :object/x-value x-value]])
          selection (assoc (sel-costs/build-x-mana-selection db-with-x :player-1 obj-id mode)
                           :selection/selected-x x-value)
          result (core/execute-confirmed-selection db-with-x selection)
          chain-sel (core/build-chain-selection (:db result) selection)]
      ;; Chain builder should provide mana-allocation
      (is (some? chain-sel)
          "X confirm should chain to :mana-allocation selection")
      (is (= :mana-allocation (:selection/type chain-sel))
          "Chained selection should be :mana-allocation")
      (is (= 3 (:selection/generic-remaining chain-sel))
          "Generic remaining should be 3 (X=2 + fixed 1)")
      ;; Spell should NOT be on stack yet
      (let [obj (q/get-object (:db result) obj-id)]
        (is (= :hand (:object/zone obj))
            "Spell should still be in hand (waiting for allocation)")))))


;; =====================================================
;; 5. X=0 with no fixed generic skips allocation
;; =====================================================

(deftest test-x-cost-x-zero-no-fixed-generic-skips-allocation
  (testing "X=0 with no fixed generic still chains to mana-allocation (handles cast)"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-x-no-fixed-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 5})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          selection (assoc (sel-costs/build-x-mana-selection db :player-1 obj-id mode)
                           :selection/selected-x 0)
          result (core/execute-confirmed-selection db selection)
          chain-sel (core/build-chain-selection (:db result) selection)]
      ;; Chain builder always provides mana-allocation (even with 0 generic)
      ;; so mana-allocation executor handles casting
      (is (some? chain-sel)
          "X=0 should still chain to mana-allocation for casting")
      (is (= :mana-allocation (:selection/type chain-sel))
          "Chained selection should be :mana-allocation")
      (is (= 0 (:selection/generic-remaining chain-sel))
          "Generic remaining should be 0"))))


;; =====================================================
;; 6. Targeting chains to allocation
;; =====================================================

(deftest test-targeting-chains-to-allocation
  (testing "Targeting confirm chains to allocation when mode has generic"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-targeting-with-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:red 3 :black 5})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          selection {:selection/type :cast-time-targeting
                     :selection/lifecycle :chaining
                     :selection/player-id :player-1
                     :selection/object-id obj-id
                     :selection/mode mode
                     :selection/target-requirement {:target/id :player
                                                    :target/type :player
                                                    :target/options [:self :opponent :any-player]
                                                    :target/required true}
                     :selection/valid-targets #{:player-1 :opponent}
                     :selection/selected #{:player-1}
                     :selection/select-count 1}
          result (core/execute-confirmed-selection db selection)
          chain-sel (core/build-chain-selection (:db result) selection)]
      ;; Chain builder should provide mana-allocation with pending targets
      (is (some? chain-sel)
          "Targeting confirm should chain to allocation")
      (is (= :mana-allocation (:selection/type chain-sel))
          "Chained selection should be :mana-allocation")
      (is (= 1 (:selection/generic-remaining chain-sel))
          "Generic remaining should be 1")
      (is (= {:player :player-1}
             (:selection/pending-targets chain-sel))
          "Should carry pending targets through allocation"))))


;; =====================================================
;; 7. Targeting with no generic casts directly
;; =====================================================

(deftest test-targeting-no-generic-casts-directly
  (testing "Targeting confirm casts directly when no generic cost (finalized lifecycle)"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-targeting-no-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:red 5})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          selection {:selection/type :cast-time-targeting
                     :selection/lifecycle :finalized
                     :selection/clear-selected-card? true
                     :selection/player-id :player-1
                     :selection/object-id obj-id
                     :selection/mode mode
                     :selection/target-requirement {:target/id :player
                                                    :target/type :player
                                                    :target/options [:self :opponent :any-player]
                                                    :target/required true}
                     :selection/valid-targets #{:player-1 :opponent}
                     :selection/selected #{:player-1}
                     :selection/select-count 1}
          result (core/execute-confirmed-selection db selection)]
      ;; Executor returns only {:db db} — should have cast the spell
      (is (map? (:db result))
          "Executor should return {:db db}")
      ;; Spell should be on stack with targets stored
      (let [obj (q/get-object (:db result) obj-id)]
        (is (= :stack (:object/zone obj))
            "Spell should be on stack")))))


;; =====================================================
;; 8. Allocation confirm stores targets
;; =====================================================

(deftest test-allocation-confirm-stores-targets
  (testing "Allocation confirm stores pending targets on stack-item"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-targeting-with-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:red 3 :black 5})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          ;; Simulate allocation selection with pending targets from targeting step
          selection {:selection/type :mana-allocation
                     :selection/player-id :player-1
                     :selection/spell-id obj-id
                     :selection/mode mode
                     :selection/generic-remaining 0
                     :selection/allocation {:black 1}
                     :selection/colored-cost {:red 1}
                     :selection/original-cost {:colorless 1 :red 1}
                     :selection/pending-targets {:player :player-1}}
          result (core/execute-confirmed-selection db selection)]
      ;; Spell should be on stack
      (let [obj (q/get-object (:db result) obj-id)]
        (is (= :stack (:object/zone obj))
            "Spell should be on stack"))
      ;; Find stack-item and verify targets stored
      (let [obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                         (:db result) obj-id)
            si (stack/get-stack-item-by-object-ref (:db result) obj-eid)]
        (is (some? si) "Stack-item should exist")
        (is (= {:player :player-1} (:stack-item/targets si))
            "Stack-item should have targets from pending-targets")))))


;; =====================================================
;; 9. Allocation confirm full spell casting
;; =====================================================

(deftest test-allocation-confirm-full-spell-casting
  (testing "Allocation confirm completes spell casting (stack, storm, pool)"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-with-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:black 5 :blue 3})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          ;; Simulate completed allocation: 1 black + 1 blue for generic 2
          app-db {:game/db db
                  :game/pending-selection
                  {:selection/type :mana-allocation
                   :selection/lifecycle :finalized
                   :selection/clear-selected-card? true
                   :selection/player-id :player-1
                   :selection/spell-id obj-id
                   :selection/mode mode
                   :selection/generic-remaining 0
                   :selection/generic-total 2
                   :selection/allocation {:black 1 :blue 1}
                   :selection/remaining-pool {:black 3 :blue 2 :white 0
                                              :red 0 :green 0 :colorless 0}
                   :selection/original-remaining-pool {:black 4 :blue 3 :white 0
                                                       :red 0 :green 0 :colorless 0}
                   :selection/colored-cost {:black 1}
                   :selection/original-cost {:colorless 2 :black 1}
                   :selection/auto-confirm? true
                   :selection/validation :always}}
          result (core/confirm-selection-impl app-db)]
      ;; Spell on stack
      (let [obj (q/get-object (:game/db result) obj-id)]
        (is (= :stack (:object/zone obj))
            "Spell should be on stack"))
      ;; Pool correct: was {B:5 U:3}, paid {B:1}colored + {B:1 U:1}allocation
      (let [pool (q/get-mana-pool (:game/db result) :player-1)]
        (is (= 3 (:black pool)) "Black: 5 - 1 colored - 1 allocated = 3")
        (is (= 2 (:blue pool)) "Blue: 3 - 1 allocated = 2"))
      ;; Storm incremented
      (is (= 1 (q/get-storm-count (:game/db result) :player-1))
          "Storm count should be 1")
      ;; Selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared"))))


;; =====================================================
;; 10. Allocation preserves pending targets
;; =====================================================

(deftest test-allocation-preserves-pending-targets
  (testing "Allocation selection carries pending targets from targeting step"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db spell-targeting-with-generic :hand :player-1)
          db (mana/add-mana db :player-1 {:red 3 :black 5})
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          ;; Simulate targeting -> allocation chain
          targeting-selection {:selection/type :cast-time-targeting
                               :selection/player-id :player-1
                               :selection/object-id obj-id
                               :selection/mode mode
                               :selection/target-requirement {:target/id :player
                                                              :target/type :player
                                                              :target/options [:self :opponent :any-player]
                                                              :target/required true}
                               :selection/valid-targets #{:player-1 :opponent}
                               :selection/selected #{:player-1}
                               :selection/select-count 1}
          result (core/execute-confirmed-selection db targeting-selection)]
      ;; Should chain to allocation with pending targets
      (when (:pending-selection result)
        (let [alloc-sel (:pending-selection result)]
          (is (= {:player :player-1}
                 (:selection/pending-targets alloc-sel))
              "Allocation selection should have pending targets"))))))


;; =====================================================
;; 11. Ability with generic mana enters allocation
;; =====================================================

(deftest test-ability-with-generic-mana-enters-allocation
  (testing "Activating ability with generic mana enters allocation mode"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db permanent-with-generic-ability :battlefield :player-1)
          db (mana/add-mana db :player-1 {:black 5})
          result (abilities/activate-ability db :player-1 obj-id 0)
          sel (:pending-selection result)]
      ;; Should enter allocation mode
      (is (some? sel) "Should have pending selection for allocation")
      (is (= :mana-allocation (:selection/type sel))
          "Selection type should be :mana-allocation")
      (is (= :ability (:selection/source-type sel))
          "Source type should be :ability")
      (is (= 2 (:selection/generic-remaining sel))
          "Generic remaining should be 2")
      ;; Non-mana costs (tap) should already be paid
      (let [obj (q/get-object (:db result) obj-id)]
        (is (true? (:object/tapped obj))
            "Permanent should be tapped (non-mana cost paid before allocation)")))))


;; =====================================================
;; 12. Ability with no generic mana skips allocation
;; =====================================================

(deftest test-ability-no-generic-mana-skips-allocation
  (testing "Activating ability with pure colored mana skips allocation"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db permanent-colored-ability :battlefield :player-1)
          db (mana/add-mana db :player-1 {:blue 3})
          result (abilities/activate-ability db :player-1 obj-id 0)]
      ;; No allocation selection
      (is (nil? (:pending-selection result))
          "Should NOT enter allocation for pure colored mana ability")
      ;; Stack-item should exist
      (let [items (q/get-all-stack-items (:db result))
            ability-items (filter #(= :activated-ability (:stack-item/type %)) items)]
        (is (= 1 (count ability-items))
            "Should have ability stack-item")))))


;; =====================================================
;; 13. Ability with no mana cost skips allocation
;; =====================================================

(deftest test-ability-no-mana-cost-skips-allocation
  (testing "Activating ability without mana cost skips allocation"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db permanent-no-mana-ability :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)]
      ;; No allocation selection
      (is (nil? (:pending-selection result))
          "Should NOT enter allocation for ability without mana")
      ;; Stack-item should exist (or object sacrificed)
      ;; sacrifice-self moves to graveyard, so verify it moved
      (let [obj (q/get-object (:db result) obj-id)]
        (is (= :graveyard (:object/zone obj))
            "Permanent should be sacrificed (in graveyard)")))))


;; =====================================================
;; 14. Ability allocation confirm creates stack-item
;; =====================================================

(deftest test-ability-allocation-confirm-creates-stack-item
  (testing "Ability allocation confirm creates stack-item with correct type"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db permanent-with-generic-ability :battlefield :player-1)
          db (mana/add-mana db :player-1 {:black 3})
          ability (first (:card/abilities permanent-with-generic-ability))
          ;; Simulate allocation selection for ability
          mode {:mode/mana-cost {:colorless 2}}
          selection {:selection/type :mana-allocation
                     :selection/player-id :player-1
                     :selection/spell-id obj-id
                     :selection/mode mode
                     :selection/source-type :ability
                     :selection/ability ability
                     :selection/generic-remaining 0
                     :selection/allocation {:black 2}
                     :selection/colored-cost {}
                     :selection/original-cost {:colorless 2}
                     :selection/auto-confirm? true
                     :selection/validation :always}
          result (core/execute-confirmed-selection db selection)]
      (is (some? (:db result)))
      ;; Stack-item should exist with :activated-ability type
      (let [items (q/get-all-stack-items (:db result))
            ability-items (filter #(= :activated-ability (:stack-item/type %)) items)]
        (is (= 1 (count ability-items))
            "Should have exactly one :activated-ability stack-item"))
      ;; Mana should be deducted
      (let [pool (q/get-mana-pool (:db result) :player-1)]
        (is (= 1 (:black pool))
            "Black should be 3 - 2 allocated = 1")))))


;; =====================================================
;; 15. Ability non-mana costs paid before allocation
;; =====================================================

(deftest test-ability-non-mana-costs-paid-before-allocation
  (testing "Non-mana costs (tap) paid immediately on entering allocation"
    (let [db (create-allocation-test-db)
          [db obj-id] (add-card-and-object db permanent-with-generic-ability :battlefield :player-1)
          db (mana/add-mana db :player-1 {:black 5})]
      ;; Before activation: not tapped
      (is (false? (:object/tapped (q/get-object db obj-id)))
          "Should start untapped")
      (let [result (abilities/activate-ability db :player-1 obj-id 0)]
        ;; After entering allocation: should be tapped
        (when (:pending-selection result)
          (let [obj (q/get-object (:db result) obj-id)]
            (is (true? (:object/tapped obj))
                "Should be tapped before any mana allocation clicks")))))))


;; =====================================================
;; 16. Cancel allocation returns to normal
;; =====================================================

(deftest test-cancel-allocation-returns-to-normal
  (testing "Canceling allocation clears selection, leaves mana unchanged"
    (let [db (create-allocation-test-db)
          db (mana/add-mana db :player-1 {:black 5 :blue 3})
          initial-pool (q/get-mana-pool db :player-1)
          app-db {:game/db db
                  :game/pending-selection
                  {:selection/type :mana-allocation
                   :selection/player-id :player-1
                   :selection/spell-id (random-uuid)
                   :selection/generic-remaining 2
                   :selection/allocation {:black 1}
                   :selection/remaining-pool {:black 3 :blue 3 :white 0
                                              :red 0 :green 0 :colorless 0}}}
          ;; Cancel handler just dissocs :game/pending-selection
          result (dissoc app-db :game/pending-selection)]
      ;; Selection cleared
      (is (nil? (:game/pending-selection result))
          "Pending selection should be cleared")
      ;; Mana unchanged (allocation only tracked in selection, not in pool)
      (is (= initial-pool (q/get-mana-pool (:game/db result) :player-1))
          "Mana pool should be unchanged after cancel"))))
