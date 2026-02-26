(ns fizzle.engine.delayed-effect-grants-test
  "Tests for delayed-effect grants system (Portent mechanic).
   Tests engine-level mechanic, not card definitions."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
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


(deftest fire-delayed-effect-grant-on-upkeep
  (testing "Delayed effect fires when upkeep phase is entered"
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
          ;; Fire delayed effects
          db-after (turn-based/fire-delayed-effects db-turn-2 :player-1)]
      ;; Player should have 1 more card in hand
      (is (= (+ initial-hand-count 1) (count (q/get-hand db-after :player-1))))
      ;; Grant should be removed after firing
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))))))


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
      ;; Grant should still be present
      (is (= 1 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))))))


(deftest multiple-delayed-effect-grants
  (testing "Multiple delayed effects fire in same upkeep"
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
          ;; Fire delayed effects
          db-after (turn-based/fire-delayed-effects db-turn-2 :player-1)]
      ;; Player should have 2 more cards in hand
      (is (= (+ initial-hand-count 2) (count (q/get-hand db-after :player-1))))
      ;; Both grants should be removed
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))))))


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
  (testing "Grant on player-1 fires during opponent's upkeep (next turn)"
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
          db-after (game-events/advance-phase db-at-untap :player-2)]
      ;; Player-1 should draw (they own the grant), even though it's player-2's upkeep
      (is (= (+ initial-hand-count 1) (count (q/get-hand db-after :player-1)))
          "Grant holder should draw even when it's opponent's upkeep")
      ;; Grant should be removed
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))
          "Grant should be removed after firing"))))


;; === Integration Tests ===

(deftest delayed-effect-fires-via-advance-phase
  (testing "Delayed effect fires automatically when advance-phase enters upkeep"
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
          db-after (game-events/advance-phase db-at-untap :player-1)]
      ;; Player should have 1 more card in hand (delayed effect fired)
      (is (= (+ initial-hand-count 1) (count (q/get-hand db-after :player-1))))
      ;; Grant should be removed after firing
      (is (= 0 (count (grants/get-player-grants-by-type db-after :player-1 :delayed-effect)))))))
