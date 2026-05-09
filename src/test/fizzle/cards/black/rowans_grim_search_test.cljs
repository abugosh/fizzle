(ns fizzle.cards.black.rowans-grim-search-test
  "Tests for Rowan's Grim Search card.

   Rowan's Grim Search: {2}{B} - Instant
   Bargain (You may sacrifice an artifact, enchantment, or token as you cast this spell.)
   If this spell was bargained, look at the top four cards of your library,
   then put up to two of them back on top of your library in any order
   and the rest into your graveyard.
   You draw two cards and you lose 2 life."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.rowans-grim-search :as rowans-grim-search]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

;; Oracle: "Rowan's Grim Search" {2}{B} Instant — verified against Scryfall
(deftest rowans-grim-search-card-definition-test
  (testing "card has correct oracle properties"
    (let [card rowans-grim-search/card]
      (is (= :rowans-grim-search (:card/id card))
          "Card ID should be :rowans-grim-search")
      (is (= "Rowan's Grim Search" (:card/name card))
          "Card name should match oracle")
      (is (= 3 (:card/cmc card))
          "CMC should be 3")
      (is (= {:colorless 2 :black 1} (:card/mana-cost card))
          "Mana cost should be {2}{B}")
      (is (= #{:black} (:card/colors card))
          "Card should be black")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= #{:bargain} (:card/keywords card))
          "Card should have the :bargain keyword")
      (is (= "Bargain (You may sacrifice an artifact, enchantment, or token as you cast this spell.)\nIf this spell was bargained, look at the top four cards of your library, then put up to two of them back on top of your library in any order and the rest into your graveyard.\nYou draw two cards and you lose 2 life."
             (:card/text card))
          "Card text should match oracle")))

  ;; Oracle: "You draw two cards and you lose 2 life." (base effects)
  (testing "card has correct base effects: draw 2 and lose 2 life"
    (let [effects (:card/effects rowans-grim-search/card)]
      (is (= 2 (count effects))
          "Should have exactly 2 base effects")
      (let [[draw-eff life-eff] effects]
        (is (= :draw (:effect/type draw-eff))
            "First effect should be :draw")
        (is (= 2 (:effect/amount draw-eff))
            "Draw amount should be 2")
        (is (= :lose-life (:effect/type life-eff))
            "Second effect should be :lose-life")
        (is (= 2 (:effect/amount life-eff))
            "Lose-life amount should be 2"))))

  ;; Oracle: "Bargain — sacrifice an artifact, enchantment, or token"
  (testing "card has one alternate cost: bargained"
    (let [alts (:card/alternate-costs rowans-grim-search/card)]
      (is (= 1 (count alts))
          "Should have exactly one alternate cost (bargain)")
      (let [bargained (first alts)]
        (is (= :bargained (:alternate/id bargained))
            "Alternate ID should be :bargained")
        (is (= :bargain (:alternate/kind bargained))
            "Alternate kind should be :bargain")
        (is (= :hand (:alternate/zone bargained))
            "Bargained zone should be :hand")
        (is (= {:colorless 2 :black 1} (:alternate/mana-cost bargained))
            "Bargained mana cost should be {2}{B} (same as primary)")
        (is (= :graveyard (:alternate/on-resolve bargained))
            "Should go to graveyard on resolve"))))

  ;; Oracle: "sacrifice an artifact, enchantment, or token"
  (testing "bargained additional cost requires artifact, enchantment, or token"
    (let [bargained (first (:card/alternate-costs rowans-grim-search/card))
          add-costs (:alternate/additional-costs bargained)]
      (is (= 1 (count add-costs))
          "Should have exactly one additional cost")
      (let [sac-cost (first add-costs)]
        (is (= :sacrifice-permanent (:cost/type sac-cost))
            "Additional cost type should be :sacrifice-permanent")
        (is (= {:match/or [{:match/types #{:artifact :enchantment}}
                           {:match/is-token true}]}
               (:cost/criteria sac-cost))
            "Sacrifice criteria should require artifact, enchantment, or token"))))

  ;; Oracle: "look at the top four cards...put up to two of them back...rest into your graveyard"
  (testing "bargained alternate effects include peek-and-select, draw 2, lose 2"
    (let [bargained (first (:card/alternate-costs rowans-grim-search/card))
          alt-effects (:alternate/effects bargained)]
      (is (= 3 (count alt-effects))
          "Bargained path should have 3 effects")
      (let [[peek-eff draw-eff life-eff] alt-effects]
        (is (= :peek-and-select (:effect/type peek-eff))
            "First effect should be :peek-and-select")
        (is (= 4 (:effect/count peek-eff))
            "Should look at top 4 cards")
        (is (= 2 (:effect/select-count peek-eff))
            "Should put up to 2 back on top")
        (is (= :top-of-library (:effect/selected-zone peek-eff))
            "Selected cards go back to top of library")
        (is (= :graveyard (:effect/remainder-zone peek-eff))
            "Remainder cards go to graveyard")
        (is (= :draw (:effect/type draw-eff))
            "Second effect should be :draw")
        (is (= 2 (:effect/amount draw-eff))
            "Draw amount should be 2")
        (is (= :lose-life (:effect/type life-eff))
            "Third effect should be :lose-life")
        (is (= 2 (:effect/amount life-eff))
            "Lose-life amount should be 2")))))


;; =====================================================
;; B. Cast-Resolve Happy Path — Base Path (no bargain)
;; =====================================================

;; Oracle: "You draw two cards and you lose 2 life."
(deftest rowans-grim-search-base-path-draws-and-loses-life-test
  (testing "Base path (no bargain): draws 2 cards and loses 2 life"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1} :life 20})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :brain-freeze :careful-study] :player-1)
          ;; hand-before includes the spell itself (1 card), which is cast and moves to stack/graveyard
          ;; net change = -1 (cast) + 2 (draw) = +1 card vs hand-before
          hand-before (th/get-hand-count db :player-1)
          life-before (q/get-life-total db :player-1)
          db-resolved (th/cast-and-resolve db :player-1 rgs-id)]
      (is (= (+ hand-before 1) (th/get-hand-count db-resolved :player-1))
          "Should have net +1 card in hand: -1 for casting, +2 for drawing")
      (is (= (- life-before 2) (q/get-life-total db-resolved :player-1))
          "Should have lost 2 life")
      (is (= :graveyard (th/get-object-zone db-resolved rgs-id))
          "Spell should be in graveyard after resolution"))))


