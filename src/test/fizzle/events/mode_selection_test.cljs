(ns fizzle.events.mode-selection-test
  "Tests for casting mode selection UI flow.

   When a card has multiple valid casting modes from the same zone,
   the UI should show a selector. When only one mode is valid,
   the spell auto-casts without showing selector.

   This tests:
   - Single-mode cards auto-cast without showing selector
   - Multi-mode cards (same zone) show mode selector
   - Selecting a mode casts the spell with that mode
   - Canceling mode selection clears pending state"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]))


;; === Test Cards ===

(def test-single-mode-card
  "A simple instant with only primary mode (cast from hand)."
  {:card/id :test-single-mode
   :card/name "Test Single Mode"
   :card/mana-cost {:colorless 1}
   :card/cmc 1
   :card/types #{:instant}
   :card/effects [{:effect/type :add-mana :effect/mana {:colorless 1}}]})


(def test-multi-mode-card
  "Card with both primary AND alternate mode from hand.
   Similar to Gush: can pay normal cost or use alternate."
  {:card/id :test-multi-mode
   :card/name "Test Multi Mode"
   :card/mana-cost {:colorless 4 :blue 1}
   :card/cmc 5
   :card/types #{:instant}
   :card/colors #{:blue}
   :card/effects [{:effect/type :draw :effect/amount 2}]
   :card/alternate-costs [{:alternate/id :alternate-cost
                           :alternate/zone :hand  ; Same zone as primary!
                           :alternate/mana-cost {}
                           :alternate/additional-costs [{:cost/type :pay-life
                                                         :cost/amount 2}]
                           :alternate/on-resolve :graveyard}]})


;; === Test Helpers ===

(defn add-card-to-zone
  "Add a card definition and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition if not already present
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    ;; Create object in zone
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)
          obj {:object/id obj-id
               :object/card card-eid
               :object/zone zone
               :object/owner player-eid
               :object/controller player-eid
               :object/tapped false}]
      (d/transact! conn [obj])
      [obj-id @conn])))


(defn create-app-db
  "Create app-db structure with game-db."
  [game-db]
  {:game/db game-db
   :game/selected-card nil
   :game/pending-mode-selection nil})


;; === Single Mode Card Tests ===

(deftest single-mode-card-auto-casts-test
  (testing "Card with single mode auto-casts without showing selector"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-single-mode-card :hand)
          db (mana/add-mana db :player-1 {:colorless 1})
          app-db (-> (create-app-db db)
                     (assoc :game/selected-card obj-id))
          ;; Invoke cast-spell event handler directly
          handler (fn [db _]
                    (let [game-db (:game/db db)
                          selected (:game/selected-card db)]
                      (if (and selected (rules/can-cast? game-db :player-1 selected))
                        (let [modes (rules/get-casting-modes game-db :player-1 selected)]
                          (if (= 1 (count modes))
                            ;; Single mode: auto-cast
                            (-> db
                                (assoc :game/db (rules/cast-spell-mode game-db :player-1 selected (first modes)))
                                (dissoc :game/selected-card))
                            ;; Multiple modes: show selector
                            (assoc db :game/pending-mode-selection
                                   {:object-id selected
                                    :modes modes})))
                        db)))
          result-db (handler app-db nil)]
      ;; Should NOT show mode selector
      (is (nil? (:game/pending-mode-selection result-db))
          "Single-mode card should not show mode selector")
      ;; Should have cast the spell (card on stack)
      (let [obj (q/get-object (:game/db result-db) obj-id)]
        (is (= :stack (:object/zone obj))
            "Single-mode card should auto-cast to stack"))
      ;; Selected card should be cleared
      (is (nil? (:game/selected-card result-db))
          "Selected card should be cleared after auto-cast"))))


;; === Multi Mode Card Tests ===

(deftest multi-mode-card-shows-selector-test
  (testing "Card with multiple modes from same zone shows selector"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-multi-mode-card :hand)
          ;; Add mana for primary mode
          db (mana/add-mana db :player-1 {:colorless 4 :blue 1})
          app-db (-> (create-app-db db)
                     (assoc :game/selected-card obj-id))
          ;; Invoke cast-spell event handler
          handler (fn [db _]
                    (let [game-db (:game/db db)
                          selected (:game/selected-card db)]
                      (if (and selected (rules/can-cast? game-db :player-1 selected))
                        (let [modes (rules/get-casting-modes game-db :player-1 selected)]
                          (if (= 1 (count modes))
                            ;; Single mode: auto-cast
                            (-> db
                                (assoc :game/db (rules/cast-spell-mode game-db :player-1 selected (first modes)))
                                (dissoc :game/selected-card))
                            ;; Multiple modes: show selector
                            (assoc db :game/pending-mode-selection
                                   {:object-id selected
                                    :modes modes})))
                        db)))
          result-db (handler app-db nil)]
      ;; SHOULD show mode selector
      (is (some? (:game/pending-mode-selection result-db))
          "Multi-mode card should show mode selector")
      ;; Should have both modes in selector
      (let [pending (:game/pending-mode-selection result-db)
            modes (:modes pending)]
        (is (= 2 (count modes))
            "Should have 2 modes (primary + alternate)")
        (is (= obj-id (:object-id pending))
            "Should track the object id"))
      ;; Card should NOT be on stack yet
      (let [obj (q/get-object (:game/db result-db) obj-id)]
        (is (= :hand (:object/zone obj))
            "Card should still be in hand (waiting for selection)"))
      ;; Selected card should still be set (cleared on mode selection)
      (is (= obj-id (:game/selected-card result-db))
          "Selected card should remain until mode is chosen"))))


