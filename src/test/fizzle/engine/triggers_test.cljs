(ns fizzle.engine.triggers-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]))


;; === Corner case tests ===

(deftest test-trigger-with-nil-controller-graceful
  (testing "resolve-trigger with nil controller doesn't crash and returns db unchanged"
    (let [db (init-game-state)
          trigger {:trigger/id (random-uuid)
                   :trigger/type :becomes-tapped
                   :trigger/source :some-source
                   :trigger/controller nil
                   :trigger/data {:effects [{:effect/type :draw
                                             :effect/amount 1}]}}
          result-db (triggers/resolve-trigger db trigger)]
      (is (= db result-db)
          "DB should be unchanged when trigger has nil controller"))))


;; =====================================================
;; Corner Case Tests: draw-step trigger
;; =====================================================

(defn set-turn
  "Set the game turn number. Returns updated db."
  [db turn-number]
  (let [game-eid (d/q '[:find ?e .
                        :where [?e :game/id _]]
                      db)]
    (d/db-with db [[:db/add game-eid :game/turn turn-number]])))


(defn add-cards-to-library
  "Add n cards to library with positions. Returns db."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [i (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :library
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false
                          :object/position i}]))
    @conn))


(deftest test-resolve-trigger-draw-step-turn-1-skips
  (testing "Draw step on turn 1 does not draw a card (MTG play/draw rule)"
    (let [db (-> (init-game-state)
                 (add-cards-to-library :player-1 5))
          hand-before (count (q/get-hand db :player-1))
          trigger {:trigger/type :draw-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)
          hand-after (count (q/get-hand db' :player-1))]
      (is (= hand-before hand-after)
          "Should not draw on turn 1"))))


(deftest test-resolve-trigger-draw-step-turn-2-draws
  (testing "Draw step on turn 2 draws one card"
    (let [db (-> (init-game-state)
                 (set-turn 2)
                 (add-cards-to-library :player-1 5))
          hand-before (count (q/get-hand db :player-1))
          trigger {:trigger/type :draw-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)
          hand-after (count (q/get-hand db' :player-1))]
      (is (= (inc hand-before) hand-after)
          "Should draw exactly 1 card on turn 2"))))


;; =====================================================
;; Corner Case Tests: untap-step trigger
;; =====================================================

(defn add-tapped-permanent
  "Add a tapped permanent to the battlefield. Returns [db object-id]."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped true}])
    [@conn obj-id]))


(deftest test-resolve-trigger-untap-step
  (testing "Untap step untaps all tapped permanents"
    (let [db (init-game-state)
          [db obj-1] (add-tapped-permanent db :player-1)
          [db obj-2] (add-tapped-permanent db :player-1)
          ;; Verify tapped
          _ (is (true? (:object/tapped (q/get-object db obj-1))))
          _ (is (true? (:object/tapped (q/get-object db obj-2))))
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)]
      (is (false? (:object/tapped (q/get-object db' obj-1)))
          "First permanent should be untapped")
      (is (false? (:object/tapped (q/get-object db' obj-2)))
          "Second permanent should be untapped"))))


(deftest test-resolve-trigger-untap-step-no-tapped-noop
  (testing "Untap step with no tapped permanents is a no-op"
    (let [db (init-game-state)
          trigger {:trigger/type :untap-step
                   :trigger/controller :player-1}
          db' (triggers/resolve-trigger db trigger)]
      (is (= db db')
          "DB should be unchanged when no permanents to untap"))))


;; =====================================================
;; Corner Case Tests: create-spell-copy
;; =====================================================

(deftest test-create-spell-copy-returns-valid-object
  (testing "create-spell-copy creates a copy with is-copy flag on stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-with-copy (triggers/create-spell-copy db-cast obj-id :player-1)
          copy-objs (d/q '[:find [(pull ?e [* {:object/card [*]}]) ...]
                           :where [?e :object/is-copy true]]
                         db-with-copy)
          copy (first copy-objs)]
      (is (= 1 (count copy-objs))
          "Should create exactly one copy")
      (is (true? (:object/is-copy copy))
          "Copy should have :object/is-copy true")
      (is (= :stack (:object/zone copy))
          "Copy should be on stack")
      (is (= :dark-ritual (get-in copy [:object/card :card/id]))
          "Copy should reference same card as original")
      ;; Copy should have a stack-item
      (let [copy-eid (:db/id copy)
            si (stack/get-stack-item-by-object-ref db-with-copy copy-eid)]
        (is (some? si) "Copy should have an associated stack-item")
        (is (= :storm-copy (:stack-item/type si))
            "Stack-item type should be :storm-copy")
        (is (true? (:stack-item/is-copy si))
            "Stack-item should be marked as copy")))))


(deftest test-create-spell-copy-nonexistent-source
  (testing "create-spell-copy with nonexistent source returns db unchanged"
    (let [db (init-game-state)
          fake-id (random-uuid)
          db' (triggers/create-spell-copy db fake-id :player-1)]
      (is (= db db')
          "Should return db unchanged for nonexistent source"))))
