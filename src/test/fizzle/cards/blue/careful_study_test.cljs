(ns fizzle.cards.blue.careful-study-test
  "Tests for Careful Study with selection modal.

   Careful Study: U - Sorcery
   Draw two cards, then discard two cards.

   This tests:
   - Card definition (type, cost)
   - Draw effect working
   - Selection state creation
   - Toggle selection
   - Confirm with exact count
   - Discard execution
   - Edge cases (exactly 2 cards, more than 2)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.careful-study :as careful-study]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-careful-study-card-definition
  (testing "Careful Study identity and core fields"
    (is (= :careful-study (:card/id careful-study/card))
        "Card id should be :careful-study")
    (is (= "Careful Study" (:card/name careful-study/card))
        "Card name should be 'Careful Study'")
    (is (= 1 (:card/cmc careful-study/card))
        "CMC should be 1")
    (is (= #{:blue} (:card/colors careful-study/card))
        "Colors should be #{:blue}")
    (is (= #{:sorcery} (:card/types careful-study/card))
        "Careful Study should be a sorcery")
    (is (= {:blue 1} (:card/mana-cost careful-study/card))
        "Careful Study should cost {U}")
    (is (= "Draw two cards, then discard two cards." (:card/text careful-study/card))
        "Card text should match"))

  (testing "Careful Study has draw 2 and discard 2 effects"
    (let [card-effects (:card/effects careful-study/card)]
      (is (= 2 (count card-effects))
          "Careful Study should have 2 effects")
      (let [draw-effect (first card-effects)
            discard-effect (second card-effects)]
        (is (= :draw (:effect/type draw-effect))
            "First effect should be :draw")
        (is (= 2 (:effect/amount draw-effect))
            "Draw effect should draw 2")
        (is (= :discard (:effect/type discard-effect))
            "Second effect should be :discard")
        (is (= 2 (:effect/count discard-effect))
            "Discard effect should discard 2")
        (is (= :player (:effect/selection discard-effect))
            "Discard effect should require player selection")))))


;; === C. Cannot-Cast Guards ===

(deftest careful-study-cannot-cast-without-mana-test
  (testing "Cannot cast Careful Study without blue mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :careful-study :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest careful-study-cannot-cast-from-graveyard-test
  (testing "Cannot cast Careful Study from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :careful-study :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest careful-study-increments-storm-count-test
  (testing "Casting Careful Study increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Careful Study"))))


;; === Draw Effect Tests ===

(deftest test-careful-study-draws-two-cards
  (testing "Casting Careful Study draws 2 cards to hand before discard selection"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library for drawing
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze]
                                                 :player-1)
          ;; Add Careful Study to hand
          [db cs-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: hand has 1 card (Careful Study)")
          ;; Cast and resolve through production path — pauses at discard selection
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-cast cs-id))
                "Careful Study should be on stack after casting")
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Draw should have happened: 2 cards drawn, CS was cast from hand
      (is (= 2 (th/get-hand-count db :player-1))
          "Should have 2 cards in hand after draw (before discard)")
      ;; Discard selection should be pending
      (is (= :discard (:selection/domain selection))
          "Selection type should be :discard")
      (is (= 2 (:selection/select-count selection))
          "Should require discarding 2 cards"))))


;; === Selection State Tests ===
;; Note: These tests require the selection state system in events/game.cljs

(deftest test-discard-selection-created-on-resolve
  (testing "Resolving Careful Study creates discard selection after draw"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to hand for discard
          [db id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db id2] (th/add-card-to-zone db :cabal-ritual :hand :player-1)
          ;; Add Careful Study to hand
          [db cs-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          ;; Add library cards for draw
          [db _lib-ids] (th/add-cards-to-library db [:brain-freeze :island] :player-1)
          ;; Cast and resolve through production path
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 cs-id)
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Draw should have happened, discard selection should be pending
      (is (= :discard (:selection/domain selection))
          "Selection type should be :discard")
      (is (= 2 (:selection/select-count selection))
          "Should require discarding 2 cards")
      ;; Cards should still be in hand (waiting for selection)
      (is (= :hand (th/get-object-zone db id1))
          "First card should still be in hand (waiting for selection)")
      (is (= :hand (th/get-object-zone db id2))
          "Second card should still be in hand (waiting for selection)"))))


;; === resolve-spell-with-selection Integration Tests ===

(deftest test-resolve-spell-with-selection-creates-pending-state
  (testing "resolve-spell-with-selection pauses on selection effect and returns pending state"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library for drawing
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                  :player-1)
          ;; Add Careful Study to hand
          [db'' cs-id] (th/add-card-to-zone db' :careful-study :hand :player-1)
          _ (is (= 1 (th/get-hand-count db'' :player-1)) "Precondition: 1 card in hand")
          ;; Cast Careful Study
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db'' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Precondition: Careful Study on stack")
          ;; Resolve with selection system
          result (resolution/resolve-one-item db-after-cast)]
      ;; Selection state should require 2 cards
      (is (= 2 (get-in result [:pending-selection :selection/select-count]))
          "Selection should require exactly 2 cards")
      ;; Selection type should be :discard
      (is (= :discard (get-in result [:pending-selection :selection/domain]))
          "Selection effect type should be :discard")
      ;; Draw effect should have executed (3 cards in hand: 2 drawn + 0 from empty hand after CS cast)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "Draw effect should have executed - 2 cards in hand")
      ;; Spell should still be on stack (waiting for selection)
      (is (= :stack (th/get-object-zone (:db result) cs-id))
          "Spell should remain on stack until selection confirmed"))))


