(ns fizzle.events.selection.storm-objects-test
  "Tests for storm object-sequence selection infrastructure.
   Covers: builder nil cases, max-picks capping, selection shape,
   confirm handler copy creation with correct targets, resolution order,
   and storm stack-item removal."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.storm-objects :as storm-objects]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helpers
;; =====================================================

(defn- make-storm-stack-item
  "Create a storm stack-item for testing without casting the spell.
   source-id: UUID of the source object already on the stack.
   copy-count: number of copies to create."
  [db source-id copy-count]
  (let [db-with-storm (stack/create-stack-item db
                                               {:stack-item/type :storm
                                                :stack-item/controller :player-1
                                                :stack-item/source source-id
                                                :stack-item/description (str "Storm - create " copy-count " copies")
                                                :stack-item/effects [{:effect/type :storm-copies
                                                                      :effect/count copy-count}]})]
    [db-with-storm
     (first (filter #(= :storm (:stack-item/type %))
                    (q/get-all-stack-items db-with-storm)))]))


;; =====================================================
;; 1. Builder nil cases
;; =====================================================

(deftest builder-returns-nil-when-no-valid-targets-test
  (testing "Builder returns nil when graveyard is empty (no valid creature targets)"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          ;; No creatures in graveyard
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 2)
          result (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)]
      (is (nil? result) "Should return nil with empty graveyard"))))


(deftest builder-returns-nil-when-only-non-creatures-test
  (testing "Builder returns nil when only non-creature cards are in graveyard"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db _] (th/add-cards-to-graveyard db [:dark-ritual :cabal-ritual] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 2)
          result (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)]
      (is (nil? result) "Should return nil with only non-creature cards in graveyard"))))


(deftest builder-returns-nil-when-copy-count-is-zero-test
  (testing "Builder returns nil when copy count is 0"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [_creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 0)
          result (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)]
      (is (nil? result) "Should return nil when copy-count is 0"))))


;; =====================================================
;; 2. max-picks capping
;; =====================================================

(deftest builder-caps-max-picks-at-valid-target-count-test
  (testing "max-picks is capped at valid-target count when storm count > targets"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          ;; 3 creatures in graveyard but storm copies = 5
          [db creature-ids] (th/add-cards-to-graveyard
                              db [:cloud-of-faeries :cloud-of-faeries :cloud-of-faeries]
                              :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 5)
          result (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)]
      (is (some? result) "Selection should not be nil")
      (is (= 3 (:selection/max-picks result))
          "max-picks should be capped at 3 (number of valid targets)")
      (is (= 3 (count (:selection/valid-targets result)))
          "valid-targets should contain all 3 creatures")
      (is (= (set creature-ids) (set (:selection/valid-targets result)))
          "valid-targets should contain all 3 creature IDs"))))


(deftest builder-caps-max-picks-at-copy-count-test
  (testing "max-picks is capped at copy-count when targets > storm count"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          ;; 5 creatures in graveyard but storm copies = 2
          [db _creature-ids] (th/add-cards-to-graveyard
                               db [:cloud-of-faeries :cloud-of-faeries :cloud-of-faeries
                                   :cloud-of-faeries :cloud-of-faeries]
                               :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 2)
          result (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)]
      (is (some? result) "Selection should not be nil")
      (is (= 2 (:selection/max-picks result))
          "max-picks should be capped at 2 (copy-count)")
      (is (= 5 (count (:selection/valid-targets result)))
          "valid-targets should still contain all 5 creatures"))))


;; =====================================================
;; 3. Selection shape
;; =====================================================

(deftest builder-returns-correct-selection-shape-test
  (testing "Builder returns selection map with correct mechanism, domain, and fields"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 3)
          result (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)]
      (is (some? result) "Selection should not be nil")
      (is (= :sequence-pick (:selection/mechanism result))
          "mechanism should be :sequence-pick")
      (is (= :storm-object-sequence (:selection/domain result))
          "domain should be :storm-object-sequence")
      (is (= :finalized (:selection/lifecycle result))
          "lifecycle should be :finalized")
      (is (= [] (:selection/sequence result))
          "sequence should start empty")
      (is (= [creature-id] (:selection/valid-targets result))
          "valid-targets should contain the creature UUID")
      (is (= 1 (:selection/max-picks result))
          "max-picks should be min(3, 1) = 1")
      (is (= :graveyard-creature (:selection/target-ref-key result))
          "target-ref-key should be :graveyard-creature from card targeting")
      (is (= rtg-id (:selection/source-object-id result))
          "source-object-id should be rtg-id")
      (is (= "Reaping the Graves" (:selection/source-name result))
          "source-name should be the card name")
      (is (= :player-1 (:selection/player-id result))
          "player-id should be :player-1")
      (is (= (:db/id storm-si) (:selection/stack-item-eid result))
          "stack-item-eid should be the storm stack-item's EID")
      (is (= #{} (:selection/selected result))
          "selected should start empty")
      (is (= :always (:selection/validation result))
          "validation should be :always")
      (is (false? (:selection/auto-confirm? result))
          "auto-confirm? should be false"))))


