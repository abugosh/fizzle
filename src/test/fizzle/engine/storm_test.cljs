(ns fizzle.engine.storm-test
  "Tests for storm keyword behavior.

   Storm triggers on cast, creates copies equal to spells cast before it.
   Copies are not cast (do not increment storm count)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.game :as game]))


;; === Test cards with storm keyword ===

(def storm-spell
  "A simple storm spell for testing."
  {:card/id :storm-spell
   :card/name "Storm Spell"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Storm"
   :card/effects []})


(def non-storm-spell
  "A spell without storm for comparison."
  {:card/id :non-storm-spell
   :card/name "Non-Storm Spell"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Do nothing."
   :card/effects []})


(defn init-storm-test-state
  "Create game state with storm spell in hand."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact cards
    (d/transact! conn [storm-spell non-storm-spell])

    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 10 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])

    ;; Get entity IDs
    (let [player-eid (d/q '[:find ?e .
                            :where [?e :player/id :player-1]]
                          @conn)
          storm-eid (d/q '[:find ?e .
                           :where [?e :card/id :storm-spell]]
                         @conn)
          non-storm-eid (d/q '[:find ?e .
                               :where [?e :card/id :non-storm-spell]]
                             @conn)]

      ;; Create objects in hand
      (d/transact! conn [{:object/id :storm-obj-1
                          :object/card storm-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}
                         {:object/id :non-storm-obj-1
                          :object/card non-storm-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}
                         {:object/id :non-storm-obj-2
                          :object/card non-storm-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])

      ;; Transact game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))

    @conn))


;; === Storm trigger creation tests ===

(deftest test-cast-storm-spell-creates-trigger
  (testing "Casting spell with :storm keyword creates storm stack-item on stack"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :storm-obj-1)
          stack-items (q/get-all-stack-items db')
          storm-items (filter #(= :storm (:stack-item/type %)) stack-items)]
      ;; Should have spell + storm on stack
      (is (= 1 (count storm-items)) "Should have exactly 1 storm stack-item")
      (let [storm-trigger (first storm-items)]
        (is (= :storm-obj-1 (:stack-item/source storm-trigger)))))))


(deftest test-cast-non-storm-spell-no-trigger
  (testing "Casting spell without :storm keyword does NOT create storm stack-item"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :non-storm-obj-1)
          stack-items (q/get-all-stack-items db')
          storm-items (filter #(= :storm (:stack-item/type %)) stack-items)]
      (is (= 0 (count storm-items)) "Non-storm spell should not create storm stack-item"))))


