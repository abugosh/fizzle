(ns fizzle.cards.artifacts.tsabos-web-test
  "Tests for Tsabo's Web.

   Tsabo's Web: Artifact {2}
   When this artifact enters, draw a card.
   Each land with an activated ability that isn't a mana ability
   doesn't untap during its controller's untap step.

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — ETB draw fires
   C. Cannot-cast guards — insufficient mana
   D. Storm count — casting increments storm
   I. Static-ability integration — Wasteland restricted, Island untaps, parity"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.artifacts.tsabos-web :as tsabos-web]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.triggers :as triggers]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest tsabos-web-id-is-tsabos-web
  (is (= :tsabos-web (:card/id tsabos-web/card))
      "Card id should be :tsabos-web"))


(deftest tsabos-web-name-is-exact
  (is (= "Tsabo's Web" (:card/name tsabos-web/card))
      "Card name should be \"Tsabo's Web\""))


(deftest tsabos-web-cmc-is-2
  (is (= 2 (:card/cmc tsabos-web/card))
      "CMC should be 2"))


(deftest tsabos-web-mana-cost-is-two-generic
  (is (= {:colorless 2} (:card/mana-cost tsabos-web/card))
      "Mana cost should be {2}"))


(deftest tsabos-web-is-colorless
  (is (= #{} (:card/colors tsabos-web/card))
      "Should be colorless"))


(deftest tsabos-web-types-is-artifact
  (is (= #{:artifact} (:card/types tsabos-web/card))
      "Should be exactly an artifact"))


(deftest tsabos-web-text-matches-oracle
  (is (= "When this artifact enters, draw a card.\nEach land with an activated ability that isn't a mana ability doesn't untap during its controller's untap step."
         (:card/text tsabos-web/card))
      "Card text should match Scryfall oracle text exactly"))


(deftest tsabos-web-has-one-enters-battlefield-trigger
  (let [triggers (:card/triggers tsabos-web/card)]
    (is (= 1 (count triggers))
        "Should have exactly 1 trigger")
    (is (= :enters-battlefield (:trigger/type (first triggers)))
        "Trigger type should be :enters-battlefield")))


(deftest tsabos-web-trigger-has-draw-effect
  (let [trigger (first (:card/triggers tsabos-web/card))
        effects (:trigger/effects trigger)]
    (is (= 1 (count effects))
        "ETB trigger should have 1 effect")
    (is (= :draw (:effect/type (first effects)))
        "ETB effect should be :draw")
    (is (= 1 (:effect/amount (first effects)))
        "ETB draw amount should be 1")))


(deftest tsabos-web-has-one-untap-restriction-static-ability
  (let [abilities (:card/static-abilities tsabos-web/card)]
    (is (= 1 (count abilities))
        "Should have exactly 1 static ability")
    (is (= :untap-restriction (:static/type (first abilities)))
        "Static type should be :untap-restriction")))


(deftest tsabos-web-restriction-criteria-matches-oracle
  (let [ability (first (:card/static-abilities tsabos-web/card))
        criteria (:modifier/criteria ability)]
    (is (= {:match/types #{:land}
            :match/has-ability-type :activated}
           criteria)
        "Criteria should match lands with activated (non-mana) abilities")))


(deftest tsabos-web-has-no-etb-effects
  (is (nil? (:card/etb-effects tsabos-web/card))
      "Should NOT use :card/etb-effects (ADR-028 mandates :card/triggers)"))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest tsabos-web-cast-enters-battlefield-and-draws
  (testing "Casting and resolving Tsabo's Web: enters battlefield and ETB draws a card"
    (let [;; Set up: library needs a card to draw
          db (th/create-test-db {:mana {:colorless 2}})
          [db _lib-card] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db web-id] (th/add-card-to-zone db :tsabos-web :hand :player-1)
          baseline-hand (th/get-hand-count db :player-1)
          ;; Cast via production path — interactive permanent, use rules/cast-spell
          db-cast (rules/cast-spell db :player-1 web-id)
          ;; Resolve spell: Tsabo's Web enters battlefield, ETB trigger goes on stack
          {:keys [db]} (th/resolve-top db-cast)
          _ (is (= :battlefield (:object/zone (q/get-object db web-id)))
                "Tsabo's Web should be on battlefield after spell resolution")
          ;; Resolve ETB trigger: draw 1 card (non-interactive)
          {:keys [db]} (th/resolve-top db)]
      (is (= :battlefield (:object/zone (q/get-object db web-id)))
          "Tsabo's Web should remain on battlefield")
      ;; After casting (removed from hand) + ETB draw: net +0 cards
      ;; baseline-hand includes the web-id card. After cast: -1. After ETB draw: +1.
      ;; So hand count should equal baseline-hand.
      (is (= baseline-hand (th/get-hand-count db :player-1))
          "Hand count should be baseline (cast used one, ETB drew one)")
      (is (= 1 (q/get-storm-count db :player-1))
          "Storm count should be 1 after casting"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest tsabos-web-cannot-cast-without-mana
  (testing "Cannot cast Tsabo's Web without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :tsabos-web :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest tsabos-web-cannot-cast-with-only-one-mana
  (testing "Cannot cast Tsabo's Web with only 1 mana (cost is {2})"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :tsabos-web :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 1 colorless"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest tsabos-web-cast-increments-storm
  (testing "Casting Tsabo's Web increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db _lib] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db web-id] (th/add-card-to-zone db :tsabos-web :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)) "Storm count starts at 0")
      (let [db-cast (rules/cast-spell db :player-1 web-id)]
        (is (= 1 (q/get-storm-count db-cast :player-1))
            "Storm count should be 1 after casting")))))


;; =====================================================
;; I. Static-Ability Integration
;; =====================================================

(deftest tsabos-web-keeps-wasteland-tapped-during-untap-step
  (testing "Tsabo's Web on battlefield keeps Wasteland tapped (has activated ability)"
    (let [;; Setup: add Tsabo's Web and both Wasteland and Island to battlefield
          ;; Contrasting fixture: Wasteland (has :activated ability) vs Island (only :mana ability)
          db (th/create-test-db)
          [db _web] (th/add-card-to-zone db :tsabos-web :battlefield :player-1)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Tap both lands
          db (th/tap-permanent db wasteland-id)
          db (th/tap-permanent db island-id)
          ;; Run untap step directly (per task design: NOT resolve-trigger :untap-step)
          db (triggers/untap-all-permanents db :player-1)]
      (is (true? (:object/tapped (q/get-object db wasteland-id)))
          "Wasteland should still be tapped (restricted by Tsabo's Web)")
      (is (false? (:object/tapped (q/get-object db island-id)))
          "Island should be untapped (only has mana ability, not affected)"))))


(deftest without-tsabos-web-wasteland-untaps-normally
  (testing "Parity: without Tsabo's Web, Wasteland untaps normally"
    (let [db (th/create-test-db)
          ;; No Tsabo's Web on battlefield
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          db (th/tap-permanent db wasteland-id)
          db (triggers/untap-all-permanents db :player-1)]
      (is (false? (:object/tapped (q/get-object db wasteland-id)))
          "Wasteland should untap normally without Tsabo's Web"))))


(deftest tsabos-web-cross-player-restricts-opponents-wasteland
  (testing "Tsabo's Web on player-2's battlefield restricts player-1's Wasteland"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Tsabo's Web owned by player-2
          [db _web] (th/add-card-to-zone db :tsabos-web :battlefield :player-2)
          ;; Wasteland and Island owned by player-1
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db wasteland-id)
          db (th/tap-permanent db island-id)
          ;; Player-1's untap step
          db (triggers/untap-all-permanents db :player-1)]
      (is (true? (:object/tapped (q/get-object db wasteland-id)))
          "Wasteland should stay tapped (Tsabo's Web is global, affects all players)")
      (is (false? (:object/tapped (q/get-object db island-id)))
          "Island should untap (only mana ability)"))))


(deftest tsabos-web-does-not-affect-artifact-untapping
  (testing "Tsabo's Web restriction is land-only; tapped Tsabo's Web itself untaps"
    ;; Tsabo's Web is an artifact, not a land. Even if tapped (e.g., by an effect),
    ;; it should untap normally since it doesn't match :match/types #{:land}.
    (let [db (th/create-test-db)
          [db web-id] (th/add-card-to-zone db :tsabos-web :battlefield :player-1)
          ;; Manually tap the web itself for this test
          db (th/tap-permanent db web-id)
          db (triggers/untap-all-permanents db :player-1)]
      (is (false? (:object/tapped (q/get-object db web-id)))
          "Tsabo's Web itself should untap (it is an artifact, not a land)"))))