;; =====================================================
;; B. Cast-Resolve Happy Path — Bargained Path
;; =====================================================

;; Oracle: "If this spell was bargained, look at the top four cards...You draw two cards and you lose 2 life."
(deftest rowans-grim-search-bargained-path-full-resolution-test
  (testing "Bargained path: sacrifice artifact, peek-and-select top 4, draw 2, lose 2 life"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1} :life 20})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; 4 cards to peek + 2 extra below them so draw-2 can find library cards
          ;; Oracle: draw happens after the peek, from the remaining library
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze :careful-study
                                                 :mental-note :island]
                                                :player-1)
          ;; hand-before includes the spell itself (1 card), which is cast and moves to stack/graveyard
          ;; net change = -1 (cast) + 2 (draw from remaining library) = +1 card vs hand-before
          hand-before (th/get-hand-count db :player-1)
          life-before (q/get-life-total db :player-1)
          ;; Step 1: cast → mode-pick selection (primary vs bargained)
          {:keys [db selection]} (th/cast-with-mode db :player-1 rgs-id)
          ;; Find the bargained mode candidate
          bargained-mode (first (filter #(= :bargained (:mode/id %)) (:selection/candidates selection)))
          ;; Step 2: confirm bargained mode → sacrifice-cost selection
          {:keys [db selection]} (th/confirm-selection db selection #{bargained-mode})
          _ (is (= :sacrifice-cost (:selection/domain selection))
                "After bargained mode: sacrifice-cost selection expected")
          ;; Step 3: confirm sacrifice of Lotus Petal → spell goes on stack (mana already in pool)
          {:keys [db]} (th/confirm-selection db selection #{petal-id})
          ;; Verify Lotus Petal was sacrificed
          _ (is (= :graveyard (th/get-object-zone db petal-id))
                "Sacrificed artifact should be in graveyard")
          ;; Step 4: resolve → peek-and-select selection for top 4 cards
          {:keys [db selection]} (th/resolve-top db)
          _ (is (= :peek-and-select (:selection/domain selection))
                "Bargained resolve should create peek-and-select selection")
          _ (is (= 4 (count (:selection/candidates selection)))
                "Should peek at 4 library cards")
          ;; Step 5: confirm peek — put top 2 back on top (select them to :top-of-library)
          ;; The 2 non-selected cards go to graveyard (remainder-zone: :graveyard)
          top-two (set (take 2 lib-ids))
          remainder-two (set (take 2 (drop 2 lib-ids)))
          ;; After peek confirms, remaining-effects (draw 2, lose-life 2) execute automatically
          {:keys [db]} (th/confirm-selection db selection top-two)]
      ;; Verify: net +1 card (cast -1, draw +2 from remaining library cards)
      (is (= (+ hand-before 1) (th/get-hand-count db :player-1))
          "Should have net +1 card in hand: -1 for casting, +2 drawn from remaining library")
      (is (= (- life-before 2) (q/get-life-total db :player-1))
          "Should have lost 2 life after bargained resolution")
      ;; Verify: the 2 kept cards are in :top-of-library zone
      (doseq [kept-id top-two]
        (is (= :top-of-library (th/get-object-zone db kept-id))
            "Kept peeked cards should be in :top-of-library zone"))
      ;; Verify: remainder 2 peeked cards went to graveyard
      (doseq [rem-id remainder-two]
        (is (= :graveyard (th/get-object-zone db rem-id))
            "Non-selected peeked cards should be in graveyard")))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: {2}{B} — cannot cast without 3 total mana including black
(deftest rowans-grim-search-cannot-cast-without-mana-test
  (testing "Cannot cast Rowan's Grim Search without sufficient mana"
    (let [db (th/create-test-db)
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 rgs-id))
          "Should not be castable without mana"))))