(deftest test-storm-trigger-on-top-of-spell
  (testing "Storm trigger has higher stack-order than spell (resolves first)"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :storm-obj-1)
          stack-items (q/get-all-stack-items db')
          storm-trigger (first (filter #(= :storm (:stack-item/type %)) stack-items))]
      ;; Trigger is first in LIFO order (highest stack-order)
      (is (= storm-trigger (first stack-items))
          "Storm trigger should be first (top of stack)"))))


;; === Storm trigger resolution tests ===

(deftest test-resolve-storm-creates-copies
  (testing "Storm trigger resolution creates N copies (N = storm count before cast)"
    (let [db (init-storm-test-state)
          ;; Cast 2 non-storm spells first
          db' (-> db
                  (rules/cast-spell :player-1 :non-storm-obj-1)
                  (rules/cast-spell :player-1 :non-storm-obj-2))
          ;; Storm count is now 2
          _ (is (= 2 (q/get-storm-count db' :player-1)))
          ;; Cast storm spell (storm count becomes 3)
          db'' (rules/cast-spell db' :player-1 :storm-obj-1)
          ;; Resolve the storm trigger (top of stack)
          db''' (:db (game/resolve-one-item db'' :player-1))
          ;; Should have 2 copies on stack (spells cast before storm spell)
          stack-after (q/get-objects-in-zone db''' :player-1 :stack)]
      ;; 2 non-storm spells + original storm spell + 2 storm copies = 5 objects on stack
      (is (= 5 (count stack-after))
          "Should have 2 non-storm + 1 original + 2 copies = 5 on stack"))))


(deftest test-storm-copies-have-is-copy-flag
  (testing "Copies created by storm have :object/is-copy true"
    (let [db (init-storm-test-state)
          ;; Cast 1 non-storm spell first
          db' (rules/cast-spell db :player-1 :non-storm-obj-1)
          ;; Cast storm spell
          db'' (rules/cast-spell db' :player-1 :storm-obj-1)
          ;; Resolve the storm trigger (top of stack)
          db''' (:db (game/resolve-one-item db'' :player-1))
          ;; Find copies (objects with :object/is-copy true)
          stack-objects (q/get-objects-in-zone db''' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 1 (count copies)) "Should have 1 copy")
      (is (true? (:object/is-copy (first copies)))))))


(deftest test-storm-copies-do-not-increment-storm
  (testing "Storm copies are not cast, so storm count stays same"
    (let [db (init-storm-test-state)
          ;; Cast 1 non-storm spell first
          db' (rules/cast-spell db :player-1 :non-storm-obj-1)
          _ (is (= 1 (q/get-storm-count db' :player-1)))
          ;; Cast storm spell (storm count becomes 2)
          db'' (rules/cast-spell db' :player-1 :storm-obj-1)
          _ (is (= 2 (q/get-storm-count db'' :player-1)))
          ;; Resolve storm trigger (creates 1 copy)
          db''' (:db (game/resolve-one-item db'' :player-1))]
      ;; Storm count should STILL be 2 (copies don't increment)
      (is (= 2 (q/get-storm-count db''' :player-1))
          "Storm count should not increase when copies created"))))


;; === Edge case tests ===

(deftest test-storm-count-zero-creates-no-copies
  (testing "Storm as first spell (count 0 before) creates 0 copies"
    (let [db (init-storm-test-state)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          ;; Cast storm spell first (no prior spells)
          db' (rules/cast-spell db :player-1 :storm-obj-1)
          _ (is (= 1 (q/get-storm-count db' :player-1)))
          ;; Resolve storm trigger (top of stack)
          db'' (:db (game/resolve-one-item db' :player-1))
          ;; Count copies
          stack-objects (q/get-objects-in-zone db'' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 0 (count copies)) "Should have 0 copies when storm is first spell"))))


(deftest test-storm-count-one-creates-one-copy
  (testing "Storm after 1 spell creates 1 copy"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :non-storm-obj-1)
          _ (is (= 1 (q/get-storm-count db' :player-1)))
          db'' (rules/cast-spell db' :player-1 :storm-obj-1)
          _ (is (= 2 (q/get-storm-count db'' :player-1)))
          db''' (:db (game/resolve-one-item db'' :player-1))
          stack-objects (q/get-objects-in-zone db''' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 1 (count copies)) "Should have 1 copy after 1 prior spell"))))


(deftest test-storm-count-two-creates-two-copies
  (testing "Storm after 2 spells creates 2 copies"
    (let [db (init-storm-test-state)
          db' (-> db
                  (rules/cast-spell :player-1 :non-storm-obj-1)
                  (rules/cast-spell :player-1 :non-storm-obj-2))
          _ (is (= 2 (q/get-storm-count db' :player-1)))
          db'' (rules/cast-spell db' :player-1 :storm-obj-1)
          _ (is (= 3 (q/get-storm-count db'' :player-1)))
          db''' (:db (game/resolve-one-item db'' :player-1))
          stack-objects (q/get-objects-in-zone db''' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 2 (count copies)) "Should have 2 copies after 2 prior spells"))))


(deftest test-storm-source-missing-returns-unchanged-db
  (testing "If source object gone at resolution, return db unchanged"
    (let [db (init-storm-test-state)
          ;; Create a storm stack-item with a source that doesn't exist
          db' (stack/create-stack-item db
                                       {:stack-item/type :storm
                                        :stack-item/controller :player-1
                                        :stack-item/source :nonexistent-obj
                                        :stack-item/effects [{:effect/type :storm-copies
                                                              :effect/count 3}]
                                        :stack-item/description "Storm - create 3 copies"})
          ;; Resolve should not crash, just return db with stack-item removed
          db'' (:db (game/resolve-one-item db' :player-1))
          ;; Should have no copies created (source doesn't exist)
          stack-objects (q/get-objects-in-zone db'' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 0 (count copies)) "Should have 0 copies when source missing"))))


;; === Integration tests: Full storm combo scenarios ===

(def brain-freeze-spell
  "Brain Freeze for integration testing - storm spell that mills 3."
  {:card/id :brain-freeze
   :card/name "Brain Freeze"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/keywords #{:storm}
   :card/text "Target player mills 3. Storm."
   :card/effects [{:effect/type :mill
                   :effect/amount 3
                   :effect/target :opponent}]})


(def ritual-spell
  "Dark Ritual analog for integration testing."
  {:card/id :ritual
   :card/name "Ritual"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Add BBB."
   :card/effects [{:effect/type :add-mana
                   :effect/mana {:black 3}}]})


(defn init-storm-combo-state
  "Create game state for integration testing storm combos.

   Sets up:
   - Player 1 with mana to cast rituals + Brain Freeze
   - Player 2 (opponent) with 12 cards in library
   - 2 ritual spells and 1 Brain Freeze in player 1's hand"
  []
  (let [conn (d/create-conn schema)]
    ;; Transact cards
    (d/transact! conn [brain-freeze-spell ritual-spell])

    ;; Transact player 1 (caster)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 10 :black 10
                                           :red 0 :green 0 :colorless 10}
                        :player/storm-count 0
                        :player/land-plays-left 1}])

    ;; Transact player 2 (opponent)
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])

    ;; Get entity IDs
    (let [player1-eid (d/q '[:find ?e .
                             :where [?e :player/id :player-1]]
                           @conn)
          player2-eid (d/q '[:find ?e .
                             :where [?e :player/id :player-2]]
                           @conn)
          brain-freeze-eid (d/q '[:find ?e .
                                  :where [?e :card/id :brain-freeze]]
                                @conn)
          ritual-eid (d/q '[:find ?e .
                            :where [?e :card/id :ritual]]
                          @conn)]

      ;; Create objects in player 1's hand
      (d/transact! conn [{:object/id :ritual-1
                          :object/card ritual-eid
                          :object/zone :hand
                          :object/owner player1-eid
                          :object/controller player1-eid
                          :object/tapped false}
                         {:object/id :ritual-2
                          :object/card ritual-eid
                          :object/zone :hand
                          :object/owner player1-eid
                          :object/controller player1-eid
                          :object/tapped false}
                         {:object/id :brain-freeze-1
                          :object/card brain-freeze-eid
                          :object/zone :hand
                          :object/owner player1-eid
                          :object/controller player1-eid
                          :object/tapped false}])

      ;; Create 12 cards in opponent's library (need buffer above 9)
      (doseq [i (range 12)]
        (d/transact! conn [{:object/id (keyword (str "library-card-" i))
                            :object/card ritual-eid  ; Use ritual as placeholder card
                            :object/zone :library
                            :object/owner player2-eid
                            :object/controller player2-eid
                            :object/position i
                            :object/tapped false}]))

      ;; Transact game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player1-eid
                          :game/priority player1-eid}]))

    @conn))


(defn count-zone
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (q/get-objects-in-zone db player-id zone)))


