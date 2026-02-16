(ns fizzle.events.priority-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as h]))


(defn- setup-app-db
  "Create a full app-db with two players (player + goldfish bot),
   player stops at main1+main2, starting at main1."
  ([]
   (setup-app-db {}))
  ([opts]
   (let [stops (or (:stops opts) #{:main1 :main2})
         db (-> (h/create-test-db (merge {:stops stops} (select-keys opts [:mana :life])))
                (h/add-opponent {:bot-archetype :goldfish}))]
     {:game/db db})))


;; === Test 1: yield on empty stack, stops at main1+main2 ===

(deftest yield-empty-stack-advances-past-combat-to-main2
  (testing "yield from main1 with stops at main1+main2 skips combat, lands on main2"
    (let [app-db (setup-app-db)
          result (game/yield-impl app-db)]
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result)))))
          "Should advance to main2, skipping upkeep/draw/combat"))))


;; === Test 2: yield with Dark Ritual on stack ===

(deftest yield-with-spell-on-stack-resolves-it
  (testing "yield resolves top of stack when both pass on non-empty stack"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' _obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast Dark Ritual to put it on the stack
          game-db (rules/cast-spell db' :player-1 _obj-id)
          app-db {:game/db game-db}
          result (game/yield-impl app-db)
          result-db (:game/db (:app-db result))
          pool (q/get-mana-pool result-db :player-1)]
      (is (= 3 (:black pool))
          "Dark Ritual should resolve, adding BBB to mana pool")
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should stay in main1 after resolving (stop is set)"))))


;; === Test 3: yield at main2 advances through turn boundary ===

(deftest yield-at-main2-advances-to-new-turn-main1
  (testing "yield from main2 advances through end, cleanup, new turn, to main1"
    (let [db (-> (h/create-test-db {:stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Advance to main2 manually
          game-db (-> db
                      (game/advance-phase :player-1)  ; main1 -> combat
                      (game/advance-phase :player-1)  ; combat -> main2
                      )
          app-db {:game/db game-db}
          result (game/yield-impl app-db)
          result-db (:game/db (:app-db result))]
      (is (= :main1 (:game/phase (q/get-game-state result-db)))
          "Should advance to main1 of next turn")
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should be turn 2"))))


;; === Test 4: yield with selection-needed spell ===

(deftest yield-with-selection-spell-returns-pending-selection
  (testing "yield with a spell that needs player selection pauses"
    (let [db (-> (h/create-test-db {:mana {:blue 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Add Careful Study (draw 2, then discard 2 — interactive discard)
          [db' obj-id] (h/add-card-to-zone db :careful-study :hand :player-1)
          ;; Need cards in library for Careful Study draw
          [db'' _] (h/add-cards-to-library db' [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          game-db (rules/cast-spell db'' :player-1 obj-id)
          app-db {:game/db game-db}
          result (game/yield-impl app-db)]
      (is (some? (:game/pending-selection (:app-db result)))
          "Should return pending-selection for interactive spell"))))


;; === Test 5: bot auto-passes synchronously ===

(deftest yield-bot-auto-passes
  (testing "goldfish bot auto-passes when priority transfers to it"
    (let [app-db (setup-app-db)
          result (game/yield-impl app-db)]
      ;; If bot didn't auto-pass, phase wouldn't advance (only one player passed)
      (is (some? (:app-db result))
          "Should return a result (bot auto-passed)")
      (is (= :main2 (:game/phase (q/get-game-state (:game/db (:app-db result)))))
          "Phase should advance because bot auto-passed"))))


;; === Test 6: cast spell retains priority ===

(deftest cast-spell-does-not-auto-yield
  (testing "casting a spell should not automatically yield priority"
    (let [db (-> (h/create-test-db {:mana {:black 1} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db (rules/cast-spell db' :player-1 obj-id)]
      ;; After casting, spell should be on stack, not resolved
      (is (seq (q/get-all-stack-items game-db))
          "Stack should have items after casting")
      ;; Mana should have been spent, not gained
      (is (= 0 (:black (q/get-mana-pool game-db :player-1)))
          "Mana should be spent on casting, not refunded by resolution"))))


;; === Test 7: yield with no stops set ===

(deftest yield-with-no-stops-advances-to-turn-boundary
  (testing "yield from main1 with no stops advances to turn boundary (untap of new turn)"
    (let [app-db (setup-app-db {:stops #{}})
          result (game/yield-impl app-db)
          result-db (:game/db (:app-db result))]
      (is (= 2 (:game/turn (q/get-game-state result-db)))
          "Should advance to turn 2")
      (is (= :untap (:game/phase (q/get-game-state result-db)))
          "Should stop at untap (turn boundary) when no stops set"))))


;; === Test 8: yield continues resolving multiple stack items ===

(deftest yield-with-continue-resolves-one-at-a-time
  (testing "yield returns :continue-yield? when more items on stack"
    (let [db (-> (h/create-test-db {:mana {:black 2} :stops #{:main1 :main2}})
                 (h/add-opponent {:bot-archetype :goldfish}))
          ;; Cast two Dark Rituals
          [db' obj1] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          game-db1 (rules/cast-spell db' :player-1 obj1)
          [db'' obj2] (h/add-card-to-zone game-db1 :dark-ritual :hand :player-1)
          game-db2 (rules/cast-spell db'' :player-1 obj2)
          app-db {:game/db game-db2}
          result (game/yield-impl app-db)]
      ;; First yield should resolve one item and signal to continue
      (is (true? (:continue-yield? result))
          "Should signal to continue yielding with more stack items"))))
