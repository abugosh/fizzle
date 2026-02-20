(ns fizzle.engine.priority-phase-test
  "Tests for priority-phase enforcement.
   Verifies that no player can cast spells, activate abilities, or
   activate mana abilities during non-priority phases (untap, cleanup)."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.engine.mana-activation :as mana-activation]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as event-abilities]
    [fizzle.test-helpers :as h]))


;; === in-priority-phase? unit tests ===

(deftest in-priority-phase-true-for-priority-phases
  (doseq [phase #{:upkeep :draw :main1 :combat :main2 :end}]
    (is (true? (priority/in-priority-phase? phase))
        (str phase " should be a priority phase"))))


(deftest in-priority-phase-false-for-non-priority-phases
  (doseq [phase [:untap :cleanup]]
    (is (false? (priority/in-priority-phase? phase))
        (str phase " should NOT be a priority phase"))))


(deftest in-priority-phase-false-for-nil
  (is (false? (priority/in-priority-phase? nil))
      "nil phase should NOT be a priority phase"))


;; === can-cast? during non-priority phases ===

(deftest can-cast-returns-false-during-untap
  (let [db (h/create-test-db {:mana {:black 1}})
        [db ritual-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        db (d/db-with db [[:db/add game-eid :game/phase :untap]])]
    (is (false? (rules/can-cast? db :player-1 ritual-id))
        "Should not be able to cast spells during untap")))


(deftest can-cast-returns-false-during-cleanup
  (let [db (h/create-test-db {:mana {:black 1}})
        [db ritual-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        db (d/db-with db [[:db/add game-eid :game/phase :cleanup]])]
    (is (false? (rules/can-cast? db :player-1 ritual-id))
        "Should not be able to cast spells during cleanup")))


(deftest can-cast-works-during-main-phase
  (let [db (h/create-test-db {:mana {:black 1}})
        [db ritual-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)]
    (is (true? (rules/can-cast? db :player-1 ritual-id))
        "Should be able to cast spells during main phase")))


;; === activate-ability during non-priority phases ===

(deftest activate-ability-noop-during-untap
  (let [db (h/create-test-db)
        [db land-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        db (d/db-with db [[:db/add game-eid :game/phase :untap]])
        result (event-abilities/activate-ability db :player-1 land-id 0)]
    (is (= db (:db result))
        "Database should be unchanged during untap")
    (is (nil? (:pending-selection result))
        "No pending selection during untap")))


(deftest activate-ability-noop-during-cleanup
  (let [db (h/create-test-db)
        [db land-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        db (d/db-with db [[:db/add game-eid :game/phase :cleanup]])
        result (event-abilities/activate-ability db :player-1 land-id 0)]
    (is (= db (:db result))
        "Database should be unchanged during cleanup")
    (is (nil? (:pending-selection result))
        "No pending selection during cleanup")))


(deftest activate-ability-works-during-upkeep
  (testing "Abilities can be activated during priority phases like upkeep"
    (let [db (h/create-test-db)
          [db land-id] (h/add-card-to-zone db :polluted-delta :battlefield :player-1)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/phase :upkeep]])
          result (event-abilities/activate-ability db :player-1 land-id 0)]
      (is (not= db (:db result))
          "Database should change when activating during upkeep"))))


;; === activate-mana-ability during non-priority phases ===

(deftest activate-mana-ability-noop-during-untap
  (let [db (h/create-test-db)
        [db land-id] (h/add-card-to-zone db :swamp :battlefield :player-1)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        db (d/db-with db [[:db/add game-eid :game/phase :untap]])]
    (is (= db (mana-activation/activate-mana-ability db :player-1 land-id :black))
        "Mana ability should be blocked during untap")))


(deftest activate-mana-ability-noop-during-cleanup
  (let [db (h/create-test-db)
        [db land-id] (h/add-card-to-zone db :swamp :battlefield :player-1)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        db (d/db-with db [[:db/add game-eid :game/phase :cleanup]])]
    (is (= db (mana-activation/activate-mana-ability db :player-1 land-id :black))
        "Mana ability should be blocked during cleanup")))


(deftest activate-mana-ability-works-during-main-phase
  (let [db (h/create-test-db)
        [db land-id] (h/add-card-to-zone db :swamp :battlefield :player-1)]
    (is (not= db (mana-activation/activate-mana-ability db :player-1 land-id :black))
        "Mana ability should work during main phase")))
