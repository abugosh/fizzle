(ns fizzle.cards.black.necrologia-test
  "Tests for Necrologia card definition and resolution flow.

   Necrologia: 3BB - Instant
   Cast this spell only during your end step.
   As an additional cost to cast this spell, pay X life.
   Draw X cards.

   Tests verify:
   - Card definition (type, cost, effects, cast restriction, additional costs)
   - Cast-resolve happy path (at end step with pay-x-life selection)
   - Cannot-cast guards (wrong phase, insufficient mana)
   - Storm count increments
   - Selection tests (accumulator for X life)
   - Edge cases (X=0 draws nothing, X=full life)"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.cards.black.necrologia :as necrologia]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.casting :as casting]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.test-helpers :as th]))


;; === Helpers ===

(defn set-phase
  "Change the game phase in the db."
  [db new-phase]
  (let [game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)]
    (d/db-with db [[:db/add game-eid :game/phase new-phase]])))


;; === A. Card Definition Tests ===

(deftest necrologia-card-definition-test
  (testing "Necrologia card has all required fields"
    (let [card necrologia/card]
      (is (= :necrologia (:card/id card)))
      (is (= "Necrologia" (:card/name card)))
      (is (= 5 (:card/cmc card)))
      (is (= {:colorless 3 :black 2} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "Cast this spell only during your end step. As an additional cost to cast this spell, pay X life. Draw X cards."
             (:card/text card)))))

  (testing "Cast restriction requires end step"
    (let [restriction (:card/cast-restriction necrologia/card)]
      (is (= :end (:restriction/phase restriction)))))

  (testing "Additional cost is :pay-x-life"
    (let [costs (:card/additional-costs necrologia/card)]
      (is (= 1 (count costs)))
      (is (= :pay-x-life (:cost/type (first costs))))))

  (testing "Effect is :draw with dynamic :chosen-x amount"
    (let [effect (first (:card/effects necrologia/card))]
      (is (= :draw (:effect/type effect)))
      (is (= {:dynamic/type :chosen-x} (:effect/amount effect))))))


;; === B. Cast-Resolve Happy Path ===

(deftest necrologia-cast-resolve-happy-path-test
  (testing "Cast during end step, pay 3 life, draw 3 cards"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3} :life 20})
          [db _lib-ids] (th/add-cards-to-library
                          db [:dark-ritual :dark-ritual :dark-ritual
                              :dark-ritual :dark-ritual]
                          :player-1)
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)
          initial-hand (th/get-hand-count db :player-1)]
      ;; Verify can-cast? passes during end step
      (is (true? (rules/can-cast? db :player-1 obj-id)))
      ;; Pre-cast pipeline should produce pay-x-life selection
      (let [mode (first (rules/get-casting-modes db :player-1 obj-id))]
        (is (= :primary (:mode/id mode)))
        (is (sel-costs/has-pay-x-life-cost? mode))
        ;; Build selection
        (let [sel (sel-costs/build-pay-x-life-selection db :player-1 obj-id mode)]
          (is (= :pay-x-life (:selection/type sel)))
          (is (= :accumulator (:selection/pattern sel)))
          (is (= 20 (:selection/max-x sel)))
          (is (= 0 (:selection/selected-x sel)))
          ;; Confirm with X=3
          (let [sel-with-x (assoc sel :selection/selected-x 3)
                result (th/confirm-selection db sel-with-x #{})
                db' (:db result)]
            ;; Life should be reduced by 3
            (is (= 17 (q/get-life-total db' :player-1)))
            ;; Spell should be on stack
            (is (= :stack (th/get-object-zone db' obj-id)))
            ;; Verify chosen-x stored on stack item
            (let [obj-eid (q/get-object-eid db' obj-id)
                  si (stack/get-stack-item-by-object-ref db' obj-eid)]
              (is (= 3 (:stack-item/chosen-x si))))
            ;; Resolve and verify draw
            (let [resolve-result (th/resolve-top db')
                  db'' (:db resolve-result)]
              ;; Should have drawn 3 cards
              (is (= (+ initial-hand 2) (th/get-hand-count db'' :player-1))
                  "Should have drawn 3 cards (minus the 1 Necrologia cast)")
              ;; Necrologia should be in graveyard (instant)
              (is (= :graveyard (th/get-object-zone db'' obj-id))))))))))


;; === C. Cannot-Cast Guards ===

(deftest necrologia-cannot-cast-wrong-phase-test
  (testing "Cannot cast during main phase"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3}})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)]
      ;; Phase is :main1 by default
      (is (false? (rules/can-cast? db :player-1 obj-id)))))

  (testing "Cannot cast during combat"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3}})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :combat)]
      (is (false? (rules/can-cast? db :player-1 obj-id)))))

  (testing "Cannot cast during upkeep"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3}})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :upkeep)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest necrologia-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with insufficient mana"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; === D. Storm Count ===