;; =====================================================
;; 4. Confirm handler: copies with correct targets
;; =====================================================

(deftest confirm-creates-copies-with-correct-target-overrides-test
  (testing "Confirm creates copies with the specified target-ref-key → target-uuid mapping"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [c1-id c2-id]] (th/add-cards-to-graveyard
                               db [:cloud-of-faeries :cloud-of-faeries]
                               :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 2)
          selection (-> (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)
                        (assoc :selection/sequence [c1-id c2-id]))
          result (core/execute-confirmed-selection db selection)
          db-after (:db result)
          all-items (q/get-all-stack-items db-after)
          copy-items (filter #(= :storm-copy (:stack-item/type %)) all-items)]
      (is (= 2 (count copy-items))
          "Should have 2 storm copies on stack")
      (let [targets (set (map :stack-item/targets copy-items))]
        (is (contains? targets {:graveyard-creature c1-id})
            "One copy should target c1-id")
        (is (contains? targets {:graveyard-creature c2-id})
            "One copy should target c2-id")))))


;; =====================================================
;; 5. Confirm handler: resolution order
;; =====================================================

(deftest confirm-creates-copies-in-reverse-order-test
  (testing "First-in-sequence ends up on top of stack (resolves first)"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [c1-id c2-id c3-id]] (th/add-cards-to-graveyard
                                     db [:cloud-of-faeries :cloud-of-faeries :cloud-of-faeries]
                                     :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 3)
          ;; sequence order: c1-id picked first, c3-id picked last
          selection (-> (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)
                        (assoc :selection/sequence [c1-id c2-id c3-id]))
          result (core/execute-confirmed-selection db selection)
          db-after (:db result)
          all-items (q/get-all-stack-items db-after)
          copy-items (->> all-items
                          (filter #(= :storm-copy (:stack-item/type %)))
                          (sort-by :stack-item/position >))
          ;; Highest position = top of stack = resolves first
          top-copy (first copy-items)]
      (is (= 3 (count copy-items))
          "Should have 3 storm copies on stack")
      (is (= {:graveyard-creature c1-id} (:stack-item/targets top-copy))
          "The copy targeting c1-id (first-picked) should be on top of the stack (resolves first)"))))


;; =====================================================
;; 6. Confirm handler: storm stack-item removal
;; =====================================================

(deftest confirm-removes-storm-stack-item-test
  (testing "Storm stack-item is removed after confirm"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 1)
          selection (-> (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)
                        (assoc :selection/sequence [creature-id]))
          result (core/execute-confirmed-selection db selection)
          db-after (:db result)
          storm-items (filter #(= :storm (:stack-item/type %))
                              (q/get-all-stack-items db-after))]
      (is (empty? storm-items)
          "Storm stack-item should be removed after confirm"))))


;; =====================================================
;; 7. Empty sequence produces no copies (valid confirm with empty picks)
;; =====================================================

(deftest confirm-with-empty-sequence-creates-no-copies-test
  (testing "Confirming with empty sequence creates no copies but removes storm stack-item"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [_creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :stack :player-1)
          [db storm-si] (make-storm-stack-item db rtg-id 1)
          selection (storm-objects/build-storm-object-sequence-selection db :player-1 storm-si)
          ;; Confirm with empty sequence (player chose no targets)
          selection (assoc selection :selection/sequence [])
          result (core/execute-confirmed-selection db selection)
          db-after (:db result)
          all-items (q/get-all-stack-items db-after)
          copy-items (filter #(= :storm-copy (:stack-item/type %)) all-items)
          storm-items (filter #(= :storm (:stack-item/type %)) all-items)]
      (is (= 0 (count copy-items))
          "No copies should be created with empty sequence")
      (is (empty? storm-items)
          "Storm stack-item should still be removed"))))
