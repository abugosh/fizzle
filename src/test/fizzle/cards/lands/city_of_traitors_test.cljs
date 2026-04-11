(ns fizzle.cards.lands.city-of-traitors-test
  "Tests for City of Traitors card implementation.

   City of Traitors:
   - {T}: Add {C}{C}
   - When you play another land, sacrifice City of Traitors

   Key behaviors being tested:
   - Card definition (all fields exact)
   - Mana production: produces 2 colorless mana
   - Trigger registration on ETB
   - Trigger does NOT fire when CoT itself enters (exclude-self)
   - Trigger DOES fire when another land enters
   - Multiple CoTs interact correctly
   - Trigger uses stack (can respond)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lands.city-of-traitors :as city-of-traitors]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.zones :as zones]
    [fizzle.events.init :as init]
    [fizzle.events.lands :as lands]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Card definition tests ===

(deftest test-cot-card-definition
  (testing "City of Traitors has correct card fields"
    (let [card city-of-traitors/card]
      (is (= :city-of-traitors (:card/id card)))
      (is (= "City of Traitors" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))
      (is (= "When you play another land, sacrifice City of Traitors. {T}: Add {C}{C}."
             (:card/text card)))))

  (testing "City of Traitors has correct trigger structure"
    (let [triggers (:card/triggers city-of-traitors/card)]
      (is (= 1 (count triggers))
          "Should have exactly one trigger")
      (let [trigger (first triggers)]
        (is (= :land-entered (:trigger/type trigger)))
        (is (true? (get-in trigger [:trigger/filter :exclude-self]))
            "Trigger should exclude self (not fire on own entry)")
        (is (= :self-controller (get-in trigger [:trigger/filter :event/controller]))
            "Trigger should only fire for controller's lands")
        (is (= 1 (count (:trigger/effects trigger)))
            "Trigger should have one effect")
        (is (= :sacrifice (:effect/type (first (:trigger/effects trigger))))))))

  (testing "City of Traitors has correct mana ability structure"
    (let [abilities (:card/abilities city-of-traitors/card)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :mana (:ability/type ability)))
        (is (true? (get-in ability [:ability/cost :tap])))
        (is (= {:colorless 2} (:ability/produces ability)))))))


;; === Mana production tests ===

(deftest test-cot-produces-colorless-mana
  (testing "City of Traitors produces 2 colorless mana when tapped"
    (let [db (th/create-test-db {:land-plays 2})
          [db' obj-id] (th/add-card-to-zone db :city-of-traitors :battlefield :player-1)
          ;; Get initial mana pool
          initial-mana (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:colorless initial-mana)) "Precondition: 0 colorless mana")
          ;; Activate mana ability (color param ignored for colorless)
          db-after-tap (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)
          final-mana (q/get-mana-pool db-after-tap :player-1)]
      (is (= 2 (:colorless final-mana))
          "City of Traitors should produce 2 colorless mana"))))


;; === Self-entry tests (exclude-self filter) ===

