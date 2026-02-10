(ns fizzle.engine.sorting-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.engine.sorting :as sorting]))


(defn- make-obj
  "Create a minimal game object for sorting tests."
  ([name cmc]
   {:object/id (random-uuid)
    :object/card {:card/name name :card/cmc cmc}})
  ([name cmc types]
   {:object/id (random-uuid)
    :object/card {:card/name name :card/cmc cmc :card/types types}}))


(deftest test-sort-by-cmc-ascending
  (testing "cards sort by CMC ascending"
    (let [dr (make-obj "Dark Ritual" 1)
          cr (make-obj "Cabal Ritual" 2)
          led (make-obj "Lion's Eye Diamond" 0)
          result (sorting/sort-cards [dr cr led])]
      (is (= ["Lion's Eye Diamond" "Dark Ritual" "Cabal Ritual"]
             (mapv #(get-in % [:object/card :card/name]) result))))))


(deftest test-same-cmc-sorts-by-name
  (testing "cards with same CMC sort alphabetically by name"
    (let [cr (make-obj "Cabal Ritual" 2)
          bw (make-obj "Burning Wish" 2)
          bf (make-obj "Brain Freeze" 2)
          result (sorting/sort-cards [cr bw bf])]
      (is (= ["Brain Freeze" "Burning Wish" "Cabal Ritual"]
             (mapv #(get-in % [:object/card :card/name]) result))))))


(deftest test-lands-sort-with-zero-cmc
  (testing "lands (CMC 0) sort among other zero-cost cards by name"
    (let [swamp (make-obj "Swamp" 0 #{:land})
          led (make-obj "Lion's Eye Diamond" 0)
          lotus (make-obj "Lotus Petal" 0)
          result (sorting/sort-cards [swamp led lotus])]
      (is (= ["Lion's Eye Diamond" "Lotus Petal" "Swamp"]
             (mapv #(get-in % [:object/card :card/name]) result))))))


(deftest test-empty-input-returns-empty
  (testing "empty input returns empty vector"
    (is (= [] (sorting/sort-cards [])))))


(deftest test-nil-cmc-defaults-to-zero
  (testing "card without :card/cmc sorts as CMC 0"
    (let [no-cmc {:object/id (random-uuid)
                  :object/card {:card/name "Mystery Card"}}
          dr (make-obj "Dark Ritual" 1)
          result (sorting/sort-cards [dr no-cmc])]
      (is (= ["Mystery Card" "Dark Ritual"]
             (mapv #(get-in % [:object/card :card/name]) result))))))


(deftest test-sort-stability-duplicates
  (testing "sort preserves all duplicates (no cards lost)"
    (let [dr1 (make-obj "Dark Ritual" 1)
          dr2 (make-obj "Dark Ritual" 1)
          dr3 (make-obj "Dark Ritual" 1)
          result (sorting/sort-cards [dr1 dr2 dr3])]
      (is (= 3 (count result)))
      (is (= #{"Dark Ritual"} (set (map #(get-in % [:object/card :card/name]) result))))
      (is (= #{(:object/id dr1) (:object/id dr2) (:object/id dr3)}
             (set (map :object/id result)))))))


(deftest test-mixed-deck-realistic
  (testing "realistic Iggy Pop hand sorts correctly by CMC then name"
    (let [igg (make-obj "Ill-Gotten Gains" 4)
          cr (make-obj "Cabal Ritual" 2)
          dr (make-obj "Dark Ritual" 1)
          led (make-obj "Lion's Eye Diamond" 0)
          lp (make-obj "Lotus Petal" 0)
          swamp (make-obj "Swamp" 0 #{:land})
          bf (make-obj "Brain Freeze" 2)
          result (sorting/sort-cards [igg cr dr led lp swamp bf])]
      ;; Verify CMC ordering
      (is (= [0 0 0 1 2 2 4]
             (mapv #(get-in % [:object/card :card/cmc] 0) result)))
      ;; Verify name ordering within CMC groups
      (is (= ["Lion's Eye Diamond" "Lotus Petal" "Swamp"
              "Dark Ritual"
              "Brain Freeze" "Cabal Ritual"
              "Ill-Gotten Gains"]
             (mapv #(get-in % [:object/card :card/name]) result))))))


;; === Battlefield grouping tests ===

(deftest test-group-by-land-separates-lands-and-non-lands
  (testing "mixed battlefield separates lands from non-lands"
    (let [swamp (make-obj "Swamp" 0 #{:land})
          island (make-obj "Island" 0 #{:land})
          led (make-obj "Lion's Eye Diamond" 0 #{:artifact})
          cr (make-obj "Chrome Mox" 1 #{:artifact})
          result (sorting/group-by-land [swamp island led cr])]
      (is (= 2 (count (:lands result))))
      (is (= 2 (count (:non-lands result))))
      (is (= #{"Swamp" "Island"}
             (set (map #(get-in % [:object/card :card/name]) (:lands result)))))
      (is (= #{"Lion's Eye Diamond" "Chrome Mox"}
             (set (map #(get-in % [:object/card :card/name]) (:non-lands result))))))))


(deftest test-group-by-land-empty-returns-empty-groups
  (testing "empty input returns empty lands and non-lands"
    (let [result (sorting/group-by-land [])]
      (is (= [] (:lands result)))
      (is (= [] (:non-lands result))))))


(deftest test-group-by-land-all-lands
  (testing "all lands go into :lands, :non-lands is empty"
    (let [swamp (make-obj "Swamp" 0 #{:land})
          island (make-obj "Island" 0 #{:land})
          result (sorting/group-by-land [swamp island])]
      (is (= 2 (count (:lands result))))
      (is (= [] (:non-lands result))))))


(deftest test-group-by-land-all-non-lands
  (testing "all non-lands go into :non-lands, :lands is empty"
    (let [led (make-obj "Lion's Eye Diamond" 0 #{:artifact})
          lp (make-obj "Lotus Petal" 0 #{:artifact})
          result (sorting/group-by-land [led lp])]
      (is (= [] (:lands result)))
      (is (= 2 (count (:non-lands result)))))))


(deftest test-group-by-land-nil-types-are-non-lands
  (testing "objects without :card/types are treated as non-lands"
    (let [no-types (make-obj "Mystery Card" 1)
          swamp (make-obj "Swamp" 0 #{:land})
          result (sorting/group-by-land [no-types swamp])]
      (is (= 1 (count (:lands result))))
      (is (= 1 (count (:non-lands result)))))))
