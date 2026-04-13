(ns fizzle.events.resolve-all-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.lightning-bolt]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.resolution :as resolution]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register the history interceptor AND SBA dispatch for dispatch-sync tests
(interceptor/register!)
(db-effect/register!)


;; === Test helpers ===

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
                        :player/land-plays-left 1}
                       {:player/id :player-2
                        :player/name "Opponent"
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


(defn dispatch-resolve-all
  "Dispatch ::resolve-all through re-frame and return the resulting app-db.
   Since ::resolve-all uses recursive :fx dispatch, we loop dispatch-sync
   until the event stops re-dispatching (stack empty, selection, or new items).
   Max 20 iterations as safety."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::resolution/resolve-all])
  ;; After first dispatch-sync, the :fx dispatch is queued but not processed.
  ;; We loop, re-dispatching with the initial-ids until stable.
  (let [initial-ids (set (map :db/id (queries/get-all-stack-items (:game/db app-db))))]
    (loop [iterations 0]
      (let [db @rf-db/app-db]
        (if (or (>= iterations 20)
                (:game/pending-selection db)
                (let [game-db (:game/db db)
                      top (stack/get-top-stack-item game-db)]
                  (or (nil? top)
                      (not (contains? initial-ids (:db/id top))))))
          db
          (do
            (rf/dispatch-sync [::resolution/resolve-all initial-ids])
            (recur (inc iterations))))))))


;; === resolve-one-item tests (game-db level) ===

(deftest test-resolve-one-item-spell
  (testing "Spell stack-item with object-ref resolves and stack-item is removed"
    (let [db (create-full-db)
          [db object-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:black 3})
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:black 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db' (rules/cast-spell-mode db :player-1 object-id mode)
          ;; Filter to spell stack-items only (storm also creates a :storm item)
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db'))]
      ;; Should have a spell stack-item with object-ref
      (is (= 1 (count spell-items)) "Should have exactly 1 spell stack-item")
      ;; Resolve via resolve-one-item (resolves top item which is :storm)
      ;; Keep resolving until stack is empty (storm meta-item + spell)
      (loop [db db'
             iterations 0]
        (if (or (nil? (stack/get-top-stack-item db))
                (> iterations 5))
          (do
            ;; Stack should be empty after all items resolved
            (is (nil? (stack/get-top-stack-item db))
                "Stack should be empty after resolution")
            ;; Spell should be in graveyard
            (is (= :graveyard (:object/zone (queries/get-object db object-id)))
                "Dark Ritual should be in graveyard after resolution"))
          (let [result (resolution/resolve-one-item db)]
            (is (nil? (:pending-selection result)) "Dark Ritual should not need selection")
            (recur (:db result) (inc iterations))))))))


(deftest test-resolve-one-item-activated-ability
  (testing "Activated-ability stack-item resolves and effect executes"
    (let [db (create-full-db)
          ;; Create a simple activated-ability stack-item with add-mana effect
          db' (stack/create-stack-item db
                                       {:stack-item/type :activated-ability
                                        :stack-item/controller :player-1
                                        :stack-item/source (random-uuid)
                                        :stack-item/effects [{:effect/type :add-mana
                                                              :effect/mana {:black 1}
                                                              :effect/target :controller}]})
          top (stack/get-top-stack-item db')]
      (is (some? top) "Stack-item should exist")
      ;; Resolve via resolve-one-item
      (let [result (resolution/resolve-one-item db')]
        (is (nil? (:pending-selection result)) "Simple ability should not need selection")
        ;; Stack-item should be removed
        (is (nil? (stack/get-top-stack-item (:db result)))
            "Stack-item should be removed after resolution")
        ;; Effect should have executed
        (is (= 1 (:black (queries/get-mana-pool (:db result) :player-1)))
            "Effect should have been executed (added black mana)")))))


(deftest test-resolve-one-item-trigger
  (testing "Non-spell, non-ability stack-item resolves via resolve-stack-item-effects"
    (let [db (create-full-db)
          ;; Create a trigger-type stack-item (permanent-tapped) with lose-life effect
          db' (stack/create-stack-item db
                                       {:stack-item/type :permanent-tapped
                                        :stack-item/controller :player-1
                                        :stack-item/source (random-uuid)
                                        :stack-item/effects [{:effect/type :lose-life
                                                              :effect/amount 1
                                                              :effect/target :controller}]})
          top (stack/get-top-stack-item db')]
      (is (some? top) "Stack-item should exist")
      ;; Resolve via resolve-one-item
      (let [result (resolution/resolve-one-item db')]
        (is (nil? (:pending-selection result)) "Trigger should not need selection")
        ;; Stack-item should be removed
        (is (nil? (stack/get-top-stack-item (:db result)))
            "Stack-item should be removed after resolution")
        ;; Effect should have executed
        (is (= 19 (queries/get-life-total (:db result) :player-1))
            "Effect should have been executed (lost 1 life)")))))


(deftest test-resolve-one-item-empty-stack
  (testing "Empty stack returns {:db db} unchanged"
    (let [db (create-full-db)]
      ;; No stack-items
      (is (nil? (stack/get-top-stack-item db)) "Stack should be empty")
      ;; Resolve via resolve-one-item
      (let [result (resolution/resolve-one-item db)]
        (is (nil? (:pending-selection result)) "Should not have pending selection")
        ;; DB should be unchanged
        (is (= db (:db result)) "DB should be unchanged for empty stack")))))


(deftest test-resolve-one-item-spell-with-selection
  (testing "Spell with tutor effect returns pending-selection and stack-item NOT removed"
    (let [db (create-full-db)
          ;; Merchant Scroll is a tutor card (search for blue instant)
          [db obj-id] (add-card-to-zone db :merchant-scroll :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 3})
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:colorless 1 :blue 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db-cast (rules/cast-spell-mode db :player-1 obj-id mode)
          ;; Find the spell stack-item (not the storm meta-item)
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db-cast))]
      (is (= 1 (count spell-items)) "Should have exactly 1 spell stack-item")
      ;; Resolve - should trigger tutor selection
      (let [result (resolution/resolve-one-item db-cast)]
        (is (some? (:pending-selection result))
            "Tutor spell should create pending selection")))))


(deftest test-resolve-one-item-storm-creates-copies
  (testing "Storm stack-item creates new stack-items for copies"
    (let [db (create-full-db)
          ;; Cast a spell first to get a source for storm copies
          [db' src-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          db' (mana/add-mana db' :player-1 {:black 3})
          ;; Create a storm stack-item directly
          db' (stack/create-stack-item db'
                                       {:stack-item/type :storm
                                        :stack-item/controller :player-1
                                        :stack-item/source src-id
                                        :stack-item/effects [{:effect/type :storm-copies
                                                              :effect/count 2}]})
          items-before (queries/get-all-stack-items db')]
      ;; Should have 1 stack-item (the storm meta-item)
      (is (= 1 (count items-before)) "Should have 1 stack-item before resolution")
      ;; Resolve the storm item
      (let [result (resolution/resolve-one-item db')
            items-after (queries/get-all-stack-items (:db result))]
        (is (nil? (:pending-selection result)) "Storm should not need selection")
        ;; Storm meta-item should be removed, but copies created
        (is (= 2 (count items-after))
            "Should have 2 storm copy stack-items after resolution")))))


;; === ::resolve-all tests (via re-frame dispatch) ===

(deftest test-resolve-all-multiple-items
  (testing "Multiple non-selection items all resolved"
    (let [db (create-full-db)
          ;; Create 3 simple trigger stack-items
          db (stack/create-stack-item db
                                      {:stack-item/type :permanent-tapped
                                       :stack-item/controller :player-1
                                       :stack-item/source (random-uuid)
                                       :stack-item/effects [{:effect/type :lose-life
                                                             :effect/amount 1
                                                             :effect/target :controller}]})
          db (stack/create-stack-item db
                                      {:stack-item/type :permanent-tapped
                                       :stack-item/controller :player-1
                                       :stack-item/source (random-uuid)
                                       :stack-item/effects [{:effect/type :lose-life
                                                             :effect/amount 1
                                                             :effect/target :controller}]})
          db (stack/create-stack-item db
                                      {:stack-item/type :permanent-tapped
                                       :stack-item/controller :player-1
                                       :stack-item/source (random-uuid)
                                       :stack-item/effects [{:effect/type :lose-life
                                                             :effect/amount 1
                                                             :effect/target :controller}]})
          _ (is (= 3 (count (queries/get-all-stack-items db))) "Should have 3 stack-items")
          app-db (merge (history/init-history) {:game/db db})
          result (dispatch-resolve-all app-db)]
      ;; All 3 items should be resolved
      (is (empty? (queries/get-all-stack-items (:game/db result)))
          "All stack-items should be resolved")
      ;; All 3 effects executed (lost 3 life total)
      (is (= 17 (queries/get-life-total (:game/db result) :player-1))
          "All 3 effects should have executed (lost 3 life)")
      ;; No pending selection
      (is (nil? (:game/pending-selection result))
          "Should not have pending selection"))))


(deftest test-resolve-all-stops-at-selection
  (testing "Resolve-all stops when a selection is needed"
    (let [db (create-full-db)
          ;; First: a simple trigger item (will be on bottom of stack, resolved last)
          db (stack/create-stack-item db
                                      {:stack-item/type :permanent-tapped
                                       :stack-item/controller :player-1
                                       :stack-item/source (random-uuid)
                                       :stack-item/effects [{:effect/type :lose-life
                                                             :effect/amount 1
                                                             :effect/target :controller}]})
          ;; Second: put Merchant Scroll (tutor) on the stack (on top, resolved first)
          [db obj-id] (add-card-to-zone db :merchant-scroll :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 3})
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:colorless 1 :blue 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db (rules/cast-spell-mode db :player-1 obj-id mode)
          items-before (queries/get-all-stack-items db)]
      ;; Should have 2+ items (spell + storm possibly + trigger)
      (is (>= (count items-before) 2) "Should have at least 2 stack items")
      (let [app-db (merge (history/init-history) {:game/db db})
            result (dispatch-resolve-all app-db)]
        ;; Should have pending selection (tutor on top stops resolution)
        (is (some? (:game/pending-selection result))
            "Should stop at selection")
        ;; The trigger item should still be on the stack (wasn't reached)
        (is (pos? (count (queries/get-all-stack-items (:game/db result))))
            "Items below selection should remain on stack")))))


(deftest test-resolve-all-only-item-needs-selection
  (testing "Single selection-requiring spell on stack: resolve-all returns pending-selection"
    (let [db (create-full-db)
          ;; Put only Merchant Scroll on stack (tutor needs selection)
          [db obj-id] (add-card-to-zone db :merchant-scroll :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 3})
          mode {:mode/id :primary
                :mode/zone :hand
                :mode/mana-cost {:colorless 1 :blue 1}
                :mode/additional-costs []
                :mode/on-resolve :graveyard}
          db (rules/cast-spell-mode db :player-1 obj-id mode)
          app-db (merge (history/init-history) {:game/db db})
          result (dispatch-resolve-all app-db)]
      ;; Should stop at the tutor's selection
      (is (some? (:game/pending-selection result))
          "Should return pending selection when only item needs selection")
      ;; The spell should still be on the stack (not removed by selection pause)
      (is (some #(:stack-item/object-ref %)
                (queries/get-all-stack-items (:game/db result)))
          "Spell stack-item should remain on stack"))))


(deftest test-resolve-all-stops-at-new-items
  (testing "Resolve-all stops when a newly-created item appears on top"
    (let [db (create-full-db)
          ;; Put a Dark Ritual on the stack as source reference
          [db src-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Create a storm stack-item that will create 2 copies
          db (stack/create-stack-item db
                                      {:stack-item/type :storm
                                       :stack-item/controller :player-1
                                       :stack-item/source src-id
                                       :stack-item/effects [{:effect/type :storm-copies
                                                             :effect/count 2}]})
          items-before (queries/get-all-stack-items db)]
      (is (= 1 (count items-before)) "Should have 1 stack-item (storm)")
      (let [app-db (merge (history/init-history) {:game/db db})
            result (dispatch-resolve-all app-db)]
        ;; Storm item should be resolved (removed)
        ;; But 2 new copies should be on the stack
        (let [items-after (queries/get-all-stack-items (:game/db result))]
          (is (= 2 (count items-after))
              "Should have 2 new copy stack-items (not resolved by resolve-all)")
          ;; No pending selection
          (is (nil? (:game/pending-selection result))
              "Should not have pending selection"))))))


(deftest test-resolve-all-single-item
  (testing "Single item on stack resolves same as resolve-top"
    (let [db (create-full-db)
          db (stack/create-stack-item db
                                      {:stack-item/type :permanent-tapped
                                       :stack-item/controller :player-1
                                       :stack-item/source (random-uuid)
                                       :stack-item/effects [{:effect/type :lose-life
                                                             :effect/amount 1
                                                             :effect/target :controller}]})
          _ (is (= 1 (count (queries/get-all-stack-items db))) "Should have 1 stack-item")
          app-db (merge (history/init-history) {:game/db db})
          result (dispatch-resolve-all app-db)]
      (is (empty? (queries/get-all-stack-items (:game/db result)))
          "Stack-item should be resolved")
      (is (= 19 (queries/get-life-total (:game/db result) :player-1))
          "Effect should have executed"))))


(deftest test-resolve-all-empty-stack
  (testing "Empty stack returns unchanged"
    (let [db (create-full-db)
          app-db (merge (history/init-history) {:game/db db})
          result (dispatch-resolve-all app-db)]
      (is (= db (:game/db result))
          "DB should be unchanged for empty stack")
      (is (nil? (:game/pending-selection result))
          "Should not have pending selection"))))


;; === History logging tests ===

;; History entry test for resolve-all moved to priority_test.cljs
;; (::yield-all is now the user-facing action that creates history entries)


;; === SBA sentinel: proves db-effect/register! is wired ===

(deftest sba-life-zero-fires-in-resolve-all-test
  (testing "db-effect/register! wired: :life-zero SBA fires after bolt kills 1-life opponent via dispatch-sync"
    ;; Bug caught: if db-effect/register! is missing, life reaches -2 but
    ;; :game/loss-condition is never set — SBAs silently skip.
    ;; NOTE: Uses th/add-card-to-zone (not local add-card-to-zone) so triggers
    ;; are registered via build-object-tx.
    (let [base-app-db (th/create-game-scenario {:bot-archetype :goldfish :mana {:red 1}})
          game-db (:game/db base-app-db)
          p2-eid (queries/get-player-eid game-db :player-2)
          game-db' (d/db-with game-db [[:db/add p2-eid :player/life 1]])
          [game-db'' obj-id] (th/add-card-to-zone game-db' :lightning-bolt :hand :player-1)
          app-db (assoc base-app-db :game/db game-db'')
          _ (reset! rf-db/app-db app-db)
          _ (rf/dispatch-sync [::casting/cast-spell {:object-id obj-id :target :player-2}])
          _ (rf/dispatch-sync [::resolution/resolve-top])
          result-db (:game/db @rf-db/app-db)
          game-state (queries/get-game-state result-db)]
      (is (= :life-zero (:game/loss-condition game-state))
          ":life-zero SBA must fire when bolt kills 1-life opponent — proves db-effect/register! is wired"))))
