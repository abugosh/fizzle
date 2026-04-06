(ns fizzle.cards.artifacts.phyrexian-devourer-test
  "Tests for Phyrexian Devourer card and supporting infrastructure.

   Phyrexian Devourer: {6} - Artifact Creature — Phyrexian Construct 1/1
   When Phyrexian Devourer's power is 7 or greater, sacrifice it.
   {Exile the top card of your library}: Put X +1/+1 counters on Phyrexian Devourer,
   where X is the exiled card's mana value.

   Key behaviors:
   - Activated ability: exile-library-top cost, add-counters dynamic value
   - State trigger: power >= 7 → sacrifice trigger goes on stack (respondable)
   - Player can activate ability in response to sacrifice trigger (combo line)
   - exile-library-top cost is generic (works with any count)
   - cost-exiled-card-mana-value dynamic value reads from :object/last-exiled-cmc"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.phyrexian-devourer :as phyrexian-devourer]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.costs :as costs]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest phyrexian-devourer-card-definition-test
  (testing "card has correct oracle properties"
    (let [card phyrexian-devourer/card]
      (is (= :phyrexian-devourer (:card/id card))
          "Card ID should be :phyrexian-devourer")
      (is (= "Phyrexian Devourer" (:card/name card))
          "Card name should match oracle")
      (is (= 6 (:card/cmc card))
          "CMC should be 6")
      (is (= {:colorless 6} (:card/mana-cost card))
          "Mana cost should be {6}")
      (is (= #{} (:card/colors card))
          "Card should be colorless")
      (is (= #{:artifact :creature} (:card/types card))
          "Card should be an artifact creature")
      (is (= #{:phyrexian :construct} (:card/subtypes card))
          "Card should be Phyrexian Construct")
      (is (= 1 (:card/power card))
          "Base power should be 1")
      (is (= 1 (:card/toughness card))
          "Base toughness should be 1")))

  (testing "card has activated ability with exile-library-top cost"
    (let [abilities (:card/abilities phyrexian-devourer/card)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :activated (:ability/type ability))
            "Ability should be :activated")
        (is (= 1 (:exile-library-top (:ability/cost ability)))
            "Ability cost should exile top 1 card of library"))))

  (testing "activated ability has add-counters effect with dynamic value"
    (let [ability (first (:card/abilities phyrexian-devourer/card))
          effs (:ability/effects ability)]
      (is (= 1 (count effs))
          "Should have exactly one effect")
      (let [effect (first effs)]
        (is (= :add-counters (:effect/type effect))
            "Effect type should be :add-counters")
        (is (= :self (:effect/target effect))
            "Target should be :self")
        (is (= {:dynamic/type :cost-exiled-card-mana-value}
               (get-in effect [:effect/counters :+1/+1]))
            "Counter amount should use cost-exiled-card-mana-value dynamic type"))))

  (testing "card has state trigger for power >= 7"
    (let [state-triggers (:card/state-triggers phyrexian-devourer/card)]
      (is (= 1 (count state-triggers))
          "Should have exactly one state trigger")
      (let [st (first state-triggers)]
        (is (= :power-gte (get-in st [:state/condition :condition/type]))
            "State trigger condition should be :power-gte")
        (is (= 7 (get-in st [:state/condition :condition/threshold]))
            "Power threshold should be 7")
        (is (= [{:effect/type :sacrifice :effect/target :self}]
               (:state/effects st))
            "State trigger effect should sacrifice :self")
        (is (= "Sacrifice Phyrexian Devourer (power >= 7)" (:state/description st))
            "State trigger description should match oracle")))))


;; === B. Cast-Resolve Happy Path ===

(deftest phyrexian-devourer-cast-to-battlefield-test
  (testing "Phyrexian Devourer enters the battlefield when cast"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 devourer-id))
                "Should be castable with {6} mana")
          db-resolved (th/cast-and-resolve db :player-1 devourer-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved devourer-id)))
          "Phyrexian Devourer should be on battlefield after resolution"))))


