(ns fizzle.cards.black.horror-of-the-broken-lands-test
  "Tests for Horror of the Broken Lands.

   Horror of the Broken Lands: {4}{B} - 4/4 Creature — Horror
   Whenever you cycle or discard another card, Horror of the Broken Lands gets +2/+1 until end of turn.
   Cycling {B} ({B}, Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — creature enters battlefield
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   I. Cycling ability — pay {B} from hand, discard self, draw 1 (no mana-allocation selection)
   I. Trigger tests — get +2/+1 when you cycle or discard another card"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.black.horror-of-the-broken-lands :as horror-of-the-broken-lands]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

;; Oracle: "Whenever you cycle or discard another card, Horror of the Broken Lands gets +2/+1 until end of turn.\nCycling {B}"
(deftest horror-of-the-broken-lands-card-definition-test
  (testing "Horror of the Broken Lands card data has correct values"
    (let [card horror-of-the-broken-lands/card]
      (is (= :horror-of-the-broken-lands (:card/id card)))
      (is (= "Horror of the Broken Lands" (:card/name card)))
      (is (= 5 (:card/cmc card)))
      (is (= {:colorless 4 :black 1} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:creature} (:card/types card)))
      (is (= #{:horror} (:card/subtypes card)))
      (is (= 4 (:card/power card)))
      (is (= 4 (:card/toughness card)))
      (is (= "Whenever you cycle or discard another card, Horror of the Broken Lands gets +2/+1 until end of turn.\nCycling {B} ({B}, Discard this card: Draw a card.)"
             (:card/text card)))))

  (testing "Horror of the Broken Lands has exactly 1 cycling ability"
    (is (= 1 (count (:card/abilities horror-of-the-broken-lands/card))))
    (let [cycling-ability (first (:card/abilities horror-of-the-broken-lands/card))]
      (is (= :cycling (:ability/type cycling-ability)))
      (is (= :hand (:ability/zone cycling-ability)))
      (is (= {:discard-self true :mana {:black 1}} (:ability/cost cycling-ability)))
      (is (= [{:effect/type :draw :effect/amount 1}] (:ability/effects cycling-ability)))
      (is (= "Cycling {B}" (:ability/description cycling-ability)))))

  (testing "Horror of the Broken Lands has exactly 1 card-discarded trigger with dual filter"
    (is (= 1 (count (:card/triggers horror-of-the-broken-lands/card))))
    (let [trigger (first (:card/triggers horror-of-the-broken-lands/card))]
      (is (= :card-discarded (:trigger/type trigger)))
      (is (= {:exclude-self true :event/controller :self-controller} (:trigger/filter trigger)))
      (is (= [{:effect/type :apply-pt-modifier
               :effect/target :self
               :effect/power 2
               :effect/toughness 1}]
             (:trigger/effects trigger))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: Creature — enters the battlefield on resolution
(deftest horror-of-the-broken-lands-enters-battlefield-test
  (testing "Horror of the Broken Lands enters battlefield as 4/4 with summoning sickness"
    (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)
          obj (q/get-object db obj-id)]
      (is (= :battlefield (:object/zone obj)))
      (is (= 4 (:object/power obj)))
      (is (= 4 (:object/toughness obj)))
      (is (true? (:object/summoning-sick obj))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Mana cost {4}{B}
(deftest horror-of-the-broken-lands-cannot-cast-insufficient-mana-test
  (testing "Cannot cast with only 4 colorless (missing black)"
    (let [db (th/create-test-db {:mana {:colorless 4}})
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest horror-of-the-broken-lands-cannot-cast-from-graveyard-test
  (testing "Cannot cast from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Casting a spell increments storm count
(deftest horror-of-the-broken-lands-increments-storm-count-test
  (testing "Casting Horror of the Broken Lands increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)))
      (let [db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1)))))))


;; =====================================================
;; I. Cycling Ability
;; =====================================================

;; Oracle: "Cycling {B} ({B}, Discard this card: Draw a card.)"
;; Path: activate-ability (index 0) → no mana allocation (specific {B} only) → resolve-top → draw
;; Note: {B} cycling has no :colorless generic cost, so no mana-allocation selection is produced.
(deftest horror-of-the-broken-lands-can-cycle-from-hand-test
  (testing "Cycling from hand: pay {B}, discard self, draw 1 — no mana-allocation selection"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (abilities/activate-ability db :player-1 obj-id 0)
          _ (is (nil? (:pending-selection result))
                "Cycling {B}: specific mana only — no mana allocation selection expected")
          db (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Card should be in graveyard after discard-self cost")
      (is (= 0 (:black (q/get-mana-pool db :player-1)))
          "Black mana should be spent")
      (let [{:keys [db]} (th/resolve-top db)]
        (is (= 1 (th/get-hand-count db :player-1))
            "Should have drawn 1 card (net 0: discarded 1, drew 1)")))))


;; Oracle: Cycling requires card to be in hand — cannot cycle without mana
(deftest horror-of-the-broken-lands-cannot-cycle-without-mana-test
  (testing "Cannot cycle without sufficient mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result)) "No selection when activation fails")
      (is (= :hand (:object/zone (q/get-object result-db obj-id)))
          "Card should remain in hand when cycling fails"))))


;; Oracle: Cycling requires card to be in hand — cannot cycle from battlefield
(deftest horror-of-the-broken-lands-cannot-cycle-from-battlefield-test
  (testing "Cannot cycle from battlefield"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :horror-of-the-broken-lands :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)
          result-db (:db result)]
      (is (nil? (:pending-selection result)) "No selection when activation fails")
      (is (= :battlefield (:object/zone (q/get-object result-db obj-id)))
          "Card should remain on battlefield when cycling fails"))))


;; =====================================================
;; I. Trigger: "Whenever you cycle or discard another card,
;;              Horror of the Broken Lands gets +2/+1 until end of turn."
;; =====================================================

;; Helper: Cast Horror, add another cycling card with specific {B} mana for cycling.
;; Street Wraith cycles via pay-life (no mana needed), making it easy to trigger Horror.
;; But instead, we use Imposing Vantasaur (cycling {1}) to cycle another card.
;; Actually simpler: use duress (no cycling) or just directly discard a card.
;; Best approach: cycle Imposing Vantasaur from hand (has cycling {1}, costs colorless).
;; Returns {:db db :horror-id horror-id :cycle-id cycle-id}
(defn- setup-horror-trigger-test
  "Cast Horror, add another cycling card to hand with mana for cycling.
   Uses Imposing Vantasaur ({5}{W} creature with cycling {1}).
   Returns {:db db :horror-id horror-id :cycle-id cycle-id}"
  []
  (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
        [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
        [db horror-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
        db (th/cast-and-resolve db :player-1 horror-id)
        [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
        p1-eid (q/get-player-eid db :player-1)
        db (d/db-with db [[:db/add p1-eid :player/mana-pool
                           {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 1}]])]
    {:db db :horror-id horror-id :cycle-id cycle-id}))


;; Oracle: "Whenever you cycle or discard another card, ...gets +2/+1 until end of turn."
;; Cycling another card triggers Horror's :card-discarded trigger (discard-self fires hand→graveyard).
(deftest horror-of-the-broken-lands-trigger-cycle-pumps-test
  (testing "Cycling another card while Horror is on battlefield pumps it +2/+1"
    (let [{:keys [db horror-id cycle-id]} (setup-horror-trigger-test)
          _ (is (= :battlefield (:object/zone (q/get-object db horror-id)))
                "Precondition: Horror on battlefield")
          _ (is (= 4 (creatures/effective-power db horror-id)) "Precondition: base power 4")
          _ (is (= 4 (creatures/effective-toughness db horror-id)) "Precondition: base toughness 4")
          ;; Cycle Imposing Vantasaur (cycling {1} — generic, uses mana allocation)
          result (abilities/activate-ability db :player-1 cycle-id 0)
          _ (is (= :allocate-resource (:selection/mechanism (:pending-selection result))) "Cycling {1}: mana-allocation selection expected")
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          ;; :card-discarded fires during discard-self cost (before cycling stack item created).
          ;; Horror trigger goes on stack at pos 0. Then cycling item is created at pos 1.
          ;; Confirm mana allocation → creates cycling stack item at pos 1 (top)
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Stack: [Horror trigger (pos 0, bottom), cycling-ability (pos 1, top)]
          ;; resolve-top → cycling ability → draw 1 card (Horror trigger still pending)
          {:keys [db]} (th/resolve-top db)
          ;; resolve-top → Horror trigger → +2/+1
          {:keys [db]} (th/resolve-top db)
          eff-power-after-trigger (creatures/effective-power db horror-id)
          eff-toughness-after-trigger (creatures/effective-toughness db horror-id)]
      (is (= 6 eff-power-after-trigger)
          "Horror should be 6 power after +2/+1 trigger resolves")
      (is (= 5 eff-toughness-after-trigger)
          "Horror should be 5 toughness after +2/+1 trigger resolves"))))


;; Oracle: "Whenever you cycle or discard another card" — Horror must be on battlefield to observe.
;; When Horror itself is cycled from hand, it is never on the battlefield, so its triggers are
;; never registered. No trigger fires because of the zone check (ADR-026), not :exclude-self.
;;
;; Note: :exclude-self is a defensive guard for a case unreachable in normal gameplay.
;; Horror cannot simultaneously be on the battlefield (trigger observer) AND in hand
;; (discardable via cycling). The :exclude-self filter would only matter if some exotic
;; effect moved Horror mid-resolution, which is not a supported scenario.
(deftest horror-of-the-broken-lands-no-trigger-on-self-cycle-test
  (testing "Cycling Horror itself does not trigger its own ability — Horror not on battlefield (zone check, ADR-026)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db horror-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          ;; Horror is in hand — NOT on battlefield, so triggers are never registered.
          ;; Cycle Horror itself
          result (abilities/activate-ability db :player-1 horror-id 0)
          _ (is (nil? (:pending-selection result)) "Cycling {B}: no mana allocation selection")
          db (:db result)
          ;; Only the cycling ability should be on stack — no trigger (Horror not on battlefield)
          stack-items (q/get-all-stack-items db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability on stack — no Horror trigger (Horror not on battlefield, zone check)")
      (is (= :graveyard (:object/zone (q/get-object db horror-id)))
          "Horror should be in graveyard after self-cycle"))))


;; Oracle: "Whenever you cycle or discard another card" — "you" means Horror's controller
;; Opponent discarding does NOT trigger Horror.
(deftest horror-of-the-broken-lands-no-trigger-opponent-discards-test
  (testing "Opponent cycling a card does not trigger Horror of the Broken Lands"
    (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-2)
          [db horror-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          db (th/cast-and-resolve db :player-1 horror-id)
          [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-2)
          p2-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add p2-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 1}]])
          _ (is (= 4 (creatures/effective-power db horror-id)) "Precondition: base power 4")
          ;; Player-2 cycles Imposing Vantasaur
          result (abilities/activate-ability db :player-2 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          stack-items (q/get-all-stack-items db)
          {:keys [db]} (th/resolve-top db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability should be on stack — Horror trigger filtered for opponent")
      (is (= 4 (creatures/effective-power db horror-id))
          "Horror power should be unchanged — opponent cycled, not controller"))))


;; Oracle: "Whenever you cycle or discard another card" — includes non-cycling discard paths
;; Simulates LED activation, Careful Study, or cleanup discard (any hand→graveyard move).
;; Verifies the :card-discarded dispatch in zone_change_dispatch fires the trigger.
(deftest horror-of-the-broken-lands-trigger-on-non-cycling-discard-test
  (testing "Non-cycling discard (hand→graveyard via move-to-zone) triggers Horror's +2/+1"
    (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db horror-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          db (th/cast-and-resolve db :player-1 horror-id)
          ;; Add a card to hand to be discarded (any non-Horror card)
          [db discard-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          _ (is (= :battlefield (:object/zone (q/get-object db horror-id)))
                "Precondition: Horror on battlefield")
          _ (is (= 4 (creatures/effective-power db horror-id)) "Precondition: base power 4")
          _ (is (= 4 (creatures/effective-toughness db horror-id)) "Precondition: base toughness 4")
          ;; Simulate non-cycling discard: move card from hand to graveyard directly
          ;; (same path as LED activation, Careful Study, or cleanup discard)
          db (zone-change-dispatch/move-to-zone-db db discard-id :graveyard)
          _ (is (= :graveyard (:object/zone (q/get-object db discard-id)))
                "Discarded card should be in graveyard")
          ;; Horror trigger should be on stack
          stack-items (q/get-all-stack-items db)
          _ (is (= 1 (count stack-items)) "Horror trigger should be on stack after discard")
          ;; Resolve the trigger
          {:keys [db]} (th/resolve-top db)]
      (is (= 6 (creatures/effective-power db horror-id))
          "Horror should be 6 power after +2/+1 trigger resolves")
      (is (= 5 (creatures/effective-toughness db horror-id))
          "Horror should be 5 toughness after +2/+1 trigger resolves"))))


;; Oracle: "until end of turn" — grant should expire at cleanup
;; After granting +2/+1, trigger creates a grant with :expires/phase :cleanup
(deftest horror-of-the-broken-lands-grant-expires-eot-test
  (testing "PT modifier grant expires at end-of-turn cleanup phase"
    (let [{:keys [db horror-id cycle-id]} (setup-horror-trigger-test)
          result (abilities/activate-ability db :player-1 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Stack: [Horror trigger (pos 0, bottom), cycling-ability (pos 1, top)]
          {:keys [db]} (th/resolve-top db) ; resolve cycling draw (top item)
          {:keys [db]} (th/resolve-top db) ; resolve Horror trigger
          ;; Check that a pt-modifier grant with cleanup expiry exists on Horror
          horror-grants (grants/get-grants-by-type db horror-id :pt-modifier)]
      (is (= 1 (count horror-grants))
          "Horror should have exactly 1 pt-modifier grant after trigger resolves")
      (is (= :cleanup (get-in (first horror-grants) [:grant/expires :expires/phase]))
          "Grant should expire at cleanup phase (until end of turn)"))))


;; Oracle: "Whenever you cycle or discard another card" — trigger fires once per discard event
;; Two cycling events while Horror is on battlefield → +4/+2 cumulative
(deftest horror-of-the-broken-lands-trigger-multiple-cycles-test
  (testing "Cycling two cards while Horror is on battlefield pumps +2/+1 each time"
    (let [db (th/create-test-db {:mana {:colorless 4 :black 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :dark-ritual] :player-1)
          [db horror-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          db (th/cast-and-resolve db :player-1 horror-id)
          [db cycle-id-1] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          [db cycle-id-2] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          p1-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add p1-eid :player/mana-pool
                             {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 2}]])
          ;; --- Cycle first card ---
          result-1 (abilities/activate-ability db :player-1 cycle-id-1 0)
          db-a (:db result-1)
          alloc-sel-1 (:pending-selection result-1)
          alloc-1 (assoc alloc-sel-1 :selection/allocation (th/auto-compute-mana-allocation alloc-sel-1))
          {:keys [db]} (th/confirm-selection db-a alloc-1 #{})
          ;; Stack: [Horror trigger (bottom), cycling-ability (top)]
          {:keys [db]} (th/resolve-top db)  ; cycling draw (top)
          {:keys [db]} (th/resolve-top db)  ; Horror trigger → +2/+1
          power-after-first (creatures/effective-power db horror-id)
          toughness-after-first (creatures/effective-toughness db horror-id)
          ;; --- Cycle second card ---
          result-2 (abilities/activate-ability db :player-1 cycle-id-2 0)
          db-b (:db result-2)
          alloc-sel-2 (:pending-selection result-2)
          alloc-2 (assoc alloc-sel-2 :selection/allocation (th/auto-compute-mana-allocation alloc-sel-2))
          {:keys [db]} (th/confirm-selection db-b alloc-2 #{})
          ;; Stack: [Horror trigger (bottom), cycling-ability (top)]
          {:keys [db]} (th/resolve-top db)  ; cycling draw (top)
          {:keys [db]} (th/resolve-top db)  ; Horror trigger → +2/+1 again
          power-after-second (creatures/effective-power db horror-id)
          toughness-after-second (creatures/effective-toughness db horror-id)]
      (is (= 6 power-after-first)
          "Horror should be 6/5 after first cycling trigger")
      (is (= 5 toughness-after-first)
          "Horror should be 6/5 after first cycling trigger")
      (is (= 8 power-after-second)
          "Horror should be 8/6 after second cycling trigger (+4/+2 cumulative)")
      (is (= 6 toughness-after-second)
          "Horror should be 8/6 after second cycling trigger (+4/+2 cumulative)"))))


;; Oracle: Trigger only observes when Horror is on battlefield (ADR-026 trigger zone check)
;; Horror in hand while another card is discarded → no trigger fires
(deftest horror-of-the-broken-lands-no-trigger-when-not-on-battlefield-test
  (testing "No pump when Horror is in hand instead of on battlefield"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          ;; Horror is in hand, NOT cast — triggers not registered
          [db _horror-id] (th/add-card-to-zone db :horror-of-the-broken-lands :hand :player-1)
          [db cycle-id] (th/add-card-to-zone db :imposing-vantasaur :hand :player-1)
          ;; Cycle Imposing Vantasaur
          result (abilities/activate-ability db :player-1 cycle-id 0)
          db-after-discard (:db result)
          alloc-sel (:pending-selection result)
          alloc-sel-with-alloc (assoc alloc-sel :selection/allocation (th/auto-compute-mana-allocation alloc-sel))
          {:keys [db]} (th/confirm-selection db-after-discard alloc-sel-with-alloc #{})
          ;; Only one stack item: the cycling ability itself
          stack-items (q/get-all-stack-items db)
          {:keys [db]} (th/resolve-top db)]
      (is (= 1 (count stack-items))
          "Only the cycling ability should be on stack — no Horror trigger (not on battlefield)")
      ;; Hand count: started with 2 (horror + imposing vantasaur), discarded 1, drew 1 → still 2
      ;; But we only had 1 card in library, so after cycling: horror still in hand (1 card), drew dark-ritual (1 card) = 2
      (is (= 2 (th/get-hand-count db :player-1))
          "Hand should have 2 cards (Horror still in hand + drawn card)"))))
