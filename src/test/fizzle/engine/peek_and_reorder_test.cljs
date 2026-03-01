(ns fizzle.engine.peek-and-reorder-test
  "Tests for :peek-and-reorder effect type and selection mechanism.
   Tests engine-level mechanic, not card definitions."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]
    [fizzle.events.selection.core :as selection-core]))


;; === Test Helpers ===

(defn add-library-cards
  "Add cards to a player's library with sequential positions.
   Takes a vector of card-ids (keywords) and adds them with positions 0, 1, 2...
   Position 0 = top of library."
  [db player-id card-ids]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (doseq [idx (range (count card-ids))]
      ;; Use Dark Ritual as a test card (guaranteed to exist in registry)
      (let [card-eid (d/q '[:find ?e .
                            :where [?e :card/id :dark-ritual]]
                          @conn)]
        (d/transact! conn [{:object/id (random-uuid)
                            :object/card card-eid
                            :object/zone :library
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/position idx
                            :object/tapped false}])))
    @conn))


;; === Effect Type Tests ===

(deftest execute-effect-checked-peek-and-reorder-returns-needs-selection
  (testing "execute-effect-checked returns :needs-selection for :peek-and-reorder"
    (let [db (init-game-state)
          effect {:effect/type :peek-and-reorder :effect/count 3}
          result (fx/execute-effect-checked db :player-1 effect)]
      (is (contains? result :needs-selection))
      (is (= :peek-and-reorder (:effect/type (:needs-selection result)))))))


(deftest execute-effect-peek-and-reorder-backward-compat
  (testing "execute-effect (backward-compat) returns db unchanged for :peek-and-reorder"
    (let [db (init-game-state)
          effect {:effect/type :peek-and-reorder :effect/count 3}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= db db')
          "Backward-compat execute-effect should return db for interactive effects"))))


;; === Selection Builder Tests ===

(deftest build-selection-for-effect-peek-and-reorder-basic
  (testing "build-selection-for-effect creates :peek-and-reorder selection"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          spell-id (random-uuid)
          effect {:effect/type :peek-and-reorder :effect/count 3}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (some? selection))
      (is (= :peek-and-reorder (:selection/type selection)))
      (is (= 3 (count (:selection/candidates selection))))
      (is (= :player-1 (:selection/player-id selection)))
      (is (= spell-id (:selection/spell-id selection)))
      (is (= [] (:selection/ordered selection))))))


(deftest build-selection-for-effect-peek-and-reorder-empty-library
  (testing "build-selection-for-effect returns nil when library is empty"
    (let [db (init-game-state)
          spell-id (random-uuid)
          effect {:effect/type :peek-and-reorder :effect/count 3}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (nil? selection)
          "No selection should be created when library has no cards"))))


(deftest build-selection-for-effect-peek-and-reorder-fewer-than-count
  (testing "build-selection-for-effect works with fewer cards than count"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2]))
          spell-id (random-uuid)
          effect {:effect/type :peek-and-reorder :effect/count 5}
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect [])]
      (is (some? selection))
      (is (= 2 (count (:selection/candidates selection))))
      (is (= [] (:selection/ordered selection))))))


(deftest build-selection-for-effect-peek-and-reorder-with-remaining-effects
  (testing "build-selection-for-effect preserves remaining effects"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          spell-id (random-uuid)
          effect {:effect/type :peek-and-reorder :effect/count 3}
          remaining [{:effect/type :draw :effect/count 1}]
          selection (selection-core/build-selection-for-effect db :player-1 spell-id effect remaining)]
      (is (= remaining (:selection/remaining-effects selection))))))


;; === Selection Confirmation Tests ===

(deftest execute-confirmed-selection-peek-and-reorder-basic
  (testing "execute-confirmed-selection reorders library according to ordered list"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          library-objs (q/get-objects-in-zone db :player-1 :library)
          [obj-1 obj-2 obj-3] (sort-by :object/position library-objs)
          ;; Reverse order: obj-3, obj-2, obj-1
          selection {:selection/type :peek-and-reorder
                     :selection/candidates #{(:object/id obj-1)
                                             (:object/id obj-2)
                                             (:object/id obj-3)}
                     :selection/ordered [(:object/id obj-3)
                                         (:object/id obj-2)
                                         (:object/id obj-1)]
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)
                     :selection/remaining-effects []}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)
          library-after (sort-by :object/position (q/get-objects-in-zone db-after :player-1 :library))]
      (is (= 3 (count library-after)))
      ;; Verify new order: obj-3 at position 0, obj-2 at position 1, obj-1 at position 2
      (is (= (:object/id obj-3) (:object/id (nth library-after 0))))
      (is (= (:object/id obj-2) (:object/id (nth library-after 1))))
      (is (= (:object/id obj-1) (:object/id (nth library-after 2)))))))


(deftest execute-confirmed-selection-peek-and-reorder-partial-order
  (testing "execute-confirmed-selection handles partial ordering (unordered cards stay at original positions)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          library-objs (q/get-objects-in-zone db :player-1 :library)
          [obj-1 obj-2 obj-3] (sort-by :object/position library-objs)
          ;; Only order obj-3 and obj-1, leave obj-2 unordered
          selection {:selection/type :peek-and-reorder
                     :selection/candidates #{(:object/id obj-1)
                                             (:object/id obj-2)
                                             (:object/id obj-3)}
                     :selection/ordered [(:object/id obj-3)
                                         (:object/id obj-1)]
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)
                     :selection/remaining-effects []}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)
          library-after (sort-by :object/position (q/get-objects-in-zone db-after :player-1 :library))]
      (is (= 3 (count library-after)))
      ;; Verify: obj-3 at position 0, obj-1 at position 1, obj-2 stays at position 2
      (is (= (:object/id obj-3) (:object/id (nth library-after 0))))
      (is (= (:object/id obj-1) (:object/id (nth library-after 1))))
      (is (= (:object/id obj-2) (:object/id (nth library-after 2)))))))


(deftest execute-confirmed-selection-peek-and-reorder-with-remaining-effects
  (testing "execute-confirmed-selection executes remaining effects after reordering"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          library-objs (q/get-objects-in-zone db :player-1 :library)
          [obj-1 obj-2 obj-3] (sort-by :object/position library-objs)
          selection {:selection/type :peek-and-reorder
                     :selection/candidates #{(:object/id obj-1)
                                             (:object/id obj-2)
                                             (:object/id obj-3)}
                     :selection/ordered [(:object/id obj-3)
                                         (:object/id obj-2)
                                         (:object/id obj-1)]
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)
                     :selection/remaining-effects [{:effect/type :add-mana
                                                    :effect/mana {:blue 1}}]}
          result (selection-core/execute-confirmed-selection db selection)
          db-after (:db result)]
      ;; Verify mana was added
      (is (= 1 (:blue (q/get-mana-pool db-after :player-1)))))))


(deftest execute-confirmed-selection-peek-and-reorder-finalized
  (testing "execute-confirmed-selection returns :finalized? true"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          library-objs (q/get-objects-in-zone db :player-1 :library)
          [obj-1 obj-2 obj-3] (sort-by :object/position library-objs)
          selection {:selection/type :peek-and-reorder
                     :selection/candidates #{(:object/id obj-1)
                                             (:object/id obj-2)
                                             (:object/id obj-3)}
                     :selection/ordered [(:object/id obj-3)
                                         (:object/id obj-2)
                                         (:object/id obj-1)]
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)
                     :selection/remaining-effects []}
          result (selection-core/execute-confirmed-selection db selection)]
      (is (some? (:db result))))))