(deftest phyrexian-devourer-activated-ability-adds-counters-test
  (testing "Activated ability exiles top card and adds counters equal to its CMC"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          ;; Dark Ritual has CMC 1
          [db [ritual-id]] (th/add-cards-to-library db [:dark-ritual] :player-1)
          ability (first (:card/abilities phyrexian-devourer/card))
          _ (is (true? (abilities/can-activate? db devourer-id ability :player-1))
                "Ability should be activatable with a card in library")
          ;; Activate ability — pays exile-library-top cost, puts on stack
          result (ability-events/activate-ability db :player-1 devourer-id 0)
          db-after-activation (:db result)
          ;; Top card should be exiled (cost paid)
          _ (is (= :exile (:object/zone (q/get-object db-after-activation ritual-id)))
                "Top library card should be exiled as cost")
          ;; CMC should be stored on Devourer
          _ (let [dev-eid (q/get-object-eid db-after-activation devourer-id)]
              (is (= 1 (d/q '[:find ?cmc .
                              :in $ ?e
                              :where [?e :object/last-exiled-cmc ?cmc]]
                            db-after-activation dev-eid))
                  "last-exiled-cmc should be 1 (Dark Ritual CMC)"))
          ;; Resolve the ability
          db-resolved (:db (resolution/resolve-one-item db-after-activation))
          dev-obj (q/get-object db-resolved devourer-id)
          counters (or (:object/counters dev-obj) {})]
      (is (= 1 (get counters :+1/+1 0))
          "Devourer should have 1 +1/+1 counter (Dark Ritual CMC = 1)"))))


(deftest phyrexian-devourer-activated-ability-zero-cmc-card-test
  (testing "Exiling a zero-CMC card adds 0 counters"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          ;; Lotus Petal has CMC 0
          [db [petal-id]] (th/add-cards-to-library db [:lotus-petal] :player-1)
          result (ability-events/activate-ability db :player-1 devourer-id 0)
          db-after-activation (:db result)
          _ (is (= :exile (:object/zone (q/get-object db-after-activation petal-id)))
                "Lotus Petal should be exiled as cost")
          db-resolved (:db (resolution/resolve-one-item db-after-activation))
          counters (or (:object/counters (q/get-object db-resolved devourer-id)) {})]
      (is (= 0 (get counters :+1/+1 0))
          "Devourer should have 0 counters when exiled card has CMC 0"))))


;; === C. Cannot-Cast Guards ===

(deftest phyrexian-devourer-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Phyrexian Devourer without {6}"
    (let [db (th/create-test-db {:mana {:colorless 5}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 devourer-id))
          "Should not be castable with only {5} mana"))))


(deftest phyrexian-devourer-cannot-cast-from-graveyard-test
  (testing "Cannot cast Phyrexian Devourer from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 devourer-id))
          "Should not be castable from graveyard"))))


(deftest phyrexian-devourer-cannot-activate-without-library-cards-test
  (testing "Cannot activate ability when library is empty"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          ability (first (:card/abilities phyrexian-devourer/card))]
      (is (false? (abilities/can-activate? db devourer-id ability :player-1))
          "Ability should not be activatable with empty library"))))


;; === D. Storm Count ===

(deftest phyrexian-devourer-increments-storm-count-test
  (testing "Casting Phyrexian Devourer increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-resolved (th/cast-and-resolve db :player-1 devourer-id)]
      (is (= (inc storm-before) (q/get-storm-count db-resolved :player-1))
          "Storm count should increment by 1"))))


;; === E. State Trigger Tests ===

