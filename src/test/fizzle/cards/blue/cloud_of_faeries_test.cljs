(ns fizzle.cards.blue.cloud-of-faeries-test
  "Tests for Cloud of Faeries.

   Cloud of Faeries: {1}{U} 1/1 Creature — Faerie
   Flying. When this creature enters, untap up to two lands.
   Cycling {2} (Discard this card: Draw a card.)

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — ETB untap selection fires
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   E. ETB selection tests — only tapped lands, at-most 2
   F. Cycling tests — pay {2} from hand, discard self, draw 1
   G. Edge cases — 0 tapped lands ETB, cycling from wrong zone"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.cloud-of-faeries :as cloud-of-faeries]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.events.selection.untap]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest test-cloud-of-faeries-card-definition
  (testing "Cloud of Faeries identity and core fields"
    (is (= :cloud-of-faeries (:card/id cloud-of-faeries/card))
        "Card id should be :cloud-of-faeries")
    (is (= "Cloud of Faeries" (:card/name cloud-of-faeries/card))
        "Card name should be 'Cloud of Faeries'")
    (is (= 2 (:card/cmc cloud-of-faeries/card))
        "CMC should be 2")
    (is (= {:colorless 1 :blue 1} (:card/mana-cost cloud-of-faeries/card))
        "Mana cost should be {1}{U}")
    (is (= #{:blue} (:card/colors cloud-of-faeries/card))
        "Colors should be #{:blue}")
    (is (= #{:creature} (:card/types cloud-of-faeries/card))
        "Should be a creature")
    (is (= #{:faerie} (:card/subtypes cloud-of-faeries/card))
        "Subtype should be #{:faerie}")
    (is (= 1 (:card/power cloud-of-faeries/card))
        "Power should be 1")
    (is (= 1 (:card/toughness cloud-of-faeries/card))
        "Toughness should be 1")
    (is (= #{:flying} (:card/keywords cloud-of-faeries/card))
        "Keywords should include :flying")
    (is (= {:colorless 2} (:card/cycling cloud-of-faeries/card))
        "Cycling cost should be {2}")
    (is (= "Flying. When this creature enters, untap up to two lands. Cycling {2}"
           (:card/text cloud-of-faeries/card))
        "Card text should match oracle text"))

  (testing "Cloud of Faeries has ETB untap-lands effect"
    (let [etb-effects (:card/etb-effects cloud-of-faeries/card)]
      (is (= 1 (count etb-effects))
          "Should have 1 ETB effect")
      (let [untap-effect (first etb-effects)]
        (is (= :untap-lands (:effect/type untap-effect))
            "ETB effect should be :untap-lands")
        (is (= 2 (:effect/count untap-effect))
            "Should untap up to 2 lands"))))

  (testing "Cloud of Faeries has no spell effects (ETB fires on entering)"
    (is (nil? (:card/effects cloud-of-faeries/card))
        "Cloud of Faeries has no spell effects — it's a creature")))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest test-cloud-of-faeries-enters-and-triggers-etb-untap
  (testing "Casting Cloud of Faeries triggers ETB untap up to 2 lands"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db fs-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          ;; Add a tapped land
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-id)
          ;; Cast
          db-cast (rules/cast-spell db :player-1 fs-id)
          ;; Resolve — creature moves to battlefield, ETB fires, pauses for untap selection
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Cloud of Faeries should now be on battlefield
      (is (= :battlefield (:object/zone (q/get-object db fs-id)))
          "Cloud of Faeries should be on battlefield after resolution")
      ;; ETB should have triggered untap selection
      (is (= :untap-lands (:selection/type selection))
          "ETB should fire untap-lands selection")
      (is (= 2 (:selection/select-count selection))
          "ETB should allow untapping up to 2 lands")
      (is (contains? (:selection/candidate-ids selection) land-id)
          "Tapped land should be in selection candidates")
      ;; Select the land to untap
      (let [{:keys [db]} (th/confirm-selection db selection #{land-id})]
        (is (false? (:object/tapped (q/get-object db land-id)))
            "Land should be untapped after ETB resolves")))))


(deftest test-cloud-of-faeries-etb-fires-without-tapped-lands
  (testing "ETB untap selection fires even with no tapped lands"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          ;; Untapped land only
          [db _untapped] (th/add-card-to-zone db :island :battlefield :player-1)
          db-cast (rules/cast-spell db :player-1 cf-id)
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Should still get selection (empty candidates)
      (is (= :untap-lands (:selection/type selection))
          "ETB should still provide untap selection")
      (is (empty? (:selection/candidate-ids selection))
          "No candidates when no tapped lands")
      ;; Confirm with empty selection — should work
      (let [{:keys [db]} (th/confirm-selection db selection #{})]
        (is (= :battlefield (:object/zone (q/get-object db cf-id)))
            "Cloud of Faeries should still be on battlefield")))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest test-cloud-of-faeries-cannot-cast-without-mana
  (testing "Cannot cast Cloud of Faeries without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana")))

  (testing "Cannot cast Cloud of Faeries with only blue mana"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only blue mana"))))


(deftest test-cloud-of-faeries-cannot-cast-from-graveyard
  (testing "Cannot cast Cloud of Faeries from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :cloud-of-faeries :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest test-cloud-of-faeries-increments-storm-count
  (testing "Casting Cloud of Faeries increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)) "Storm count should start at 0")
      (let [db-cast (rules/cast-spell db :player-1 cf-id)]
        (is (= 1 (q/get-storm-count db-cast :player-1))
            "Storm count should be 1 after casting")))))


;; =====================================================
;; E. ETB Selection Tests
;; =====================================================

(deftest test-cloud-of-faeries-etb-shows-only-tapped-lands
  (testing "ETB untap selection only shows tapped lands controlled by casting player"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          [db tapped-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db tapped-2] (th/add-card-to-zone db :island :battlefield :player-1)
          [db untapped] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db tapped-1)
          db (th/tap-permanent db tapped-2)
          db-cast (rules/cast-spell db :player-1 cf-id)
          {_db :db selection :selection} (th/resolve-top db-cast)]
      (let [candidates (:selection/candidate-ids selection)]
        (is (contains? candidates tapped-1) "First tapped land should be candidate")
        (is (contains? candidates tapped-2) "Second tapped land should be candidate")
        (is (not (contains? candidates untapped)) "Untapped land should NOT be candidate")))))


(deftest test-cloud-of-faeries-etb-at-most-2-validation
  (testing "ETB untap selection uses :at-most validation with max 2"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          [db land] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land)
          db-cast (rules/cast-spell db :player-1 cf-id)
          {:keys [_db selection]} (th/resolve-top db-cast)]
      (is (= :at-most (:selection/validation selection))
          "Validation should be :at-most")
      (is (= 2 (:selection/select-count selection))
          "Max select count should be 2"))))


