(ns fizzle.cards.multicolor.monstrous-carabid-test
  "Tests for Monstrous Carabid.

   Monstrous Carabid: {3}{B}{R} - 4/4 Creature — Insect
   This creature attacks each combat if able.
   Cycling {B/R} ({B/R}, Discard this card: Draw a card.)

   Ruling (2008-10-01): Cycling is an activated ability. Effects that interact with
   activated abilities (such as Stifle or Rings of Brighthearth) will interact with cycling.
   Effects that interact with spells (such as Remove Soul or Faerie Tauntings) will not.

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — creature enters battlefield
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   I. Cycling ability — two cycling abilities modeling hybrid {B/R}: pay {B} or {R}, draw 1
   G. Edge cases — cycle with empty library, cycling does not increment storm"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.multicolor.monstrous-carabid :as monstrous-carabid]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "This creature attacks each combat if able.\nCycling {B/R}"
(deftest monstrous-carabid-card-definition-test
  (testing "Monstrous Carabid card data has correct values"
    (let [card monstrous-carabid/card]
      (is (= :monstrous-carabid (:card/id card)))
      (is (= "Monstrous Carabid" (:card/name card)))
      (is (= 5 (:card/cmc card)))
      (is (= {:colorless 3 :black 1 :red 1} (:card/mana-cost card)))
      (is (= #{:black :red} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:insect} (:card/subtypes card)))
      (is (= 4 (:card/power card)))
      (is (= 4 (:card/toughness card)))
      (is (= #{:must-attack} (:card/keywords card)))
      (is (= "Monstrous Carabid attacks each combat if able.\nCycling {B/R} ({B/R}, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Monstrous Carabid has 2 cycling abilities modeling hybrid {B/R}"
    (is (= 2 (count (:card/abilities monstrous-carabid/card))))
    (let [[cycling-b cycling-r] (:card/abilities monstrous-carabid/card)]
      (is (= :cycling (:ability/type cycling-b)))
      (is (= :hand (:ability/zone cycling-b)))
      (is (= {:discard-self true :mana {:black 1}} (:ability/cost cycling-b)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-b)))
      (is (= "Cycling {B}" (:ability/description cycling-b)))
      (is (= :cycling (:ability/type cycling-r)))
      (is (= :hand (:ability/zone cycling-r)))
      (is (= {:discard-self true :mana {:red 1}} (:ability/cost cycling-r)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-r)))
      (is (= "Cycling {R}" (:ability/description cycling-r))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: Creature — enters the battlefield on resolution
(deftest monstrous-carabid-enters-battlefield-test
  (testing "Monstrous Carabid enters battlefield as 4/4 with summoning sickness"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj)))
      (is (= 4 (:object/power obj)))
      (is (= 4 (:object/toughness obj)))
      (is (true? (:object/summoning-sick obj))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Mana cost {3}{B}{R}
(deftest monstrous-carabid-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with only colorless mana (missing black and red)"
    (let [db (th/create-test-db {:mana {:colorless 5}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id)))))

  (testing "Cannot cast with only black mana (missing red)"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 2}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest monstrous-carabid-cannot-cast-from-graveyard-test
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Casting a spell increments storm count
(deftest monstrous-carabid-increments-storm-count-test
  (testing "Casting Monstrous Carabid increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 3 :black 1 :red 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)))
      (let [db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1)))))))


;; =====================================================
;; I. Cycling Ability — Hybrid {B/R} modeled as two abilities
;; =====================================================

;; Oracle: "Cycling {B/R} ({B/R}, Discard this card: Draw a card.)"
;; Ability index 0 = {B} cycling, index 1 = {R} cycling
(deftest monstrous-carabid-cycle-with-black-mana-test
  (testing "Cycling from hand with black mana: pay {B}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (nil? (:pending-selection result))
                "Cycling {B}: specific mana only — no mana allocation selection")
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Card should be in graveyard after discard-self cost")
      (is (= 0 (:black (q/get-mana-pool db :player-1)))
          "Black mana should be spent")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")))))


;; Oracle: "Cycling {B/R}" — {R} half of hybrid
(deftest monstrous-carabid-cycle-with-red-mana-test
  (testing "Cycling from hand with red mana: pay {R}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (abilities/activate-ability db :player-1 obj-id 1)
          _ (is (nil? (:pending-selection result))
                "Cycling {R}: specific mana only — no mana allocation selection")
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Card should be in graveyard after discard-self cost")
      (is (= 0 (:red (q/get-mana-pool db :player-1)))
          "Red mana should be spent")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")))))


;; Oracle: Cycling requires mana — cannot cycle without either black or red
(deftest monstrous-carabid-cannot-cycle-without-mana-test
  (testing "Cannot cycle without any mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)]
      (doseq [ability-index [0 1]]
        (let [result (abilities/activate-ability db :player-1 obj-id ability-index)]
          (is (nil? (:pending-selection result))
              (str "No selection when cycling ability " ability-index " fails"))
          (is (= :hand (:object/zone (q/get-object (:db result) obj-id)))
              "Card should remain in hand"))))))


;; Oracle: Cycling requires wrong color — cannot cycle {B} with only red, or {R} with only black
(deftest monstrous-carabid-cannot-cycle-wrong-color-test
  (testing "Cannot use {B} cycling ability with only red mana"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)]
      (is (nil? (:pending-selection result)))
      (is (= :hand (:object/zone (q/get-object (:db result) obj-id))))))

  (testing "Cannot use {R} cycling ability with only black mana"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 1)]
      (is (nil? (:pending-selection result)))
      (is (= :hand (:object/zone (q/get-object (:db result) obj-id)))))))


;; Oracle: Cycling requires card to be in hand — cannot cycle from battlefield
(deftest monstrous-carabid-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :battlefield :player-1)]
      (doseq [ability-index [0 1]]
        (let [result (abilities/activate-ability db :player-1 obj-id ability-index)]
          (is (nil? (:pending-selection result)))
          (is (= :battlefield (:object/zone (q/get-object (:db result) obj-id)))))))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

;; Oracle: "Discard this card: Draw a card." — with empty library, draw fails gracefully
(deftest monstrous-carabid-cycle-with-empty-library-test
  (testing "Cycling with empty library discards but draws nothing"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id))))
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 0 (th/get-hand-count db :player-1))
            "Hand should be empty (discarded 1, drew 0 from empty library)")))))


;; Ruling (2008-10-01): Cycling is an activated ability — does not increment storm
(deftest monstrous-carabid-cycling-does-not-increment-storm-test
  (testing "Cycling does not increment storm count"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          result (abilities/activate-ability db :player-1 obj-id 0)
          db (:db result)]
      (is (= 0 (q/get-storm-count db :player-1))
          "Storm count should remain 0 after cycling activation")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 0 (q/get-storm-count db :player-1))
            "Storm count should remain 0 after cycling resolves")))))


;; Oracle: Hybrid {B/R} — with both colors available, first ability ({B}) is used
;; This tests the UI subscription behavior: cycling-ability-index returns first activatable
(deftest monstrous-carabid-cycle-prefers-first-ability-when-both-available-test
  (testing "With both black and red mana, {B} cycling (index 0) activates successfully"
    (let [db (th/create-test-db {:mana {:black 1 :red 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :monstrous-carabid :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id))))
      (is (= 0 (:black (q/get-mana-pool db :player-1)))
          "Black mana should be spent (first ability)")
      (is (= 1 (:red (q/get-mana-pool db :player-1)))
          "Red mana should be untouched"))))
