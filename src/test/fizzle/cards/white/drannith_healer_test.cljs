(ns fizzle.cards.white.drannith-healer-test
  "Tests for Drannith Healer.

   Drannith Healer: {1}{W} - 2/2 Creature — Human Cleric
   Whenever you cycle another card, you gain 1 life.
   Cycling {1} ({1}, Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — creature enters battlefield
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   I. Cycling ability — pay {1} from hand, discard self, draw 1
   I. Trigger tests — gain 1 life when you cycle another card"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.white.drannith-healer :as drannith-healer]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "Whenever you cycle another card, you gain 1 life.\nCycling {1}"
(deftest drannith-healer-card-definition-test
  (testing "Drannith Healer card data has correct values"
    (let [card drannith-healer/card]
      (is (= :drannith-healer (:card/id card)))
      (is (= "Drannith Healer" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :white 1} (:card/mana-cost card)))
      (is (= #{:white} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:human :cleric} (:card/subtypes card)))
      (is (= 2 (:card/power card)))
      (is (= 2 (:card/toughness card)))
      (is (= "Whenever you cycle another card, you gain 1 life.\nCycling {1} ({1}, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Drannith Healer has exactly 1 cycling ability"
    (is (= 1 (count (:card/abilities drannith-healer/card))))
    (let [cycling-ability (first (:card/abilities drannith-healer/card))]
      (is (= :cycling (:ability/type cycling-ability)))
      (is (= :hand (:ability/zone cycling-ability)))
      (is (= {:discard-self true :mana {:colorless 1}} (:ability/cost cycling-ability)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-ability)))))

  (testing "Drannith Healer has exactly 1 card-cycled trigger with self-controller filter"
    (is (= 1 (count (:card/triggers drannith-healer/card))))
    (let [trigger (first (:card/triggers drannith-healer/card))]
      (is (= :card-cycled (:trigger/type trigger)))
      (is (= {:event/controller :self-controller} (:trigger/filter trigger)))
      (is (= [{:effect/type :gain-life :effect/amount 1}] (:trigger/effects trigger))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: Creature — enters the battlefield on resolution
(deftest drannith-healer-enters-battlefield-test
  (testing "Drannith Healer enters battlefield as 2/2 with summoning sickness"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj)))
      (is (= 2 (:object/power obj)))
      (is (= 2 (:object/toughness obj)))
      (is (true? (:object/summoning-sick obj))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Mana cost {1}{W}
(deftest drannith-healer-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with only one white mana (missing colorless)"
    (let [db (th/create-test-db {:mana {:white 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest drannith-healer-cannot-cast-from-graveyard-test
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-healer :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Casting a spell increments storm count
(deftest drannith-healer-increments-storm-count-test
  (testing "Casting Drannith Healer increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)))
      (let [db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1)))))))


;; =====================================================
;; I. Cycling Ability
;; =====================================================

;; Oracle: "Cycling {1} ({1}, Discard this card: Draw a card.)"
;; Path: activate-ability (index 0) → confirm mana allocation → resolve-top → draw
(deftest drannith-healer-can-cycle-from-hand-test
  (testing "Cycling from hand: pay {1}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (some? (:pending-selection result)) "Cycling {1}: mana allocation selection expected")
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)]
      ;; Card should be in graveyard after paying discard-self cost
      (is (= :graveyard (:object/zone (q/get-object db-after-discard obj-id)))
          "Card should be in graveyard after discard-self cost")
      ;; Confirm mana allocation and resolve the draw effect
      (let [alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
            {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
            {:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")
        (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
            "Cycling cost should be paid")))))


;; Oracle: Cycling requires card to be in hand — cannot cycle without mana
(deftest drannith-healer-cannot-cycle-without-mana-test
  (testing "Cannot cycle without sufficient mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result)) "No selection when activation fails")
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails"))))


;; Oracle: Cycling requires card to be in hand — cannot cycle from battlefield
(deftest drannith-healer-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-healer :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result)) "No selection when activation fails")
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "Card should remain on battlefield when cycling fails"))))


;; =====================================================
;; I. Trigger: "Whenever you cycle another card, you gain 1 life."
;; =====================================================

;; Helper: set up db with DH on battlefield, another cycling card in hand,
;; mana available for cycling.
;; Returns {:db db :dh-id dh-id :cycle-id cycle-id}
(defn- setup-dh-trigger-test
  "Cast DH, add another cycling card to hand with mana for cycling.
   The cycle card is Imposing Vantasaur ({5}{W} creature with cycling {1}).
   Mana: create with enough for cast ({1}{W}), then re-add {1} for cycling cost."
  []
  (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
        [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
        [db dh-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
        ;; Cast DH — puts it on battlefield and registers triggers
        db (th/cast-and-resolve db :player-1 dh-id)
        ;; Add another cycling card and mana for cycling cost
        [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
        p1-eid (q/get-player-eid db :player-1)
        ;; Re-add mana for cycling cost {1} (pool was drained by casting DH)
        db (d/db-with db [[:db/add p1-eid :player/mana-pool
                           {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 1}]])]
    {:db db :dh-id dh-id :cycle-id cycle-id}))


;; Oracle: "Whenever you cycle another card, you gain 1 life."
(deftest drannith-healer-trigger-gain-life-test
  (testing "Cycling another card while DH is on battlefield gains 1 life"
    (let [{:keys [db dh-id cycle-id]} (setup-dh-trigger-test)
          life-before (q/get-life-total db :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db dh-id)))
                "Precondition: DH on battlefield")
          ;; Activate cycling on Imposing Vantasaur
          result (abilities/activate-ability db :player-1 cycle-id 0)
          _ (is (some? (:pending-selection result)) "Precondition: mana allocation selection expected")
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          ;; Confirm mana allocation — also fires DH trigger (card-cycled event)
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Stack now has: [cycling-ability (bottom), DH-trigger (top)]
          ;; resolve-top → DH trigger → gain 1 life
          {:keys [db]} (th/resolve-top db)
          life-after-trigger (q/get-life-total db :player-1)
          ;; resolve-top → cycling ability → draw 1 card
          {:keys [db]} (th/resolve-top db)]
      (is (= (+ life-before 1) life-after-trigger)
          "Should gain 1 life after DH trigger resolves")
      ;; Final life total unchanged from trigger result (draw doesn't change life)
      (is (= (+ life-before 1) (q/get-life-total db :player-1))
          "Life total should be +1 after full resolution"))))


;; Oracle: "Whenever you cycle another card, you gain 1 life." — stacks per cycling event
(deftest drannith-healer-trigger-multiple-cycles-test
  (testing "Cycling two cards while DH is on battlefield gains 1 life each time"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-1)
          [db dh-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
          db (th/cast-and-resolve db :player-1 dh-id)
          ;; Add two Imposing Vantasaurs (both cycle {1}) with mana for both
          [db cycle-id-1] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          [db cycle-id-2] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          p1-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add p1-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 2}]])
          life-before (q/get-life-total db :player-1)
          ;; --- Cycle first card ---
          result-1 (abilities/activate-ability db :player-1 cycle-id-1 0)
          db-a (:db result-1)
          alloc-sel-1 (:pending-selection result-1)
          alloc-1 (assoc alloc-sel-1 :selection/allocation (th/auto-compute-mana-allocation alloc-sel-1))
          {:keys [db]} (th/confirm-selection db-a alloc-1 #{})
          {:keys [db]} (th/resolve-top db)                 ; DH trigger → +1 life
          life-after-first (q/get-life-total db :player-1)
          {:keys [db]} (th/resolve-top db)                 ; cycling draw
          ;; --- Cycle second card (still has 1 colorless mana in pool) ---
          result-2 (abilities/activate-ability db :player-1 cycle-id-2 0)
          db-b (:db result-2)
          alloc-sel-2 (:pending-selection result-2)
          alloc-2 (assoc alloc-sel-2 :selection/allocation (th/auto-compute-mana-allocation alloc-sel-2))
          {:keys [db]} (th/confirm-selection db-b alloc-2 #{})
          {:keys [db]} (th/resolve-top db)                 ; DH trigger → +1 life
          life-after-second (q/get-life-total db :player-1)
          _ (th/resolve-top db)]                           ; cycling draw (result unused)
      (is (= (+ life-before 1) life-after-first)
          "Should gain 1 life after first cycling trigger")
      (is (= (+ life-before 2) life-after-second)
          "Should gain 2 life total after two cycling triggers"))))


;; Oracle: "Whenever you cycle another card" — trigger only fires when DH is on battlefield
(deftest drannith-healer-no-trigger-when-not-on-battlefield-test
  (testing "No life gain when DH is in hand instead of on battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          ;; DH is in hand, NOT cast — triggers not registered
          [db _dh-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
          [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          life-before (q/get-life-total db :player-1)
          ;; Cycle Imposing Vantasaur — only 1 card on stack (cycling ability, no trigger)
          result (abilities/activate-ability db :player-1 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Only one stack item: the cycling ability itself
          stack-items (q/get-all-stack-items db)
          {:keys [db]} (th/resolve-top db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability should be on stack — no DH trigger")
      (is (= life-before (q/get-life-total db :player-1))
          "Life total should be unchanged — DH not on battlefield"))))


;; Oracle: "Whenever you cycle another card" — filter {:event/controller :self-controller}
;; means opponent cycling does NOT trigger DH owned by player-1
(deftest drannith-healer-no-trigger-opponent-cycles-test
  (testing "Opponent cycling a card does not trigger Drannith Healer"
    (let [db (th/create-test-db {:mana {:colorless 1 :white 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-2)
          ;; Cast DH as player-1 — registers trigger filtered to player-1 controller
          [db dh-id] (th/add-card-to-zone db :drannith-healer :hand :player-1)
          db (th/cast-and-resolve db :player-1 dh-id)
          ;; Add cycling card to player-2's hand and mana for player-2
          [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-2)
          p2-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add p2-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 1}]])
          life-before (q/get-life-total db :player-1)
          ;; Player-2 cycles Imposing Vantasaur
          result (abilities/activate-ability db :player-2 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Only cycling ability on stack — no DH trigger (event/controller = player-2, filter = player-1)
          stack-items (q/get-all-stack-items db)
          {:keys [db]} (th/resolve-top db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability should be on stack — DH trigger filtered out for opponent")
      (is (= life-before (q/get-life-total db :player-1))
          "Player-1 life should be unchanged when opponent cycles"))))