(deftest necrologia-increments-storm-count-test
  (testing "Casting Necrologia increments storm count"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3}})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)
          initial-storm (q/get-storm-count db :player-1)
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          sel (sel-costs/build-pay-x-life-selection db :player-1 obj-id mode)
          sel-with-x (assoc sel :selection/selected-x 0)
          result (th/confirm-selection db sel-with-x #{})
          db' (:db result)]
      (is (= (inc initial-storm) (q/get-storm-count db' :player-1))))))


;; === E. Selection Tests ===

(deftest necrologia-pay-x-life-selection-builds-correctly-test
  (testing "Pay-x-life selection has correct accumulator params"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3} :life 15})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          sel (sel-costs/build-pay-x-life-selection db :player-1 obj-id mode)]
      (is (= :pay-x-life (:selection/type sel)))
      (is (= :accumulator (:selection/pattern sel)))
      (is (= :finalized (:selection/lifecycle sel)))
      (is (= 15 (:selection/max-x sel))
          "Max X should equal current life")
      (is (= 0 (:selection/selected-x sel))
          "Default X should be 0")
      (is (= :always (:selection/validation sel)))
      (is (false? (:selection/auto-confirm? sel))))))


;; === G. Edge Cases ===

(deftest necrologia-x-zero-draws-nothing-test
  (testing "X=0 pays 0 life and draws 0 cards"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3} :life 20})
          [db _lib-ids] (th/add-cards-to-library
                          db [:dark-ritual :dark-ritual :dark-ritual]
                          :player-1)
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)
          initial-hand (th/get-hand-count db :player-1)
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          sel (sel-costs/build-pay-x-life-selection db :player-1 obj-id mode)
          sel-with-x (assoc sel :selection/selected-x 0)
          result (th/confirm-selection db sel-with-x #{})
          db' (:db result)]
      ;; Life unchanged (paid 0)
      (is (= 20 (q/get-life-total db' :player-1)))
      ;; Resolve spell
      (let [resolve-result (th/resolve-top db')
            db'' (:db resolve-result)]
        ;; Hand count should decrease by 1 (Necrologia cast, 0 drawn)
        (is (= (dec initial-hand) (th/get-hand-count db'' :player-1))
            "X=0 should draw 0 cards")))))


(deftest necrologia-x-full-life-test
  (testing "X=20 pays full life and draws 20 cards"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3} :life 20})
          ;; Add 25 cards to library (more than 20)
          [db _lib-ids] (th/add-cards-to-library
                          db (vec (repeat 25 :dark-ritual))
                          :player-1)
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)
          initial-hand (th/get-hand-count db :player-1)
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          sel (sel-costs/build-pay-x-life-selection db :player-1 obj-id mode)
          sel-with-x (assoc sel :selection/selected-x 20)
          result (th/confirm-selection db sel-with-x #{})
          db' (:db result)]
      ;; Life should be 0
      (is (= 0 (q/get-life-total db' :player-1)))
      ;; Resolve spell
      (let [resolve-result (th/resolve-top db')
            db'' (:db resolve-result)]
        ;; Should have drawn 20 cards (minus 1 for Necrologia cast)
        (is (= (+ initial-hand 19) (th/get-hand-count db'' :player-1))
            "Should have drawn 20 cards minus the Necrologia itself")))))


(deftest necrologia-pre-cast-pipeline-produces-selection-test
  (testing "Pre-cast pipeline correctly triggers pay-x-life selection"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3} :life 20})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)
          db (set-phase db :end)
          mode (first (rules/get-casting-modes db :player-1 obj-id))
          ctx {:game-db db :player-id :player-1 :object-id obj-id :mode mode :target nil}
          result (casting/evaluate-pre-cast-step :pay-x-life ctx)]
      (is (map? result) "Pipeline should return a result for pay-x-life")
      (is (= :pay-x-life (:selection/type (:selection result))) "Result should contain a pay-x-life selection"))))


(deftest necrologia-castable-only-during-end-step-test
  (testing "Necrologia is an instant but restricted to end step"
    (let [db (th/create-test-db {:mana {:black 2 :colorless 3}})
          [db obj-id] (th/add-card-to-zone db :necrologia :hand :player-1)]
      ;; Cannot cast during any phase except :end
      (doseq [phase [:main1 :main2 :combat :draw]]
        (let [db' (set-phase db phase)]
          (is (false? (rules/can-cast? db' :player-1 obj-id))
              (str "Should not be castable during " (name phase)))))
      ;; CAN cast during :end
      (let [db' (set-phase db :end)]
        (is (true? (rules/can-cast? db' :player-1 obj-id))
            "Must be castable during end step")))))