(defn resolve-all-spells-on-stack
  "Resolve all spell objects on the stack (not triggers).
   Returns updated db after all resolutions."
  [db player-id]
  (loop [db' db]
    (let [stack-objects (q/get-objects-in-zone db' player-id :stack)]
      (if (empty? stack-objects)
        db'
        ;; Resolve first spell on stack
        (let [spell (first stack-objects)
              obj-id (:object/id spell)]
          (recur (rules/resolve-spell db' player-id obj-id)))))))


(deftest test-storm-combo-two-rituals-brain-freeze
  (testing "Integration: 2 rituals + Brain Freeze = 3 mill resolutions (9 cards milled)"
    (let [db (init-storm-combo-state)
          ;; Verify initial state
          _ (is (= 0 (q/get-storm-count db :player-1)) "Storm count starts at 0")
          _ (is (= 12 (count-zone db :player-2 :library)) "Opponent has 12 cards in library")
          _ (is (= 0 (count-zone db :player-2 :graveyard)) "Opponent graveyard is empty")

          ;; Cast first ritual (storm count = 1)
          db (rules/cast-spell db :player-1 :ritual-1)
          _ (is (= 1 (q/get-storm-count db :player-1)) "Storm count = 1 after first ritual")

          ;; Cast second ritual (storm count = 2)
          db (rules/cast-spell db :player-1 :ritual-2)
          _ (is (= 2 (q/get-storm-count db :player-1)) "Storm count = 2 after second ritual")

          ;; Cast Brain Freeze (storm count = 3, creates storm trigger)
          db (rules/cast-spell db :player-1 :brain-freeze-1)
          _ (is (= 3 (q/get-storm-count db :player-1)) "Storm count = 3 after Brain Freeze")

          ;; Get storm trigger from stack
          stack-items (q/get-all-stack-items db)
          storm-trigger (first (filter #(= :storm (:stack-item/type %)) stack-items))
          _ (is (some? storm-trigger) "Storm trigger exists on stack")

          ;; Resolve storm trigger (creates 2 copies - spells before = 2)
          db (:db (game/resolve-one-item db :player-1))

          ;; Verify copies created
          stack-objects (q/get-objects-in-zone db :player-1 :stack)
          copies (filter :object/is-copy stack-objects)
          _ (is (= 2 (count copies)) "2 copies created by storm trigger")

          ;; Resolve all 3 Brain Freeze spells (original + 2 copies)
          ;; Each mills 3 cards from opponent = 9 total
          db (resolve-all-spells-on-stack db :player-1)]

      ;; Final assertions
      (is (= 9 (count-zone db :player-2 :graveyard))
          "Opponent should have 9 cards in graveyard (3 Brain Freeze resolutions x 3 cards each)")
      (is (= 3 (count-zone db :player-2 :library))
          "Opponent should have 3 cards left in library (12 - 9)")
      (is (= 0 (count-zone db :player-1 :stack))
          "Stack should be empty after all resolutions"))))


;; === Storm copy stack positioning tests ===

(deftest test-storm-copies-have-stack-position
  (testing "Storm copies get :object/position for correct stack ordering"
    (let [db (init-storm-test-state)
          ;; Cast non-storm spell first
          db (rules/cast-spell db :player-1 :non-storm-obj-1)
          ;; Cast storm spell (creates trigger)
          db (rules/cast-spell db :player-1 :storm-obj-1)
          ;; Resolve storm trigger (creates 1 copy)
          db (:db (game/resolve-one-item db :player-1))
          ;; Find copy
          stack-objects (q/get-objects-in-zone db :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))]
      (is (some? (:object/position copy))
          "Storm copy should have a stack position"))))


(deftest test-storm-copies-above-original-spell
  (testing "Storm copies have higher stack position than original spell"
    (let [db (init-storm-test-state)
          ;; Cast non-storm spell first
          db (rules/cast-spell db :player-1 :non-storm-obj-1)
          ;; Cast storm spell
          db (rules/cast-spell db :player-1 :storm-obj-1)
          ;; Get original spell's position
          original (q/get-object db :storm-obj-1)
          original-pos (:object/position original)
          ;; Resolve storm trigger
          db (:db (game/resolve-one-item db :player-1))
          ;; Find copy
          stack-objects (q/get-objects-in-zone db :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))]
      (is (> (:object/position copy) original-pos)
          "Storm copy should be above original spell on stack"))))


(deftest test-unified-stack-order-spells-and-triggers
  (testing "Spells and triggers share a unified stack order counter"
    (let [db (init-storm-test-state)
          ;; Cast non-storm spell (gets position 0)
          db (rules/cast-spell db :player-1 :non-storm-obj-1)
          spell-pos (:object/position (q/get-object db :non-storm-obj-1))
          ;; Cast storm spell (gets position 1, trigger gets order 2)
          db (rules/cast-spell db :player-1 :storm-obj-1)
          storm-pos (:object/position (q/get-object db :storm-obj-1))
          stack-items (q/get-all-stack-items db)
          storm-trigger (first (filter #(= :storm (:stack-item/type %)) stack-items))
          trigger-order (:stack-item/position storm-trigger)]
      ;; Each item should have a distinct, increasing order
      (is (< spell-pos storm-pos)
          "Storm spell should be above non-storm spell")
      (is (< storm-pos trigger-order)
          "Storm trigger should be above storm spell"))))


;; === Storm copy resolution tests ===

(deftest test-storm-copies-cease-to-exist-after-resolution
  (testing "Storm copies cease to exist when leaving the stack (not sent to graveyard)"
    (let [db (init-storm-combo-state)
          ;; Cast ritual + Brain Freeze (storm count = 2 before BF, creates 1 copy)
          db (-> db
                 (rules/cast-spell :player-1 :ritual-1)
                 (rules/cast-spell :player-1 :brain-freeze-1))
          ;; Resolve storm trigger (creates 1 copy)
          db (:db (game/resolve-one-item db :player-1))
          ;; Find the copy on the stack
          stack-objects (q/get-objects-in-zone db :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          copy-id (:object/id copy)
          _ (is (some? copy) "Precondition: copy exists on stack")
          ;; Count graveyard objects owned by player 1 before resolving copy
          gy-before (count-zone db :player-1 :graveyard)
          ;; Resolve the copy
          db (rules/resolve-spell db :player-1 copy-id)
          ;; The copy should NOT be in the graveyard
          gy-after (count-zone db :player-1 :graveyard)]
      ;; Copy should not be in graveyard (it ceases to exist per MTG rules)
      (is (= gy-before gy-after)
          "Storm copy should NOT go to graveyard - copies cease to exist")
      ;; Copy should not be findable anywhere
      (is (nil? (q/get-object db copy-id))
          "Storm copy should be completely removed from the database"))))


(deftest test-original-spell-still-goes-to-graveyard
  (testing "Non-copy spells still go to graveyard after resolution"
    (let [db (init-storm-combo-state)
          ;; Cast Brain Freeze (no storm copies, storm count = 0)
          db (rules/cast-spell db :player-1 :brain-freeze-1)
          ;; Resolve storm trigger (0 copies created)
          db (:db (game/resolve-one-item db :player-1))
          ;; Resolve original Brain Freeze
          db (rules/resolve-spell db :player-1 :brain-freeze-1)]
      ;; Original spell should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db :brain-freeze-1)))
          "Original (non-copy) spell should go to graveyard after resolution"))))


;; === High storm count corner case ===

(defn init-high-storm-state
  "Create game state with high storm count for performance testing.
   Sets up storm count at 20 (simulating 20 spells already cast).
   Returns db with Brain Freeze in hand ready to cast."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact Brain Freeze card
    (d/transact! conn [brain-freeze-spell])

    ;; Transact player with high storm count already
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 10 :black 10
                                           :red 0 :green 0 :colorless 10}
                        :player/storm-count 20  ; Already cast 20 spells
                        :player/land-plays-left 1}])

    ;; Transact opponent with library
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])

    ;; Get entity IDs
    (let [player1-eid (d/q '[:find ?e .
                             :where [?e :player/id :player-1]]
                           @conn)
          player2-eid (d/q '[:find ?e .
                             :where [?e :player/id :player-2]]
                           @conn)
          brain-freeze-eid (d/q '[:find ?e .
                                  :where [?e :card/id :brain-freeze]]
                                @conn)]

      ;; Create Brain Freeze in hand
      (d/transact! conn [{:object/id :brain-freeze-obj
                          :object/card brain-freeze-eid
                          :object/zone :hand
                          :object/owner player1-eid
                          :object/controller player1-eid
                          :object/tapped false}])

      ;; Create 70 cards in opponent's library (enough for 21 x 3 = 63 mills)
      (doseq [i (range 70)]
        (d/transact! conn [{:object/id (keyword (str "library-card-" i))
                            :object/card brain-freeze-eid
                            :object/zone :library
                            :object/owner player2-eid
                            :object/controller player2-eid
                            :object/position i
                            :object/tapped false}]))

      ;; Transact game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player1-eid
                          :game/priority player1-eid}]))

    @conn))