(deftest test-cot-does-not-sacrifice-on-self-entry
  (testing "City of Traitors does NOT sacrifice when it enters the battlefield"
    (let [db (th/create-test-db {:land-plays 2})
          [db' obj-id] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          ;; Trigger is registered at object creation (build-object-tx) — exists while in hand
          ;; The exclude-self filter prevents it from firing for its own entry
          initial-triggers (filter #(= :land-entered (:trigger/event-type %))
                                   (trigger-db/get-all-triggers db'))
          _ (is (= 1 (count initial-triggers))
                "Precondition: CoT trigger registered at object creation (in hand)")
          ;; Play the land
          db-after-play (lands/play-land db' :player-1 obj-id)]
      ;; Verify CoT is on battlefield (not sacrificed)
      (is (= :battlefield (:object/zone (q/get-object db-after-play obj-id)))
          "City of Traitors should be on battlefield after entering")
      ;; Verify no trigger on stack (exclude-self filter should prevent self-entry from triggering)
      (is (= 0 (count (q/get-all-stack-items db-after-play)))
          "No trigger should fire when CoT enters its own land-entered event (exclude-self filter)"))))


;; === Opponent land entry tests (should NOT trigger) ===

(deftest test-cot-does-not-sacrifice-when-opponent-plays-land
  (testing "City of Traitors does NOT sacrifice when opponent plays a land"
    (let [db (th/create-test-db {:land-plays 2})
          db (th/add-opponent db)
          [db cot-id] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          ;; Play CoT
          db (lands/play-land db :player-1 cot-id)
          _ (is (= :battlefield (:object/zone (q/get-object db cot-id)))
                "Precondition: CoT on battlefield")
          ;; Give opponent a land and play it
          [db island-id] (th/add-card-to-zone db :island :hand :player-2)
          ;; Give opponent land plays
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add opp-eid :player/land-plays-left 1]])
          db-after-opp-land (lands/play-land db :player-2 island-id)]
      ;; CoT should NOT trigger
      (is (= 0 (count (q/get-all-stack-items db-after-opp-land)))
          "CoT trigger should NOT fire when opponent plays a land")
      ;; CoT should still be on battlefield
      (is (= :battlefield (:object/zone (q/get-object db-after-opp-land cot-id)))
          "CoT should remain on battlefield"))))


;; === Another land entry tests ===

(deftest test-cot-sacrifices-when-another-land-enters
  (testing "City of Traitors sacrifices when another land enters the battlefield"
    (let [db (th/create-test-db {:land-plays 2})
          [db' cot-id] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          ;; Play CoT first
          db-after-cot (lands/play-land db' :player-1 cot-id)
          _ (is (= :battlefield (:object/zone (q/get-object db-after-cot cot-id)))
                "Precondition: CoT on battlefield")
          ;; Add an Island to hand
          [db'' island-id] (th/add-card-to-zone db-after-cot :island :hand :player-1)
          ;; Play the Island (should trigger CoT sacrifice)
          db-after-island (lands/play-land db'' :player-1 island-id)]
      ;; Verify CoT trigger is on stack
      (is (= 1 (count (q/get-all-stack-items db-after-island)))
          "CoT sacrifice trigger should be on stack")
      ;; Resolve the trigger
      (let [db-after-resolve (:db (resolution/resolve-one-item db-after-island))]
        ;; Verify CoT is now in graveyard
        (is (= :graveyard (:object/zone (q/get-object db-after-resolve cot-id)))
            "City of Traitors should be in graveyard after trigger resolves")
        ;; Verify Island is still on battlefield
        (is (= :battlefield (:object/zone (q/get-object db-after-resolve island-id)))
            "Island should remain on battlefield")))))


;; === Multiple City of Traitors tests ===

(deftest test-two-cots-both-sacrifice-when-land-enters
  (testing "When two City of Traitors are on battlefield and another land enters, both sacrifice"
    (let [db (th/create-test-db {:land-plays 2})
          ;; Give player 3 land plays for this test
          player-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add player-eid :player/land-plays-left 3]])
          ;; Add first CoT to hand and play
          [db' cot-id-1] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          db-after-cot1 (lands/play-land db' :player-1 cot-id-1)
          _ (is (= :battlefield (:object/zone (q/get-object db-after-cot1 cot-id-1)))
                "Precondition: CoT #1 on battlefield")
          ;; Add second CoT to hand and play (this triggers CoT #1's sacrifice)
          [db'' cot-id-2] (th/add-card-to-zone db-after-cot1 :city-of-traitors :hand :player-1)
          db-after-cot2 (lands/play-land db'' :player-1 cot-id-2)]
      ;; First CoT's trigger should be on stack
      (is (= 1 (count (q/get-all-stack-items db-after-cot2)))
          "CoT #1 sacrifice trigger should be on stack when CoT #2 enters")
      ;; Resolve CoT #1's trigger
      (let [db-after-resolve1 (:db (resolution/resolve-one-item db-after-cot2))]
        (is (= :graveyard (:object/zone (q/get-object db-after-resolve1 cot-id-1)))
            "CoT #1 should be sacrificed")
        (is (= :battlefield (:object/zone (q/get-object db-after-resolve1 cot-id-2)))
            "CoT #2 should still be on battlefield")
        ;; Now play an Island - should trigger CoT #2
        (let [[db''' island-id] (th/add-card-to-zone db-after-resolve1 :island :hand :player-1)
              db-after-island (lands/play-land db''' :player-1 island-id)]
          (is (= 1 (count (q/get-all-stack-items db-after-island)))
              "CoT #2 sacrifice trigger should be on stack when Island enters")
          ;; Resolve CoT #2's trigger
          (let [db-final (:db (resolution/resolve-one-item db-after-island))]
            (is (= :graveyard (:object/zone (q/get-object db-final cot-id-2)))
                "CoT #2 should be sacrificed")))))))


;; === Trigger uses stack tests ===

(deftest test-cot-trigger-uses-stack
  (testing "City of Traitors trigger goes on the stack (can respond before sacrifice)"
    (let [db (th/create-test-db {:land-plays 2})
          [db' cot-id] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          ;; Play CoT
          db-after-cot (lands/play-land db' :player-1 cot-id)
          ;; Add Island to hand and play
          [db'' island-id] (th/add-card-to-zone db-after-cot :island :hand :player-1)
          db-after-island (lands/play-land db'' :player-1 island-id)]
      ;; Trigger should be on stack, not resolved yet
      (is (= 1 (count (q/get-all-stack-items db-after-island)))
          "Trigger should be on stack")
      ;; CoT should still be on battlefield (can tap for mana in response)
      (is (= :battlefield (:object/zone (q/get-object db-after-island cot-id)))
          "CoT should still be on battlefield with trigger on stack")
      ;; Can tap for mana before trigger resolves
      (let [db-after-tap (engine-mana/activate-mana-ability db-after-island :player-1 cot-id :colorless)
            final-mana (q/get-mana-pool db-after-tap :player-1)]
        (is (= 2 (:colorless final-mana))
            "Can tap CoT for mana with trigger on stack")))))


;; === Trigger source leaves before resolution ===

(deftest test-cot-trigger-resolves-if-cot-already-gone
  (testing "City of Traitors trigger still tries to resolve if CoT already sacrificed"
    (let [db (th/create-test-db {:land-plays 2})
          [db' cot-id] (th/add-card-to-zone db :city-of-traitors :hand :player-1)
          ;; Play CoT
          db-after-cot (lands/play-land db' :player-1 cot-id)
          ;; Add Island to hand and play (trigger goes on stack)
          [db'' island-id] (th/add-card-to-zone db-after-cot :island :hand :player-1)
          db-after-island (lands/play-land db'' :player-1 island-id)
          _ (is (= 1 (count (q/get-all-stack-items db-after-island)))
                "Precondition: trigger on stack")
          ;; Manually sacrifice CoT (simulating response)
          db-after-sacrifice (zones/move-to-zone* db-after-island cot-id :graveyard)
          _ (is (= :graveyard (:object/zone (q/get-object db-after-sacrifice cot-id)))
                "Precondition: CoT in graveyard before trigger resolves")
          ;; Resolve the trigger - should handle gracefully
          db-after-resolve (:db (resolution/resolve-one-item db-after-sacrifice))
          obj-after (q/get-object db-after-resolve cot-id)]
      ;; CoT still in graveyard (can't sacrifice again)
      (is (= :graveyard (:object/zone obj-after))
          "CoT should remain in graveyard")
      ;; No stack items (trigger resolved)
      (is (= 0 (count (q/get-all-stack-items db-after-resolve)))
          "Stack should be empty after trigger resolves"))))


;; === land-entered event tests ===

(deftest test-land-entered-event-dispatched
  (testing "Playing a land dispatches :land-entered event"
    (let [db (th/create-test-db {:land-plays 2})
          [db' island-id] (th/add-card-to-zone db :island :hand :player-1)
          ;; Create a Datascript trigger that listens for :land-entered
          player-eid (q/get-player-eid db' :player-1)
          tx-data (trigger-db/create-trigger-tx
                    {:trigger/event-type :land-entered
                     :trigger/controller player-eid
                     :trigger/filter {}  ; Match all land-entered events
                     :trigger/uses-stack? true
                     :trigger/effects [{:effect/type :deal-damage
                                        :effect/amount 0
                                        :effect/target :controller}]})
          db'' (d/db-with db' tx-data)
          ;; Play the land
          db-after-play (lands/play-land db'' :player-1 island-id)]
      ;; A trigger should be on stack (proving event was dispatched)
      (is (= 1 (count (q/get-all-stack-items db-after-play)))
          ":land-entered event should be dispatched when land enters"))))


;; === Production path regression: init-game-state + play-land (fizzle-lmro bug fix) ===

(def ^:private cot-deck
  "Minimal deck with City of Traitors and Islands for regression test."
  (into [{:card/id :city-of-traitors :count 1}]
        (repeat 59 {:card/id :island :count 1})))


(deftest test-cot-fires-exactly-once-via-init-game-state
  (testing "City of Traitors sacrifice trigger fires exactly ONCE when second land played (regression: fizzle-lmro duplicate)"
    ;; Use init-game-state to exercise the FULL production path (init → register → play-land)
    ;; This is the key regression test for the duplicate trigger bug.
    (let [app-db (init/init-game-state {:main-deck cot-deck
                                        :must-contain {:city-of-traitors 1 :island 1}})
          game-db (:game/db app-db)
          ;; Grant 2 land plays so we can play both CoT and Island
          player-eid (q/get-player-eid game-db :player-1)
          game-db (d/db-with game-db [[:db/add player-eid :player/land-plays-left 2]])
          ;; Find CoT and Island in hand
          hand (q/get-hand game-db :player-1)
          cot-obj (some (fn [o] (when (= :city-of-traitors (get-in o [:object/card :card/id])) o)) hand)
          island-obj (some (fn [o] (when (= :island (get-in o [:object/card :card/id])) o)) hand)
          _ (is (some? cot-obj) "Precondition: CoT must be in sculpted hand")
          _ (is (some? island-obj) "Precondition: Island must be in sculpted hand")
          cot-id (:object/id cot-obj)
          island-id (:object/id island-obj)
          ;; Play CoT first (should not trigger — exclude-self)
          db-after-cot (lands/play-land game-db :player-1 cot-id)
          _ (is (= :battlefield (:object/zone (q/get-object db-after-cot cot-id)))
                "Precondition: CoT on battlefield after play")
          _ (is (= 0 (count (q/get-all-stack-items db-after-cot)))
                "Precondition: No trigger when CoT enters (exclude-self)")
          ;; Play Island — CoT trigger should fire exactly ONCE
          db-after-island (lands/play-land db-after-cot :player-1 island-id)
          stack-items (q/get-all-stack-items db-after-island)]
      (is (= 1 (count stack-items))
          (str "CoT sacrifice trigger should fire exactly ONCE (not duplicate). "
               "Got " (count stack-items) " stack items. "
               "If 2: duplicate trigger bug — triggers registered multiple times."))
      ;; Resolve the trigger and verify CoT is sacrificed
      (let [db-final (:db (resolution/resolve-one-item db-after-island))]
        (is (= :graveyard (:object/zone (q/get-object db-final cot-id)))
            "CoT should be in graveyard after trigger resolves")
        (is (= 0 (count (q/get-all-stack-items db-final)))
            "Stack should be empty after trigger resolves")))))