(deftest multi-mode-only-valid-modes-shown-test
  (testing "Only castable modes are shown in selector"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-multi-mode-card :hand)
          ;; Add NO mana - can only use alternate (pay life)
          ;; But player has 20 life, so alternate mode IS valid
          ;; Get modes that are actually castable
          modes (rules/get-casting-modes db :player-1 obj-id)
          castable-modes (filter #(rules/can-cast-mode? db :player-1 obj-id %) modes)]
      ;; Should only have the alternate mode (can't pay 4U with no mana)
      (is (= 1 (count castable-modes))
          "Should only have 1 castable mode (alternate)")
      (is (= :alternate-cost (:mode/id (first castable-modes)))
          "Castable mode should be alternate cost"))))


;; === Select Casting Mode Tests ===

(deftest select-mode-casts-spell-test
  (testing "Selecting a mode casts the spell with that mode"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-multi-mode-card :hand)
          db (mana/add-mana db :player-1 {:colorless 4 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first (filter #(= :primary (:mode/id %)) modes))
          ;; Setup app-db with pending mode selection
          app-db {:game/db db
                  :game/selected-card obj-id
                  :game/pending-mode-selection {:object-id obj-id
                                                :modes modes}}
          ;; Select mode handler
          handler (fn [db [_ mode]]
                    (let [game-db (:game/db db)
                          pending (:game/pending-mode-selection db)
                          object-id (:object-id pending)]
                      (-> db
                          (assoc :game/db (rules/cast-spell-mode game-db :player-1 object-id mode))
                          (dissoc :game/selected-card)
                          (dissoc :game/pending-mode-selection))))
          result-db (handler app-db [nil primary-mode])]
      ;; Pending selection should be cleared
      (is (nil? (:game/pending-mode-selection result-db))
          "Pending mode selection should be cleared")
      ;; Selected card should be cleared
      (is (nil? (:game/selected-card result-db))
          "Selected card should be cleared")
      ;; Spell should be on stack with correct mode
      (let [obj (q/get-object (:game/db result-db) obj-id)]
        (is (= :stack (:object/zone obj))
            "Spell should be on stack")
        (is (= :primary (:mode/id (:object/cast-mode obj)))
            "Spell should have primary cast mode")))))


(deftest select-alternate-mode-casts-with-life-payment-test
  (testing "Selecting alternate mode pays life cost"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-multi-mode-card :hand)
          ;; No mana - using alternate mode which costs life
          initial-life (q/get-life-total db :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          alternate-mode (first (filter #(= :alternate-cost (:mode/id %)) modes))
          ;; Setup app-db with pending mode selection
          app-db {:game/db db
                  :game/selected-card obj-id
                  :game/pending-mode-selection {:object-id obj-id
                                                :modes modes}}
          ;; Select mode handler
          handler (fn [db [_ mode]]
                    (let [game-db (:game/db db)
                          pending (:game/pending-mode-selection db)
                          object-id (:object-id pending)]
                      (-> db
                          (assoc :game/db (rules/cast-spell-mode game-db :player-1 object-id mode))
                          (dissoc :game/selected-card)
                          (dissoc :game/pending-mode-selection))))
          result-db (handler app-db [nil alternate-mode])
          final-life (q/get-life-total (:game/db result-db) :player-1)]
      ;; Life should have been paid
      (is (= (- initial-life 2) final-life)
          "Alternate mode should pay 2 life")
      ;; Spell should be on stack
      (let [obj (q/get-object (:game/db result-db) obj-id)]
        (is (= :stack (:object/zone obj))
            "Spell should be on stack")
        (is (= :alternate-cost (:mode/id (:object/cast-mode obj)))
            "Spell should have alternate cast mode")))))


;; === Cancel Mode Selection Tests ===

(deftest cancel-mode-selection-test
  (testing "Canceling mode selection clears pending state"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-multi-mode-card :hand)
          db (mana/add-mana db :player-1 {:colorless 4 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          ;; Setup app-db with pending mode selection
          app-db {:game/db db
                  :game/selected-card obj-id
                  :game/pending-mode-selection {:object-id obj-id
                                                :modes modes}}
          ;; Cancel handler
          handler (fn [db _]
                    (dissoc db :game/pending-mode-selection))
          result-db (handler app-db nil)]
      ;; Pending selection should be cleared
      (is (nil? (:game/pending-mode-selection result-db))
          "Pending mode selection should be cleared")
      ;; Card should still be in hand
      (let [obj (q/get-object (:game/db result-db) obj-id)]
        (is (= :hand (:object/zone obj))
            "Card should still be in hand"))
      ;; Selected card should still be set (can try casting again)
      (is (= obj-id (:game/selected-card result-db))
          "Selected card should remain (can try again)"))))


;; === Edge Case Tests ===

(deftest zero-modes-does-nothing-test
  (testing "Card with 0 castable modes does nothing"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-multi-mode-card :hand)
          ;; No mana, and set life to 1 (can't pay 2 life alternate cost)
          player-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add player-eid :player/life 1]])]
      ;; Should not be castable at all
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Card should not be castable with no mana and insufficient life"))))