(deftest phyrexian-devourer-state-trigger-fires-at-power-7-test
  (testing "State trigger fires when power reaches exactly 7"
    (let [;; Cast Devourer to battlefield so :object/power is set via zones/move-to-zone
          db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          ;; Add 6 +1/+1 counters — power becomes 1(base) + 6 = 7
          dev-eid (q/get-object-eid db devourer-id)
          db-with-counters (d/db-with db [[:db/add dev-eid :object/counters {:+1/+1 6}]])
          ;; SBA check should generate state-check-trigger
          sbas (sba/check-all-sbas db-with-counters)]
      (is (= 1 (count (filter #(= :state-check-trigger (:sba/type %)) sbas)))
          "Should fire exactly one state-check-trigger SBA")
      (let [trigger-sba (first (filter #(= :state-check-trigger (:sba/type %)) sbas))]
        (is (= devourer-id (:sba/target trigger-sba))
            "SBA target should be the Devourer"))))

  (testing "State trigger puts sacrifice trigger on the stack"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          dev-eid (q/get-object-eid db devourer-id)
          db-with-counters (d/db-with db [[:db/add dev-eid :object/counters {:+1/+1 6}]])
          ;; Execute SBAs — should push trigger onto stack
          db-after-sba (sba/check-and-execute-sbas db-with-counters)
          top-item (stack/get-top-stack-item db-after-sba)]
      (is (= :state-check-trigger (:stack-item/type top-item))
          "Stack should have a :state-check-trigger item")
      (is (= devourer-id (:stack-item/source top-item))
          "Stack item source should be the Devourer"))))


(deftest phyrexian-devourer-state-trigger-does-not-fire-below-7-test
  (testing "State trigger does NOT fire when power is below 7"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          ;; Add 5 +1/+1 counters — power becomes 6 (not yet 7)
          dev-eid (q/get-object-eid db devourer-id)
          db-with-counters (d/db-with db [[:db/add dev-eid :object/counters {:+1/+1 5}]])
          sbas (sba/check-all-sbas db-with-counters)]
      (is (zero? (count (filter #(= :state-check-trigger (:sba/type %)) sbas)))
          "Should NOT fire state-check-trigger when power is 6"))))


(deftest phyrexian-devourer-state-trigger-not-duplicated-test
  (testing "State trigger does NOT fire again if already on stack"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          dev-eid (q/get-object-eid db devourer-id)
          db-with-counters (d/db-with db [[:db/add dev-eid :object/counters {:+1/+1 6}]])
          ;; Execute SBAs — trigger goes on stack
          db-after-first-sba (sba/check-and-execute-sbas db-with-counters)
          ;; Run SBAs again — should not add a second trigger
          sbas-second (sba/check-all-sbas db-after-first-sba)]
      (is (zero? (count (filter #(= :state-check-trigger (:sba/type %)) sbas-second)))
          "Should not fire duplicate state-check-trigger if already on stack"))))


(deftest phyrexian-devourer-sacrifice-trigger-resolves-to-graveyard-test
  (testing "When state-check-trigger resolves, Devourer moves to graveyard"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          dev-eid (q/get-object-eid db devourer-id)
          db-with-counters (d/db-with db [[:db/add dev-eid :object/counters {:+1/+1 6}]])
          ;; Run SBAs to put trigger on stack
          db-with-trigger (sba/check-and-execute-sbas db-with-counters)
          ;; Resolve the trigger
          result (resolution/resolve-one-item db-with-trigger)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved devourer-id)))
          "Devourer should be sacrificed (in graveyard) after trigger resolves"))))


;; === F. Combo Line Tests (activate in response to sacrifice trigger) ===

(deftest phyrexian-devourer-activate-in-response-to-sacrifice-trigger-test
  (testing "Player can activate ability in response to sacrifice trigger"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          dev-eid (q/get-object-eid db devourer-id)
          ;; Add cards to library for the ability
          [db [led-id]] (th/add-cards-to-library db [:lions-eye-diamond] :player-1)
          ;; Push power to 7 by adding 6 counters
          db-at-7 (d/db-with db [[:db/add dev-eid :object/counters {:+1/+1 6}]])
          ;; SBAs run — sacrifice trigger is put on stack
          db-with-trigger (sba/check-and-execute-sbas db-at-7)
          ;; Verify trigger is on stack
          _ (is (= :state-check-trigger (:stack-item/type (stack/get-top-stack-item db-with-trigger)))
                "Sacrifice trigger should be on stack")
          ;; Now player activates ability in response (still has LED in library)
          ability (first (:card/abilities phyrexian-devourer/card))
          _ (is (true? (abilities/can-activate? db-with-trigger devourer-id ability :player-1))
                "Should still be able to activate ability while trigger is on stack")
          ;; Activate ability — exile LED (CMC 0), add 0 counters
          result (ability-events/activate-ability db-with-trigger :player-1 devourer-id 0)
          db-after-activation (:db result)
          _ (is (= :exile (:object/zone (q/get-object db-after-activation led-id)))
                "LED should be exiled as cost")]
      ;; Stack should now have both: ability on top, sacrifice trigger below
      (let [stack-count (count (d/q '[:find ?e :where [?e :stack-item/type _]] db-after-activation))]
        (is (>= stack-count 2)
            "Stack should have at least 2 items (ability + sacrifice trigger)")))))


;; === G. Edge Cases ===

(deftest phyrexian-devourer-multiple-activations-accumulate-counters-test
  (testing "Multiple activations accumulate +1/+1 counters"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          ;; Add 2 cards to library: dark-ritual (CMC 1) then cabal-ritual (CMC 2)
          [db _lib-ids] (th/add-cards-to-library
                          db [:dark-ritual :cabal-ritual] :player-1)
          ;; First activation (exiles dark-ritual CMC 1)
          result1 (ability-events/activate-ability db :player-1 devourer-id 0)
          db1 (:db (resolution/resolve-one-item (:db result1)))
          ;; Second activation (exiles cabal-ritual CMC 2)
          result2 (ability-events/activate-ability db1 :player-1 devourer-id 0)
          db2 (:db (resolution/resolve-one-item (:db result2)))
          counters (or (:object/counters (q/get-object db2 devourer-id)) {})]
      (is (= 3 (get counters :+1/+1 0))
          "After two activations (CMC 1 + CMC 2), should have 3 counters total"))))


