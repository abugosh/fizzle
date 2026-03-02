(ns fizzle.events.tutor-source-zone-test
  "Tests for tutor effect with :effect/source-zone parameter.
   Verifies that tutor searches the specified source zone instead of
   defaulting to library."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.events.selection.library :as library]
    [fizzle.test-helpers :as th]))


;; === Source Zone Parameter ===

(deftest test-tutor-defaults-to-library
  (testing "tutor with no :effect/source-zone searches library (backwards compat)"
    (let [db (th/create-test-db {:mana {:blue 2}})
          [db _] (th/add-cards-to-library db [:brain-freeze :dark-ritual] :player-1)
          [db hand-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          tutor-effect {:effect/type :tutor
                        :effect/criteria {:match/types #{:instant}
                                          :match/colors #{:blue}}
                        :effect/target-zone :hand}
          selection (library/build-tutor-selection db :player-1 hand-id tutor-effect [])]
      (is (= :library (:selection/zone selection))
          "Zone should default to :library")
      (is (= :library (:selection/card-source selection))
          "Card source should default to :library")
      (is (= 1 (count (:selection/candidates selection)))
          "Should find 1 blue instant in library"))))


(deftest test-tutor-from-sideboard
  (testing "tutor with :effect/source-zone :sideboard searches sideboard"
    (let [db (th/create-test-db {:mana {:blue 2}})
          ;; Put cards in sideboard, not library
          [db sb-id-1] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :sideboard :player-1)
          [db hand-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          tutor-effect {:effect/type :tutor
                        :effect/criteria {:match/types #{:instant}
                                          :match/colors #{:blue}}
                        :effect/target-zone :hand
                        :effect/source-zone :sideboard
                        :effect/shuffle? false}
          selection (library/build-tutor-selection db :player-1 hand-id tutor-effect [])]
      (is (= :sideboard (:selection/zone selection))
          "Zone should be :sideboard")
      (is (= :sideboard (:selection/card-source selection))
          "Card source should be :sideboard")
      (is (= 1 (count (:selection/candidates selection)))
          "Should find 1 blue instant in sideboard (Brain Freeze, not Dark Ritual)")
      (is (contains? (:selection/candidates selection) sb-id-1)
          "Candidate should be the Brain Freeze in sideboard"))))


(deftest test-tutor-from-sideboard-empty
  (testing "tutor from sideboard with no matching cards has empty candidates"
    (let [db (th/create-test-db {:mana {:blue 2}})
          ;; Sideboard with no blue instants
          [db _] (th/add-card-to-zone db :dark-ritual :sideboard :player-1)
          [db hand-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          tutor-effect {:effect/type :tutor
                        :effect/criteria {:match/types #{:instant}
                                          :match/colors #{:blue}}
                        :effect/target-zone :hand
                        :effect/source-zone :sideboard
                        :effect/shuffle? false}
          selection (library/build-tutor-selection db :player-1 hand-id tutor-effect [])]
      (is (empty? (:selection/candidates selection))
          "Should have no candidates when sideboard has no matching cards")
      (is (= 0 (:selection/select-count selection))
          "Select count should be 0 when no candidates"))))


(deftest test-tutor-from-sideboard-no-shuffle
  (testing "wish-style tutor with :shuffle? false skips library shuffle"
    (let [db (th/create-test-db {:mana {:blue 2}})
          [db _] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db hand-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          tutor-effect {:effect/type :tutor
                        :effect/criteria {:match/types #{:instant}
                                          :match/colors #{:blue}}
                        :effect/target-zone :hand
                        :effect/source-zone :sideboard
                        :effect/shuffle? false}
          selection (library/build-tutor-selection db :player-1 hand-id tutor-effect [])]
      (is (false? (:selection/shuffle? selection))
          "Selection should have :shuffle? false"))))


(deftest test-tutor-from-sideboard-execute-moves-to-hand
  (testing "executing a sideboard tutor selection moves card to hand"
    (let [db (th/create-test-db {:mana {:blue 2}})
          [db sb-id] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db _] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          selection {:selection/type :tutor
                     :selection/selected #{sb-id}
                     :selection/target-zone :hand
                     :selection/player-id :player-1
                     :selection/shuffle? false}
          db-after (library/execute-tutor-selection db selection)
          hand (q/get-objects-in-zone db-after :player-1 :hand)
          sb (q/get-objects-in-zone db-after :player-1 :sideboard)
          hand-card-ids (set (map #(get-in % [:object/card :card/id]) hand))]
      (is (contains? hand-card-ids :brain-freeze)
          "Brain Freeze should be in hand after tutor from sideboard")
      (is (= 0 (count sb))
          "Sideboard should be empty after moving the card"))))


(deftest test-tutor-from-sideboard-fail-to-find
  (testing "fail-to-find from sideboard tutor does not shuffle library"
    (let [db (th/create-test-db {:mana {:blue 2}})
          ;; Add library cards with known positions
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db _] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          ;; Record library positions before
          lib-before (q/get-objects-in-zone db :player-1 :library)
          pos-before (:object/position (first lib-before))
          ;; Execute with empty selection (fail-to-find) and shuffle? false
          selection {:selection/type :tutor
                     :selection/selected #{}
                     :selection/target-zone :hand
                     :selection/player-id :player-1
                     :selection/shuffle? false}
          db-after (library/execute-tutor-selection db selection)
          lib-after (q/get-objects-in-zone db-after :player-1 :library)
          pos-after (:object/position (first lib-after))]
      (is (= pos-before pos-after)
          "Library positions should be unchanged when shuffle? is false"))))


(deftest test-tutor-source-zone-does-not-affect-library
  (testing "sideboard tutor does not interact with library zone"
    (let [db (th/create-test-db {:mana {:blue 2}})
          ;; Library has a blue instant
          [db _] (th/add-cards-to-library db [:brain-freeze :opt] :player-1)
          ;; Sideboard has a different blue instant
          [db sb-id] (th/add-card-to-zone db :mental-note :sideboard :player-1)
          [db hand-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          tutor-effect {:effect/type :tutor
                        :effect/criteria {:match/types #{:instant}
                                          :match/colors #{:blue}}
                        :effect/target-zone :hand
                        :effect/source-zone :sideboard
                        :effect/shuffle? false}
          selection (library/build-tutor-selection db :player-1 hand-id tutor-effect [])]
      (is (= #{sb-id} (:selection/candidates selection))
          "Should only find Mental Note from sideboard, not library cards")
      (is (= 1 (count (:selection/candidates selection)))
          "Should have exactly 1 candidate from sideboard"))))