(deftest rowans-grim-search-cannot-cast-with-partial-mana-test
  (testing "Cannot cast Rowan's Grim Search with only {1}{B} (needs {2}{B})"
    (let [db (th/create-test-db {:mana {:colorless 1 :black 1}})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 rgs-id))
          "Should not be castable with only 2 mana"))))


(deftest rowans-grim-search-cannot-cast-from-graveyard-test
  (testing "Cannot cast Rowan's Grim Search from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 rgs-id))
          "Should not be castable from graveyard"))))


;; Oracle: "Bargain — You may sacrifice an artifact, enchantment, or token"
;; Without any such permanent, bargained mode is infeasible
(deftest rowans-grim-search-bargained-mode-not-available-without-targets-test
  (testing "Bargained mode is infeasible when no artifacts, enchantments, or tokens on battlefield"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
          modes (rules/get-casting-modes db :player-1 rgs-id)
          castable-modes (filterv #(rules/can-cast-mode? db :player-1 rgs-id %) modes)]
      ;; Only primary mode should be castable
      (is (= 1 (count castable-modes))
          "Only primary mode should be castable when no bargain targets")
      (is (= :primary (:mode/id (first castable-modes)))
          "The only castable mode should be the primary mode"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Rowan's Grim Search is a spell, casting it should increment storm count
(deftest rowans-grim-search-increments-storm-count-test
  (testing "Casting Rowan's Grim Search increments storm count by 1"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-resolved (th/cast-and-resolve db :player-1 rgs-id)]
      (is (= (inc storm-before) (q/get-storm-count db-resolved :player-1))
          "Storm count should increment by 1"))))


;; =====================================================
;; E. Selection Tests — Peek-and-Select (Bargained Path)
;; =====================================================

;; Oracle: "put up to two of them back on top of your library in any order"
;; 0, 1, or 2 cards may be kept on top; rest go to graveyard
(deftest rowans-grim-search-peek-select-count-variants-test
  (testing "Peek-and-select: can keep 0, 1, or 2 cards on top; rest go to graveyard"
    (doseq [keep-count [0 1 2]]
      (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
            [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
            [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
            [db lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze :careful-study]
                                                  :player-1)
            {:keys [db selection]} (th/cast-with-mode db :player-1 rgs-id)
            bargained-mode (first (filter #(= :bargained (:mode/id %)) (:selection/candidates selection)))
            {:keys [db selection]} (th/confirm-selection db selection #{bargained-mode})
            ;; Sacrifice the artifact → spell goes on stack (mana already in pool, no alloc step)
            {:keys [db]} (th/confirm-selection db selection #{petal-id})
            {:keys [db selection]} (th/resolve-top db)
            _ (is (= :peek-and-select (:selection/domain selection))
                  (str "keep-count=" keep-count ": should be peek-and-select selection"))
            kept-ids (set (take keep-count lib-ids))
            remainder-ids (drop keep-count (take 4 lib-ids))
            {:keys [db]} (th/confirm-selection db selection kept-ids)]
        ;; Kept cards are placed back on top of library (engine zone: :top-of-library)
        (doseq [kept-id kept-ids]
          (is (= :top-of-library (th/get-object-zone db kept-id))
              (str "keep-count=" keep-count ": kept card should be in :top-of-library zone")))
        ;; Unchosen peeked cards should go to graveyard
        (doseq [rem-id remainder-ids]
          (is (= :graveyard (th/get-object-zone db rem-id))
              (str "keep-count=" keep-count ": remainder card should go to graveyard")))))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

;; Oracle: "look at the top four cards" — fewer than 4 means only available cards are peeked
(deftest rowans-grim-search-bargained-with-small-library-test
  (testing "Library has fewer than 4 cards: peek only available cards"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          ;; Only 2 cards in library
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          {:keys [db selection]} (th/cast-with-mode db :player-1 rgs-id)
          bargained-mode (first (filter #(= :bargained (:mode/id %)) (:selection/candidates selection)))
          {:keys [db selection]} (th/confirm-selection db selection #{bargained-mode})
          ;; Sacrifice the artifact → spell goes on stack (mana already in pool, no alloc step)
          {:keys [db]} (th/confirm-selection db selection #{petal-id})
          {:keys [db selection]} (th/resolve-top db)]
      (is (= :peek-and-select (:selection/domain selection))
          "Should still produce peek-and-select with small library")
      (is (= 2 (count (:selection/candidates selection)))
          "Should peek at only 2 cards (all available in library)")
      ;; Keep both cards on top (keep all)
      (let [all-lib-ids (set lib-ids)
            {:keys [db]} (th/confirm-selection db selection all-lib-ids)]
        (doseq [lib-id lib-ids]
          (is (= :top-of-library (th/get-object-zone db lib-id))
              "Both peeked cards should be in :top-of-library zone when kept"))))))


;; Oracle: bargain cost can be paid with an artifact — Lotus Petal is an artifact
;; Verify that an artifact on the battlefield enables the bargained mode
(deftest rowans-grim-search-bargained-mode-available-with-artifact-test
  (testing "Bargained mode is feasible when an artifact is on the battlefield"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db rgs-id] (th/add-card-to-zone db :rowans-grim-search :hand :player-1)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          modes (rules/get-casting-modes db :player-1 rgs-id)
          castable-modes (filterv #(rules/can-cast-mode? db :player-1 rgs-id %) modes)
          mode-ids (set (map :mode/id castable-modes))]
      (is (contains? mode-ids :bargained)
          "Bargained mode should be feasible when an artifact is available")
      (is (= 2 (count castable-modes))
          "Both primary and bargained modes should be available"))))
