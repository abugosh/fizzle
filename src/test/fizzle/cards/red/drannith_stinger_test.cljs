(ns fizzle.cards.red.drannith-stinger-test
  "Tests for Drannith Stinger.

   Drannith Stinger: {1}{R} - 2/2 Creature — Human Wizard
   Whenever you cycle another card, this creature deals 1 damage to each opponent.
   Cycling {1} ({1}, Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — creature enters battlefield
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   I. Cycling ability — pay {1} from hand, discard self, draw 1
   I. Trigger tests — deal 1 damage to each opponent when you cycle another card"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.drannith-stinger :as drannith-stinger]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "Whenever you cycle another card, this creature deals 1 damage to each opponent.\nCycling {1}"
(deftest drannith-stinger-card-definition-test
  (testing "Drannith Stinger card data has correct values"
    (let [card drannith-stinger/card]
      (is (= :drannith-stinger (:card/id card)))
      (is (= "Drannith Stinger" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :red 1} (:card/mana-cost card)))
      (is (= #{:red} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:human :wizard} (:card/subtypes card)))
      (is (= 2 (:card/power card)))
      (is (= 2 (:card/toughness card)))
      (is (= "Whenever you cycle another card, Drannith Stinger deals 1 damage to each opponent.\nCycling {1} ({1}, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Drannith Stinger has exactly 1 cycling ability"
    (is (= 1 (count (:card/abilities drannith-stinger/card))))
    (let [cycling-ability (first (:card/abilities drannith-stinger/card))]
      (is (= :cycling (:ability/type cycling-ability)))
      (is (= :hand (:ability/zone cycling-ability)))
      (is (= {:discard-self true :mana {:colorless 1}} (:ability/cost cycling-ability)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-ability)))))

  (testing "Drannith Stinger has exactly 1 card-cycled trigger with self-controller filter"
    (is (= 1 (count (:card/triggers drannith-stinger/card))))
    (let [trigger (first (:card/triggers drannith-stinger/card))]
      (is (= :card-cycled (:trigger/type trigger)))
      (is (= {:event/controller :self-controller} (:trigger/filter trigger)))
      (is (= [{:effect/type :deal-damage :effect/amount 1 :effect/target :opponent}]
             (:trigger/effects trigger))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: Creature — enters the battlefield on resolution
(deftest drannith-stinger-enters-battlefield-test
  (testing "Drannith Stinger enters battlefield as 2/2 with summoning sickness"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj)))
      (is (= 2 (:object/power obj)))
      (is (= 2 (:object/toughness obj)))
      (is (true? (:object/summoning-sick obj))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Mana cost {1}{R}
(deftest drannith-stinger-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with only one red mana (missing colorless)"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest drannith-stinger-cannot-cast-from-graveyard-test
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Casting a spell increments storm count
(deftest drannith-stinger-increments-storm-count-test
  (testing "Casting Drannith Stinger increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)))
      (let [db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1)))))))


;; =====================================================
;; I. Cycling Ability
;; =====================================================

;; Oracle: "Cycling {1} ({1}, Discard this card: Draw a card.)"
;; Path: activate-ability (index 0) → confirm mana allocation → resolve-top → draw
(deftest drannith-stinger-can-cycle-from-hand-test
  (testing "Cycling from hand: pay {1}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (some? (:pending-selection result)) "Cycling {1}: mana allocation selection expected")
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)]
      (is (= :graveyard (:object/zone (q/get-object db-after-discard obj-id)))
          "Card should be in graveyard after discard-self cost")
      (let [alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
            {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
            {:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")
        (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
            "Cycling cost should be paid")))))


;; Oracle: Cycling requires card to be in hand — cannot cycle without mana
(deftest drannith-stinger-cannot-cycle-without-mana-test
  (testing "Cannot cycle without sufficient mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result)) "No selection when activation fails")
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails"))))


;; Oracle: Cycling requires card to be in hand — cannot cycle from battlefield
(deftest drannith-stinger-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :drannith-stinger :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result)) "No selection when activation fails")
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "Card should remain on battlefield when cycling fails"))))


;; =====================================================
;; I. Trigger: "Whenever you cycle another card, this creature
;;              deals 1 damage to each opponent."
;; =====================================================

(defn- setup-ds-trigger-test
  "Cast Drannith Stinger, add another cycling card to hand with mana for cycling.
   Uses Imposing Vantasaur ({5}{W} creature with cycling {1}).
   Returns {:db db :ds-id ds-id :cycle-id cycle-id}"
  []
  (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
        db (th/add-opponent db)
        [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
        [db ds-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
        db (th/cast-and-resolve db :player-1 ds-id)
        [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
        p1-eid (q/get-player-eid db :player-1)
        db (d/db-with db [[:db/add p1-eid :player/mana-pool
                           {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 1}]])]
    {:db db :ds-id ds-id :cycle-id cycle-id}))


;; Oracle: "Whenever you cycle another card, this creature deals 1 damage to each opponent."
(deftest drannith-stinger-trigger-deal-damage-test
  (testing "Cycling another card while DS is on battlefield deals 1 damage to opponent"
    (let [{:keys [db ds-id cycle-id]} (setup-ds-trigger-test)
          opp-life-before (q/get-life-total db :player-2)
          _ (is (= :battlefield (:object/zone (q/get-object db ds-id)))
                "Precondition: DS on battlefield")
          result (abilities/activate-ability db :player-1 cycle-id 0)
          _ (is (some? (:pending-selection result)) "Precondition: mana allocation selection expected")
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Stack: [cycling-ability (bottom), DS-trigger (top)]
          ;; resolve-top → DS trigger → deal 1 damage to opponent
          {:keys [db]} (th/resolve-top db)
          opp-life-after-trigger (q/get-life-total db :player-2)
          ;; resolve-top → cycling ability → draw 1 card
          {:keys [db]} (th/resolve-top db)]
      (is (= (- opp-life-before 1) opp-life-after-trigger)
          "Opponent should lose 1 life after DS trigger resolves")
      (is (= (- opp-life-before 1) (q/get-life-total db :player-2))
          "Opponent life total should be -1 after full resolution"))))


;; Oracle: "Whenever you cycle another card" — stacks per cycling event
(deftest drannith-stinger-trigger-multiple-cycles-test
  (testing "Cycling two cards while DS is on battlefield deals 1 damage each time"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-1)
          [db ds-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
          db (th/cast-and-resolve db :player-1 ds-id)
          [db cycle-id-1] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          [db cycle-id-2] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          p1-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add p1-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 2}]])
          opp-life-before (q/get-life-total db :player-2)
          ;; --- Cycle first card ---
          result-1 (abilities/activate-ability db :player-1 cycle-id-1 0)
          db-a (:db result-1)
          alloc-sel-1 (:pending-selection result-1)
          alloc-1 (assoc alloc-sel-1 :selection/allocation (th/auto-compute-mana-allocation alloc-sel-1))
          {:keys [db]} (th/confirm-selection db-a alloc-1 #{})
          {:keys [db]} (th/resolve-top db)                 ; DS trigger → -1 life
          opp-life-after-first (q/get-life-total db :player-2)
          {:keys [db]} (th/resolve-top db)                 ; cycling draw
          ;; --- Cycle second card ---
          result-2 (abilities/activate-ability db :player-1 cycle-id-2 0)
          db-b (:db result-2)
          alloc-sel-2 (:pending-selection result-2)
          alloc-2 (assoc alloc-sel-2 :selection/allocation (th/auto-compute-mana-allocation alloc-sel-2))
          {:keys [db]} (th/confirm-selection db-b alloc-2 #{})
          {:keys [db]} (th/resolve-top db)                 ; DS trigger → -1 life
          opp-life-after-second (q/get-life-total db :player-2)
          _ (th/resolve-top db)]                           ; cycling draw
      (is (= (- opp-life-before 1) opp-life-after-first)
          "Opponent should lose 1 life after first cycling trigger")
      (is (= (- opp-life-before 2) opp-life-after-second)
          "Opponent should lose 2 life total after two cycling triggers"))))


;; Oracle: "Whenever you cycle another card" — trigger only fires when DS is on battlefield
(deftest drannith-stinger-no-trigger-when-not-on-battlefield-test
  (testing "No damage when DS is in hand instead of on battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _ds-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
          [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          opp-life-before (q/get-life-total db :player-2)
          result (abilities/activate-ability db :player-1 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          stack-items (q/get-all-stack-items db)
          {:keys [db]} (th/resolve-top db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability should be on stack — no DS trigger")
      (is (= opp-life-before (q/get-life-total db :player-2))
          "Opponent life should be unchanged — DS not on battlefield"))))


;; Oracle: "Whenever you cycle another card" — filter {:event/controller :self-controller}
;; means opponent cycling does NOT trigger DS owned by player-1
(deftest drannith-stinger-no-trigger-opponent-cycles-test
  (testing "Opponent cycling a card does not trigger Drannith Stinger"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-2)
          [db ds-id] (th/add-card-to-zone db :drannith-stinger :hand :player-1)
          db (th/cast-and-resolve db :player-1 ds-id)
          [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-2)
          p2-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add p2-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 1}]])
          opp-life-before (q/get-life-total db :player-2)
          result (abilities/activate-ability db :player-2 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          stack-items (q/get-all-stack-items db)
          {:keys [db]} (th/resolve-top db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability should be on stack — DS trigger filtered out for opponent")
      (is (= opp-life-before (q/get-life-total db :player-2))
          "Opponent life should be unchanged when they cycle their own card"))))
