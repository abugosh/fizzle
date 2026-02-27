(ns fizzle.engine.delayed-effect-grants-test
  "Tests for delayed-effect grants system (Portent mechanic).
   Tests engine-level mechanic, not card definitions."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.resolution :as resolution]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.game :as game-events]))


;; === Test Helpers ===

(defn add-library-cards
  "Add N cards to a player's library for testing draw effects.
   All cards are Dark Ritual (simplicity)."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      @conn)]
    (doseq [idx (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :library
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/position idx
                          :object/tapped false}]))
    @conn))


(defn resolve-all-stack-items
  "Resolve all stack items until the stack is empty.
   Returns updated db."
  [db]
  (loop [d db]
    (if-let [top (stack/get-top-stack-item d)]
      (let [result (resolution/resolve-stack-item d top)
            db-after (:db result)
            db-cleaned (stack/remove-stack-item db-after (:db/id top))]
        (recur db-cleaned))
      d)))


;; === Delayed Effect Grant Tests ===

(deftest add-delayed-effect-grant-to-player
  (testing "Can add delayed-effect grant to player"
    (let [db (init-game-state)
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db' (grants/add-player-grant db :player-1 grant)]
      (is (= 1 (count (grants/get-player-grants db' :player-1))))
      (is (= :delayed-effect (:grant/type (first (grants/get-player-grants db' :player-1))))))))


(deftest get-delayed-effect-grants
  (testing "Can filter delayed-effect grants by type"
    (let [db (init-game-state)
          delayed-grant {:grant/id (random-uuid)
                         :grant/type :delayed-effect
                         :grant/source (random-uuid)
                         :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                         :grant/data {:delayed/phase :upkeep
                                      :delayed/turn :next
                                      :delayed/effect {:effect/type :draw :effect/amount 1}}}
          other-grant {:grant/id (random-uuid)
                       :grant/type :restriction
                       :grant/source (random-uuid)
                       :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                       :grant/data {:restriction/type :cannot-cast-spells}}
          db' (-> db
                  (grants/add-player-grant :player-1 delayed-grant)
                  (grants/add-player-grant :player-1 other-grant))]
      (is (= 1 (count (grants/get-player-grants-by-type db' :player-1 :delayed-effect))))
      (is (= :draw (-> (grants/get-player-grants-by-type db' :player-1 :delayed-effect)
                       first
                       :grant/data
                       :delayed/effect
                       :effect/type))))))


(deftest fire-delayed-effect-creates-stack-item
  (testing "Delayed effect creates stack item on upkeep (not immediate execution)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Advance to turn 2 upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-turn-2 (d/db-with db-with-grant [[:db/add game-eid :game/turn 2]
                                              [:db/add game-eid :game/phase :upkeep]])
          ;; Fire delayed effects — creates stack items, does NOT draw
          db-after (turn-based/fire-delayed-effects db-turn-2 :player-1)
          stack-items (q/get-all-stack-items db-after)]
      ;; Should NOT have drawn yet (effect is on the stack)
      (is (= initial-hand-count (count (q/get-hand db-after :player-1)))
          "Hand size unchanged — draw is on stack, not executed")
      ;; Stack item should exist
      (is (= 1 (count stack-items))
          "Should have 1 delayed trigger on the stack")
      (is (= :delayed-trigger (:stack-item/type (first stack-items)))
          "Stack item type should be :delayed-trigger")
      (is (= :player-1 (:stack-item/controller (first stack-items)))
          "Stack item controller should be the grant holder")
      ;; Grant should be removed (consumed when trigger goes on stack)
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))
          "Grant removed after firing"))))


(deftest fire-delayed-effect-resolves-draw
  (testing "Delayed effect draws card when stack item resolves"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Advance to turn 2 upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-turn-2 (d/db-with db-with-grant [[:db/add game-eid :game/turn 2]
                                              [:db/add game-eid :game/phase :upkeep]])
          ;; Fire delayed effects (creates stack item)
          db-fired (turn-based/fire-delayed-effects db-turn-2 :player-1)
          ;; Resolve the stack item
          db-resolved (resolve-all-stack-items db-fired)]
      ;; NOW player should have drawn
      (is (= (+ initial-hand-count 1) (count (q/get-hand db-resolved :player-1)))
          "Player draws 1 card after delayed trigger resolves"))))


(deftest delayed-effect-grant-doesnt-fire-wrong-phase
  (testing "Delayed effect doesn't fire in wrong phase"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Advance to turn 2 draw phase (not upkeep)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-turn-2 (d/db-with db-with-grant [[:db/add game-eid :game/turn 2]
                                              [:db/add game-eid :game/phase :draw]])
          ;; Try to fire delayed effects
          db-after (turn-based/fire-delayed-effects db-turn-2 :player-1)]
      ;; Player should have same number of cards in hand (no draw fired)
      (is (= initial-hand-count (count (q/get-hand db-after :player-1))))
      ;; No stack items created
      (is (empty? (q/get-all-stack-items db-after))
          "No stack items should be created for wrong phase")
      ;; Grant should still be present
      (is (= 1 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))))))


(deftest delayed-effect-grant-doesnt-fire-wrong-turn
  (testing "Delayed effect doesn't fire on wrong turn"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Advance to turn 1 upkeep (grant is for turn 2)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-turn-1 (d/db-with db-with-grant [[:db/add game-eid :game/phase :upkeep]])
          ;; Try to fire delayed effects
          db-after (turn-based/fire-delayed-effects db-turn-1 :player-1)]
      ;; Player should have same number of cards in hand (no draw fired)
      (is (= initial-hand-count (count (q/get-hand db-after :player-1))))
      ;; No stack items created
      (is (empty? (q/get-all-stack-items db-after))
          "No stack items should be created for wrong turn")
      ;; Grant should still be present
      (is (= 1 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))))))


(deftest multiple-delayed-effect-grants
  (testing "Multiple delayed effects create multiple stack items"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant-1 {:grant/id (random-uuid)
                   :grant/type :delayed-effect
                   :grant/source (random-uuid)
                   :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                   :grant/data {:delayed/phase :upkeep
                                :delayed/turn :next
                                :delayed/effect {:effect/type :draw :effect/amount 1}}}
          grant-2 {:grant/id (random-uuid)
                   :grant/type :delayed-effect
                   :grant/source (random-uuid)
                   :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                   :grant/data {:delayed/phase :upkeep
                                :delayed/turn :next
                                :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grants (-> db
                             (grants/add-player-grant :player-1 grant-1)
                             (grants/add-player-grant :player-1 grant-2))
          initial-hand-count (count (q/get-hand db-with-grants :player-1))
          ;; Advance to turn 2 upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grants)
          db-turn-2 (d/db-with db-with-grants [[:db/add game-eid :game/turn 2]
                                               [:db/add game-eid :game/phase :upkeep]])
          ;; Fire delayed effects (creates 2 stack items)
          db-fired (turn-based/fire-delayed-effects db-turn-2 :player-1)]
      ;; 2 stack items should exist
      (is (= 2 (count (q/get-all-stack-items db-fired)))
          "Should have 2 delayed triggers on the stack")
      ;; Both grants should be removed
      (is (= 0 (count (grants/get-player-grants-by-type db-fired :player-1 :delayed-effect))))
      ;; Resolve all — player draws 2
      (let [db-resolved (resolve-all-stack-items db-fired)]
        (is (= (+ initial-hand-count 2) (count (q/get-hand db-resolved :player-1)))
            "Player should draw 2 after resolving both delayed triggers")))))


(deftest delayed-effect-grant-expires-naturally
  (testing "Delayed effect grant expires naturally at cleanup if not fired"
    (let [db (init-game-state)
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :cleanup}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          ;; Advance to turn 2 cleanup (past upkeep, grant should expire)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-turn-2-cleanup (d/db-with db-with-grant [[:db/add game-eid :game/turn 2]
                                                      [:db/add game-eid :game/phase :cleanup]])
          ;; Expire grants
          db-after (grants/expire-grants db-turn-2-cleanup 2 :cleanup)]
      ;; Grant should be expired and removed
      (is (= 0 (count (grants/get-player-grants db-after :player-1)))))))


