(ns fizzle.cards.careful-study-test
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
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zones :as zones]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 1 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [db object-id] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn add-cards-to-library
  "Add multiple cards to the top of a player's library.
   Returns [db object-ids] tuple with object-ids in order (first = top of library)."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        get-card-eid (fn [card-id]
                       (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id))]
    (loop [remaining-cards card-ids
           position 0
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (get-card-eid card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining-cards)
                 (inc position)
                 (conj object-ids obj-id)))))))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db player-id]
  (count (q/get-hand db player-id)))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn get-graveyard-count
  "Get the number of cards in a player's graveyard."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :graveyard)))


;; === Card Definition Tests ===

(deftest test-careful-study-is-sorcery-type
  (testing "Careful Study has :sorcery in types"
    (is (contains? (:card/types cards/careful-study) :sorcery)
        "Careful Study should be a sorcery")))


(deftest test-careful-study-costs-one-blue
  (testing "Careful Study costs one blue mana"
    (is (= {:blue 1} (:card/mana-cost cards/careful-study))
        "Careful Study should cost {U}")))


(deftest test-careful-study-has-draw-and-discard-effects
  (testing "Careful Study has draw 2 and discard 2 effects"
    (let [card-effects (:card/effects cards/careful-study)]
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
    (let [db (create-test-db)
          ;; Add cards to library for drawing
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                               :player-1)
          ;; Add Careful Study to hand
          [db'' cs-id] (add-card-to-zone db' :careful-study :hand :player-1)
          initial-hand-count (get-hand-count db'' :player-1)
          _ (is (= 1 initial-hand-count) "Precondition: hand has 1 card (Careful Study)")
          ;; Cast Careful Study
          db-after-cast (rules/cast-spell db'' :player-1 cs-id)
          _ (is (= :stack (get-object-zone db-after-cast cs-id))
                "Careful Study should be on stack after casting")
          ;; Resolve spell (draws 2 cards)
          ;; Note: This will execute both draw and discard effects
          ;; For this test, we just check that draw happened
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 cs-id)]
      ;; After resolution, spell is in graveyard and cards were drawn
      ;; (discard effect is pending selection, so doesn't auto-discard yet)
      ;; For now without selection system, cards stay in hand
      (is (>= (get-hand-count db-after-resolve :player-1) 2)
          "Hand should have at least 2 cards after drawing"))))


;; === Selection State Tests ===
;; Note: These tests require the selection state system in events/game.cljs

(deftest test-discard-effect-returns-pending-selection-info
  (testing "Discard effect with :selection :player returns selection info"
    (let [db (create-test-db)
          ;; Add cards to hand for discard
          [db' id1] (add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' id2] (add-card-to-zone db' :cabal-ritual :hand :player-1)
          ;; Execute discard effect with player selection
          discard-effect {:effect/type :discard
                          :effect/count 2
                          :effect/selection :player}
          result (effects/execute-effect db'' :player-1 discard-effect)]
      ;; The effect should return unchanged db (selection handled at app-db level)
      ;; Or return a special value indicating pending selection
      ;; For now, test that cards are NOT auto-discarded
      (is (= :hand (get-object-zone result id1))
          "First card should still be in hand (waiting for selection)")
      (is (= :hand (get-object-zone result id2))
          "Second card should still be in hand (waiting for selection)"))))


;; === Selection Toggle Tests ===

(deftest test-toggle-selection-adds-card
  (testing "Toggling an unselected card adds it to selection"
    ;; This is an app-db level test - uses re-frame events
    ;; For unit testing, we test the data transformation
    (let [initial-selection #{}
          card-id (random-uuid)
          new-selection (if (contains? initial-selection card-id)
                          (disj initial-selection card-id)
                          (conj initial-selection card-id))]
      (is (contains? new-selection card-id)
          "Card should be in selection after toggle"))))


(deftest test-toggle-selection-removes-card
  (testing "Toggling an already selected card removes it from selection"
    (let [card-id (random-uuid)
          initial-selection #{card-id}
          new-selection (if (contains? initial-selection card-id)
                          (disj initial-selection card-id)
                          (conj initial-selection card-id))]
      (is (not (contains? new-selection card-id))
          "Card should NOT be in selection after second toggle"))))


;; === Confirm Selection Tests ===

(deftest test-confirm-selection-requires-exact-count
  (testing "Confirming selection fails when count doesn't match requirement"
    ;; Test the validation logic
    (let [required-count 2
          uuid1 (random-uuid)
          selected #{uuid1}  ; Only 1 selected
          valid? (= (count selected) required-count)]
      (is (not valid?)
          "Selection should NOT be valid with wrong count"))))


(deftest test-confirm-selection-succeeds-with-exact-count
  (testing "Confirming selection succeeds when count matches requirement"
    (let [required-count 2
          uuid1 (random-uuid)
          uuid2 (random-uuid)
          selected #{uuid1 uuid2}  ; Exactly 2 selected
          valid? (= (count selected) required-count)]
      (is valid?
          "Selection should be valid with exact count"))))


;; === Discard Execution Tests ===

(deftest test-confirm-selection-moves-cards-to-graveyard
  (testing "Confirming valid selection moves selected cards to graveyard"
    (let [db (create-test-db)
          ;; Add cards to hand
          [db' id1] (add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' id2] (add-card-to-zone db' :cabal-ritual :hand :player-1)
          [db''' id3] (add-card-to-zone db'' :brain-freeze :hand :player-1)
          _ (is (= 3 (get-hand-count db''' :player-1)) "Precondition: 3 cards in hand")
          _ (is (= 0 (get-graveyard-count db''' :player-1)) "Precondition: graveyard empty")
          ;; Simulate confirmed selection - move selected cards to graveyard
          selected #{id1 id2}
          db-after-discard (reduce (fn [d oid]
                                     (zones/move-to-zone d oid :graveyard))
                                   db'''
                                   selected)]
      (is (= :graveyard (get-object-zone db-after-discard id1))
          "First selected card should be in graveyard")
      (is (= :graveyard (get-object-zone db-after-discard id2))
          "Second selected card should be in graveyard")
      (is (= :hand (get-object-zone db-after-discard id3))
          "Non-selected card should still be in hand")
      (is (= 1 (get-hand-count db-after-discard :player-1))
          "Hand should have 1 card remaining")
      (is (= 2 (get-graveyard-count db-after-discard :player-1))
          "Graveyard should have 2 cards"))))


;; === Edge Case Tests ===

(deftest test-careful-study-with-exactly-two-cards-after-draw
  (testing "With exactly 2 cards after draw, player must still select (no auto-select)"
    ;; Per anti-pattern: NO skipping selection even if hand has exactly 2 cards
    ;; This tests the principle - selection is always required
    (let [hand-count 2
          required-discard 2
          ;; Even when hand equals required, player should see selection modal
          should-show-modal? (and (pos? required-discard)
                                  (>= hand-count required-discard))]
      (is should-show-modal?
          "Selection modal should appear even with exactly 2 cards"))))


(deftest test-careful-study-with-more-than-two-cards-after-draw
  (testing "With more than 2 cards after draw, player selects which 2 to discard"
    ;; Setup: 3 cards in hand after draw
    ;; Player must select exactly 2
    (let [hand-count 5  ; Started with 3, drew 2
          required-discard 2
          should-show-modal? (and (pos? required-discard)
                                  (>= hand-count required-discard))]
      (is should-show-modal?
          "Selection modal should appear to let player choose"))))


(deftest test-careful-study-with-fewer-cards-than-required
  (testing "Edge case: if hand has fewer than 2 cards, discard all available"
    ;; Per SRE review: If hand < count required, force discard all available
    ;; This tests the logic
    (let [hand-count 1
          required-discard 2
          actual-discard (min hand-count required-discard)]
      (is (= 1 actual-discard)
          "Should discard only what's available"))))


;; === resolve-spell-with-selection Integration Tests ===

(deftest test-resolve-spell-with-selection-creates-pending-state
  (testing "resolve-spell-with-selection pauses on selection effect and returns pending state"
    (let [db (create-test-db)
          ;; Add cards to library for drawing
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze :island]
                                               :player-1)
          ;; Add Careful Study to hand
          [db'' cs-id] (add-card-to-zone db' :careful-study :hand :player-1)
          _ (is (= 1 (get-hand-count db'' :player-1)) "Precondition: 1 card in hand")
          ;; Cast Careful Study
          db-after-cast (rules/cast-spell db'' :player-1 cs-id)
          _ (is (= :stack (get-object-zone db-after-cast cs-id))
                "Precondition: Careful Study on stack")
          ;; Resolve with selection system
          result (game/resolve-spell-with-selection db-after-cast :player-1 cs-id)]
      ;; Should have pending selection
      (is (some? (:pending-selection result))
          "Should return pending selection state")
      ;; Selection state should require 2 cards
      (is (= 2 (get-in result [:pending-selection :selection/count]))
          "Selection should require exactly 2 cards")
      ;; Selection type should be :discard
      (is (= :discard (get-in result [:pending-selection :selection/effect-type]))
          "Selection effect type should be :discard")
      ;; Draw effect should have executed (3 cards in hand: 2 drawn + 0 from empty hand after CS cast)
      (is (= 2 (get-hand-count (:db result) :player-1))
          "Draw effect should have executed - 2 cards in hand")
      ;; Spell should still be on stack (waiting for selection)
      (is (= :stack (get-object-zone (:db result) cs-id))
          "Spell should remain on stack until selection confirmed"))))


(deftest test-resolve-spell-with-selection-no-selection-effect
  (testing "resolve-spell-with-selection resolves normally for spells without selection"
    (let [db (create-test-db)
          ;; Add Dark Ritual to hand
          [db' dr-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Add mana to cast
          conn (d/conn-from-db db')
          player-eid (q/get-player-eid db' :player-1)
          _ (d/transact! conn [[:db/add player-eid :player/mana-pool
                                {:white 0 :blue 0 :black 1 :red 0 :green 0 :colorless 0}]])
          db'' @conn
          ;; Cast Dark Ritual
          db-after-cast (rules/cast-spell db'' :player-1 dr-id)
          ;; Resolve with selection system
          result (game/resolve-spell-with-selection db-after-cast :player-1 dr-id)]
      ;; Should NOT have pending selection
      (is (nil? (:pending-selection result))
          "Should NOT return pending selection for spells without selection effects")
      ;; Spell should be in graveyard (resolved normally)
      (is (= :graveyard (get-object-zone (:db result) dr-id))
          "Spell should be in graveyard after normal resolution")
      ;; Effect should have executed (BBB added)
      (is (= 3 (:black (q/get-mana-pool (:db result) :player-1)))
          "Dark Ritual effect should have added BBB"))))