(deftest phyrexian-devourer-high-cmc-card-triggers-sba-test
  (testing "Exiling a 6-CMC card (Phyrexian Devourer itself) would push power to 7"
    (let [db (th/create-test-db {:mana {:colorless 6}})
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :hand :player-1)
          db (th/cast-and-resolve db :player-1 devourer-id)
          ;; Put another Phyrexian Devourer (CMC 6) in library
          [db [lib-dev-id]] (th/add-cards-to-library db [:phyrexian-devourer] :player-1)
          ;; Activate ability — exiles lib-dev (CMC 6), adds 6 counters
          result (ability-events/activate-ability db :player-1 devourer-id 0)
          db-after-activation (:db result)
          _ (is (= :exile (:object/zone (q/get-object db-after-activation lib-dev-id)))
                "Library copy should be exiled as cost")
          db-resolved (:db (resolution/resolve-one-item db-after-activation))
          ;; SBAs fire via :db effect handler in production; call manually in tests
          db-after-sba (sba/check-and-execute-sbas db-resolved)
          counters (or (:object/counters (q/get-object db-resolved devourer-id)) {})]
      ;; Power = 1 (base) + 6 (counters) = 7 — SBA should fire
      (is (= 6 (get counters :+1/+1 0))
          "Should have 6 counters from exiling 6-CMC card")
      (is (= 7 (creatures/effective-power db-resolved devourer-id))
          "Effective power should be 7 (1 base + 6 counters)")
      ;; After SBAs run, trigger should be on stack or Devourer sacrificed
      (let [zone (:object/zone (q/get-object db-after-sba devourer-id))
            stack-item (stack/get-top-stack-item db-after-sba)]
        (is (or (= :graveyard zone)
                (= :state-check-trigger (:stack-item/type stack-item)))
            "After reaching power 7, Devourer should either be sacrificed or have sacrifice trigger on stack")))))


