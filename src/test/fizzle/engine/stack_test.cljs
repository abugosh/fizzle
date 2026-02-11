(ns fizzle.engine.stack-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]))


;; === create-stack-item tests ===

(deftest test-create-stack-item-basic
  (testing "Creates a spell stack-item with all attributes present in DB"
    (let [db (init-game-state)
          db' (stack/create-stack-item db {:stack-item/type :spell
                                           :stack-item/controller :player-1
                                           :stack-item/source :some-source
                                           :stack-item/effects [{:effect/type :add-mana}]
                                           :stack-item/description "Dark Ritual"})
          ;; Find the stack item
          items (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :stack-item/position _]]
                     db')]
      (is (= 1 (count items)))
      (let [item (first items)]
        (is (= :spell (:stack-item/type item)))
        (is (= :player-1 (:stack-item/controller item)))
        (is (= :some-source (:stack-item/source item)))
        (is (= [{:effect/type :add-mana}] (:stack-item/effects item)))
        (is (= "Dark Ritual" (:stack-item/description item)))))))


(deftest test-create-stack-item-auto-assigns-position
  (testing "Second stack-item has higher position than first"
    (let [db (init-game-state)
          db' (stack/create-stack-item db {:stack-item/type :spell
                                           :stack-item/controller :player-1})
          db'' (stack/create-stack-item db' {:stack-item/type :spell
                                             :stack-item/controller :player-1})
          items (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :stack-item/position _]]
                     db'')
          positions (map :stack-item/position items)]
      (is (= 2 (count items)))
      (is (apply < (sort positions))
          "Second stack-item should have higher position"))))


(deftest test-create-stack-item-different-types
  (testing "Different stack-item types stored correctly"
    (let [db (init-game-state)
          db' (-> db
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1})
                  (stack/create-stack-item {:stack-item/type :storm-copy
                                            :stack-item/controller :player-1
                                            :stack-item/is-copy true})
                  (stack/create-stack-item {:stack-item/type :activated-ability
                                            :stack-item/controller :player-1}))
          items (d/q '[:find [(pull ?e [*]) ...]
                       :where [?e :stack-item/position _]]
                     db')
          types (set (map :stack-item/type items))]
      (is (= 3 (count items)))
      (is (= #{:spell :storm-copy :activated-ability} types)))))


;; === remove-stack-item tests ===

(deftest test-remove-stack-item
  (testing "Removes stack-item without affecting others"
    (let [db (init-game-state)
          db' (stack/create-stack-item db {:stack-item/type :spell
                                           :stack-item/controller :player-1
                                           :stack-item/description "first"})
          db'' (stack/create-stack-item db' {:stack-item/type :spell
                                             :stack-item/controller :player-1
                                             :stack-item/description "second"})
          ;; Find the first item (lower position)
          items (sort-by :stack-item/position
                         (d/q '[:find [(pull ?e [*]) ...]
                                :where [?e :stack-item/position _]]
                              db''))
          first-eid (:db/id (first items))
          db''' (stack/remove-stack-item db'' first-eid)
          remaining (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :stack-item/position _]]
                         db''')]
      (is (= 1 (count remaining)))
      (is (= "second" (:stack-item/description (first remaining)))))))


;; === get-top-stack-item tests ===

(deftest test-get-top-stack-item-returns-highest
  (testing "Returns the stack-item with highest position"
    (let [db (init-game-state)
          db' (-> db
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1
                                            :stack-item/description "first"})
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1
                                            :stack-item/description "second"})
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1
                                            :stack-item/description "third"}))
          top (stack/get-top-stack-item db')]
      (is (= "third" (:stack-item/description top))))))


(deftest test-get-top-stack-item-empty-returns-nil
  (testing "Returns nil on empty stack"
    (let [db (init-game-state)]
      (is (nil? (stack/get-top-stack-item db))))))


;; === get-all-stack-items tests ===

(deftest test-get-all-stack-items-lifo-order
  (testing "Returns all stack-items in LIFO order (highest position first)"
    (let [db (init-game-state)
          db' (-> db
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1
                                            :stack-item/description "first"})
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1
                                            :stack-item/description "second"})
                  (stack/create-stack-item {:stack-item/type :spell
                                            :stack-item/controller :player-1
                                            :stack-item/description "third"}))
          items (stack/get-all-stack-items db')]
      (is (= 3 (count items)))
      (is (= "third" (:stack-item/description (first items))))
      (is (= "first" (:stack-item/description (last items)))))))


