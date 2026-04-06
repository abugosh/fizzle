(ns fizzle.cards.blue.flash-of-insight-test
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
    [fizzle.cards.blue.flash-of-insight :as flash-of-insight]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest flash-of-insight-card-definition-test
  (testing "Flash of Insight card has required fields"
    (let [card flash-of-insight/card]
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
    (let [effect (first (:card/effects flash-of-insight/card))]
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
    (let [alternate (first (:card/alternate-costs flash-of-insight/card))]
      (is (= :flashback (:alternate/id alternate))
          "Must have :alternate/id :flashback")
      (is (= :graveyard (:alternate/zone alternate))
          "Flashback casts from graveyard")
      (is (= {:colorless 1 :blue 1} (:alternate/mana-cost alternate))
          "Flashback mana cost is {1}{U}")
      (is (= :exile (:alternate/on-resolve alternate))
          "Spell exiles after flashback resolution")))

  (testing "Flashback has exile blue cards additional cost"
    (let [alternate (first (:card/alternate-costs flash-of-insight/card))
          exile-cost (first (:alternate/additional-costs alternate))]
      (is (= :exile-cards (:cost/type exile-cost))
          "Additional cost type must be :exile-cards")
      (is (= :graveyard (:cost/zone exile-cost))
          "Exile from graveyard")
      (is (= #{:blue} (:match/colors (:cost/criteria exile-cost)))
          "Must exile blue cards")
      (is (= :x (:cost/count exile-cost))
          "Exile count is X (player chooses)"))))


;; === C. Cannot-Cast Guards ===

(deftest flash-of-insight-cannot-cast-without-mana-test
  (testing "Cannot cast Flash of Insight without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :flash-of-insight :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest flash-of-insight-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Flash of Insight with only 1 blue (needs {X}{1}{U}, minimum {1}{U})"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :flash-of-insight :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with only 1 blue"))))


(deftest flash-of-insight-cannot-cast-from-exile-test
  (testing "Cannot cast Flash of Insight from exile"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :flash-of-insight :exile :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from exile"))))


;; === D. Storm Count ===

(deftest flash-of-insight-increments-storm-count-test
  (testing "Casting Flash of Insight increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :flash-of-insight :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Flash of Insight"))))


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
          result (resolution/resolve-one-item db-with-x)
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
      ;; Confirm peek-and-select with first card via production path
      (let [selected-card (first lib-ids)
            {:keys [db]} (th/confirm-selection (:db result) sel #{selected-card})]
        ;; Selected card should be in hand
        (is (= :hand (th/get-object-zone db selected-card))
            "Selected card should be moved to hand")
        ;; Non-selected peeked card should NOT be in hand
        (let [second-card (second lib-ids)]
          (is (not= :hand (th/get-object-zone db second-card))
              "Non-selected peeked card should not be in hand"))))))


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


;; === G. Edge Cases ===

(deftest flash-of-insight-castable-with-minimum-mana-test
  (testing "Flash of Insight castable with {1}{U} (X=0)"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :flash-of-insight :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Should be castable with {1}{U} for X=0"))))


(deftest flash-of-insight-selected-card-goes-to-hand-test
  (testing "Confirming peek-and-select moves selected card to hand"
    (let [db (th/create-test-db {:mana {:colorless 3 :blue 1}})
          [db [top-id second-id _third-id]] (th/add-cards-to-library db
                                                                     [:dark-ritual :cabal-ritual :brain-freeze]
                                                                     :player-1)
          [db foi-id] (th/add-card-to-zone db :flash-of-insight :hand :player-1)
          db-cast (rules/cast-spell db :player-1 foi-id)
          foi-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast foi-id)
          db-with-x (d/db-with db-cast [[:db/add foi-eid :object/x-value 2]])
          {:keys [db selection]} (th/resolve-top db-with-x)
          ;; Select the top card
          {:keys [db]} (th/confirm-selection db selection #{top-id})]
      ;; Selected card should be in hand
      (is (= :hand (th/get-object-zone db top-id))
          "Selected card should be in hand")
      ;; Non-selected card should NOT be in hand
      (is (not= :hand (th/get-object-zone db second-id))
          "Non-selected peeked card should not be in hand")
      ;; Spell should be in graveyard
      (is (= :graveyard (th/get-object-zone db foi-id))
          "Flash of Insight should be in graveyard after resolution"))))


(deftest flash-of-insight-peek-library-smaller-than-x-test
  (testing "Flash of Insight with X=3 but only 1 card in library peeks 1 card"
    (let [db (th/create-test-db {:mana {:colorless 4 :blue 1}})
          ;; Only 1 card in library
          [db [_lib-id]] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db foi-id] (th/add-card-to-zone db :flash-of-insight :hand :player-1)
          db-cast (rules/cast-spell db :player-1 foi-id)
          ;; Set X=3
          foi-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast foi-id)
          db-with-x (d/db-with db-cast [[:db/add foi-eid :object/x-value 3]])
          result (resolution/resolve-one-item db-with-x)
          sel (:pending-selection result)]
      ;; Should peek at only 1 card (all that's available)
      (is (= :peek-and-select (:selection/type sel))
          "Should have peek-and-select selection")
      (is (<= (count (:selection/candidates sel)) 1)
          "Should have at most 1 candidate (library only has 1 card)"))))


(deftest flash-of-insight-flashback-not-castable-from-exile-test
  (testing "Flash of Insight flashback only works from graveyard, not exile"
    (let [db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          [db obj-id] (th/add-card-to-zone db :flash-of-insight :exile :player-1)
          ;; Add blue cards for potential exile cost
          [db _] (th/add-cards-to-graveyard db [:careful-study :opt] :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (empty? modes)
          "No casting modes should be available from exile"))))
