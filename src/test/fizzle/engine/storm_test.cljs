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
    [fizzle.engine.triggers :as triggers]))


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
  (testing "Casting spell with :storm keyword creates trigger on stack"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :storm-obj-1)
          stack-items (q/get-stack-items db')]
      ;; Should have at least one trigger on stack
      (is (>= (count stack-items) 1) "Should have trigger on stack")
      ;; Find the storm trigger
      (let [storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))]
        (is (some? storm-trigger) "Should have a :storm trigger")
        (is (= :storm-obj-1 (:trigger/source storm-trigger)))))))


(deftest test-cast-non-storm-spell-no-trigger
  (testing "Casting spell without :storm keyword does NOT create trigger"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :non-storm-obj-1)
          stack-items (q/get-stack-items db')]
      ;; Should have no triggers
      (is (= 0 (count stack-items)) "Non-storm spell should not create trigger"))))


(deftest test-storm-trigger-on-top-of-spell
  (testing "Storm trigger has higher stack-order than spell (resolves first)"
    (let [db (init-storm-test-state)
          db' (rules/cast-spell db :player-1 :storm-obj-1)
          stack-items (q/get-stack-items db')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))]
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
          ;; Get storm trigger
          stack-items (q/get-stack-items db'')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))
          ;; Resolve the trigger
          db''' (triggers/resolve-trigger db'' storm-trigger)
          ;; Should have 2 copies on stack (spells cast before storm spell)
          stack-after (q/get-objects-in-zone db''' :player-1 :stack)]
      ;; Original spell + 2 copies = 3 objects on stack
      ;; Note: We also need to count non-storm spells still on stack
      (is (>= (count stack-after) 3)
          "Should have original + 2 copies on stack"))))


(deftest test-storm-copies-have-is-copy-flag
  (testing "Copies created by storm have :object/is-copy true"
    (let [db (init-storm-test-state)
          ;; Cast 1 non-storm spell first
          db' (rules/cast-spell db :player-1 :non-storm-obj-1)
          ;; Cast storm spell
          db'' (rules/cast-spell db' :player-1 :storm-obj-1)
          ;; Get and resolve storm trigger
          stack-items (q/get-stack-items db'')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))
          db''' (triggers/resolve-trigger db'' storm-trigger)
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
          stack-items (q/get-stack-items db'')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))
          db''' (triggers/resolve-trigger db'' storm-trigger)]
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
          ;; Get and resolve storm trigger
          stack-items (q/get-stack-items db')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))
          db'' (triggers/resolve-trigger db' storm-trigger)
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
          stack-items (q/get-stack-items db'')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))
          db''' (triggers/resolve-trigger db'' storm-trigger)
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
          stack-items (q/get-stack-items db'')
          storm-trigger (first (filter #(= :storm (:trigger/type %)) stack-items))
          db''' (triggers/resolve-trigger db'' storm-trigger)
          stack-objects (q/get-objects-in-zone db''' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 2 (count copies)) "Should have 2 copies after 2 prior spells"))))


(deftest test-storm-source-missing-returns-unchanged-db
  (testing "If source object gone at resolution, return db unchanged"
    (let [db (init-storm-test-state)
          ;; Create a trigger with a source that doesn't exist
          fake-trigger (triggers/create-trigger :storm :nonexistent-obj :player-1 {:count 3})
          db' (triggers/add-trigger-to-stack db fake-trigger)
          ;; Resolve should not crash, just return db unchanged
          db'' (triggers/resolve-trigger db' fake-trigger)
          ;; Should have same objects (no copies created, no crash)
          stack-objects (q/get-objects-in-zone db'' :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 0 (count copies)) "Should have 0 copies when source missing"))))
