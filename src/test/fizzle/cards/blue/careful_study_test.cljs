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
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-careful-study-card-definition
  (testing "Careful Study type and cost"
    (is (= #{:sorcery} (:card/types careful-study/card))
        "Careful Study should be a sorcery")
    (is (= {:blue 1} (:card/mana-cost careful-study/card))
        "Careful Study should cost {U}"))

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


;; === Draw Effect Tests ===

(deftest test-careful-study-draws-two-cards
  (testing "Casting Careful Study draws 2 cards to hand"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library for drawing
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze]
                                                  :player-1)
          ;; Add Careful Study to hand
          [db'' cs-id] (th/add-card-to-zone db' :careful-study :hand :player-1)
          initial-hand-count (th/get-hand-count db'' :player-1)
          _ (is (= 1 initial-hand-count) "Precondition: hand has 1 card (Careful Study)")
          ;; Cast Careful Study
          db-after-cast (rules/cast-spell db'' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Careful Study should be on stack after casting")
          ;; Resolve spell (draws 2 cards)
          ;; Note: This will execute both draw and discard effects
          ;; For this test, we just check that draw happened
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 cs-id)]
      ;; After resolution, spell is in graveyard and cards were drawn
      ;; (discard effect is pending selection, so doesn't auto-discard yet)
      ;; For now without selection system, cards stay in hand
      (is (= 2 (th/get-hand-count db-after-resolve :player-1))
          "Hand should have 2 cards after drawing (discard is pending selection)"))))


;; === Selection State Tests ===
;; Note: These tests require the selection state system in events/game.cljs

(deftest test-discard-effect-returns-pending-selection-info
  (testing "Discard effect with :selection :player returns selection info"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to hand for discard
          [db' id1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' id2] (th/add-card-to-zone db' :cabal-ritual :hand :player-1)
          ;; Execute discard effect with player selection
          discard-effect {:effect/type :discard
                          :effect/count 2
                          :effect/selection :player}
          result (effects/execute-effect db'' :player-1 discard-effect)]
      ;; The effect should return unchanged db (selection handled at app-db level)
      ;; Or return a special value indicating pending selection
      ;; For now, test that cards are NOT auto-discarded
      (is (= :hand (th/get-object-zone result id1))
          "First card should still be in hand (waiting for selection)")
      (is (= :hand (th/get-object-zone result id2))
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
          db-after-cast (rules/cast-spell db'' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Precondition: Careful Study on stack")
          ;; Resolve with selection system
          result (game/resolve-one-item db-after-cast)]
      ;; Selection state should require 2 cards
      (is (= 2 (get-in result [:pending-selection :selection/select-count]))
          "Selection should require exactly 2 cards")
      ;; Selection type should be :discard
      (is (= :discard (get-in result [:pending-selection :selection/type]))
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
          [db' dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Add mana to cast
          conn (d/conn-from-db db')
          player-eid (q/get-player-eid db' :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/mana-pool
                                {:white 0 :blue 0 :black 1 :red 0 :green 0 :colorless 0}]])
          db'' @conn
          ;; Cast Dark Ritual
          db-after-cast (rules/cast-spell db'' :player-1 dr-id)
          ;; Resolve with selection system
          result (game/resolve-one-item db-after-cast)]
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
          db-after-cast (rules/cast-spell db''' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Careful Study should be on stack")
          ;; Resolve - draw 0 (empty library), then discard 2 from hand
          result (game/resolve-one-item db-after-cast)]
      ;; Draw from empty library draws nothing - hand should have 2 cards
      ;; (started with 3, CS moved to stack, draw 0 from empty library)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "After drawing from empty library, hand should have 2 cards")
      ;; Should still have pending selection for discard
      (is (some? (:pending-selection result))
          "Should have pending selection even with empty library draw"))))


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
          db-after-cast (rules/cast-spell db''' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-after-cast cs-id))
                "Careful Study should be on stack")
          ;; Resolve - draw 1 (only card in library), then discard 2
          result (game/resolve-one-item db-after-cast)]
      ;; After draw, hand should have 2 cards (1 original + 1 drawn)
      ;; (started with 2, CS moved to stack, drew 1)
      (is (= 2 (th/get-hand-count (:db result) :player-1))
          "After drawing 1 from library, hand should have 2 cards")
      ;; Library should now be empty
      (is (= 0 (count (q/get-objects-in-zone (:db result) :player-1 :library)))
          "Library should be empty after drawing the only card")
      ;; Should have pending selection for discard
      (is (some? (:pending-selection result))
          "Should have pending selection to discard 2 cards"))))


(deftest test-careful-study-discard-via-production-handler
  ;; Bug caught: discard handler broken when using production confirm flow
  (testing "Full cast -> draw -> discard selection -> confirm discard flow"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add 4 cards to library for drawing
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze :island]
                                                  :player-1)
          ;; Add Careful Study to hand
          [db'' cs-id] (th/add-card-to-zone db' :careful-study :hand :player-1)
          _ (is (= 1 (th/get-hand-count db'' :player-1)) "Precondition: 1 card in hand")
          ;; Cast Careful Study
          db-cast (rules/cast-spell db'' :player-1 cs-id)
          _ (is (= :stack (th/get-object-zone db-cast cs-id))
                "Precondition: CS on stack")
          ;; Resolve - draws 2, pauses for discard selection
          result (game/resolve-one-item db-cast)
          sel (:pending-selection result)
          db-after-draw (:db result)]
      ;; Should have 2 cards in hand (drew 2, CS was cast from hand)
      (is (= 2 (th/get-hand-count db-after-draw :player-1))
          "Should have 2 cards in hand after draw")
      ;; Should have pending discard selection
      (is (= :discard (:selection/type sel))
          "Selection type should be :discard")
      (is (= 2 (:selection/select-count sel))
          "Should require discarding 2 cards")
      ;; Simulate selecting 2 cards for discard
      (let [hand-cards (q/get-hand db-after-draw :player-1)
            card-ids (set (map :object/id hand-cards))
            ;; Build app-db with selection
            sel-with-choice (assoc sel :selection/selected card-ids)
            app-db {:game/db db-after-draw
                    :game/pending-selection sel-with-choice}
            ;; Confirm discard via the production confirm-selection-impl
            result-app-db (core/confirm-selection-impl app-db)
            final-db (:game/db result-app-db)]
        ;; Hand should be empty (discarded 2 cards)
        (is (= 0 (th/get-hand-count final-db :player-1))
            "Hand should be empty after discarding 2 cards")
        ;; Graveyard should have 3 cards (2 discarded + CS itself)
        (is (= 3 (th/get-zone-count final-db :graveyard :player-1))
            "Graveyard should have 3 cards (2 discarded + Careful Study)")
        ;; CS should be in graveyard
        (is (= :graveyard (th/get-object-zone final-db cs-id))
            "Careful Study should be in graveyard after resolution")
        ;; No pending selection
        (is (nil? (:game/pending-selection result-app-db))
            "Should not have pending selection after confirmation")))))
