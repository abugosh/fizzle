(ns fizzle.engine.counter-ability-test
  "Tests for :counter-ability effect type (Stifle mechanic).
   Tests engine-level mechanic, not card definitions."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]
    [fizzle.engine.stack :as stack]))


;; === Test Helpers ===

(defn get-all-stack-items
  "Get all stack-items from db."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :stack-item/position _]]
       db))


(defn add-activated-ability-to-stack
  "Add an activated ability stack-item for testing.
   Returns updated db."
  [db player-id source-id ability-type]
  (let [stack-item {:stack-item/type :activated-ability
                    :stack-item/controller player-id
                    :stack-item/source source-id
                    :stack-item/effects [{:effect/type :draw :effect/amount 1}]
                    :stack-item/description "Test ability"
                    :stack-item/ability-type ability-type}]
    (stack/create-stack-item db stack-item)))


;; === Effect Type Tests ===

(deftest execute-effect-counter-ability-basic
  (testing "Counter-ability removes activated ability from stack"
    (let [db (init-game-state)
          source-id (random-uuid)
          ;; Add activated ability to stack
          db-with-ability (add-activated-ability-to-stack db :player-1 source-id :activated)
          stack-items (get-all-stack-items db-with-ability)
          ability-item (first stack-items)
          ability-eid (:db/id ability-item)
          ;; Counter the ability
          effect {:effect/type :counter-ability
                  :effect/target ability-eid}
          db-after (fx/execute-effect db-with-ability :player-1 effect)]
      ;; Stack should be empty after countering
      (is (empty? (get-all-stack-items db-after))))))


(deftest execute-effect-counter-ability-no-target
  (testing "Counter-ability with no target is no-op — existing stack items remain"
    (let [db (init-game-state)
          ;; Add an ability to stack first — no-target no-op must not touch it
          source-id (random-uuid)
          db-with-ability (add-activated-ability-to-stack db :player-1 source-id :activated)
          effect {:effect/type :counter-ability}
          db-after (fx/execute-effect db-with-ability :player-1 effect)
          items-after (get-all-stack-items db-after)]
      ;; Bug caught: (= db db-after) only checks empty-stack no-op; with an item on stack,
      ;; a buggy counter-ability might remove ALL items when target is missing
      (is (= 1 (count items-after))
          "No-target counter-ability must leave existing stack items untouched")
      (is (= :activated-ability (:stack-item/type (first items-after)))
          "The untargeted ability should remain on the stack with its type intact"))))


(deftest execute-effect-counter-ability-invalid-target
  (testing "Counter-ability with invalid target is no-op — existing stack items remain"
    (let [db (init-game-state)
          ;; Add an ability to stack first — invalid target must not remove it
          source-id (random-uuid)
          db-with-ability (add-activated-ability-to-stack db :player-1 source-id :activated)
          items-before (get-all-stack-items db-with-ability)
          valid-eid (:db/id (first items-before))
          effect {:effect/type :counter-ability
                  :effect/target 99999}  ; Non-existent eid — must not match valid-eid
          db-after (fx/execute-effect db-with-ability :player-1 effect)
          items-after (get-all-stack-items db-after)]
      ;; Bug caught: (= db db-after) hides a bug where invalid eid accidentally matches
      ;; Datascript entity 0 or some other unexpected eid
      (is (= 1 (count items-after))
          "Invalid-target counter-ability must leave the existing ability on the stack")
      (is (= valid-eid (:db/id (first items-after)))
          "The correct stack-item eid should be preserved after a no-op counter"))))


(deftest execute-effect-counter-ability-wrong-type
  (testing "Counter-ability targeting spell is no-op"
    (let [db (init-game-state)
          ;; Get the Dark Ritual spell in hand
          ritual-obj (first (q/get-hand db :player-1))
          ;; Create a spell stack-item
          db-with-spell (stack/create-stack-item db {:stack-item/type :spell
                                                     :stack-item/controller :player-1
                                                     :stack-item/source (random-uuid)
                                                     :stack-item/object-ref (:db/id ritual-obj)
                                                     :stack-item/effects []})
          stack-items (get-all-stack-items db-with-spell)
          spell-item (first stack-items)
          spell-eid (:db/id spell-item)
          ;; Try to counter it as an ability (should fail)
          effect {:effect/type :counter-ability
                  :effect/target spell-eid}
          db-after (fx/execute-effect db-with-spell :player-1 effect)]
      ;; Spell should still be on stack (counter-ability doesn't work on spells)
      (is (= 1 (count (get-all-stack-items db-after)))))))


;; === Mana Ability Exclusion Tests ===

(deftest mana-abilities-cannot-be-countered
  (testing "Counter-ability on mana ability is no-op (mana abilities can't be targeted)"
    (let [db (init-game-state)
          source-id (random-uuid)
          ;; Add mana ability to stack (hypothetically, though mana abilities don't use stack)
          db-with-mana-ability (add-activated-ability-to-stack db :player-1 source-id :mana)
          stack-items (get-all-stack-items db-with-mana-ability)
          ability-item (first stack-items)
          ability-eid (:db/id ability-item)
          ;; Try to counter the mana ability
          effect {:effect/type :counter-ability
                  :effect/target ability-eid}
          db-after (fx/execute-effect db-with-mana-ability :player-1 effect)]
      ;; Mana ability should still be on stack (cannot be countered)
      (is (= 1 (count (get-all-stack-items db-after)))))))


;; === Integration Tests ===

(deftest counter-ability-removes-ability-effects
  (testing "Countering ability prevents its effects from resolving"
    (let [db (init-game-state)
          source-id (random-uuid)
          ;; Add activated ability that would draw a card
          db-with-ability (add-activated-ability-to-stack db :player-1 source-id :activated)
          initial-hand-count (count (q/get-hand db-with-ability :player-1))
          stack-items (get-all-stack-items db-with-ability)
          ability-item (first stack-items)
          ability-eid (:db/id ability-item)
          ;; Counter the ability
          effect {:effect/type :counter-ability
                  :effect/target ability-eid}
          db-after (fx/execute-effect db-with-ability :player-1 effect)]
      ;; Hand count should be unchanged (ability was countered before resolving)
      (is (= initial-hand-count (count (q/get-hand db-after :player-1))))
      ;; Stack should be empty
      (is (empty? (get-all-stack-items db-after))))))


(deftest counter-ability-multiple-abilities
  (testing "Counter-ability only affects targeted ability"
    (let [db (init-game-state)
          source-id-1 (random-uuid)
          source-id-2 (random-uuid)
          ;; Add two abilities to stack
          db-with-ab1 (add-activated-ability-to-stack db :player-1 source-id-1 :activated)
          db-with-ab2 (add-activated-ability-to-stack db-with-ab1 :player-1 source-id-2 :activated)
          stack-items (get-all-stack-items db-with-ab2)
          first-ability-eid (:db/id (first stack-items))
          ;; Counter only the first ability
          effect {:effect/type :counter-ability
                  :effect/target first-ability-eid}
          db-after (fx/execute-effect db-with-ab2 :player-1 effect)]
      ;; Only one ability should remain on stack
      (is (= 1 (count (get-all-stack-items db-after)))))))
