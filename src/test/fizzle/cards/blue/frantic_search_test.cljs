(ns fizzle.cards.blue.frantic-search-test
  "Tests for Frantic Search.

   Frantic Search: {2}{U} - Instant
   Draw two cards, then discard two cards. Untap up to three lands.

   Test categories:
   A. Card definition — all fields with exact values
   B. Cast-resolve happy path — full sequence
   C. Cannot-cast guards — insufficient mana, wrong zone
   D. Storm count — casting increments storm
   E. Selection tests — discard selection, untap-lands selection
   F. Edge cases — 0 tapped lands, fewer than 3, opponent lands excluded"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.frantic-search :as frantic-search]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection.untap]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest test-frantic-search-card-definition
  (testing "Frantic Search identity and core fields"
    (is (= :frantic-search (:card/id frantic-search/card))
        "Card id should be :frantic-search")
    (is (= "Frantic Search" (:card/name frantic-search/card))
        "Card name should be 'Frantic Search'")
    (is (= 3 (:card/cmc frantic-search/card))
        "CMC should be 3")
    (is (= {:colorless 2 :blue 1} (:card/mana-cost frantic-search/card))
        "Mana cost should be {2}{U}")
    (is (= #{:blue} (:card/colors frantic-search/card))
        "Colors should be #{:blue}")
    (is (= #{:instant} (:card/types frantic-search/card))
        "Frantic Search should be an instant")
    (is (= "Draw two cards, then discard two cards. Untap up to three lands."
           (:card/text frantic-search/card))
        "Card text should match oracle text"))

  (testing "Frantic Search has draw, discard, and untap-lands effects"
    (let [effects (:card/effects frantic-search/card)]
      (is (= 3 (count effects))
          "Should have 3 effects")
      (let [draw-effect (nth effects 0)
            discard-effect (nth effects 1)
            untap-effect (nth effects 2)]
        (is (= :draw (:effect/type draw-effect))
            "First effect should be :draw")
        (is (= 2 (:effect/amount draw-effect))
            "Draw effect should draw 2")
        (is (= :discard (:effect/type discard-effect))
            "Second effect should be :discard")
        (is (= 2 (:effect/count discard-effect))
            "Discard effect should discard 2")
        (is (= :player (:effect/selection discard-effect))
            "Discard effect should require player selection")
        (is (= :untap-lands (:effect/type untap-effect))
            "Third effect should be :untap-lands")
        (is (= 3 (:effect/count untap-effect))
            "Untap-lands effect should untap up to 3")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest test-frantic-search-draw-then-discard-then-untap
  (testing "Full Frantic Search resolution: draw 2, discard 2, untap up to 3 lands"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          ;; Add 4 cards to library for drawing
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :brain-freeze :island]
                                          :player-1)
          ;; Add Frantic Search to hand
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          ;; Add a tapped land to battlefield
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Tap the land
          db (th/tap-permanent db land-id)
          ;; Cast Frantic Search
          db-cast (rules/cast-spell db :player-1 fs-id)
          ;; Resolve → pauses at discard selection
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; After draw, hand should have 2 cards (drew 2, Frantic Search went to stack)
      (is (= 2 (th/get-hand-count db :player-1))
          "Should have drawn 2 cards")
      ;; Should have discard selection pending
      (is (= :discard (:selection/type selection))
          "First selection should be :discard")
      (is (= 2 (:selection/select-count selection))
          "Should require discarding 2 cards")
      ;; Discard both cards
      (let [hand-cards (q/get-hand db :player-1)
            card-ids (set (map :object/id hand-cards))
            ;; After discard, should get untap-lands selection
            {:keys [db selection]} (th/confirm-selection db selection card-ids)]
        ;; Hand should be empty after discarding
        (is (= 0 (th/get-hand-count db :player-1))
            "Hand should be empty after discarding 2 cards")
        ;; Should now have untap-lands selection
        (is (= :untap-lands (:selection/type selection))
            "Second selection should be :untap-lands")
        (is (= 3 (:selection/select-count selection))
            "Should allow selecting up to 3 lands")
        ;; The tapped land should be a candidate
        (is (contains? (:selection/candidate-ids selection) land-id)
            "Tapped land should be a candidate for untapping")
        ;; Untap the land
        (let [{:keys [db]} (th/confirm-selection db selection #{land-id})]
          ;; Land should now be untapped
          (is (false? (:object/tapped (q/get-object db land-id)))
              "Land should be untapped after selection"))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest test-frantic-search-cannot-cast-without-mana
  (testing "Cannot cast Frantic Search without enough mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :frantic-search :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana")))

  (testing "Cannot cast Frantic Search with only {U} (needs {2}{U})"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :frantic-search :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 1 blue mana"))))


(deftest test-frantic-search-cannot-cast-from-graveyard
  (testing "Cannot cast Frantic Search from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :frantic-search :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest test-frantic-search-increments-storm-count
  (testing "Casting Frantic Search increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)]
      (is (= 0 (q/get-storm-count db :player-1)) "Storm count should start at 0")
      (let [db-cast (rules/cast-spell db :player-1 fs-id)]
        (is (= 1 (q/get-storm-count db-cast :player-1))
            "Storm count should be 1 after casting")))))


;; =====================================================
;; E. Selection Tests
;; =====================================================

(deftest test-untap-lands-selection-shows-only-tapped-lands
  (testing "Untap-lands selection only shows tapped lands controlled by casting player"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          ;; Add tapped lands
          [db tapped-land-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db tapped-land-2] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Add an untapped land
          [db untapped-land] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Tap only two of the three lands
          db (th/tap-permanent db tapped-land-1)
          db (th/tap-permanent db tapped-land-2)
          ;; Cast and resolve through discard
          db-cast (rules/cast-spell db :player-1 fs-id)
          {db1 :db discard-sel :selection} (th/resolve-top db-cast)
          hand-cards (q/get-hand db1 :player-1)
          card-ids (set (map :object/id hand-cards))
          {_db2 :db untap-sel :selection} (th/confirm-selection db1 discard-sel card-ids)]
      ;; Should now have untap-lands selection
      (is (= :untap-lands (:selection/type untap-sel))
          "Should have untap-lands selection")
      (let [candidates (:selection/candidate-ids untap-sel)]
        ;; Only tapped lands should be candidates
        (is (contains? candidates tapped-land-1)
            "First tapped land should be a candidate")
        (is (contains? candidates tapped-land-2)
            "Second tapped land should be a candidate")
        (is (not (contains? candidates untapped-land))
            "Untapped land should NOT be a candidate")))))


(deftest test-untap-lands-validation-at-most
  (testing "Untap-lands selection uses :at-most validation (can select 0)"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-id)
          db-cast (rules/cast-spell db :player-1 fs-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          hand-cards (q/get-hand db :player-1)
          card-ids (set (map :object/id hand-cards))
          {:keys [db selection]} (th/confirm-selection db selection card-ids)]
      (is (= :at-most (:selection/validation selection))
          "Untap-lands validation should be :at-most")
      (is (= 3 (:selection/select-count selection))
          "Should be able to select up to 3")
      ;; Confirming with empty selection (0 lands) should be valid
      (let [{:keys [db]} (th/confirm-selection db selection #{})]
        ;; Land should still be tapped (we chose 0)
        (is (true? (:object/tapped (q/get-object db land-id)))
            "Land should still be tapped when 0 selected")))))


;; =====================================================
;; F. Edge Cases
;; =====================================================

(deftest test-untap-lands-with-zero-tapped-lands
  (testing "Untap-lands selection with no tapped lands: empty candidates"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          ;; Add an UNTAPPED land (no tapped lands)
          [db untapped-land] (th/add-card-to-zone db :island :battlefield :player-1)
          db-cast (rules/cast-spell db :player-1 fs-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          hand-cards (q/get-hand db :player-1)
          card-ids (set (map :object/id hand-cards))
          {:keys [db selection]} (th/confirm-selection db selection card-ids)]
      (is (= :untap-lands (:selection/type selection))
          "Should still get untap-lands selection")
      (is (empty? (:selection/candidate-ids selection))
          "Candidate list should be empty with no tapped lands")
      ;; Should be able to confirm with empty selection
      (let [{:keys [db]} (th/confirm-selection db selection #{})]
        (is (false? (:object/tapped (q/get-object db untapped-land)))
            "Untapped land should remain untapped after confirming 0 selections")))))


(deftest test-untap-lands-respects-up-to-3-constraint
  (testing "Untap-lands selection with fewer than 3 tapped lands"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          ;; Add only 2 tapped lands (fewer than 3 max)
          [db land-1] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land-2] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-1)
          db (th/tap-permanent db land-2)
          db-cast (rules/cast-spell db :player-1 fs-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          hand-cards (q/get-hand db :player-1)
          card-ids (set (map :object/id hand-cards))
          {:keys [db selection]} (th/confirm-selection db selection card-ids)]
      (is (= 2 (count (:selection/candidate-ids selection)))
          "Should have exactly 2 candidates (the 2 tapped lands)")
      ;; Select both lands (fewer than max of 3 — should be valid)
      (let [{:keys [db]} (th/confirm-selection db selection #{land-1 land-2})]
        (is (false? (:object/tapped (q/get-object db land-1)))
            "First land should be untapped")
        (is (false? (:object/tapped (q/get-object db land-2)))
            "Second land should be untapped")))))


(deftest test-untap-lands-excludes-opponent-lands
  (testing "Opponent's tapped lands do NOT appear in untap selection"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          db (th/add-opponent db)
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          ;; Add tapped lands for both players
          [db my-land] (th/add-card-to-zone db :island :battlefield :player-1)
          [db opp-land] (th/add-card-to-zone db :island :battlefield :player-2)
          db (th/tap-permanent db my-land)
          db (th/tap-permanent db opp-land)
          db-cast (rules/cast-spell db :player-1 fs-id)
          {db1 :db discard-sel :selection} (th/resolve-top db-cast)
          hand-cards (q/get-hand db1 :player-1)
          card-ids (set (map :object/id hand-cards))
          {_db2 :db untap-sel :selection} (th/confirm-selection db1 discard-sel card-ids)]
      (let [candidates (:selection/candidate-ids untap-sel)]
        (is (contains? candidates my-land)
            "Player's own tapped land should be a candidate")
        (is (not (contains? candidates opp-land))
            "Opponent's tapped land should NOT be a candidate")))))


(deftest test-frantic-search-untap-does-not-move-zones
  (testing "Untapping lands does not move them to another zone"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db fs-id] (th/add-card-to-zone db :frantic-search :hand :player-1)
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (th/tap-permanent db land-id)
          db-cast (rules/cast-spell db :player-1 fs-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          hand-cards (q/get-hand db :player-1)
          card-ids (set (map :object/id hand-cards))
          {:keys [db selection]} (th/confirm-selection db selection card-ids)
          {:keys [db]} (th/confirm-selection db selection #{land-id})]
      ;; Land should still be on battlefield (not moved)
      (is (= :battlefield (:object/zone (q/get-object db land-id)))
          "Untapped land should remain on battlefield, not moved to another zone"))))