;; Regression: delayed grants must fire for ALL players at upkeep, not just active player.
;; Portent: "Draw a card at the beginning of the next turn's upkeep."
;; If caster is player-1 and next turn is opponent's, the draw fires at opponent's upkeep.
(deftest delayed-effect-fires-for-non-active-player
  (testing "Grant on player-1 creates stack item during opponent's upkeep"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Set to turn 2 untap (opponent's turn — player-2 is active)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-at-untap (d/db-with db-with-grant [[:db/add game-eid :game/turn 2]
                                                [:db/add game-eid :game/phase :untap]])
          ;; Advance to upkeep with player-2 as active player
          db-after (game-events/advance-phase db-at-untap :player-2)
          stack-items (q/get-all-stack-items db-after)]
      ;; Stack item should exist with player-1 as controller
      (is (some #(and (= :delayed-trigger (:stack-item/type %))
                      (= :player-1 (:stack-item/controller %)))
                stack-items)
          "Delayed trigger for player-1 should be on stack during opponent's upkeep")
      ;; Grant should be removed
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))
          "Grant should be removed after firing")
      ;; Resolve stack to verify draw
      (let [db-resolved (resolve-all-stack-items db-after)]
        (is (= (+ initial-hand-count 1) (count (q/get-hand db-resolved :player-1)))
            "Grant holder should draw after delayed trigger resolves")))))