;; === H. exile-library-top Cost Infrastructure Tests ===

(deftest exile-library-top-can-pay-with-cards-in-library-test
  (testing "exile-library-top can be paid when library has cards"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          [db _lib-id] (th/add-cards-to-library db [:dark-ritual] :player-1)]
      (is (true? (costs/can-pay? db devourer-id {:exile-library-top 1}))
          "Should be able to pay when library has 1+ cards"))))


(deftest exile-library-top-cannot-pay-with-empty-library-test
  (testing "exile-library-top cannot be paid when library is empty"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)]
      (is (false? (costs/can-pay? db devourer-id {:exile-library-top 1}))
          "Should not be able to pay with empty library"))))


(deftest exile-library-top-stores-cmc-on-object-test
  (testing "exile-library-top pay-cost stores exiled card's CMC on the object"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          ;; opt has CMC 1
          [db [_opt-id]] (th/add-cards-to-library db [:opt] :player-1)
          db-after-cost (costs/pay-cost db devourer-id {:exile-library-top 1})
          dev-eid (q/get-object-eid db-after-cost devourer-id)]
      (is (= 1 (d/q '[:find ?cmc .
                      :in $ ?e
                      :where [?e :object/last-exiled-cmc ?cmc]]
                    db-after-cost dev-eid))
          "last-exiled-cmc should equal opt's CMC (1)"))))


(deftest exile-library-top-works-with-multiple-cards-test
  (testing "exile-library-top with count=2 exiles 2 cards and sums their CMC"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          ;; Dark Ritual (CMC 1) at position 0, opt (CMC 1) at position 1
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual :opt] :player-1)
          db-after-cost (costs/pay-cost db devourer-id {:exile-library-top 2})
          dev-eid (q/get-object-eid db-after-cost devourer-id)
          total-cmc (d/q '[:find ?cmc .
                           :in $ ?e
                           :where [?e :object/last-exiled-cmc ?cmc]]
                         db-after-cost dev-eid)]
      (is (= 2 total-cmc)
          "Total CMC should be 2 (1 + 1)")
      (doseq [lib-id lib-ids]
        (is (= :exile (:object/zone (q/get-object db-after-cost lib-id)))
            "All exiled library cards should be in exile zone")))))


;; === I. cost-exiled-card-mana-value Dynamic Value Tests ===

(deftest cost-exiled-card-mana-value-reads-from-object-test
  (testing "cost-exiled-card-mana-value dynamic value reads :object/last-exiled-cmc"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          ;; Manually set :object/last-exiled-cmc to 4
          dev-eid (q/get-object-eid db devourer-id)
          db-with-cmc (d/db-with db [[:db/add dev-eid :object/last-exiled-cmc 4]])
          dynamic {:dynamic/type :cost-exiled-card-mana-value}
          result (effects/resolve-dynamic-value db-with-cmc :player-1 dynamic devourer-id)]
      (is (= 4 result)
          "Dynamic value should read 4 from :object/last-exiled-cmc"))))


(deftest cost-exiled-card-mana-value-defaults-to-zero-test
  (testing "cost-exiled-card-mana-value returns 0 when no CMC stored on object"
    (let [db (th/create-test-db)
          [db devourer-id] (th/add-card-to-zone db :phyrexian-devourer :battlefield :player-1)
          dynamic {:dynamic/type :cost-exiled-card-mana-value}
          result (effects/resolve-dynamic-value db :player-1 dynamic devourer-id)]
      (is (= 0 result)
          "Should default to 0 when no :object/last-exiled-cmc stored"))))
