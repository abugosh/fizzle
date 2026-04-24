(ns fizzle.events.selection.storm-split-test
  "Tests for storm split selection infrastructure.
   Covers: create-spell-copy target override, builder, stepper, confirm handler,
   resolve-one-item routing for targeted storm."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.brain-freeze :as brain-freeze]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.storm :as storm]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === Test Helpers ===

(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/is-opponent true
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0}])
    @conn))


(defn add-cards-to-library
  "Add N cards to a player's library with sequential positions."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [idx (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :library
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/position idx
                          :object/tapped false}]))
    @conn))


(defn get-zone-count
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (or (q/get-objects-in-zone db player-id zone) [])))


(defn cast-brain-freeze-with-target
  "Cast Brain Freeze selecting the given target player. Returns updated db."
  [db player-id object-id target-player-id]
  (let [card brain-freeze/card
        target-req (first (:card/targeting card))
        modes (rules/get-casting-modes db player-id object-id)
        mode (first (filter #(= :primary (:mode/id %)) modes))
        selection {:selection/type :cast-time-targeting
                   :selection/player-id player-id
                   :selection/object-id object-id
                   :selection/mode mode
                   :selection/target-requirement target-req
                   :selection/selected #{target-player-id}}]
    (sel-targeting/confirm-cast-time-target db selection)))


;; ============================================================
;; 1. create-spell-copy target override tests
;; ============================================================

(deftest create-spell-copy-target-override-test
  (testing "4-arg create-spell-copy uses target override instead of inherited targets"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          ;; Create copy with explicit target override
          db-with-copy (triggers/create-spell-copy db' source-id :player-1
                                                   {:player :player-1})
          stack-objects (q/get-objects-in-zone db-with-copy :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          copy-eid (d/q '[:find ?e .
                          :in $ ?oid
                          :where [?e :object/id ?oid]]
                        db-with-copy (:object/id copy))
          copy-si (stack/get-stack-item-by-object-ref db-with-copy copy-eid)]
      (is (some? copy) "Copy should exist")
      (is (= {:player :player-1} (:stack-item/targets copy-si))
          "Copy's stack-item should have the overridden target"))))


(deftest create-spell-copy-3-arg-backward-compat-test
  (testing "3-arg create-spell-copy still inherits targets from original"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          [db' source-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          db-m (mana/add-mana db' :player-1 {:blue 1 :colorless 1})
          db-cast (cast-brain-freeze-with-target db-m :player-1 source-id :player-2)
          ;; Create copy with 3-arg (should inherit opponent target)
          db-with-copy (triggers/create-spell-copy db-cast source-id :player-1)
          stack-objects (q/get-objects-in-zone db-with-copy :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          copy-eid (d/q '[:find ?e .
                          :in $ ?oid
                          :where [?e :object/id ?oid]]
                        db-with-copy (:object/id copy))
          copy-si (stack/get-stack-item-by-object-ref db-with-copy copy-eid)]
      (is (some? copy) "Copy should exist")
      (is (= {:player :player-2} (:stack-item/targets copy-si))
          "Copy's stack-item should inherit targets from original"))))


;; ============================================================
;; 2. build-storm-split-selection tests
;; ============================================================

(deftest build-storm-split-selection-correct-state-test
  (testing "Builder produces correct selection state"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          ;; Put Brain Freeze on stack directly (no casting, avoids double storm SI)
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          ;; Create a storm stack-item manually
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (storm/build-storm-split-selection db-with-storm :player-1 storm-si)]
      (is (some? selection) "Selection should not be nil")
      (is (= :storm-split (:selection/domain selection)))
      (is (= :finalized (:selection/lifecycle selection)))
      (is (= 3 (:selection/copy-count selection)))
      (is (= source-id (:selection/source-object-id selection)))
      (is (= :player-1 (:selection/player-id selection)))
      (is (= (:db/id storm-si) (:selection/stack-item-eid selection)))
      ;; valid-targets uses player-ids
      (is (= 2 (count (:selection/valid-targets selection)))
          "Should have 2 valid targets (opponent + self)")
      (is (contains? (set (:selection/valid-targets selection)) :player-2))
      (is (contains? (set (:selection/valid-targets selection)) :player-1))
      ;; Default allocation: all to first target (opponent)
      (let [alloc (:selection/allocation selection)]
        (is (= 3 (get alloc :player-2))
            "Default should allocate all copies to opponent")
        (is (= 0 (get alloc :player-1))
            "Default should allocate 0 to self")))))


