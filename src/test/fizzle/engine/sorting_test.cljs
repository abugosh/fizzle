(ns fizzle.engine.sorting-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.sorting :as sorting]
    [fizzle.events.game :as game]))


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


(deftest test-lands-sort-before-zero-cmc-spells
  (testing "lands sort before 0-cost spells within CMC 0 group"
    (let [swamp (make-obj "Swamp" 0 #{:land})
          led (make-obj "Lion's Eye Diamond" 0)
          lotus (make-obj "Lotus Petal" 0)
          result (sorting/sort-cards [swamp led lotus])]
      (is (= ["Swamp" "Lion's Eye Diamond" "Lotus Petal"]
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
      ;; Verify ordering: lands first within CMC group, then name
      (is (= ["Swamp" "Lion's Eye Diamond" "Lotus Petal"
              "Dark Ritual"
              "Brain Freeze" "Cabal Ritual"
              "Ill-Gotten Gains"]
             (mapv #(get-in % [:object/card :card/name]) result))))))


(deftest test-sort-lands-before-zero-cmc-datascript
  (testing "lands sort before 0-cost spells with actual Datascript entities"
    (let [game-db (:game/db (game/init-game-state
                              {:main-deck (:deck/main cards/iggy-pop-decklist)}))
          ;; Get hand from real Datascript db
          hand (q/get-hand game-db (q/get-human-player-id game-db))
          ;; Also get library objects to have a bigger sample
          lib (q/get-objects-in-zone game-db :player-1 :library)
          all-objs (concat hand lib)
          ;; Filter to CMC 0 objects
          cmc-zero (filter #(= 0 (get-in % [:object/card :card/cmc] 0)) all-objs)
          sorted (sorting/sort-cards cmc-zero)
          names (mapv #(get-in % [:object/card :card/name]) sorted)
          ;; Find the index ranges: all lands should come before all non-lands
          land-names #{"Cephalid Coliseum" "City of Brass" "Gemstone Mine"
                       "Island" "Polluted Delta" "Swamp" "Underground River"}
          land-indices (keep-indexed (fn [i obj]
                                       (when (land-names (get-in obj [:object/card :card/name]))
                                         i))
                                     sorted)
          non-land-indices (keep-indexed (fn [i obj]
                                           (when (not (land-names (get-in obj [:object/card :card/name])))
                                             i))
                                         sorted)]
      ;; All land indices should be less than all non-land indices
      (when (and (seq land-indices) (seq non-land-indices))
        (is (< (apply max land-indices) (apply min non-land-indices))
            (str "All lands should sort before non-lands. Got: " names))))))


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


;; === Three-tier grouping tests ===

(deftest test-group-by-type-mixed-permanents
  (testing "mixed battlefield separates creatures, other, and lands"
    (let [swamp (make-obj "Swamp" 0 #{:land})
          island (make-obj "Island" 0 #{:land})
          elf (make-obj "Llanowar Elves" 1 #{:creature})
          mox (make-obj "Chrome Mox" 0 #{:artifact})
          enchant (make-obj "Necromancy" 3 #{:enchantment})
          result (sorting/group-by-type [swamp island elf mox enchant])]
      (is (= 1 (count (:creatures result))))
      (is (= 2 (count (:other result))))
      (is (= 2 (count (:lands result))))
      (is (= #{"Llanowar Elves"}
             (set (map #(get-in % [:object/card :card/name]) (:creatures result)))))
      (is (= #{"Chrome Mox" "Necromancy"}
             (set (map #(get-in % [:object/card :card/name]) (:other result)))))
      (is (= #{"Swamp" "Island"}
             (set (map #(get-in % [:object/card :card/name]) (:lands result))))))))


(deftest test-group-by-type-empty-returns-empty-groups
  (testing "empty input returns empty creatures, other, and lands"
    (let [result (sorting/group-by-type [])]
      (is (= [] (:creatures result)))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-group-by-type-all-lands
  (testing "all lands go into :lands, other groups empty"
    (let [swamp (make-obj "Swamp" 0 #{:land})
          island (make-obj "Island" 0 #{:land})
          result (sorting/group-by-type [swamp island])]
      (is (= [] (:creatures result)))
      (is (= [] (:other result)))
      (is (= 2 (count (:lands result)))))))


(deftest test-group-by-type-all-creatures
  (testing "all creatures go into :creatures, other groups empty"
    (let [elf (make-obj "Llanowar Elves" 1 #{:creature})
          goblin (make-obj "Goblin Guide" 1 #{:creature})
          result (sorting/group-by-type [elf goblin])]
      (is (= 2 (count (:creatures result))))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-group-by-type-all-artifacts
  (testing "all artifacts go into :other, other groups empty"
    (let [mox (make-obj "Chrome Mox" 0 #{:artifact})
          led (make-obj "Lion's Eye Diamond" 0 #{:artifact})
          result (sorting/group-by-type [mox led])]
      (is (= [] (:creatures result)))
      (is (= 2 (count (:other result))))
      (is (= [] (:lands result))))))


(deftest test-group-by-type-artifact-creature
  (testing "artifact-creature goes to :creatures (creature priority)"
    (let [golem (make-obj "Steel Golem" 3 #{:artifact :creature})
          result (sorting/group-by-type [golem])]
      (is (= 1 (count (:creatures result))))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-group-by-type-artifact-land
  (testing "artifact-land goes to :lands (land priority over artifact)"
    (let [seat (make-obj "Seat of the Synod" 0 #{:artifact :land})
          result (sorting/group-by-type [seat])]
      (is (= [] (:creatures result)))
      (is (= [] (:other result)))
      (is (= 1 (count (:lands result)))))))


(deftest test-group-by-type-land-creature
  (testing "land-creature goes to :creatures (creature priority over land)"
    (let [dryad (make-obj "Dryad Arbor" 0 #{:land :creature})
          result (sorting/group-by-type [dryad])]
      (is (= 1 (count (:creatures result))))
      (is (= [] (:other result)))
      (is (= [] (:lands result))))))


(deftest test-group-by-type-nil-types
  (testing "objects without :card/types go to :other"
    (let [no-types (make-obj "Mystery Card" 1)
          result (sorting/group-by-type [no-types])]
      (is (= [] (:creatures result)))
      (is (= 1 (count (:other result))))
      (is (= [] (:lands result))))))
