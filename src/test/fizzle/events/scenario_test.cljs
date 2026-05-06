(ns fizzle.events.scenario-test
  "Tests for scenario CRUD events.
   Tests operate directly on handler functions, not via re-frame dispatch,
   so they are pure unit tests with no side effects."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.storage :as storage]
    [fizzle.events.scenario :as scenario]))


;; === Mock localStorage for persistence tests ===

(defn- create-mock-storage
  []
  (let [store (atom {})]
    #js {:getItem (fn [key] (get @store key nil))
         :setItem (fn [key value] (swap! store assoc key value) nil)
         :removeItem (fn [key] (swap! store dissoc key) nil)}))


(set! js/localStorage (create-mock-storage))


;; === Fixtures ===

(def ^:private sample-id #uuid "00000000-0000-0000-0000-000000000001")

(def ^:private sample-scenario
  {:scenario/id     sample-id
   :scenario/title  "Tendrils Turn with LED in hand"
   :scenario/player {:deck      [{:card/id :dark-ritual :count 4}]
                     :zones     {:hand         [:dark-ritual :lions-eye-diamond]
                                 :graveyard    [:cabal-ritual]
                                 :battlefield  [:swamp :swamp]}
                     :library-top [:tendrils-of-agony]
                     :mana-pool {}
                     :life      18}
   :scenario/opponent {:archetype :burn
                       :deck      [{:card/id :mountain :count 20}]
                       :zones     {:hand         [:lightning-bolt]
                                   :battlefield  [:mountain :mountain]}
                       :library-top []
                       :mana-pool {}
                       :life      20}
   :scenario/phase  :main1})

(def ^:private empty-db {})


;; === ::save ===

(deftest test-save-adds-scenario-to-library
  (testing "save upserts scenario into :scenario/library under its id"
    (let [result (scenario/save-handler empty-db [nil sample-scenario])]
      (is (= sample-scenario (get-in result [:scenario/library sample-id]))
          "scenario should be stored under its id"))))


(deftest test-save-overwrites-existing-scenario
  (testing "save replaces an existing scenario with the same id"
    (let [db (scenario/save-handler empty-db [nil sample-scenario])
          updated (assoc sample-scenario :scenario/title "Updated Title")
          result (scenario/save-handler db [nil updated])]
      (is (= "Updated Title" (get-in result [:scenario/library sample-id :scenario/title]))
          "title should be updated to new value"))))


(deftest test-save-multiple-scenarios
  (testing "save can store multiple distinct scenarios"
    (let [id2 #uuid "00000000-0000-0000-0000-000000000002"
          scenario2 (assoc sample-scenario :scenario/id id2 :scenario/title "Scenario 2")
          db (-> empty-db
                 (scenario/save-handler [nil sample-scenario])
                 (scenario/save-handler [nil scenario2]))]
      (is (= 2 (count (:scenario/library db)))
          "library should contain two scenarios"))))


;; === ::delete ===

(deftest test-delete-removes-scenario-by-id
  (testing "delete removes scenario with matching id"
    (let [db (scenario/save-handler empty-db [nil sample-scenario])
          result (scenario/delete-handler db [nil sample-id])]
      (is (nil? (get-in result [:scenario/library sample-id]))
          "scenario should no longer be present after delete"))))


(deftest test-delete-unknown-id-is-noop
  (testing "delete with unknown id leaves library unchanged"
    (let [db (scenario/save-handler empty-db [nil sample-scenario])
          result (scenario/delete-handler db [nil #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"])]
      (is (= 1 (count (:scenario/library result)))
          "library count should be unchanged for unknown id"))))


;; === ::update-field ===

(deftest test-update-field-changes-nested-value
  (testing "update-field sets value at nested path within :scenario/editing"
    (let [db (scenario/set-editing-handler empty-db [nil sample-scenario])
          result (scenario/update-field-handler db [nil [:scenario/title] "New Title"])]
      (is (= "New Title" (get-in result [:scenario/editing :scenario/title]))
          "nested title should be updated"))))


(deftest test-update-field-deep-nesting
  (testing "update-field works for deeper nested paths"
    (let [db (scenario/set-editing-handler empty-db [nil sample-scenario])
          result (scenario/update-field-handler db [nil [:scenario/player :life] 20])]
      (is (= 20 (get-in result [:scenario/editing :scenario/player :life]))
          "player life should be updated to 20"))))


;; === ::set-editing ===

(deftest test-set-editing-sets-scenario
  (testing "set-editing places scenario at :scenario/editing"
    (let [result (scenario/set-editing-handler empty-db [nil sample-scenario])]
      (is (= sample-scenario (:scenario/editing result))
          "editing should equal the provided scenario"))))


(deftest test-set-editing-nil-clears-editing
  (testing "set-editing with nil clears :scenario/editing"
    (let [db (scenario/set-editing-handler empty-db [nil sample-scenario])
          result (scenario/set-editing-handler db [nil nil])]
      (is (nil? (:scenario/editing result))
          ":scenario/editing should be nil after set-editing with nil"))))


;; === ::load-all ===

(deftest test-load-all-populates-library-from-storage
  (testing "load-all reads scenarios from localStorage into :scenario/library"
    (let [scenarios {sample-id sample-scenario}]
      ;; Prime localStorage
      (storage/save-scenarios! scenarios)
      (let [result (scenario/load-all-handler empty-db nil)]
        (is (= scenarios (:scenario/library result))
            "library should match what was stored in localStorage")))))


(deftest test-load-all-returns-empty-map-when-storage-empty
  (testing "load-all returns empty library when nothing is stored"
    ;; Clear localStorage by re-creating the mock
    (set! js/localStorage (create-mock-storage))
    (let [result (scenario/load-all-handler empty-db nil)]
      (is (= {} (:scenario/library result))
          "library should be empty when localStorage is empty"))))


;; === Storage round-trip ===

(deftest test-storage-round-trip
  (testing "save then load returns identical scenario"
    (set! js/localStorage (create-mock-storage))
    (let [scenarios {sample-id sample-scenario}]
      (storage/save-scenarios! scenarios)
      (is (= scenarios (storage/load-scenarios))
          "loaded scenarios should equal saved scenarios"))))


(deftest test-corrupted-storage-returns-empty-map
  (testing "load-scenarios returns {} when localStorage contains corrupted data"
    (set! js/localStorage (create-mock-storage))
    ;; Write unmatched brace — this is invalid EDN and will throw at read-string
    (.setItem js/localStorage "fizzle-scenarios" "{{{{{{{{{")
    (is (= {} (storage/load-scenarios))
        "corrupted data should return empty map, not crash")))