(deftest build-storm-split-selection-zero-copies-returns-nil-test
  (testing "Builder returns nil for 0 copies"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 0}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (storm/build-storm-split-selection db-with-storm :player-1 storm-si)]
      (is (nil? selection) "Should return nil for 0 copies"))))


(deftest build-storm-split-selection-source-not-found-returns-nil-test
  (testing "Builder returns nil when source object doesn't exist"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          fake-source-id (random-uuid)
          db-with-storm (stack/create-stack-item db
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source fake-source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (storm/build-storm-split-selection db-with-storm :player-1 storm-si)]
      (is (nil? selection) "Should return nil when source object not found"))))


(deftest build-storm-split-selection-goldfish-mode-test
  (testing "Builder with no opponent puts only self in valid-targets"
    (let [db (th/create-test-db)
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (storm/build-storm-split-selection db-with-storm :player-1 storm-si)]
      (is (some? selection) "Selection should not be nil")
      (is (= 1 (count (:selection/valid-targets selection)))
          "Should have only 1 valid target (self)")
      (is (= [:player-1] (:selection/valid-targets selection)))
      (is (= 3 (get (:selection/allocation selection) :player-1))
          "All copies default to self"))))


;; ============================================================
;; 3. Stepper event tests
;; ============================================================

(deftest adjust-storm-split-increment-test
  (testing "Incrementing a target allocation when total < copy-count"
    (let [app-db {:game/db {}
                  :game/pending-selection
                  {:selection/type :storm-split
                   :selection/copy-count 3
                   :selection/valid-targets [:player-2 :player-1]
                   :selection/allocation {:player-2 2 :player-1 0}
                   :selection/auto-confirm? false}}
          result (storm/adjust-storm-split-impl app-db :player-1 1)]
      (is (= 1 (get-in result [:game/pending-selection :selection/allocation :player-1]))
          "Self allocation should increment to 1"))))


(deftest adjust-storm-split-decrement-test
  (testing "Decrementing a target allocation"
    (let [app-db {:game/db {}
                  :game/pending-selection
                  {:selection/type :storm-split
                   :selection/copy-count 3
                   :selection/valid-targets [:player-2 :player-1]
                   :selection/allocation {:player-2 3 :player-1 0}
                   :selection/auto-confirm? false}}
          result (storm/adjust-storm-split-impl app-db :player-2 -1)]
      (is (= 2 (get-in result [:game/pending-selection :selection/allocation :player-2]))
          "Opponent allocation should decrement to 2"))))


(deftest adjust-storm-split-rejects-negative-test
  (testing "Cannot decrement below 0"
    (let [app-db {:game/db {}
                  :game/pending-selection
                  {:selection/type :storm-split
                   :selection/copy-count 3
                   :selection/valid-targets [:player-2 :player-1]
                   :selection/allocation {:player-2 3 :player-1 0}
                   :selection/auto-confirm? false}}
          result (storm/adjust-storm-split-impl app-db :player-1 -1)]
      (is (= 0 (get-in result [:game/pending-selection :selection/allocation :player-1]))
          "Self allocation should remain 0"))))


(deftest adjust-storm-split-rejects-over-allocation-test
  (testing "Cannot exceed copy count total"
    (let [app-db {:game/db {}
                  :game/pending-selection
                  {:selection/type :storm-split
                   :selection/copy-count 3
                   :selection/valid-targets [:player-2 :player-1]
                   :selection/allocation {:player-2 3 :player-1 0}
                   :selection/auto-confirm? false}}
          result (storm/adjust-storm-split-impl app-db :player-2 1)]
      (is (= 3 (get-in result [:game/pending-selection :selection/allocation :player-2]))
          "Opponent allocation should remain 3 (total would exceed copy-count)"))))


;; ============================================================
;; 4. Confirm handler tests
;; ============================================================

(deftest confirm-creates-correct-copy-count-test
  (testing "Confirm creates correct number of copies"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          ;; Put Brain Freeze on stack directly (no casting, avoids double storm SI)
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (-> (storm/build-storm-split-selection db-with-storm :player-1 storm-si)
                        (assoc :selection/allocation {:player-2 2 :player-1 1}))
          result (core/execute-confirmed-selection db-with-storm selection)
          db-after (:db result)]
      (is (some? db-after))
      (let [stack-objects (q/get-objects-in-zone db-after :player-1 :stack)
            copies (filter :object/is-copy stack-objects)]
        (is (= 3 (count copies))
            "Should have 3 storm copies on stack")))))


