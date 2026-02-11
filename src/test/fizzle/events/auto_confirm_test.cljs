(ns fizzle.events.auto-confirm-test
  "Tests for auto-confirm behavior in ::toggle-selection.

   When a single-select selection is completed (not deselected), the toggle
   handler should automatically confirm the selection without requiring a
   separate Confirm click.

   Auto-confirm applies to:
   - tutor (select-count=1)
   - cast-time-targeting (always select-count=1)
   - player-target (always select-count=1)
   - ability-targeting (always select-count=1)

   Auto-confirm does NOT apply to:
   - tutor (select-count > 1, e.g. Intuition)
   - discard, cleanup-discard, scry, pile-choice
   - graveyard-return, peek-and-select, exile-cards-cost, x-mana-cost
   - Deselecting (clicking already-selected item)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.events.selection :as selection]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; === Test helpers ===

(defn- create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 3 :black 3
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}
                       {:player/id :opponent
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn- add-cards-to-library
  "Add cards to library with positions. Returns [db object-ids]."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (loop [remaining card-ids
           position 0
           obj-ids []]
      (if (empty? remaining)
        [@conn obj-ids]
        (let [card-id (first remaining)
              obj-id (random-uuid)
              card-eid (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining)
                 (inc position)
                 (conj obj-ids obj-id)))))))


(defn- add-card-to-zone
  "Add a card to a zone. Returns [db object-id]."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn- get-object-zone
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn- dispatch-sync-on-db
  "Set rf-db, dispatch synchronously, return new rf-db."
  [db event]
  (reset! rf-db/app-db db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; === Single-select tutor auto-confirm ===

(deftest test-single-select-tutor-auto-confirms
  (testing "Single-select tutor auto-confirms on card selection"
    (let [game-db (create-test-db)
          [game-db' [bf-id _dr-id]] (add-cards-to-library game-db
                                                          [:brain-freeze :dark-ritual]
                                                          :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id _dr-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection bf-id])]
      ;; After auto-confirm, pending-selection should be cleared
      (is (nil? (:game/pending-selection result))
          "Auto-confirm should clear pending selection")
      ;; Card should be in hand
      (is (= :hand (get-object-zone (:game/db result) bf-id))
          "Selected card should be moved to hand"))))


(deftest test-multi-select-tutor-does-not-auto-confirm
  (testing "Multi-select tutor does NOT auto-confirm on first card"
    (let [game-db (create-test-db)
          [game-db' [obj1 obj2 obj3]] (add-cards-to-library game-db
                                                            [:dark-ritual :cabal-ritual :brain-freeze]
                                                            :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 3
                   :selection/exact? true
                   :selection/candidates #{obj1 obj2 obj3}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection obj1])]
      ;; Pending selection should still be present
      (is (= #{obj1} (get-in result [:game/pending-selection :selection/selected]))
          "Card should be added to selection"))))


(deftest test-tutor-deselect-does-not-auto-confirm
  (testing "Deselecting a tutor card does NOT auto-confirm"
    (let [game-db (create-test-db)
          [game-db' [bf-id _dr-id]] (add-cards-to-library game-db
                                                          [:brain-freeze :dark-ritual]
                                                          :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{bf-id}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id _dr-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection bf-id])]
      ;; Deselecting should NOT auto-confirm
      (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
          "Card should be deselected"))))


;; === Targeting auto-confirm ===

(deftest test-player-target-auto-confirms
  (testing "Player target auto-confirms on player selection"
    (let [game-db (create-test-db)
          pending {:selection/type :player-target
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/valid-targets #{:player-1 :opponent}
                   :selection/spell-id (random-uuid)
                   :selection/target-effect {:effect/type :drain
                                             :effect/amount 2
                                             :effect/target :any-player}
                   :selection/remaining-effects []
                   :selection/validation :exact
                   :selection/auto-confirm? true}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection :opponent])]
      ;; After auto-confirm, pending-selection should be cleared
      (is (nil? (:game/pending-selection result))
          "Player target should auto-confirm"))))


(deftest test-player-target-deselect-does-not-auto-confirm
  (testing "Deselecting player target does NOT auto-confirm"
    (let [game-db (create-test-db)
          pending {:selection/type :player-target
                   :selection/player-id :player-1
                   :selection/selected #{:opponent}
                   :selection/select-count 1
                   :selection/valid-targets #{:player-1 :opponent}
                   :selection/spell-id (random-uuid)
                   :selection/target-effect {:effect/type :drain
                                             :effect/amount 2
                                             :effect/target :any-player}
                   :selection/remaining-effects []
                   :selection/validation :exact
                   :selection/auto-confirm? true}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection :opponent])]
      ;; Deselect should NOT auto-confirm
      (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
          "Player should be deselected"))))