(deftest test-resolve-spell-with-selection-no-selection-effect
  (testing "resolve-spell-with-selection resolves normally for spells without selection"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add Dark Ritual to hand
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Set black mana for Dark Ritual via immutable d/db-with
          player-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add player-eid :player/mana-pool
                             {:white 0 :blue 0 :black 1 :red 0 :green 0 :colorless 0}]])
          ;; Cast Dark Ritual
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db :player-1 dr-id)
          ;; Resolve with selection system
          result (resolution/resolve-one-item db-after-cast)]
      ;; Should NOT have pending selection
      (is (nil? (:pending-selection result))
          "Should NOT return pending selection for spells without selection effects")
      ;; Spell should be in graveyard (resolved normally)
      (is (= :graveyard (th/get-object-zone (:db result) dr-id))
          "Spell should be in graveyard after normal resolution")
      ;; Effect should have executed (BBB added)
      (is (= 3 (:black (q/get-mana-pool (:db result) :player-1)))
          "Dark Ritual effect should have added BBB"))))


;; === Selection System Corner Cases ===

(deftest test-careful-study-empty-library
  (testing "Careful Study with empty library draws nothing, still requires discard of what's in hand"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to hand (these become discardable after failed draw)
          [db' _id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _id2] (th/add-card-to-zone db' :cabal-ritual :hand :player-1)
          ;; Add Careful Study to hand (NOT to library - library is empty)
          [db''' cs-id] (th/add-card-to-zone db'' :careful-study :hand :player-1)
          ;; Library is empty (no cards added)
          library-count (count (q/get-objects-in-zone db''' :player-1 :library))
          _ (is (= 0 library-count) "Precondition: library should be empty")
          ;; Hand has 3 cards (2 rituals + Careful Study)
          hand-before (th/get-hand-count db''' :player-1)
          _ (is (= 3 hand-before) "Precondition: hand should have 3 cards")
          ;; Cast Careful Study
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db''' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Careful Study should be on stack")
          ;; Resolve - draw 0 (empty library), then discard 2 from hand
          result (resolution/resolve-one-item db-after-cast)]
      ;; Draw from empty library draws nothing - hand should have 2 cards
      ;; (started with 3, CS moved to stack, draw 0 from empty library)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "After drawing from empty library, hand should have 2 cards")
      ;; Should still have pending discard selection
      (is (= :discard (get-in result [:pending-selection :selection/domain]))
          "Selection type should be :discard even with empty library draw")
      (is (= 2 (get-in result [:pending-selection :selection/select-count]))
          "Should require discarding 2 cards"))))


(deftest test-careful-study-one-card-library
  (testing "Careful Study with exactly 1 card in library draws 1, requires discard 2"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add exactly 1 card to library
          [db' [_lib-id]] (th/add-cards-to-library db [:brain-freeze] :player-1)
          ;; Add cards to hand
          [db'' _id1] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Add Careful Study to hand
          [db''' cs-id] (th/add-card-to-zone db'' :careful-study :hand :player-1)
          ;; Verify library has exactly 1 card
          library-count (count (q/get-objects-in-zone db''' :player-1 :library))
          _ (is (= 1 library-count) "Precondition: library should have exactly 1 card")
          ;; Hand has 2 cards (1 ritual + Careful Study)
          hand-before (th/get-hand-count db''' :player-1)
          _ (is (= 2 hand-before) "Precondition: hand should have 2 cards")
          ;; Cast Careful Study
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db''' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Careful Study should be on stack")
          ;; Resolve - draw 1 (only card in library), then discard 2
          result (resolution/resolve-one-item db-after-cast)]
      ;; After draw, hand should have 2 cards (1 original + 1 drawn)
      ;; (started with 2, CS moved to stack, drew 1)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "After drawing 1 from library, hand should have 2 cards")
      ;; Library should now be empty
      (is (= 0 (count (q/get-objects-in-zone (:db result) :player-1 :library)))
          "Library should be empty after drawing the only card")
      ;; Should have pending discard selection
      (is (= :discard (get-in result [:pending-selection :selection/domain]))
          "Selection type should be :discard")
      (is (= 2 (get-in result [:pending-selection :selection/select-count]))
          "Should require discarding 2 cards"))))


(deftest test-careful-study-discard-via-production-handler
  ;; Bug caught: discard handler broken when using production confirm flow
  (testing "Full cast -> draw -> discard selection -> confirm discard flow"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add 4 cards to library for drawing
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                 :player-1)
          ;; Add Careful Study to hand
          [db cs-id] (th/add-card-to-zone db :careful-study :hand :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1)) "Precondition: 1 card in hand")
          ;; Cast Careful Study
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-cast cs-id))
                "Precondition: CS on stack")
          ;; Resolve - draws 2, pauses for discard selection
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Should have 2 cards in hand (drew 2, CS was cast from hand)
      (is (= 2 (th/get-hand-count db :player-1))
          "Should have 2 cards in hand after draw")
      ;; Should have pending discard selection
      (is (= :discard (:selection/domain selection))
          "Selection type should be :discard")
      (is (= 2 (:selection/select-count selection))
          "Should require discarding 2 cards")
      ;; Select all hand cards for discard via production path
      (let [hand-cards (q/get-hand db :player-1)
            card-ids (set (map :object/id hand-cards))
            {:keys [db]} (th/confirm-selection db selection card-ids)]
        ;; Hand should be empty (discarded 2 cards)
        (is (= 0 (th/get-hand-count db :player-1))
            "Hand should be empty after discarding 2 cards")
        ;; Graveyard should have 3: 2 discarded cards + Careful Study itself
        (is (= 3 (th/get-zone-count db :graveyard :player-1))
            "Graveyard should have 2 discarded cards + spell")))))