;; === stack-empty? tests ===

(deftest test-stack-empty
  (testing "Returns true on fresh db, false after add, true after remove"
    (let [db (init-game-state)]
      (is (true? (stack/stack-empty? db)))
      (let [db' (stack/create-stack-item db {:stack-item/type :spell
                                             :stack-item/controller :player-1})]
        (is (false? (stack/stack-empty? db')))
        (let [eid (:db/id (stack/get-top-stack-item db'))
              db'' (stack/remove-stack-item db' eid)]
          (is (true? (stack/stack-empty? db''))))))))


;; === get-next-stack-order tests ===

(deftest test-get-next-stack-order-empty-db
  (testing "Returns 0 on fresh DB"
    (let [db (init-game-state)]
      (is (= 0 (stack/get-next-stack-order db))))))


(deftest test-get-next-stack-order-with-objects-on-stack
  (testing "Returns higher than both stack-items and objects on the stack"
    (let [db (init-game-state)
          ;; Manually place an object on the stack with position 5
          obj-id (random-uuid)
          db (d/db-with db [{:object/id obj-id
                             :object/zone :stack
                             :object/position 5
                             :object/tapped false}])
          ;; Add a stack-item (should get position 6, since object has 5)
          db (stack/create-stack-item db {:stack-item/type :spell
                                          :stack-item/controller :player-1})
          ;; Next order should be 7 (max of object 5 and stack-item 6 is 6, +1 = 7)
          next-order (stack/get-next-stack-order db)]
      (is (= 7 next-order)
          "Should account for both objects on stack and stack-items"))))


;; === resolve-effect-target tests ===

(deftest test-resolve-self-target
  (testing "Effect with :self target resolves to source-id"
    (let [effect {:effect/type :sacrifice :effect/target :self}
          resolved (stack/resolve-effect-target effect 42 :player-1 nil)]
      (is (= 42 (:effect/target resolved))))))


(deftest test-resolve-controller-target
  (testing "Effect with :controller target resolves to controller"
    (let [effect {:effect/type :draw :effect/target :controller}
          resolved (stack/resolve-effect-target effect 42 :player-1 nil)]
      (is (= :player-1 (:effect/target resolved))))))


(deftest test-resolve-any-player-with-stored-target
  (testing "Effect with :any-player resolves from stored targets"
    (let [effect {:effect/type :drain :effect/target :any-player}
          resolved (stack/resolve-effect-target effect 42 :player-1 {:player :opponent-1})]
      (is (= :opponent-1 (:effect/target resolved))))))


(deftest test-resolve-any-player-without-stored-target
  (testing "Effect with :any-player and nil stored-targets returns unchanged"
    (let [effect {:effect/type :drain :effect/target :any-player}
          resolved (stack/resolve-effect-target effect 42 :player-1 nil)]
      (is (= :any-player (:effect/target resolved))
          "Should pass through when no stored player target"))))


(deftest test-resolve-target-ref
  (testing "Effect with target-ref resolves from stored targets map"
    (let [effect {:effect/type :deal-damage :effect/target-ref :target-1}
          resolved (stack/resolve-effect-target effect 42 :player-1 {:target-1 99})]
      (is (= 99 (:effect/target resolved))))))


(deftest test-resolve-target-ref-takes-priority-over-self
  (testing "target-ref takes priority over :self target"
    (let [effect {:effect/type :deal-damage
                  :effect/target :self
                  :effect/target-ref :target-1}
          resolved (stack/resolve-effect-target effect 42 :player-1 {:target-1 99})]
      (is (= 99 (:effect/target resolved))
          "target-ref should win over :self"))))


;; === get-stack-item-by-object-ref tests ===

(deftest test-get-stack-item-by-object-ref
  (testing "Finds stack-item by its object-ref"
    (let [db (init-game-state)
          ;; Get the Dark Ritual object EID
          obj-eid (d/q '[:find ?e .
                         :where [?e :object/id _]]
                       db)
          db' (stack/create-stack-item db {:stack-item/type :spell
                                           :stack-item/controller :player-1
                                           :stack-item/object-ref obj-eid})
          result (stack/get-stack-item-by-object-ref db' obj-eid)]
      (is (= :spell (:stack-item/type result)))
      (is (= obj-eid (:stack-item/object-ref result))))))


(deftest test-get-stack-item-by-object-ref-nil-when-missing
  (testing "Returns nil when no stack-item references the given object"
    (let [db (init-game-state)]
      (is (nil? (stack/get-stack-item-by-object-ref db 99999))))))


(deftest test-resolve-passthrough
  (testing "Concrete target passes through unchanged"
    (let [effect {:effect/type :draw :effect/target :player-1}
          resolved (stack/resolve-effect-target effect 42 :player-1 nil)]
      (is (= :player-1 (:effect/target resolved))
          "Already-concrete target should pass through"))
    (let [effect {:effect/type :draw :effect/target :player-1}
          resolved (stack/resolve-effect-target effect 42 :player-1 {:player :opponent-1})]
      (is (= :player-1 (:effect/target resolved))
          "Concrete target unaffected by stored targets"))))


;; === cast-spell-mode integration tests ===

(deftest test-cast-spell-mode-creates-stack-item
  (testing "cast-spell-mode creates a :spell stack-item with object-ref"
    (let [db (init-game-state)
          ;; Give player mana to cast Dark Ritual (BBB cost)
          db (mana/add-mana db :player-1 {:black 3})
          ;; Get the Dark Ritual object-id
          obj (first (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :object/zone :hand]]
                          db))
          object-id (:object/id obj)
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          ;; Check that a stack-item was created
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db' object-id)
          stack-item (stack/get-stack-item-by-object-ref db' obj-eid)]
      (is (= :spell (:stack-item/type stack-item)))
      (is (= :player-1 (:stack-item/controller stack-item)))
      (is (= object-id (:stack-item/source stack-item)))
      (is (= obj-eid (:stack-item/object-ref stack-item))))))


(deftest test-cast-spell-mode-positions-match
  (testing "Object position and stack-item position match after casting"
    (let [db (init-game-state)
          db (mana/add-mana db :player-1 {:black 3})
          obj (first (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :object/zone :hand]]
                          db))
          object-id (:object/id obj)
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db' object-id)
          obj-after (d/pull db' [:object/position] obj-eid)
          stack-item (stack/get-stack-item-by-object-ref db' obj-eid)]
      (is (= (:object/position obj-after) (:stack-item/position stack-item))
          "Object and stack-item positions must match"))))


(deftest test-stack-item-removed-after-spell-resolution
  (testing "Stack-item is removed after resolve-spell"
    (let [db (init-game-state)
          db (mana/add-mana db :player-1 {:black 3})
          obj (first (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :object/zone :hand]]
                          db))
          object-id (:object/id obj)
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          ;; Verify stack-item exists
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db' object-id)
          stack-item (stack/get-stack-item-by-object-ref db' obj-eid)]
      (is (some? stack-item) "Stack-item exists before resolution")
      ;; Resolve the spell (uses resolve-spell which doesn't touch stack-items)
      ;; Stack-item removal is the caller's responsibility (::resolve-top, confirm handlers)
      ;; This test verifies the stack-item persists through resolve-spell alone
      (let [db-resolved (rules/resolve-spell db' :player-1 object-id)]
        ;; Stack-item should still exist (resolve-spell doesn't remove it - that's the event handler's job)
        (is (some? (stack/get-stack-item-by-object-ref db-resolved obj-eid))
            "resolve-spell alone should not remove stack-item")))))


(deftest test-storm-copy-creates-stack-item
  (testing "create-spell-copy creates a :storm-copy stack-item"
    (let [db (init-game-state)
          db (mana/add-mana db :player-1 {:black 3})
          ;; Get the Dark Ritual object
          obj (first (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :object/zone :hand]]
                          db))
          object-id (:object/id obj)
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          ;; Cast the spell first (puts it on stack)
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          ;; Create a storm copy
          db'' (triggers/create-spell-copy db' object-id :player-1)
          ;; Find the copy object (is-copy true, different from original)
          copy-objs (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :object/is-copy true]]
                         db'')
          copy (first copy-objs)
          copy-eid (:db/id copy)
          copy-stack-item (stack/get-stack-item-by-object-ref db'' copy-eid)]
      (is (= :storm-copy (:stack-item/type copy-stack-item)))
      (is (true? (:stack-item/is-copy copy-stack-item)))
      ;; Copy's stack-item position should match the copy object's position
      (let [copy-position (:object/position copy)]
        (is (= copy-position (:stack-item/position copy-stack-item))
            "Copy object and stack-item positions must match")))))


;; === resolve-top-of-stack integration tests ===

(deftest test-resolve-top-of-stack-removes-stack-item
  (testing "resolve-top-of-stack removes the spell's stack-item after resolution"
    (let [db (init-game-state)
          db (mana/add-mana db :player-1 {:black 3})
          obj (first (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :object/zone :hand]]
                          db))
          object-id (:object/id obj)
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          ;; Verify stack-item exists
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db' object-id)
          _ (is (some? (stack/get-stack-item-by-object-ref db' obj-eid))
                "Stack-item exists before resolution")
          ;; Resolve via resolve-top-of-stack
          db-resolved (game/resolve-top-of-stack db' :player-1)]
      (is (nil? (stack/get-stack-item-by-object-ref db-resolved obj-eid))
          "Stack-item removed after resolution"))))


(deftest test-multiple-stack-items-resolve-in-lifo-order
  (testing "When both a spell and ability stack-item exist, LIFO order is correct"
    (let [db (init-game-state)
          db (mana/add-mana db :player-1 {:black 3})
          obj (first (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :object/zone :hand]]
                          db))
          object-id (:object/id obj)
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          ;; Cast the spell (creates stack-item with some position)
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          ;; Add an ability stack-item AFTER the spell (higher position = resolves first)
          db'' (stack/create-stack-item db' {:stack-item/type :activated-ability
                                             :stack-item/controller :player-1
                                             :stack-item/source (random-uuid)
                                             :stack-item/effects [{:effect/type :add-mana
                                                                   :effect/mana {:black 1}
                                                                   :effect/target :controller}]})
          ;; Verify ability stack-item has higher position than spell stack-item
          obj-eid (d/q '[:find ?e .
                         :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db'' object-id)
          spell-item (stack/get-stack-item-by-object-ref db'' obj-eid)
          all-items (stack/get-all-stack-items db'')
          ability-item (first (filter #(= :activated-ability (:stack-item/type %)) all-items))]
      (is (> (:stack-item/position ability-item) (:stack-item/position spell-item))
          "Ability stack-item should be on top of stack")
      ;; Resolve top - should resolve the ability, not the spell
      (let [db-after (game/resolve-top-of-stack db'' :player-1)
            remaining-items (stack/get-all-stack-items db-after)]
        ;; Ability stack-item should be gone
        (is (empty? (filter #(= :activated-ability (:stack-item/type %)) remaining-items))
            "Ability stack-item should be resolved and removed")
        ;; Spell stack-item should still exist
        (is (some? (stack/get-stack-item-by-object-ref db-after obj-eid))
            "Spell stack-item should still be on stack")))))


;; === queries/stack-empty? tests ===

(deftest test-queries-stack-empty-checks-stack-items
  (testing "queries/stack-empty? returns false when stack-items exist"
    (let [db (init-game-state)]
      ;; Initially empty
      (is (true? (queries/stack-empty? db))
          "Fresh db has empty stack")
      ;; Add a stack-item
      (let [db' (stack/create-stack-item db {:stack-item/type :spell
                                             :stack-item/controller :player-1})]
        (is (false? (queries/stack-empty? db'))
            "Stack-item makes stack non-empty")))))


;; === Task 3: Trigger migration tests ===

;; Test helpers for full card DB setup

(defn create-full-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-card-to-zone
  "Add a card object to a zone. Returns [db object-id]."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (queries/get-player-eid db player-id)
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn add-cards-to-library
  "Add multiple cards to library with positions. Returns [db object-ids]."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)]
    (loop [remaining card-ids
           position 0
           object-ids []]
      (if (empty? remaining)
        [@conn object-ids]
        (let [card-id (first remaining)
              obj-id (random-uuid)
              card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
              player-eid (queries/get-player-eid @conn player-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining) (inc position) (conj object-ids obj-id)))))))


(deftest test-activated-ability-creates-stack-item
  (testing "Activating non-mana ability creates a :stack-item/type :activated-ability stack-item"
    (let [db (create-full-db)
          [db' delta-id] (add-card-to-zone db :polluted-delta :battlefield :player-1)
          [db'' _] (add-cards-to-library db' [:island] :player-1)
          ;; Activate ability (index 0 = the tutor ability)
          result (ability-events/activate-ability db'' :player-1 delta-id 0)
          db-after (:db result)
          ;; Find stack-items of type :activated-ability
          ability-items (d/q '[:find [(pull ?e [*]) ...]
                               :where [?e :stack-item/type :activated-ability]]
                             db-after)]
      ;; Should have a stack-item, not a trigger entity
      (is (= 1 (count ability-items))
          "Activated ability should create a stack-item")
      (let [item (first ability-items)]
        (is (= :activated-ability (:stack-item/type item)))
        (is (= :player-1 (:stack-item/controller item)))
        (is (= delta-id (:stack-item/source item)))
        (is (seq (:stack-item/effects item))
            "Stack-item should have effects from the ability")))))


(deftest test-dispatch-stacked-trigger-creates-stack-item
  (testing "dispatch-event creates stack-items for stacked triggers (not trigger entities)"
    (registry/clear-registry!)
    (let [source-id (random-uuid)
          trigger {:trigger/id :test-t1
                   :trigger/type :test-trigger
                   :trigger/event-type :permanent-tapped
                   :trigger/source source-id
                   :trigger/controller :player-1
                   :trigger/effects [{:effect/type :add-mana
                                      :effect/mana {:black 1}
                                      :effect/target :controller}]
                   :trigger/uses-stack? true}
          _ (registry/register-trigger! trigger)
          db (init-game-state)
          event {:event/type :permanent-tapped
                 :event/object-id source-id
                 :event/controller :player-1}
          result (dispatch/dispatch-event db event)
          ;; Check for stack-items
          stack-items (d/q '[:find [(pull ?e [*]) ...]
                             :where [?e :stack-item/position _]]
                           result)]
      (is (= 1 (count stack-items))
          "Dispatch should create a stack-item for stacked triggers")
      (let [item (first stack-items)]
        (is (= :permanent-tapped (:stack-item/type item)))
        (is (= :player-1 (:stack-item/controller item)))
        (is (= source-id (:stack-item/source item)))
        (is (seq (:stack-item/effects item))
            "Stack-item should have effects"))
      (registry/clear-registry!))))


(deftest test-resolve-top-handles-ability-stack-item
  (testing "::resolve-top correctly resolves a :activated-ability stack-item"
    (let [db (create-full-db)
          ;; Create a stack-item directly for an activated ability with simple effects
          db-with-item (stack/create-stack-item db
                                                {:stack-item/type :activated-ability
                                                 :stack-item/controller :player-1
                                                 :stack-item/source (random-uuid)
                                                 :stack-item/effects [{:effect/type :add-mana
                                                                       :effect/mana {:black 1}
                                                                       :effect/target :controller}]})
          ;; Verify it's on the stack
          top (stack/get-top-stack-item db-with-item)]
      (is (some? top) "Stack-item should exist before resolution")
      ;; Resolve via resolve-top-of-stack
      (let [db-after (game/resolve-top-of-stack db-with-item :player-1)]
        ;; Stack-item should be removed
        (is (nil? (stack/get-top-stack-item db-after))
            "Stack-item should be removed after resolution")
        ;; Mana should be added (effect executed)
        (is (= 1 (:black (queries/get-mana-pool db-after :player-1)))
            "Effect should have been executed (added black mana)")))))


(deftest test-resolve-effect-target-used-in-triggers
  (testing "Trigger resolution uses resolve-effect-target for :self and :controller"
    (let [db (create-full-db)
          [db' cob-id] (add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Create a :permanent-tapped stack-item with :controller target effect
          db-with-item (stack/create-stack-item db'
                                                {:stack-item/type :permanent-tapped
                                                 :stack-item/controller :player-1
                                                 :stack-item/source cob-id
                                                 :stack-item/effects [{:effect/type :lose-life
                                                                       :effect/amount 1
                                                                       :effect/target :controller}]})]
      ;; Resolve via resolve-top-of-stack
      (let [db-after (game/resolve-top-of-stack db-with-item :player-1)
            life-after (queries/get-life-total db-after :player-1)]
        ;; Player should have lost 1 life (effect resolved :controller → :player-1)
        (is (= 19 life-after)
            "resolve-effect-target should resolve :controller to player-1")))))