(deftest test-invalid-target-rejected
  (testing "Toggling an invalid target is rejected (no selection change)"
    (let [game-db (create-test-db)
          pending {:selection/type :player-target
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/valid-targets #{:player-1 :opponent}
                   :selection/spell-id (random-uuid)
                   :selection/target-effect {:effect/type :drain
                                             :effect/amount 2
                                             :effect/target :any-player}
                   :selection/remaining-effects []
                   :selection/validation :exact
                   :selection/auto-confirm? true}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection :invalid-id])]
      ;; Selection should be unchanged
      (is (= #{} (get-in result [:game/pending-selection :selection/selected]))
          "Selection should remain empty"))))


;; === Non-auto-confirm types (negative tests) ===

(deftest test-discard-does-not-auto-confirm
  (testing "Discard selection does NOT auto-confirm even with select-count=1"
    (let [game-db (create-test-db)
          [game-db' card-id] (add-card-to-zone game-db :dark-ritual :hand :player-1)
          pending {:selection/type :discard
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/validation :exact
                   :selection/auto-confirm? false}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection card-id])]
      ;; Discard should NOT auto-confirm
      (is (= #{card-id} (get-in result [:game/pending-selection :selection/selected]))
          "Card should be selected but not confirmed"))))


(deftest test-pile-choice-does-not-auto-confirm
  (testing "Pile choice does NOT auto-confirm even with hand-count=1"
    (let [game-db (create-test-db)
          card-id (random-uuid)
          pending {:selection/type :pile-choice
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/hand-count 1
                   :selection/select-count 1
                   :selection/candidates #{card-id}
                   :selection/bottom-pile []
                   :selection/validation :exact
                   :selection/auto-confirm? false}
          app-db {:game/db game-db
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection card-id])]
      ;; Pile choice should NOT auto-confirm
      (is (some? (:game/pending-selection result))
          "Pile choice should NOT auto-confirm"))))


(deftest test-graveyard-return-does-not-auto-confirm
  (testing "Graveyard return does NOT auto-confirm even with select-count=1"
    (let [game-db (create-test-db)
          [game-db' card-id] (add-card-to-zone game-db :dark-ritual :graveyard :player-1)
          pending {:selection/type :graveyard-return
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/min-count 0
                   :selection/target-zone :hand
                   :selection/candidate-ids #{card-id}
                   :selection/validation :at-most
                   :selection/auto-confirm? false}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          result (dispatch-sync-on-db app-db [::selection/toggle-selection card-id])]
      ;; Graveyard return should NOT auto-confirm (user may want 0)
      (is (some? (:game/pending-selection result))
          "Graveyard return should NOT auto-confirm"))))


;; === Find Nothing still works ===

(deftest test-find-nothing-via-confirm
  (testing "Find Nothing (empty selection confirm) still works for tutor"
    (let [game-db (create-test-db)
          [game-db' [bf-id]] (add-cards-to-library game-db [:brain-freeze] :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          ;; Dispatch confirm directly with empty selection (Find Nothing)
          result (dispatch-sync-on-db app-db [::selection/confirm-selection])]
      ;; Pending selection cleared
      (is (nil? (:game/pending-selection result))
          "Find Nothing should clear pending selection")
      ;; Card should NOT be in hand
      (is (= :library (get-object-zone (:game/db result) bf-id))
          "Card should remain in library on Find Nothing"))))


;; === Tutor single-select replaces (not accumulates) ===

(deftest test-single-select-tutor-replaces-selection
  (testing "Clicking a different card in single-select tutor replaces and auto-confirms"
    (let [game-db (create-test-db)
          [game-db' [bf-id dr-id]] (add-cards-to-library game-db
                                                         [:brain-freeze :dark-ritual]
                                                         :player-1)
          pending {:selection/type :tutor
                   :selection/player-id :player-1
                   :selection/selected #{bf-id}
                   :selection/select-count 1
                   :selection/exact? true
                   :selection/candidates #{bf-id dr-id}
                   :selection/spell-id (random-uuid)
                   :selection/target-zone :hand
                   :selection/shuffle? true
                   :selection/allow-fail-to-find? true
                   :selection/validation :exact-or-zero
                   :selection/auto-confirm? true}
          app-db {:game/db game-db'
                  :game/pending-selection pending}
          ;; Click a different card (not deselect — replace)
          result (dispatch-sync-on-db app-db [::selection/toggle-selection dr-id])]
      ;; Auto-confirm should fire since new card was selected (replace, not deselect)
      (is (nil? (:game/pending-selection result))
          "Replacing selection in single-select tutor should auto-confirm")
      ;; The new card (dark ritual) should be in hand
      (is (= :hand (get-object-zone (:game/db result) dr-id))
          "Newly selected card should be in hand"))))