(deftest confirm-assigns-targets-per-copy-test
  (testing "Each copy gets its individually assigned target"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          selection (-> (storm/build-storm-split-selection db-with-storm :player-1 storm-si)
                        (assoc :selection/allocation {:player-2 2 :player-1 1}))
          result (core/execute-confirmed-selection db-with-storm selection)
          db-after (:db result)
          all-items (q/get-all-stack-items db-after)
          copy-items (filter #(= :storm-copy (:stack-item/type %)) all-items)
          opp-targeted (filter #(= {:player :player-2} (:stack-item/targets %)) copy-items)
          self-targeted (filter #(= {:player :player-1} (:stack-item/targets %)) copy-items)]
      (is (= 3 (count copy-items))
          "Should have 3 copy stack-items")
      (is (= 2 (count opp-targeted))
          "2 copies should target opponent")
      (is (= 1 (count self-targeted))
          "1 copy should target self"))))


(deftest confirm-removes-storm-stack-item-test
  (testing "Storm stack-item is removed after confirm"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          ;; Builder default puts all 3 copies on :player-2 (opponent first), matching test scenario
          selection (storm/build-storm-split-selection db-with-storm :player-1 storm-si)
          result (core/execute-confirmed-selection db-with-storm selection)
          db-after (:db result)
          storm-items (filter #(= :storm (:stack-item/type %))
                              (q/get-all-stack-items db-after))]
      (is (empty? storm-items)
          "Storm stack-item should be removed after confirm"))))


(deftest confirm-rejects-partial-allocation-test
  (testing "Confirm rejects allocation where total != copy-count"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          db-with-storm (stack/create-stack-item db'
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 3}]})
          storm-si (first (filter #(= :storm (:stack-item/type %))
                                  (q/get-all-stack-items db-with-storm)))
          ;; Force a partial allocation (total=1 != copy-count=3) to test rejection
          selection (-> (storm/build-storm-split-selection db-with-storm :player-1 storm-si)
                        (assoc :selection/allocation {:player-2 1 :player-1 0}))
          result (core/execute-confirmed-selection db-with-storm selection)
          db-after (:db result)
          stack-objects (q/get-objects-in-zone db-after :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 0 (count copies))
          "No copies should be created with partial allocation"))))


;; ============================================================
;; 5. Routing tests (resolve-one-item for targeted storm)
;; ============================================================

(deftest resolve-one-item-targeted-storm-returns-selection-test
  (testing "resolve-one-item returns storm-split selection for targeted storm"
    (let [db (-> (th/create-test-db)
                 (add-opponent))
          ;; Cast Dark Ritual first to build storm count
          [db1 dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db1m (mana/add-mana db1 :player-1 {:black 1})
          db1c (rules/cast-spell db1m :player-1 dr-id)
          db1r (rules/resolve-spell db1c :player-1 dr-id)
          ;; Cast Brain Freeze as 2nd spell (storm count=2, copies=1)
          [db2 bf-id] (th/add-card-to-zone db1r :brain-freeze :hand :player-1)
          db2m (mana/add-mana db2 :player-1 {:blue 1 :colorless 1})
          db2c (cast-brain-freeze-with-target db2m :player-1 bf-id :player-2)
          ;; Storm SI is on top, source spell has :card/targeting
          result (resolution/resolve-one-item db2c)]
      (is (some? (:pending-selection result))
          "Should return pending storm-split selection")
      (when-let [sel (:pending-selection result)]
        (is (= :storm-split (:selection/domain sel))
            "Selection type should be :storm-split")
        (is (= 1 (:selection/copy-count sel))
            "Should have 1 copy (storm count was 2, so copies = 1)")))))


(deftest resolve-one-item-non-targeted-storm-no-selection-test
  (testing "resolve-one-item does NOT return selection for non-targeted storm"
    (let [db (th/create-test-db)
          [db' source-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m (mana/add-mana db' :player-1 {:black 1})
          db-cast (rules/cast-spell db-m :player-1 source-id)
          db-res (rules/resolve-spell db-cast :player-1 source-id)
          ;; Create storm SI for Dark Ritual (non-targeted spell)
          db-with-storm (stack/create-stack-item db-res
                                                 {:stack-item/type :storm
                                                  :stack-item/controller :player-1
                                                  :stack-item/source source-id
                                                  :stack-item/effects [{:effect/type :storm-copies
                                                                        :effect/count 2}]})
          result (resolution/resolve-one-item db-with-storm)]
      (is (nil? (:pending-selection result))
          "Should NOT return selection for non-targeted storm")
      (let [stack-objects (q/get-objects-in-zone (:db result) :player-1 :stack)
            copies (filter :object/is-copy stack-objects)]
        (is (= 2 (count copies))
            "Should have 2 copies created directly (non-targeted path)")))))
