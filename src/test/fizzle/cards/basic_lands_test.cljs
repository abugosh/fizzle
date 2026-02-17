(ns fizzle.cards.basic-lands-test
  "Tests for basic land cards (Plains, Island, Swamp, Mountain, Forest)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.basic-lands :as basic-lands]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === File-specific helpers ===

(defn get-object-tapped
  "Get the tapped state of an object by its ID."
  [db object-id]
  (:object/tapped (q/get-object db object-id)))


(defn get-land-plays
  "Get the land plays remaining for a player."
  [db player-id]
  (d/q '[:find ?plays .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/land-plays-left ?plays]]
       db player-id))


;; === Card definition tests ===

(deftest test-plains-card-definition
  (testing "Plains has correct card fields"
    (let [card basic-lands/plains]
      (is (= :plains (:card/id card)))
      (is (= "Plains" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (= #{:plains} (:card/subtypes card)))
      (is (= #{:basic} (:card/supertypes card)))
      (is (= "{T}: Add {W}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= {:white 1} (:ability/produces (first (:card/abilities card))))))))


(deftest test-island-card-definition
  (testing "Island has correct card fields"
    (let [card cards/island]
      (is (= :island (:card/id card)))
      (is (= "Island" (:card/name card)))
      (is (= #{:land} (:card/types card)))
      (is (= #{:island} (:card/subtypes card)))
      (is (= #{:basic} (:card/supertypes card)))
      (is (= {:blue 1} (:ability/produces (first (:card/abilities card))))))))


(deftest test-swamp-card-definition
  (testing "Swamp has correct card fields"
    (let [card cards/swamp]
      (is (= :swamp (:card/id card)))
      (is (= "Swamp" (:card/name card)))
      (is (= #{:land} (:card/types card)))
      (is (= #{:swamp} (:card/subtypes card)))
      (is (= #{:basic} (:card/supertypes card)))
      (is (= {:black 1} (:ability/produces (first (:card/abilities card))))))))


(deftest test-mountain-card-definition
  (testing "Mountain has correct card fields"
    (let [card basic-lands/mountain]
      (is (= :mountain (:card/id card)))
      (is (= "Mountain" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (= #{:mountain} (:card/subtypes card)))
      (is (= #{:basic} (:card/supertypes card)))
      (is (= "{T}: Add {R}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= {:red 1} (:ability/produces (first (:card/abilities card))))))))


(deftest test-forest-card-definition
  (testing "Forest has correct card fields"
    (let [card basic-lands/forest]
      (is (= :forest (:card/id card)))
      (is (= "Forest" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (= #{:forest} (:card/subtypes card)))
      (is (= #{:basic} (:card/supertypes card)))
      (is (= "{T}: Add {G}." (:card/text card)))
      (is (= 1 (count (:card/abilities card))))
      (is (= {:green 1} (:ability/produces (first (:card/abilities card))))))))


;; === Island tests ===

(deftest test-island-taps-for-blue-mana
  (testing "Island taps for blue mana when activated"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :island :battlefield :player-1)
          _ (is (false? (get-object-tapped db' obj-id))
                "Precondition: Island starts untapped")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (true? (get-object-tapped db'' obj-id))
          "Island should be tapped after activating mana ability")
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added to pool"))))


;; === Swamp tests ===

(deftest test-swamp-taps-for-black-mana
  (testing "Swamp taps for black mana when activated"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          _ (is (false? (get-object-tapped db' obj-id))
                "Precondition: Swamp starts untapped")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      (is (true? (get-object-tapped db'' obj-id))
          "Swamp should be tapped after activating mana ability")
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Black mana should be added to pool"))))


;; === Plains tests ===

(deftest test-plains-taps-for-white-mana
  (testing "Plains taps for white mana when activated"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :plains :battlefield :player-1)
          _ (is (= 0 (:white (q/get-mana-pool db' :player-1))) "Precondition: white mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :white)]
      (is (true? (get-object-tapped db'' obj-id))
          "Plains should be tapped after activating mana ability")
      (is (= 1 (:white (q/get-mana-pool db'' :player-1)))
          "White mana should be added to pool"))))


;; === Mountain tests ===

(deftest test-mountain-taps-for-red-mana
  (testing "Mountain taps for red mana when activated"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          _ (is (= 0 (:red (q/get-mana-pool db' :player-1))) "Precondition: red mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :red)]
      (is (true? (get-object-tapped db'' obj-id))
          "Mountain should be tapped after activating mana ability")
      (is (= 1 (:red (q/get-mana-pool db'' :player-1)))
          "Red mana should be added to pool"))))


;; === Forest tests ===

(deftest test-forest-taps-for-green-mana
  (testing "Forest taps for green mana when activated"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :forest :battlefield :player-1)
          _ (is (= 0 (:green (q/get-mana-pool db' :player-1))) "Precondition: green mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :green)]
      (is (true? (get-object-tapped db'' obj-id))
          "Forest should be tapped after activating mana ability")
      (is (= 1 (:green (q/get-mana-pool db'' :player-1)))
          "Green mana should be added to pool"))))


;; === Playing from hand tests ===

(deftest test-basic-land-played-from-hand
  (testing "Island can be played from hand and enters battlefield untapped"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :island :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id)) "Precondition: Island starts in hand")
          _ (is (= 1 (get-land-plays db' :player-1)) "Precondition: land-plays-left = 1")
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Island should be on battlefield after play-land")
      (is (= 0 (get-land-plays db'' :player-1))
          "Land plays should be decremented to 0"))))


;; === Edge case: tapped land cannot tap again ===

(deftest test-tapped-land-cannot-tap-again
  (testing "Already tapped Island cannot be tapped again for mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; First tap produces mana
          db-after-first-tap (ability-events/activate-mana-ability db' :player-1 obj-id :blue)
          _ (is (= 1 (:blue (q/get-mana-pool db-after-first-tap :player-1)))
                "Precondition: first tap adds mana")
          _ (is (true? (get-object-tapped db-after-first-tap obj-id))
                "Precondition: land is tapped")
          ;; Second tap should fail (no additional mana)
          db-after-second-tap (ability-events/activate-mana-ability db-after-first-tap :player-1 obj-id :blue)]
      (is (= 1 (:blue (q/get-mana-pool db-after-second-tap :player-1)))
          "Second tap should NOT add more mana")
      (is (true? (get-object-tapped db-after-second-tap obj-id))
          "Land should still be tapped"))))


;; === Edge case: land in hand cannot activate mana ===

(deftest test-land-in-hand-cannot-activate-mana
  (testing "Island in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :island :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id)) "Precondition: Island is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          ;; Attempt to activate mana ability from hand
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (= 0 (:blue (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (land not on battlefield)")
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Island should remain in hand"))))
