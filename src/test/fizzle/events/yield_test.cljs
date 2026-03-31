(ns fizzle.events.yield-test
  "Tests for yield behavior with stack items.

   Verifies that yield (without yield-all) resolves one stack item and stops,
   giving the player priority to respond to remaining stack items.
   Verifies that yield-all resolves all stack items (cascade)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as queries]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.director :as director]
    [fizzle.history.core :as history]
    [fizzle.test-helpers :as th]))


(deftest yield-resolves-one-item-and-stops-when-stack-has-more
  (testing "yield with 2 items on stack resolves one and stops"
    (let [db (th/create-test-db {:mana {:black 1} :stops #{:main1}})
          db (th/add-opponent db {:bot-archetype :goldfish})
          ;; Cast two Dark Rituals to get 2 spells on the stack
          [db ritual-1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-1)
          db (mana/add-mana db :player-1 {:black 1})
          [db ritual-2] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-2)
          ;; Verify: 2 spell items on stack
          stack-items (queries/get-all-stack-items db)
          spell-items (filter #(= :spell (:stack-item/type %)) stack-items)
          _ (assert (= 2 (count spell-items)) "Setup: should have 2 spells on stack")
          ;; Build app-db (yield-all? false = manual yield)
          app-db (merge (history/init-history)
                        {:game/db db})
          ;; Run director: human yields (passes once), both pass, resolve one item, stop
          result (director/run-to-decision app-db {:yield-all? false :human-yielded? true})
          result-db (:game/db (:app-db result))]
      ;; Should have resolved exactly one spell (one ritual resolves)
      (let [remaining-stack (queries/get-all-stack-items result-db)
            remaining-spells (filter #(= :spell (:stack-item/type %)) remaining-stack)]
        (is (= 1 (count remaining-spells))
            "Should have 1 spell remaining on stack after resolving one"))
      ;; Should have stopped (not cascaded through remaining stack)
      (is (= :await-human (:reason result))
          "Should stop at :await-human after resolving one stack item"))))


(deftest yield-all-resolves-entire-stack
  (testing "yield-all with 2 items on stack resolves all items but stays on same turn"
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
          app-db (merge (history/init-history)
                        {:game/db db})
          ;; yield-all with non-empty stack: resolve all items, then stop
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))]
      ;; Both rituals should have resolved (stack empty)
      (is (empty? (queries/get-all-stack-items result-db))
          "Stack should be empty after yield-all")
      ;; Should stay on turn 1 — yield-all with stack items should NOT F6
      (is (= 1 (:game/turn (queries/get-game-state result-db)))
          "Should stay on turn 1 — yield-all with stack should not F6")
      ;; Should stop at human's stop
      (is (= :await-human (:reason result))
          "Should stop at :await-human after stack empties")
      ;; Rituals should be in graveyard (proof they resolved)
      (let [gy (queries/get-objects-in-zone result-db :player-1 :graveyard)
            ritual-count (count (filter #(= :dark-ritual (get-in % [:object/card :card/id])) gy))]
        (is (= 2 ritual-count)
            "Both Dark Rituals should be in graveyard after resolution")))))