(deftest test-storm-high-count
  (testing "Storm with 20+ copies completes without timeout or performance issue"
    ;; Corner case: high storm counts (20+) should work without issues.
    ;; Bug it catches: performance regression, memory issues, stack overflow.
    ;; Note: This is a behavior test, not a strict performance benchmark.
    (let [db (init-high-storm-state)
          ;; Verify initial state
          _ (is (= 20 (q/get-storm-count db :player-1))
                "Storm count should start at 20")
          _ (is (= 70 (count-zone db :player-2 :library))
                "Opponent should have 70 cards in library")

          ;; Cast Brain Freeze (storm count becomes 21)
          db (rules/cast-spell db :player-1 :brain-freeze-obj)
          _ (is (= 21 (q/get-storm-count db :player-1))
                "Storm count should be 21 after casting")

          ;; Get and resolve storm trigger (creates 20 copies)
          stack-items (q/get-all-stack-items db)
          storm-trigger (first (filter #(= :storm (:stack-item/type %)) stack-items))
          _ (is (some? storm-trigger) "Storm trigger should exist")

          ;; Resolve storm trigger - this is the performance-critical part
          db (:db (game/resolve-one-item db :player-1))

          ;; Count copies created
          stack-objects (q/get-objects-in-zone db :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]

      ;; Verify 20 copies were created (storm count - 1 = 20)
      (is (= 20 (count copies))
          "Should create 20 storm copies")

      ;; Verify original + copies = 21 on stack
      (is (= 21 (count stack-objects))
          "Should have 21 objects on stack (original + 20 copies)"))))