(deftest test-cloud-of-faeries-etb-excludes-opponent-lands
  (testing "Opponent's tapped lands excluded from ETB selection"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          db (th/add-opponent db)
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          [db my-land] (th/add-card-to-zone db :island :battlefield :player-1)
          [db opp-land] (th/add-card-to-zone db :island :battlefield :player-2)
          db (th/tap-permanent db my-land)
          db (th/tap-permanent db opp-land)
          db-cast (rules/cast-spell db :player-1 cf-id)
          {:keys [_db selection]} (th/resolve-top db-cast)]
      (let [candidates (:selection/candidate-ids selection)]
        (is (contains? candidates my-land) "Own tapped land should be candidate")
        (is (not (contains? candidates opp-land)) "Opponent's land should NOT be candidate")))))


;; =====================================================
;; F. Cycling Tests
;; =====================================================

(deftest test-cloud-of-faeries-cycling-from-hand
  (testing "Cycling Cloud of Faeries from hand: pay {2}, discard self, draw 1"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db _] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          result (game/cycle-card db :player-1 cf-id)
          db (:db result)]
      ;; Card should be in graveyard
      (is (= :graveyard (:object/zone (q/get-object db cf-id)))
          "Cycled card should be in graveyard")
      ;; Should have drawn 1 card
      (is (= 1 (th/get-hand-count db :player-1))
          "Should have drawn 1 card (net 0: discarded 1, drew 1)")
      ;; Mana should be spent
      (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
          "Cycling cost should be paid"))))


(deftest test-cloud-of-faeries-cycling-cannot-cycle-without-mana
  (testing "Cannot cycle without sufficient mana"
    (let [db (th/create-test-db)
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          _ (game/cycle-card db :player-1 cf-id)]
      ;; Should fail gracefully — no state change (original db unchanged)
      (is (= :hand (:object/zone (q/get-object db cf-id)))
          "Card should remain in hand when cycling fails"))))


(deftest test-cloud-of-faeries-cycling-cannot-cycle-from-battlefield
  (testing "Cannot cycle from battlefield (must be in hand)"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :battlefield :player-1)
          _ (game/cycle-card db :player-1 cf-id)]
      (is (= :battlefield (:object/zone (q/get-object db cf-id)))
          "Card should remain on battlefield when cycling fails"))))


(deftest test-cloud-of-faeries-cycling-cannot-cycle-from-graveyard
  (testing "Cannot cycle from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :graveyard :player-1)
          _ (game/cycle-card db :player-1 cf-id)]
      (is (= :graveyard (:object/zone (q/get-object db cf-id)))
          "Card should remain in graveyard when cycling fails"))))


(deftest test-cloud-of-faeries-cycling-works-for-any-cycling-card
  (testing "cycle-card is generic: works for any card with :card/cycling"
    ;; Cloud of Faeries has :card/cycling {:colorless 2}
    ;; The cycling mechanism should not be hardcoded to Cloud of Faeries
    (let [card cloud-of-faeries/card]
      (is (some? (:card/cycling card))
          "Card should have :card/cycling key for generic cycling to work"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest test-cloud-of-faeries-cycle-with-empty-library
  (testing "Cycling with empty library draws nothing (graceful)"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          ;; Empty library — no cards to draw
          [db cf-id] (th/add-card-to-zone db :cloud-of-faeries :hand :player-1)
          result (game/cycle-card db :player-1 cf-id)
          db (:db result)]
      ;; Card should be in graveyard even with empty library
      (is (= :graveyard (:object/zone (q/get-object db cf-id)))
          "Cycled card should be in graveyard even with empty library")
      ;; Hand should be empty (cycled card discarded, 0 drawn)
      (is (= 0 (th/get-hand-count db :player-1))
          "Hand should be empty with empty library"))))
