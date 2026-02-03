(ns fizzle.engine.scry-effect-test
  "Tests for the :scry effect type.
   Scry effects return db unchanged - actual scry happens at app-db level
   via re-frame events when player confirms selection."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as fx]))


;; === Test helpers ===

(defn add-library-cards
  "Add cards to a player's library with sequential positions.
   Takes a vector of card-ids (keywords) and adds them with positions 0, 1, 2...
   Position 0 = top of library."
  [db player-id card-ids]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (doseq [idx (range (count card-ids))]
      ;; Use Dark Ritual card def for simplicity
      (let [card-eid (d/q '[:find ?e .
                            :where [?e :card/id :dark-ritual]]
                          @conn)]
        (d/transact! conn [{:object/id (random-uuid)
                            :object/card card-eid
                            :object/zone :library
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/position idx
                            :object/tapped false}])))
    @conn))


(defn count-zone
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (q/get-objects-in-zone db player-id zone)))


;; === :scry effect tests ===

(deftest test-scry-effect-returns-db-unchanged
  ;; Bug caught: If scry modifies db directly, selection flow breaks
  (testing "Scry effect returns db unchanged (triggers selection flow)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3 :card-4 :card-5]))
          effect {:effect/type :scry
                  :effect/amount 2}
          db' (fx/execute-effect db :player-1 effect)]
      ;; db should be unchanged - selection handled at app-db level
      (is (= 5 (count-zone db' :player-1 :library))
          "Scry must return db unchanged for selection flow - library should still have 5 cards"))))


(deftest test-scry-effect-amount-1
  ;; Bug caught: Hardcoded scry 2+ assumption
  (testing "Scry 1 is valid and returns db unchanged"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :scry
                  :effect/amount 1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (count-zone db' :player-1 :library))
          "Scry 1 should return db unchanged"))))


(deftest test-scry-effect-amount-0-no-op
  ;; Bug caught: Division by zero or empty array issues
  (testing "Scry 0 is a no-op (returns db unchanged)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :scry
                  :effect/amount 0}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (count-zone db' :player-1 :library))
          "Scry 0 should be no-op"))))


(deftest test-scry-effect-missing-amount-defaults-safely
  ;; Bug caught: NPE on nil amount
  (testing "Scry with missing :effect/amount defaults safely (no crash)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :scry}  ; no :effect/amount
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (count-zone db' :player-1 :library))
          "Missing amount should be safe (treated as no-op)"))))


(deftest test-scry-effect-negative-amount-no-op
  ;; Bug caught: Negative amount causing issues
  (testing "Scry with negative amount is treated as no-op"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:card-1 :card-2 :card-3]))
          effect {:effect/type :scry
                  :effect/amount -1}
          db' (fx/execute-effect db :player-1 effect)]
      (is (= 3 (count-zone db' :player-1 :library))
          "Negative amount should be treated as no-op"))))