;; === Integration Tests ===

(deftest delayed-effect-fires-via-advance-phase
  (testing "Delayed effect creates stack item when advance-phase enters upkeep"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Start at untap phase and advance to upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-at-untap (d/db-with db-with-grant [[:db/add game-eid :game/phase :untap]])
          ;; Use game-events/advance-phase to move to upkeep
          db-after (game-events/advance-phase db-at-untap :player-1)
          stack-items (q/get-all-stack-items db-after)]
      ;; Stack item should exist
      (is (some #(= :delayed-trigger (:stack-item/type %)) stack-items)
          "Delayed trigger should be on the stack")
      ;; Grant should be removed after firing
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect))))
      ;; Resolve stack to verify draw
      (let [db-resolved (resolve-all-stack-items db-after)]
        (is (= (+ initial-hand-count 1) (count (q/get-hand db-resolved :player-1)))
            "Player draws after delayed trigger resolves")))))


;; Stifle interaction: delayed triggers can be countered
(deftest delayed-trigger-can-be-stifled
  (testing "Stifle can counter a delayed trigger on the stack"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 10))
          grant {:grant/id (random-uuid)
                 :grant/type :delayed-effect
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :upkeep}
                 :grant/data {:delayed/phase :upkeep
                              :delayed/turn :next
                              :delayed/effect {:effect/type :draw :effect/amount 1}}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          initial-hand-count (count (q/get-hand db-with-grant :player-1))
          ;; Advance to turn 2 upkeep
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db-with-grant)
          db-turn-2 (d/db-with db-with-grant [[:db/add game-eid :game/turn 2]
                                              [:db/add game-eid :game/phase :upkeep]])
          ;; Fire delayed effects (creates stack item)
          db-fired (turn-based/fire-delayed-effects db-turn-2 :player-1)
          top-item (stack/get-top-stack-item db-fired)
          ;; Remove the stack item (simulates Stifle countering the trigger)
          db-stifled (stack/remove-stack-item db-fired (:db/id top-item))]
      ;; Grant was consumed (can't re-trigger)
      (is (= 0 (count (grants/get-player-grants-by-type db-stifled :player-1 :delayed-effect)))
          "Grant should be consumed even when trigger is countered")
      ;; Player should NOT have drawn (trigger was countered)
      (is (= initial-hand-count (count (q/get-hand db-stifled :player-1)))
          "Player should not draw when delayed trigger is Stifled")
      ;; Stack should be empty
      (is (empty? (q/get-all-stack-items db-stifled))
          "Stack should be empty after countering"))))
