(ns fizzle.events.scenario-library-page-test
  "Tests for scenario library page:
   - ::scenario-list subscription (sorted by title)
   - auto-summary pure function
   - ::delete handler removes scenario
   - ::edit-existing handler loads scenario into editing and shows builder"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.scenario :as scenario]
    [fizzle.subs.scenario :as scenario-subs]
    [fizzle.views.scenarios :as scenarios-views]))


;; === Fixtures ===

(def ^:private id-a #uuid "aaaaaaaa-0000-0000-0000-000000000001")
(def ^:private id-b #uuid "bbbbbbbb-0000-0000-0000-000000000002")
(def ^:private id-c #uuid "cccccccc-0000-0000-0000-000000000003")


(def ^:private scenario-alpha
  {:scenario/id    id-a
   :scenario/title "Alpha Scenario"
   :scenario/player {:deck [{:card/id :dark-ritual :count 4}
                            {:card/id :swamp :count 10}]
                     :zones {:hand [:dark-ritual :dark-ritual :dark-ritual]
                             :graveyard []
                             :battlefield [:swamp :swamp]}
                     :life 20
                     :mana-pool {}}
   :scenario/opponent {:archetype :goldfish
                       :deck [{:card/id :island :count 20}]
                       :zones {}
                       :life 20
                       :mana-pool {}}
   :scenario/phase :main1})


(def ^:private scenario-zeta
  {:scenario/id    id-b
   :scenario/title "Zeta Scenario"
   :scenario/player {:deck [{:card/id :lotus-petal :count 4}]
                     :zones {:hand []
                             :graveyard []
                             :battlefield []}
                     :life 18
                     :mana-pool {}}
   :scenario/opponent {:archetype :burn
                       :deck [{:card/id :mountain :count 20}]
                       :zones {}
                       :life 15
                       :mana-pool {}}
   :scenario/phase :main1})


(def ^:private scenario-middle
  {:scenario/id    id-c
   :scenario/title "Middle Scenario"
   :scenario/player {:deck [{:card/id :dark-ritual :count 4}]
                     :zones {:hand []
                             :graveyard []
                             :battlefield []}
                     :life 20
                     :mana-pool {}}
   :scenario/opponent {:archetype :goldfish
                       :deck []
                       :zones {}
                       :life 20
                       :mana-pool {}}
   :scenario/phase :main1})


(def ^:private db-with-library
  {:scenario/library {id-a scenario-alpha
                      id-b scenario-zeta
                      id-c scenario-middle}})


;; === ::scenario-list subscription ===

(deftest test-scenario-list-returns-sorted-by-title
  (testing "scenario-list returns scenarios sorted by title ascending"
    (let [result (scenario-subs/scenario-list-fn db-with-library)]
      (is (= ["Alpha Scenario" "Middle Scenario" "Zeta Scenario"]
             (mapv :scenario/title result))
          "scenarios should be sorted alphabetically by title"))))


(deftest test-scenario-list-empty-library
  (testing "scenario-list returns empty vector when library is empty"
    (let [result (scenario-subs/scenario-list-fn {:scenario/library {}})]
      (is (= [] result)
          "empty library should produce empty list"))))


(deftest test-scenario-list-nil-library
  (testing "scenario-list returns empty vector when library is nil"
    (let [result (scenario-subs/scenario-list-fn {})]
      (is (= [] result)
          "nil library should produce empty list"))))


(deftest test-scenario-list-single-item
  (testing "scenario-list works with single scenario"
    (let [result (scenario-subs/scenario-list-fn {:scenario/library {id-a scenario-alpha}})]
      (is (= [scenario-alpha] result)
          "single scenario returned as a one-element vector"))))


;; === auto-summary pure function ===

(deftest test-auto-summary-basic
  (testing "auto-summary includes deck size and hand count"
    (let [summary (scenarios-views/auto-summary scenario-alpha)]
      (is (string? summary) "summary should be a string")
      (is (not (empty? summary)) "summary should not be empty"))))


(deftest test-auto-summary-hand-count
  (testing "auto-summary counts hand cards correctly"
    (let [summary (scenarios-views/auto-summary scenario-alpha)]
      ;; alpha has 3 in hand
      (is (.includes summary "3 in hand")
          "summary should show 3 in hand"))))


(deftest test-auto-summary-lands-count
  (testing "auto-summary counts lands on battlefield correctly"
    (let [summary (scenarios-views/auto-summary scenario-alpha)]
      ;; alpha has 2 swamps on battlefield
      (is (.includes summary "2 lands")
          "summary should show 2 lands"))))


(deftest test-auto-summary-deck-size
  (testing "auto-summary counts total deck size"
    (let [summary (scenarios-views/auto-summary scenario-alpha)]
      ;; alpha has 14 cards (4 dark-ritual + 10 swamp)
      (is (.includes summary "14")
          "summary should include deck size"))))


(deftest test-auto-summary-opponent-archetype
  (testing "auto-summary includes opponent archetype"
    (let [summary (scenarios-views/auto-summary scenario-alpha)]
      (is (.includes summary "goldfish")
          "summary should include opponent archetype"))))


(deftest test-auto-summary-opponent-life
  (testing "auto-summary includes opponent life total"
    (let [summary (scenarios-views/auto-summary scenario-zeta)]
      ;; zeta opponent has 15 life
      (is (.includes summary "15")
          "summary should include opponent life total"))))


;; === ::delete handler ===

(deftest test-delete-removes-from-library
  (testing "delete-handler removes scenario from library by id"
    (let [result (scenario/delete-handler db-with-library [nil id-a])]
      (is (nil? (get-in result [:scenario/library id-a]))
          "deleted scenario should not be in library")
      (is (= 2 (count (:scenario/library result)))
          "library should have 2 remaining scenarios"))))


(deftest test-delete-last-scenario-empties-library
  (testing "delete-handler on last scenario results in empty library"
    (let [db {:scenario/library {id-a scenario-alpha}}
          result (scenario/delete-handler db [nil id-a])]
      (is (empty? (:scenario/library result))
          "library should be empty after deleting last scenario"))))


;; === ::edit-existing handler ===

(deftest test-edit-existing-sets-editing
  (testing "edit-existing-handler sets :scenario/editing to the given scenario"
    (let [result (scenario/edit-existing-handler db-with-library [nil scenario-alpha])]
      (is (= scenario-alpha (:scenario/editing result))
          "editing should be set to the provided scenario"))))


(deftest test-edit-existing-shows-builder
  (testing "edit-existing-handler sets active-view to :builder"
    (let [result (scenario/edit-existing-handler db-with-library [nil scenario-alpha])]
      (is (= :builder (:scenario/active-view result))
          "active-view should be :builder"))))


;; === ::quick-play handler ===

(deftest test-quick-play-sets-editing
  (testing "quick-play-handler sets :scenario/editing to the given scenario"
    (let [result (scenario/quick-play-handler db-with-library [nil scenario-zeta])]
      (is (= scenario-zeta (:scenario/editing result))
          "editing should be set to the provided scenario"))))
