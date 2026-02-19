(ns fizzle.events.yield-test
  "Tests for manual yield behavior with stack items.

   Verifies that ::yield resolves one stack item and stops (no cascade)
   when auto-mode is not set, giving the player priority to respond."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as queries]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(deftest yield-resolves-one-item-and-stops-when-stack-has-more
  (testing "Manual yield with 2 items on stack resolves one and stops"
    (let [db (th/create-test-db {:mana {:black 1} :stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :goldfish})
          ;; Cast two Dark Rituals to get 2 spells on the stack
          ;; First: give enough mana and cast
          [db ritual-1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-1)
          ;; Second: add mana and cast another
          db (mana/add-mana db :player-1 {:black 1})
          [db ritual-2] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-2)
          ;; Verify: 2 spell items on stack
          stack-items (queries/get-all-stack-items db)
          spell-items (filter #(= :spell (:stack-item/type %)) stack-items)
          _ (assert (= 2 (count spell-items)) "Setup: should have 2 spells on stack")
          ;; Build app-db (no auto-mode set)
          app-db (merge (history/init-history)
                        {:game/db db})
          ;; Call yield-impl (the pure function under test)
          result (game/yield-impl app-db)]
      ;; After resolving one item, should NOT cascade (no continue-yield?)
      (is (not (:continue-yield? result))
          "Manual yield should not cascade when stack has more items")
      ;; Should have resolved exactly one spell
      (let [result-db (:game/db (:app-db result))
            remaining-stack (queries/get-all-stack-items result-db)
            remaining-spells (filter #(= :spell (:stack-item/type %)) remaining-stack)]
        (is (= 1 (count remaining-spells))
            "Should have 1 spell remaining on stack after resolving one")))))


(deftest yield-cascades-with-auto-mode
  (testing "Yield with :resolving auto-mode continues cascade through stack"
    (let [db (th/create-test-db {:mana {:black 1} :stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :goldfish})
          ;; Cast two Dark Rituals
          [db ritual-1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-1)
          db (mana/add-mana db :player-1 {:black 1})
          [db ritual-2] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-2)
          _ (assert (>= (count (filter #(= :spell (:stack-item/type %))
                                       (queries/get-all-stack-items db))) 2)
                    "Setup: should have 2 spells on stack")
          ;; Build app-db WITH auto-mode (simulating yield-all / F6)
          db (priority/set-auto-mode db :resolving)
          app-db (merge (history/init-history)
                        {:game/db db})
          result (game/yield-impl app-db)]
      ;; With auto-mode, should cascade
      (is (true? (:continue-yield? result))
          "Yield with auto-mode should cascade when stack has items"))))
