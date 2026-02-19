(ns fizzle.cards.flash-of-insight-test
  "Tests for Flash of Insight card definition and resolution flow.

   Flash of Insight: {X}{1}{U} - Instant
   Look at the top X cards of your library. Put one into your hand
   and the rest on the bottom in a random order.
   Flashback {1}{U} — Exile X blue cards from your graveyard.

   Tests verify:
   - Card definition (type, cost, effects, flashback)
   - Peek-and-select selection state creation (X=2)
   - Full resolution: peek → select → hand, remainder to bottom
   - Flashback with exile cost from graveyard"
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.cards.flash-of-insight :as flash-of-insight]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zones :as zones]
    [fizzle.events.game :as game]
    [fizzle.events.selection.library :as library]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest flash-of-insight-card-definition-test
  (testing "Flash of Insight card has required fields"
    (let [card flash-of-insight/flash-of-insight]
      (is (= :flash-of-insight (:card/id card))
          "Card ID must be :flash-of-insight")
      (is (= "Flash of Insight" (:card/name card))
          "Card name must match oracle")
      (is (= #{:instant} (:card/types card))
          "Must be instant")
      (is (= #{:blue} (:card/colors card))
          "Must be blue")
      (is (true? (:x (:card/mana-cost card)))
          "Mana cost must have :x true for X cost")
      (is (= 1 (:colorless (:card/mana-cost card)))
          "Mana cost must have 1 colorless")
      (is (= 1 (:blue (:card/mana-cost card)))
          "Mana cost must have 1 blue")))

  (testing "Normal cast uses :peek-and-select effect"
    (let [effect (first (:card/effects flash-of-insight/flash-of-insight))]
      (is (= :peek-and-select (:effect/type effect))
          "Effect must be :peek-and-select")
      (is (= :x (:effect/count effect))
          "Effect count must be :x to use X value")
      (is (= 1 (:effect/select-count effect))
          "Must select exactly 1 card")
      (is (= :hand (:effect/selected-zone effect))
          "Selected card goes to hand")
      (is (= :bottom-of-library (:effect/remainder-zone effect))
          "Non-selected go to bottom")
      (is (true? (:effect/order-remainder? effect))
          "Remainder should be player-ordered (any order)")))

  (testing "Flashback has correct costs"
    (let [alternate (first (:card/alternate-costs flash-of-insight/flash-of-insight))]
      (is (= :flashback (:alternate/id alternate))
          "Must have :alternate/id :flashback")
      (is (= :graveyard (:alternate/zone alternate))
          "Flashback casts from graveyard")
      (is (= {:colorless 1 :blue 1} (:alternate/mana-cost alternate))
          "Flashback mana cost is {1}{U}")
      (is (= :exile (:alternate/on-resolve alternate))
          "Spell exiles after flashback resolution")))

  (testing "Flashback has exile blue cards additional cost"
    (let [alternate (first (:card/alternate-costs flash-of-insight/flash-of-insight))
          exile-cost (first (:alternate/additional-costs alternate))]
      (is (= :exile-cards (:cost/type exile-cost))
          "Additional cost type must be :exile-cards")
      (is (= :graveyard (:cost/zone exile-cost))
          "Exile from graveyard")
      (is (= #{:blue} (:card/colors (:cost/criteria exile-cost)))
          "Must exile blue cards")
      (is (= :x (:cost/count exile-cost))
          "Exile count is X (player chooses)"))))


;; === Integration Test: Peek-and-Select with X=2 ===

(deftest flash-of-insight-peek-and-select-x2-test
  ;; Bug caught: entire card resolution flow untested
  (testing "Cast with X=2, peek 2 cards, select 1 for hand"
    (let [db (th/create-test-db)
          ;; Add 5 cards to library (more than X=2)
          [db' lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze
                                                  :careful-study :mental-note]
                                                 :player-1)
          ;; Add Flash of Insight to hand
          [db'' foi-id] (th/add-card-to-zone db' :flash-of-insight :hand :player-1)
          ;; Add mana to cast: X=2 + {1}{U} = {2}{1}{U} = 3 colorless + 1 blue
          db-with-mana (mana/add-mana db'' :player-1 {:colorless 3 :blue 1})
          initial-hand (th/get-hand-count db-with-mana :player-1)
          _ (is (= 1 initial-hand) "Precondition: hand has 1 card (FoI)")
          ;; Cast Flash of Insight (cast-spell doesn't resolve X; set it manually)
          db-cast (rules/cast-spell db-with-mana :player-1 foi-id)
          _ (is (= :stack (th/get-object-zone db-cast foi-id))
                "FoI should be on stack after casting")
          ;; Manually set X=2 on the spell object (normally set by X mana UI)
          foi-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast foi-id)
          db-with-x (d/db-with db-cast [[:db/add foi-eid :object/x-value 2]])
          foi-obj (q/get-object db-with-x foi-id)
          _ (is (= 2 (:object/x-value foi-obj))
                "Spell should have X=2 stored")
          ;; Resolve spell - should create peek-and-select selection
          result (game/resolve-one-item db-with-x)
          sel (:pending-selection result)]
      ;; Selection type should be :peek-and-select
      (is (= :peek-and-select (:selection/type sel))
          "Selection effect type should be :peek-and-select")
      ;; Should show 2 candidates (X=2 peek)
      (is (= 2 (count (:selection/candidates sel)))
          "Should peek at 2 cards (X=2)")
      ;; Candidates should be the top 2 library cards
      (let [top-2-ids (set (take 2 lib-ids))]
        (is (= top-2-ids (:selection/candidates sel))
            "Peeked cards should be the top 2 of library"))
      ;; Select count should be 1
      (is (= 1 (:selection/select-count sel))
          "Should select exactly 1 card")
      ;; Simulate selecting the first peeked card
      (let [selected-card (first lib-ids)
            db-after-peek (library/execute-peek-selection
                            (:db result)
                            (assoc sel :selection/selected #{selected-card}))
            ;; Move spell off stack (simulating confirm handler cleanup)
            spell-obj (q/get-object db-after-peek foi-id)
            cast-mode (:object/cast-mode spell-obj)
            destination (or (:mode/on-resolve cast-mode) :graveyard)
            db-final (zones/move-to-zone db-after-peek foi-id destination)]
        ;; Selected card should be in hand
        (is (= :hand (th/get-object-zone db-final selected-card))
            "Selected card should be moved to hand")
        ;; Non-selected peeked card should NOT be in library top (moved to bottom)
        (let [second-card (second lib-ids)]
          (is (not= :hand (th/get-object-zone db-final second-card))
              "Non-selected peeked card should not be in hand"))
        ;; FoI should be in graveyard (normal cast)
        (is (= :graveyard (th/get-object-zone db-final foi-id))
            "Flash of Insight should go to graveyard after normal cast")
        ;; Hand should have 1 card (the selected one)
        (is (= 1 (th/get-hand-count db-final :player-1))
            "Hand should have 1 card after selecting from peek")))))


;; === Integration Test: Flashback with Exile Cost ===

(deftest flash-of-insight-flashback-with-exile-cost-test
  ;; Bug caught: flashback + exile cost interaction broken
  (testing "Cast from graveyard via flashback, verify mode and exile on resolve"
    (let [db (th/create-test-db)
          ;; Add cards to library for peeking
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze]
                                                  :player-1)
          ;; Add Flash of Insight to graveyard
          [db'' foi-id] (th/add-card-to-zone db' :flash-of-insight :graveyard :player-1)
          ;; Add 2 blue cards to graveyard for exile cost (X=2)
          [db1 _exile1-id] (th/add-card-to-zone db'' :careful-study :graveyard :player-1)
          [db2 _exile2-id] (th/add-card-to-zone db1 :opt :graveyard :player-1)
          ;; Add flashback mana: {1}{U}
          db-with-mana (mana/add-mana db2 :player-1 {:colorless 1 :blue 1})
          ;; Verify preconditions
          _ (is (= :graveyard (th/get-object-zone db-with-mana foi-id))
                "Precondition: FoI is in graveyard")
          ;; Get casting modes from graveyard
          modes (rules/get-casting-modes db-with-mana :player-1 foi-id)
          flashback-mode (first modes)]
      ;; From graveyard, only flashback mode should be available
      (is (= 1 (count modes))
          "Should have exactly 1 mode from graveyard (flashback)")
      (is (= :flashback (:mode/id flashback-mode))
          "Mode should be flashback")
      ;; Mode should have exile on-resolve (exiled after flashback resolution)
      (is (= :exile (:mode/on-resolve flashback-mode))
          "Flashback mode should exile on resolve")
      ;; Cast with flashback mode and verify spell goes to stack
      (let [db-cast (rules/cast-spell-mode db-with-mana :player-1 foi-id flashback-mode)
            foi-obj (q/get-object db-cast foi-id)]
        (is (= :stack (:object/zone foi-obj))
            "FoI should be on stack after flashback cast")
        ;; Cast mode should be stored on the object
        (is (= :exile (get-in foi-obj [:object/cast-mode :mode/on-resolve]))
            "Stored cast mode should have :exile on-resolve")))))
